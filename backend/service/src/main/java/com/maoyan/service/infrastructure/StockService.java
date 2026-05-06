package com.maoyan.service.infrastructure;

import com.maoyan.common.constants.CacheConstants;
import com.maoyan.domain.model.po.SchedulePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务 — Redis Lua 脚本原子操作（防超卖核心组件）
 *
 * <h3>防超卖方案（面试亮点）：</h3>
 * <pre>
 * 第一层: Redis Lua 脚本原子预扣 — 高并发拦截 99% 无效请求
 * 第二层: DB 乐观锁(version) 最终确认 — 保证数据一致性
 * 第三层: 订单超时自动回滚 — 兜底保证库存不泄漏
 * </pre>
 *
 * <p>降级策略：当 Redis 不可用时跳过预扣，由 DB 乐观锁兜底</p>
 */
@Slf4j
@Service
public class StockService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Redis Lua 预扣库存脚本（原子操作, 防超卖）
     *
     * <p>逻辑：检查剩余库存 >= 扣减数量 → 扣减 → 返回剩余库存；否则返回 -1</p>
     */
    private static final String DEDUCT_STOCK_LUA = """
            local key = KEYS[1]
            local count = tonumber(ARGV[1])
            local stock = tonumber(redis.call('GET', key) or '0')
            if stock >= count then
                local newStock = stock - count
                redis.call('SET', key, newStock)
                return newStock
            else
                return -1
            end
            """;

    /**
     * Redis Lua 回滚库存脚本
     */
    private static final String ROLLBACK_STOCK_LUA = """
            local key = KEYS[1]
            local count = tonumber(ARGV[1])
            local newStock = redis.call('INCRBY', key, count)
            return newStock
            """;

    private final DefaultRedisScript<Long> deductScript = new DefaultRedisScript<>(DEDUCT_STOCK_LUA, Long.class);
    private final DefaultRedisScript<Long> rollbackScript = new DefaultRedisScript<>(ROLLBACK_STOCK_LUA, Long.class);

    /**
     * 初始化场次库存到 Redis
     */
    public void initStock(Long scheduleId, int availableSeats) {
        if (stringRedisTemplate == null) {
            log.debug("[Stock] Redis unavailable, skip stock init for scheduleId={}", scheduleId);
            return;
        }
        String key = CacheConstants.SCHEDULE_STOCK_PREFIX + scheduleId;
        stringRedisTemplate.opsForValue().set(key, String.valueOf(availableSeats),
                CacheConstants.STOCK_EXPIRE_HOURS, TimeUnit.HOURS);
        log.info("[Stock] Initialized: scheduleId={}, stock={}", scheduleId, availableSeats);
    }

    /**
     * Redis 预扣库存（原子操作）
     *
     * @return 剩余库存，-1 表示库存不足
     */
    public long preDeduct(Long scheduleId, int seatCount) {
        if (stringRedisTemplate == null) {
            log.debug("[Stock] Redis unavailable, skip pre-deduct, rely on DB optimistic lock");
            return Integer.MAX_VALUE;
        }
        String key = CacheConstants.SCHEDULE_STOCK_PREFIX + scheduleId;

        Long result = stringRedisTemplate.execute(
                deductScript,
                Collections.singletonList(key),
                String.valueOf(seatCount)
        );

        long remaining = result != null ? result : -1;
        if (remaining >= 0) {
            log.info("[Stock] Pre-deducted: scheduleId={}, deducted={}, remaining={}", scheduleId, seatCount, remaining);
        } else {
            log.warn("[Stock] Insufficient: scheduleId={}, requested={}", scheduleId, seatCount);
        }
        return remaining;
    }

    /**
     * Redis 回滚库存
     *
     * <p>异常不外抛：回滚失败时打印日志 + 记录到脏数据队列，供后续人工修复或定时任务处理</p>
     */
    public void rollback(Long scheduleId, int seatCount) {
        if (stringRedisTemplate == null) {
            log.warn("[Stock] Redis unavailable, skip rollback for scheduleId={}", scheduleId);
            return;
        }
        try {
            String key = CacheConstants.SCHEDULE_STOCK_PREFIX + scheduleId;
            Long result = stringRedisTemplate.execute(
                    rollbackScript,
                    Collections.singletonList(key),
                    String.valueOf(seatCount)
            );
            log.info("[Stock] Rolled back: scheduleId={}, count={}, newStock={}", scheduleId, seatCount, result);
        } catch (Exception e) {
            // Redis 挂了也继续，不要影响主流程
            // 记录脏数据：库存可能存在不一致，定时任务会从 DB 拉真值同步 Redis
            log.error("[Stock] CRITICAL: Failed to rollback Redis stock, scheduleId={}, seats={}. Needs reconciliation.",
                    scheduleId, seatCount, e);
            recordDirtyRollback(scheduleId, seatCount);
        }
    }

    /**
     * 查询剩余库存
     */
    public int getStock(Long scheduleId) {
        if (stringRedisTemplate == null) return -1;
        String key = CacheConstants.SCHEDULE_STOCK_PREFIX + scheduleId;
        String val = stringRedisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : -1;
    }

    /**
     * 初始化场次详情到 Redis（Hash 结构）
     */
    public void initScheduleDetail(Long scheduleId, Long movieId, Long cinemaId,
                                   String hallName, String showDate, String showTime,
                                   String endTime, String lang, Integer totalSeats,
                                   Integer availableSeats, BigDecimal price,
                                   Integer status, Integer version) {
        if (stringRedisTemplate == null) return;
        String key = CacheConstants.SCHEDULE_DETAIL_PREFIX + scheduleId;
        Map<String, String> fields = new HashMap<>();
        fields.put("movieId", String.valueOf(movieId));
        fields.put("cinemaId", String.valueOf(cinemaId));
        fields.put("hallName", hallName);
        fields.put("showDate", showDate);
        fields.put("showTime", showTime);
        fields.put("endTime", endTime);
        fields.put("lang", lang);
        fields.put("totalSeats", String.valueOf(totalSeats));
        fields.put("price", price.toString());
        fields.put("status", String.valueOf(status));
        fields.put("version", String.valueOf(version));
        stringRedisTemplate.opsForHash().putAll(key, fields);
        stringRedisTemplate.expire(key, CacheConstants.STOCK_EXPIRE_HOURS, TimeUnit.HOURS);
        log.info("[Stock] Initialized schedule detail: scheduleId={}, version={}", scheduleId, version);
    }

    /**
     * 从 Redis 查询场次详情，不存在返回 null
     *
     * @return Map，key 为字段名，value 为字符串值
     */
    public Map<Object, Object> getScheduleDetail(Long scheduleId) {
        if (stringRedisTemplate == null) return null;
        String key = CacheConstants.SCHEDULE_DETAIL_PREFIX + scheduleId;
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(key);
        return fields.isEmpty() ? null : fields;
    }

    /**
     * 从 Redis 缓存读取场次信息，构造 SchedulePO 返回
     *
     * @return 缓存命中返回 SchedulePO（version 字段可用）；未命中返回 null
     */
    public SchedulePO getScheduleFromCache(Long scheduleId) {
        Map<Object, Object> fields = getScheduleDetail(scheduleId);
        if (fields == null) return null;
        try {
            SchedulePO po = new SchedulePO();
            po.setId(scheduleId);
            po.setMovieId(parseLong(fields.get("movieId")));
            po.setCinemaId(parseLong(fields.get("cinemaId")));
            po.setHallName((String) fields.get("hallName"));
            po.setShowDate((String) fields.get("showDate"));
            po.setShowTime((String) fields.get("showTime"));
            po.setEndTime((String) fields.get("endTime"));
            po.setLang((String) fields.get("lang"));
            po.setTotalSeats(parseInt(fields.get("totalSeats")));
            po.setPrice(new java.math.BigDecimal((String) fields.get("price")));
            po.setStatus(parseInt(fields.get("status")));
            po.setVersion(parseInt(fields.get("version")));
            return po;
        } catch (Exception e) {
            log.warn("[Stock] Failed to parse schedule detail from cache: scheduleId={}", scheduleId, e);
            return null;
        }
    }

    /**
     * 将 SchedulePO 写入 Redis Hash 缓存
     */
    public void initScheduleDetail(SchedulePO schedule) {
        initScheduleDetail(
                schedule.getId(), schedule.getMovieId(), schedule.getCinemaId(),
                schedule.getHallName(), schedule.getShowDate(), schedule.getShowTime(),
                schedule.getEndTime(), schedule.getLang(), schedule.getTotalSeats(),
                schedule.getAvailableSeats(), schedule.getPrice(),
                schedule.getStatus(), schedule.getVersion()
        );
    }

    private static Long parseLong(Object val) {
        return val == null ? null : Long.parseLong(val.toString());
    }

    private static Integer parseInt(Object val) {
        return val == null ? null : Integer.parseInt(val.toString());
    }

    /**
     * 删除场次缓存（库存 + 详情），改排片时调用
     */
    public void evictScheduleCache(Long scheduleId) {
        if (stringRedisTemplate == null) return;
        stringRedisTemplate.delete(CacheConstants.SCHEDULE_STOCK_PREFIX + scheduleId);
        stringRedisTemplate.delete(CacheConstants.SCHEDULE_DETAIL_PREFIX + scheduleId);
        log.info("[Stock] Evicted cache for scheduleId={}", scheduleId);
    }

    /**
     * 记录无法回滚的脏数据
     *
     * <p>当 Redis 回滚失败时，将 scheduleId 和应回滚的座位数记入脏列表，<br>
     * 由定时任务从 DB 拉真值强制覆盖 Redis，保证最终一致性。</p>
     */
    public void recordDirtyRollback(Long scheduleId, int seatCount) {
        if (stringRedisTemplate == null) return;
        String key = CacheConstants.DIRTY_ROLLBACK_KEY;
        stringRedisTemplate.opsForHash().put(key, String.valueOf(scheduleId), String.valueOf(seatCount));
    }

    /**
     * 获取所有脏数据（回滚失败的记录）
     *
     * @return Map&lt;scheduleId, seatCount&gt;
     */
    public Map<Object, Object> getDirtyRollbacks() {
        if (stringRedisTemplate == null) return Map.of();
        Map<Object, Object> entries = stringRedisTemplate.opsForHash()
                .entries(CacheConstants.DIRTY_ROLLBACK_KEY);
        return entries.isEmpty() ? Map.of() : entries;
    }

    /**
     * 清空脏数据列表（定时任务处理完后调用）
     */
    public void clearDirtyRollbacks() {
        if (stringRedisTemplate == null) return;
        stringRedisTemplate.delete(CacheConstants.DIRTY_ROLLBACK_KEY);
    }
}
