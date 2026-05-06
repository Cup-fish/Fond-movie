package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.dao.mapper.*;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.po.*;
import com.maoyan.domain.model.vo.SeatLayoutVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 座位服务 — 高并发锁座核心（面试重难点）
 *
 * <h3>锁座方案（企业级三重保障）：</h3>
 * <pre>
 * 1. [Redis 分布式锁]   — 保证同一时刻只有一个请求能操作同一场次的座位（串行化）
 * 2. [DB 唯一索引]      — 防止分布式环境下并发插入同一座位的锁定记录
 * 3. [TTL 自动过期]     — 15分钟未支付自动释放座位，定时任务清理 + 查询时过滤
 * </pre>
 *
 * <h3>座位状态流转：</h3>
 * <pre>
 * 可选(0) → 锁定(1) → 已购买(2)
 *                   → 释放(0)（用户取消/超时）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatService {

    private final CinemaHallMapper cinemaHallMapper;
    private final SeatLockMapper seatLockMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final ScheduleMapper scheduleMapper;
    private final CinemaMapper cinemaMapper;
    private final DistributedLockService lockService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取座位布局 + 实时状态
     */
    public SeatLayoutVO getSeatLayout(Long scheduleId, Long userId) {
        SchedulePO schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || schedule.getDeleted() == 1) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在");
        }

        // 查找影厅布局
        CinemaHallPO hall = cinemaHallMapper.selectByCinemaAndHall(schedule.getCinemaId(), schedule.getHallName());
        if (hall == null) {
            // 使用默认布局（10行14列，过道在3、11列后）
            hall = buildDefaultHall(schedule.getCinemaId(), schedule.getHallName());
        }

        return buildSeatLayout(hall, scheduleId, userId);
    }

    /**
     * 锁定座位（高并发安全）
     *
     * <p>使用分布式锁确保同一场次的锁座操作串行化，配合DB唯一索引兜底</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public Map<String, Object> lockSeats(Long userId, LockSeatsDTO dto) {
        Long scheduleId = dto.getScheduleId();
        String lockKey = "seat:" + scheduleId;

        // 分布式锁保护（等待3秒，持有10秒）
        Map<String, Object> result = lockService.executeWithBoundedLock(lockKey, 3, 12, () -> {
            // 1. 验证场次
            SchedulePO schedule = scheduleMapper.selectById(scheduleId);
            if (schedule == null || schedule.getStatus() != 1) {
                throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在或已停售");
            }

            LocalDateTime now = LocalDateTime.now();
            seatLockMapper.cleanExpiredLocks(now);

            // 2. 先释放该用户在此场次的旧锁定
            seatLockMapper.releaseUserLocks(scheduleId, userId, now);

            Set<String> requested = new HashSet<>();
            for (LockSeatsDTO.SeatPos seat : dto.getSeats()) {
                String seatKey = seat.getRow() + "," + seat.getCol();
                if (!requested.add(seatKey)) {
                    throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "不能重复选择同一座位");
                }
            }

            // 3. 检查每个座位是否可锁定
            for (LockSeatsDTO.SeatPos seat : dto.getSeats()) {
                SeatLockPO existing = seatLockMapper.selectActiveLock(scheduleId, seat.getRow(), seat.getCol(), now);
                if (existing != null) {
                    throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
                }
                // 检查是否已被购买
                List<OrderSeatPO> purchased = orderSeatMapper.selectPurchasedSeats(scheduleId);
                boolean alreadyPurchased = purchased.stream()
                        .anyMatch(os -> os.getRowNum().equals(seat.getRow()) && os.getColNum().equals(seat.getCol()));
                if (alreadyPurchased) {
                    throw new BizException(ResponseCodeEnum.SEAT_LOCKED.getCode(), seat.getRow() + "排" + seat.getCol() + "座已售出");
                }
            }

            // 4. 插入锁定记录
            LocalDateTime lockUntil = now.plusMinutes(CacheConstants.SEAT_LOCK_MINUTES);
            String lockToken = UUID.randomUUID().toString().replace("-", "");
            for (LockSeatsDTO.SeatPos seat : dto.getSeats()) {
                SeatLockPO lock = new SeatLockPO();
                lock.setScheduleId(scheduleId);
                lock.setRowNum(seat.getRow());
                lock.setColNum(seat.getCol());
                lock.setUserId(userId);
                lock.setLockToken(lockToken);
                lock.setLockUntil(lockUntil);
                lock.setStatus(1);
                lock.setCreateTime(now);
                lock.setUpdateTime(now);
                seatLockMapper.insert(lock);
            }

            log.info("[Seat] Locked {} seats for user={}, schedule={}, until={}",
                    dto.getSeats().size(), userId, scheduleId, lockUntil);

            Map<String, Object> res = new HashMap<>();
            res.put("lockToken", lockToken);
            res.put("lockUntil", lockUntil.toString());
            res.put("seatCount", dto.getSeats().size());
            res.put("price", schedule.getPrice());
            return res;
        });

        if (result == null) {
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(), "系统繁忙，请重试");
        }
        return result;
    }

    /**
     * 释放座位锁定
     */
    @Transactional(rollbackFor = Exception.class)
    public void unlockSeats(Long userId, Long scheduleId) {
        int released = seatLockMapper.releaseUserLocks(scheduleId, userId, LocalDateTime.now());
        log.info("[Seat] Released {} locked seats for user={}, schedule={}", released, userId, scheduleId);
    }

    /**
     * 清理过期锁定（可由定时任务调用）
     */
    public int cleanExpiredLocks() {
        int cleaned = seatLockMapper.cleanExpiredLocks(LocalDateTime.now());
        if (cleaned > 0) {
            log.info("[Seat] Cleaned {} expired seat locks", cleaned);
        }
        return cleaned;
    }

    // ========== 私有方法 ==========

    /**
     * 构建默认影厅布局（无cinema_hall记录时使用）
     */
    private CinemaHallPO buildDefaultHall(Long cinemaId, String hallName) {
        CinemaHallPO hall = new CinemaHallPO();
        hall.setCinemaId(cinemaId);
        hall.setHallName(hallName);
        hall.setSeatRows(8);
        hall.setSeatCols(12);
        hall.setAisleAfterCol("3,9");
        hall.setCoupleRows("");
        hall.setDisabledSeats("[]");
        hall.setHallType("普通厅");
        return hall;
    }

    /**
     * 构建座位布局VO（合并布局+锁定+已售状态）
     */
    private SeatLayoutVO buildSeatLayout(CinemaHallPO hall, Long scheduleId, Long userId) {
        SeatLayoutVO vo = new SeatLayoutVO();
        vo.setHallName(hall.getHallName());
        vo.setHallType(hall.getHallType());
        vo.setRows(hall.getSeatRows());
        vo.setCols(hall.getSeatCols());

        // 解析过道列
        List<Integer> aisles = parseIntList(hall.getAisleAfterCol());
        vo.setAisles(aisles);

        // 解析情侣座行
        List<Integer> coupleRowList = parseIntList(hall.getCoupleRows());
        vo.setCoupleRows(coupleRowList);

        // 解析不可用座位
        Set<String> disabledSet = parseDisabledSeats(hall.getDisabledSeats());

        // 查询锁定状态
        LocalDateTime now = LocalDateTime.now();
        List<SeatLockPO> activeLocks = seatLockMapper.selectActiveLocks(scheduleId, now);
        Map<String, SeatLockPO> lockMap = activeLocks.stream()
                .collect(Collectors.toMap(l -> l.getRowNum() + "," + l.getColNum(), l -> l, (a, b) -> a));

        // 查询已售座位
        List<OrderSeatPO> purchasedSeats = orderSeatMapper.selectPurchasedSeats(scheduleId);
        Set<String> soldSet = purchasedSeats.stream()
                .map(os -> os.getRowNum() + "," + os.getColNum())
                .collect(Collectors.toSet());

        // 构建二维座位数组
        List<List<SeatLayoutVO.SeatInfo>> seats = new ArrayList<>();
        for (int r = 1; r <= hall.getSeatRows(); r++) {
            List<SeatLayoutVO.SeatInfo> row = new ArrayList<>();
            for (int c = 1; c <= hall.getSeatCols(); c++) {
                SeatLayoutVO.SeatInfo info = new SeatLayoutVO.SeatInfo();
                info.setRow(r);
                info.setCol(c);
                info.setLabel(r + "排" + c + "座");
                info.setCouple(coupleRowList.contains(r));

                String key = r + "," + c;

                if (disabledSet.contains(key)) {
                    info.setStatus(-1); // 不可用
                } else if (soldSet.contains(key)) {
                    info.setStatus(1); // 已售
                } else if (lockMap.containsKey(key)) {
                    SeatLockPO lock = lockMap.get(key);
                    if (lock.getUserId().equals(userId)) {
                        info.setStatus(3); // 我锁定的
                    } else {
                        info.setStatus(2); // 他人锁定
                    }
                } else {
                    info.setStatus(0); // 可选
                }

                row.add(info);
            }
            seats.add(row);
        }
        vo.setSeats(seats);

        return vo;
    }

    private List<Integer> parseIntList(String str) {
        if (str == null || str.isEmpty()) return Collections.emptyList();
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    private Set<String> parseDisabledSeats(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptySet();
        try {
            List<List<Integer>> disabled = objectMapper.readValue(json, new TypeReference<>() {});
            return disabled.stream()
                    .map(pair -> pair.get(0) + "," + pair.get(1))
                    .collect(Collectors.toSet());
        } catch (JsonProcessingException e) {
            log.warn("解析不可用座位JSON失败: {}", json, e);
            return Collections.emptySet();
        }
    }
}
