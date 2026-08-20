package com.maoyan.service.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;

/**
 * MQ 消费幂等服务 — 基于 Redis 原子 Lua 实现。
 *
 * <p>RocketMQ 是至少一次投递，重复消费时必须跳过已处理的事件。
 * 使用 done 标记 + processing 锁两个 key：
 * <ul>
 *   <li>tryProcess：原子检查 done，并尝试获取 processing 锁；</li>
 *   <li>markDone：成功后写 done 标记并释放 processing 锁；</li>
 *   <li>release：失败后释放 processing 锁，允许 RocketMQ 重试。</li>
 * </ul>
 * Redis 不可用时选择放行（fail-open），由业务自身的 DB 唯一约束/状态机兜底。</p>
 */
@Slf4j
@Service
public class MqIdempotencyService {

    private static final String PROCESSING_PREFIX = "mq:processing:";
    private static final String DONE_PREFIX = "mq:done:";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(5);
    private static final Duration DONE_TTL = Duration.ofDays(7);

    private static final String TRY_PROCESS_LUA = """
            local done_key       = KEYS[1]
            local processing_key = KEYS[2]
            local ttl            = tonumber(ARGV[1])

            if redis.call('EXISTS', done_key) == 1 then
                return 0
            end

            local ok = redis.call('SET', processing_key, '1', 'NX', 'EX', ttl)
            if ok then
                return 1
            end
            return 0
            """;

    private final DefaultRedisScript<Long> tryProcessScript = new DefaultRedisScript<>(TRY_PROCESS_LUA, Long.class);

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取事件处理权。
     *
     * @return true 表示本次可以继续处理；false 表示已处理过或正在处理，应跳过
     */
    public boolean tryProcess(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (stringRedisTemplate == null) {
            return true;
        }
        try {
            String doneKey = DONE_PREFIX + eventId;
            String processingKey = PROCESSING_PREFIX + eventId;
            Long result = stringRedisTemplate.execute(
                    tryProcessScript,
                    Arrays.asList(doneKey, processingKey),
                    String.valueOf(PROCESSING_TTL.getSeconds())
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[MqIdempotency] tryProcess failed for eventId={}, allow processing", eventId, e);
            return true;
        }
    }

    /**
     * 处理成功后标记为已完成。
     */
    public void markDone(String eventId) {
        if (eventId == null || eventId.isBlank() || stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(DONE_PREFIX + eventId, "1", DONE_TTL);
            stringRedisTemplate.delete(PROCESSING_PREFIX + eventId);
        } catch (Exception e) {
            log.warn("[MqIdempotency] markDone failed for eventId={}", eventId, e);
        }
    }

    /**
     * 处理失败后释放 processing 锁，允许后续重试。
     */
    public void release(String eventId) {
        if (eventId == null || eventId.isBlank() || stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(PROCESSING_PREFIX + eventId);
        } catch (Exception e) {
            log.warn("[MqIdempotency] release failed for eventId={}", eventId, e);
        }
    }
}
