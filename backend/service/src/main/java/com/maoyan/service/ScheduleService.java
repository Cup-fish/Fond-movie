package com.maoyan.service;

import com.maoyan.dao.mapper.CinemaMapper;
import com.maoyan.dao.mapper.ScheduleMapper;
import com.maoyan.domain.model.po.CinemaPO;
import com.maoyan.domain.model.po.SchedulePO;
import com.maoyan.domain.model.vo.ScheduleVO;
import com.maoyan.service.cache.MultiLevelCacheService;
import com.maoyan.service.infrastructure.StockService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 场次服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final StockService stockService;
    private final MultiLevelCacheService cacheService;

    @Resource
    private CinemaMapper cinemaMapper;

    private static final String SCHEDULE_LIST_CACHE_PREFIX = "schedule:list:";
    private static final String SCHEDULE_DATES_CACHE_PREFIX = "schedule:dates:";
    private static final String CINEMA_SCHEDULE_LIST_CACHE_PREFIX = "schedule:cinema:list:";
    private static final String CINEMA_SCHEDULE_DATES_CACHE_PREFIX = "schedule:cinema:dates:";

    /**
     * 应用启动时：刷新日期 → 预热库存
     */
    @PostConstruct
    public void init() {
        refreshScheduleDates();
        evictScheduleReadCaches();
        warmUpStock();
    }

    /**
     * 将排片日期平移到当前日期——演示数据永不过期。
     * 算法：取最早排片日期与今天的差值，整体平移所有日期，
     *       同时重置库存和版本号，保证数据始终新鲜。
     */
    private void refreshScheduleDates() {
        String minDateStr = scheduleMapper.selectMinShowDate();
        if (minDateStr != null) {
            LocalDate minDate = LocalDate.parse(minDateStr);
            LocalDate today = LocalDate.now();
            long daysDiff = ChronoUnit.DAYS.between(minDate, today);
            if (daysDiff > 0) {
                int rows = scheduleMapper.refreshAllScheduleDates(daysDiff);
                log.info("[Schedule] 排片日期刷新：前移 {} 天（{} → {}），共更新 {} 条记录", daysDiff, minDate, today, rows);
            }
        }
    }

    /**
     * 每 5 分钟执行一次：检查并修复 Redis 与 DB 的库存不一致
     *
     * <p>触发条件：Redis 回滚失败（记入脏队列）</p>
     */
    @Scheduled(fixedRate = 300000)
    public void reconcileStock() {
        Map<Object, Object> dirty = stockService.getDirtyRollbacks();
        if (dirty == null || dirty.isEmpty()) return;

        log.info("[Schedule] Starting stock reconciliation, {} dirty records", dirty.size());
        for (Map.Entry<Object, Object> entry : dirty.entrySet()) {
            Long scheduleId = Long.parseLong(entry.getKey().toString());
            int lostRollback = Integer.parseInt(entry.getValue().toString());
            try {
                // 从 DB 查真实库存，强覆盖 Redis
                SchedulePO db = scheduleMapper.selectById(scheduleId);
                if (db != null) {
                    stockService.initStock(scheduleId, db.getAvailableSeats());
                    stockService.initScheduleDetail(db);
                    log.info("[Schedule] Reconciled: scheduleId={}, DB stock={}, recovered {} seats",
                            scheduleId, db.getAvailableSeats(), lostRollback);
                }
            } catch (Exception e) {
                log.error("[Schedule] Failed to reconcile scheduleId={}", scheduleId, e);
            }
        }
        stockService.clearDirtyRollbacks();
        log.info("[Schedule] Reconciliation completed");
    }

    /**
     * 每天凌晨 0:05 自动刷新排片日期并重新预热库存。
     * 这样即使服务器不重启，排片数据也永远显示"今天/明天"。
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void dailyRefresh() {
        log.info("[Schedule] 每日定时刷新排片日期...");
        refreshScheduleDates();
        evictScheduleReadCaches();
        warmUpStock();
        log.info("[Schedule] 每日定时刷新完成");
    }

    /**
     * 启动时预热库存到 Redis
     */
    public void warmUpStock() {
        String today = LocalDate.now().toString();
        List<SchedulePO> schedules = scheduleMapper.selectByMovieAndDate(null, today);
        // selectByMovieAndDate 需要 movieId，这里用全量查询
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SchedulePO> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(SchedulePO::getStatus, 1)
                .eq(SchedulePO::getDeleted, 0);
        schedules = scheduleMapper.selectList(wrapper);

        for (SchedulePO s : schedules) {
            stockService.initStock(s.getId(), s.getAvailableSeats());
            stockService.initScheduleDetail(s);
        }
        log.info("[Schedule] Warmed up {} schedules' stock to Redis", schedules.size());
    }

    /**
     * 查询电影某日的场次列表
     */
    public List<ScheduleVO> getSchedules(Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        List<SchedulePO> pos = getCachedSchedules(movieId, showDate);
        return pos.stream().map(this::toVO).toList();
    }

    /**
     * 根据ID获取场次
     */
    public SchedulePO getById(Long scheduleId) {
        return scheduleMapper.selectById(scheduleId);
    }

    /**
     * 查询某电影在某天所有影院的场次（按影院分组）
     *
     * @return { cinemaId: { cinemaName, cinemaAddr, schedules: [ScheduleVO...] } }
     */
    public List<Map<String, Object>> getSchedulesByCinema(Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }

        List<SchedulePO> allSchedules = getCachedSchedules(movieId, showDate);

        // 按影院分组
        Map<Long, List<SchedulePO>> grouped = allSchedules.stream()
                .collect(Collectors.groupingBy(SchedulePO::getCinemaId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<SchedulePO>> entry : grouped.entrySet()) {
            Long cinemaId = entry.getKey();
            CinemaPO cinema = cinemaMapper.selectById(cinemaId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cinemaId", cinemaId);
            item.put("cinemaName", cinema != null ? cinema.getNm() : "未知影院");
            item.put("cinemaAddr", cinema != null ? cinema.getAddr() : "");
            item.put("schedules", entry.getValue().stream().map(this::toVO).toList());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取电影有场次的日期列表
     */
    public List<String> getAvailableDates(Long movieId) {
        // 查询未来7天有场次的日期
        List<String> dates = new ArrayList<>();
        String cacheKey = SCHEDULE_DATES_CACHE_PREFIX + movieId;
        return cacheService.get(cacheKey, () -> {
            List<String> dates = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 7; i++) {
                String date = today.plusDays(i).toString();
                List<SchedulePO> schedules = getCachedSchedules(movieId, date);
                if (!schedules.isEmpty()) {
                    dates.add(date);
                }
            }
            return dates;
        });
    }

    private ScheduleVO toVO(SchedulePO po) {
        ScheduleVO vo = new ScheduleVO();
        vo.setId(po.getId());
        vo.setMovieId(po.getMovieId());
        vo.setCinemaId(po.getCinemaId());
        vo.setHallName(po.getHallName());
        vo.setShowDate(po.getShowDate());
        vo.setShowTime(po.getShowTime());
        vo.setEndTime(po.getEndTime());
        vo.setLang(po.getLang());
        vo.setTotalSeats(po.getTotalSeats());
        // 优先从 Redis 获取实时库存
        int redisStock = stockService.getStock(po.getId());
        vo.setAvailableSeats(redisStock >= 0 ? redisStock : po.getAvailableSeats());
        vo.setPrice(po.getPrice());
        return vo;
    }

    // ==================== 影院详情页专用 ====================

    /**
     * 获取影院详情
     */
    public CinemaPO getCinemaById(Long cinemaId) {
        return cinemaMapper.selectById(cinemaId);
    }

    /**
     * 查询某影院有排片的电影ID列表
     */
    public List<Long> getMovieIdsByCinema(Long cinemaId) {
        return scheduleMapper.selectMovieIdsByCinema(cinemaId, LocalDate.now().toString());
    }

    /**
     * 查询影院某电影某日的场次
     */
    public List<ScheduleVO> getCinemaSchedules(Long cinemaId, Long movieId, String showDate) {
        if (showDate == null || showDate.isEmpty()) {
            showDate = LocalDate.now().toString();
        }
        String finalShowDate = showDate;
        String cacheKey = CINEMA_SCHEDULE_LIST_CACHE_PREFIX + cinemaId + ":" + movieId + ":" + finalShowDate;
        return cacheService.<List<SchedulePO>>get(cacheKey,
                        () -> scheduleMapper.selectByCinemaAndMovieAndDate(cinemaId, movieId, finalShowDate))
                .stream().map(this::toVO).toList();
    }

    /**
     * 查询影院某电影有排片的日期列表
     */
    public List<String> getCinemaAvailableDates(Long cinemaId, Long movieId) {
        String cacheKey = CINEMA_SCHEDULE_DATES_CACHE_PREFIX + cinemaId + ":" + movieId;
        return cacheService.get(cacheKey,
                () -> scheduleMapper.selectAvailableDatesByCinemaAndMovie(cinemaId, movieId, LocalDate.now().toString()));
    }

    private List<SchedulePO> getCachedSchedules(Long movieId, String showDate) {
        String cacheKey = SCHEDULE_LIST_CACHE_PREFIX + movieId + ":" + showDate;
        return cacheService.get(cacheKey, () -> scheduleMapper.selectByMovieAndDate(movieId, showDate));
    }

    private void evictScheduleReadCaches() {
        cacheService.evictByPrefix(SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(SCHEDULE_DATES_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_LIST_CACHE_PREFIX);
        cacheService.evictByPrefix(CINEMA_SCHEDULE_DATES_CACHE_PREFIX);
    }
}
