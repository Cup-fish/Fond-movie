package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.ScheduleMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.SchedulePO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.infrastructure.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final ScheduleMapper scheduleMapper;
    private final SeatLockMapper seatLockMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final StockService stockService;
    private final DistributedLockService lockService;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter VO_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public OrderVO createOrder(Long userId, CreateOrderDTO dto) {
        if (dto.getSeatCount() == null || dto.getSeatCount() <= 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "购票数量不合法");
        }

        Long scheduleId = dto.getScheduleId();
        OrderVO result = lockService.executeWithBoundedLock("seat:" + scheduleId, 3, 12,
                () -> createOrderInScheduleLock(userId, dto));
        if (result == null) {
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(), "系统繁忙，请重试");
        }
        return result;
    }

    private OrderVO createOrderInScheduleLock(Long userId, CreateOrderDTO dto) {
        Long scheduleId = dto.getScheduleId();
        int seatCount = dto.getSeatCount();
        LocalDateTime now = LocalDateTime.now();

        SchedulePO schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || schedule.getDeleted() == 1 || schedule.getStatus() != 1) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在或已停售");
        }

        seatLockMapper.cleanExpiredLocks(now);

        String lockToken = normalize(dto.getLockToken());
        if (lockToken == null) {
            lockToken = lockSeatsForOrder(userId, schedule, dto, now);
        }

        List<SeatLockPO> lockedSeats = seatLockMapper.selectActiveLocksByToken(scheduleId, userId, lockToken, now);
        validateLockedSeats(dto, lockedSeats, seatCount);

        long remaining = stockService.preDeduct(scheduleId, seatCount);
        if (remaining < 0) {
            throw new BizException(ResponseCodeEnum.STOCK_NOT_ENOUGH);
        }

        try {
            SchedulePO latest = scheduleMapper.selectById(scheduleId);
            if (latest == null || latest.getStatus() != 1 || latest.getDeleted() == 1) {
                throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在或已停售");
            }

            int affected = scheduleMapper.deductStock(scheduleId, seatCount, latest.getVersion());
            if (affected == 0) {
                throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(), "库存扣减失败，请重新下单");
            }
            refreshScheduleDetailCache(scheduleId);

            String orderNo = generateOrderNo(userId);
            OrderPO order = buildPendingOrder(userId, dto, latest, lockToken, orderNo, now);
            orderMapper.insert(order);

            int bound = seatLockMapper.bindLocksToOrder(scheduleId, userId, lockToken, orderNo,
                    order.getExpireTime(), now);
            if (bound != seatCount) {
                throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
            }

            sendOrderEvent(OrderEvent.Type.CREATED, order);
            log.info("[Order] Created: orderNo={}, userId={}, scheduleId={}, seats={}, total={}",
                    orderNo, userId, scheduleId, seatCount, order.getTotalPrice());
            return toVO(order);
        } catch (BizException e) {
            if (e.getCode() != ResponseCodeEnum.STOCK_NOT_ENOUGH.getCode()) {
                stockService.rollback(scheduleId, seatCount);
            }
            throw e;
        } catch (Exception e) {
            stockService.rollback(scheduleId, seatCount);
            log.error("[Order] Create failed, stock rolled back: scheduleId={}, seats={}", scheduleId, seatCount, e);
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED);
        }
    }

    private String lockSeatsForOrder(Long userId, SchedulePO schedule, CreateOrderDTO dto, LocalDateTime now) {
        List<LockSeatsDTO.SeatPos> seats = dto.getSeats();
        if (seats == null || seats.isEmpty()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "请选择座位");
        }
        if (seats.size() != dto.getSeatCount()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "座位数量与购票数量不一致");
        }

        Long scheduleId = schedule.getId();
        seatLockMapper.releaseUserLocks(scheduleId, userId, now);

        Set<String> requested = new HashSet<>();
        for (LockSeatsDTO.SeatPos seat : seats) {
            String seatKey = seat.getRow() + "," + seat.getCol();
            if (!requested.add(seatKey)) {
                throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "不能重复选择同一座位");
            }
            SeatLockPO existing = seatLockMapper.selectActiveLock(scheduleId, seat.getRow(), seat.getCol(), now);
            if (existing != null) {
                throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
            }
        }

        Set<String> soldSeats = orderSeatMapper.selectPurchasedSeats(scheduleId).stream()
                .map(os -> os.getRowNum() + "," + os.getColNum())
                .collect(Collectors.toSet());
        for (LockSeatsDTO.SeatPos seat : seats) {
            if (soldSeats.contains(seat.getRow() + "," + seat.getCol())) {
                throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
            }
        }

        String lockToken = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime lockUntil = now.plusMinutes(CacheConstants.SEAT_LOCK_MINUTES);
        for (LockSeatsDTO.SeatPos seat : seats) {
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
        return lockToken;
    }

    private void validateLockedSeats(CreateOrderDTO dto, List<SeatLockPO> lockedSeats, int seatCount) {
        if (lockedSeats == null || lockedSeats.size() != seatCount) {
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }
        if (dto.getSeats() == null || dto.getSeats().isEmpty()) {
            return;
        }
        Set<String> requestSeats = dto.getSeats().stream()
                .map(s -> s.getRow() + "," + s.getCol())
                .collect(Collectors.toSet());
        Set<String> locked = lockedSeats.stream()
                .map(s -> s.getRowNum() + "," + s.getColNum())
                .collect(Collectors.toSet());
        if (!requestSeats.equals(locked)) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "锁座信息与下单座位不一致");
        }
    }

    private OrderPO buildPendingOrder(Long userId, CreateOrderDTO dto, SchedulePO schedule,
                                      String lockToken, String orderNo, LocalDateTime now) {
        int seatCount = dto.getSeatCount();
        OrderPO order = new OrderPO();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setScheduleId(schedule.getId());
        order.setLockToken(lockToken);
        order.setMovieName(null);
        order.setCinemaName(null);
        order.setHallName(schedule.getHallName());
        order.setShowTime(schedule.getShowDate() + " " + schedule.getShowTime());
        order.setSeatCount(seatCount);
        order.setSeatsInfo(dto.getSeatsInfo());
        order.setUnitPrice(schedule.getPrice());
        order.setTotalPrice(schedule.getPrice().multiply(BigDecimal.valueOf(seatCount)));
        order.setStatus(OrderStatusEnum.PENDING.getCode());
        order.setExpireTime(now.plusMinutes(CacheConstants.ORDER_PAY_TIMEOUT_MINUTES));
        return order;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public void cancelOrder(Long userId, String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getUserId, userId);
        OrderPO order = orderMapper.selectOne(wrapper);

        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "当前订单状态不允许取消");
        }

        closePendingOrder(order, "USER_CANCEL");
    }

    @Scheduled(fixedDelay = 60000)
    public void cancelExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<OrderPO> expired = orderMapper.selectExpiredPendingOrders(now, 100);
        if (expired.isEmpty()) {
            return;
        }
        for (OrderPO order : expired) {
            try {
                closePendingOrder(order, "TIMEOUT");
            } catch (Exception e) {
                log.error("[Order] Failed to close expired order: orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    private void closePendingOrder(OrderPO order, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int closed = orderMapper.closePendingOrder(order.getOrderNo(), now);
        if (closed == 0) {
            return;
        }
        scheduleMapper.rollbackStock(order.getScheduleId(), order.getSeatCount());
        stockService.rollback(order.getScheduleId(), order.getSeatCount());
        refreshScheduleDetailCache(order.getScheduleId());
        seatLockMapper.releaseOrderLocks(order.getOrderNo());
        sendOrderEvent(OrderEvent.Type.CANCELLED, order);
        log.info("[Order] Closed: orderNo={}, reason={}, seatsReturned={}",
                order.getOrderNo(), reason, order.getSeatCount());
    }

    public List<OrderVO> getUserOrders(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        return orderMapper.selectByUserIdWithPage(userId, offset, size).stream()
                .map(this::toVO)
                .toList();
    }

    private void sendOrderEvent(OrderEvent.Type type, OrderPO order) {
        if (rocketMQTemplate == null) {
            return;
        }
        OrderEvent event = new OrderEvent(
                type,
                order.getOrderNo(), order.getUserId(), order.getScheduleId(), null,
                null, order.getSeatCount(), order.getTotalPrice(), System.currentTimeMillis()
        );
        try {
            String tag = switch (type) {
                case CREATED -> MQConstants.TAG_ORDER_CREATED;
                case PAID -> MQConstants.TAG_ORDER_PAID;
                case CANCELLED -> MQConstants.TAG_ORDER_CANCELLED;
                case REFUNDED -> MQConstants.TAG_ORDER_REFUNDED;
            };
            rocketMQTemplate.syncSend(MQConstants.ORDER_TOPIC + ":" + tag, event, 1000);
        } catch (Exception e) {
            log.error("[Order] MQ notify failed, type={}, orderNo={}", type, order.getOrderNo(), e);
        }
    }

    private void refreshScheduleDetailCache(Long scheduleId) {
        try {
            SchedulePO schedule = scheduleMapper.selectById(scheduleId);
            if (schedule != null) {
                stockService.initScheduleDetail(schedule);
            }
        } catch (Exception e) {
            log.warn("[Order] Failed to refresh schedule cache: scheduleId={}", scheduleId, e);
        }
    }

    private String generateOrderNo(Long userId) {
        String time = LocalDateTime.now().format(ORDER_NO_FMT);
        String userSuffix = String.format("%04d", userId % 10000);
        String random = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "MO" + time + userSuffix + random;
    }

    private OrderVO toVO(OrderPO po) {
        OrderVO vo = new OrderVO();
        vo.setId(po.getId());
        vo.setOrderNo(po.getOrderNo());
        vo.setLockToken(po.getLockToken());
        vo.setMovieName(po.getMovieName());
        vo.setCinemaName(po.getCinemaName());
        vo.setHallName(po.getHallName());
        vo.setShowTime(po.getShowTime());
        vo.setSeatCount(po.getSeatCount());
        vo.setSeatsInfo(po.getSeatsInfo());
        vo.setUnitPrice(po.getUnitPrice());
        vo.setTotalPrice(po.getTotalPrice());
        vo.setStatus(po.getStatus());
        vo.setStatusDesc(OrderStatusEnum.of(po.getStatus()).getDesc());
        vo.setScheduleId(po.getScheduleId());
        if (po.getCreateTime() != null) {
            vo.setCreateTime(po.getCreateTime().format(VO_TIME_FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(VO_TIME_FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(VO_TIME_FMT));
        }
        return vo;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
