package com.maoyan.service.infrastructure;

import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.event.SeatLockRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 抢座请求缓冲服务 — MQ 削峰填谷 + 异步结果同步桥接（面试重点）
 *
 * <h3>核心架构（高并发抢座三板斧）：</h3>
 * <pre>
 * ┌──────────┐    令牌桶限流     ┌─────────────┐    MQ 缓冲     ┌──────────────┐
 * │ 用户请求  │ ──────────────→ │ BufferService │ ────────────→ │ RocketMQ Topic│
 * └──────────┘                 └──────┬──────┘                └──────┬───────┘
 *                                     │                              │
 *                               等待结果(5s)                   SeatLockConsumer
 *                                     │                         处理锁座请求
 *                                     ↓                              │
 *                            ┌────────────────┐                      │
 *                            │ CompletableFuture │ ←── 结果回填 ──────┘
 *                            └────────────────┘
 * </pre>
 *
 * <h3>降级策略：</h3>
 * <pre>
 * 1. MQ 不可用 → 直接同步调用 SeatService（降级到分布式锁模式）
 * 2. 队列堆积过多 → 拒绝请求（返回 QUEUE_FULL）
 * 3. 等待超时 → 返回"系统繁忙"（请求可能仍在处理中）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatRequestBufferService {

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 内存中的 Future 映射表（单实例有效）
     * <p>Key: requestId, Value: CompletableFuture 等待消费结果</p>
     */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>>
            pendingRequests = new ConcurrentHashMap<>();

    /** 等待结果超时时间(秒) */
    private static final int WAIT_TIMEOUT_SECONDS = 5;

    /**
     * 提交抢座请求到 MQ 队列（异步缓冲）
     *
     * @return requestId 请求唯一标识
     * @throws BizException 队列满/MQ 不可用时抛出
     */
    public String submitLockRequest(Long userId, LockSeatsDTO dto) {
        // 1. 检查队列深度（防止无限堆积）
        if (!checkQueueDepth()) {
            throw new BizException(ResponseCodeEnum.QUEUE_FULL);
        }

        // 2. 生成唯一请求 ID
        String requestId = UUID.randomUUID().toString().replace("-", "");

        // 3. 构建事件消息
        SeatLockRequestEvent event = new SeatLockRequestEvent();
        event.setRequestId(requestId);
        event.setUserId(userId);
        event.setScheduleId(dto.getScheduleId());
        event.setSeats(dto.getSeats().stream()
                .map(s -> new SeatLockRequestEvent.SeatPos(s.getRow(), s.getCol()))
                .toList());
        event.setTimestamp(System.currentTimeMillis());

        // 4. 注册 Future（等待消费端回填结果）
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // 5. 发布到 RocketMQ
        try {
            String destination = MQConstants.SEAT_LOCK_TOPIC + ":" + MQConstants.TAG_SEAT_LOCK;
            rocketMQTemplate.syncSend(destination, event);
            incrementQueueDepth();
            log.info("[SeatBuffer] Request submitted: requestId={}, userId={}, scheduleId={}, seats={}",
                    requestId, userId, dto.getScheduleId(), dto.getSeats().size());
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            log.error("[SeatBuffer] Failed to send to MQ, requestId={}", requestId, e);
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "系统繁忙，请重试");
        }

        return requestId;
    }

    /**
     * 等待抢座结果（阻塞等待，超时返回 null）
     *
     * <p>Controller 调用此方法同步等待异步消费的结果</p>
     */
    public Map<String, Object> waitForResult(String requestId) {
        CompletableFuture<Map<String, Object>> future = pendingRequests.get(requestId);
        if (future == null) {
            return null;
        }

        try {
            Map<String, Object> result = future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result;
        } catch (TimeoutException e) {
            log.warn("[SeatBuffer] Wait timeout: requestId={}", requestId);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            log.error("[SeatBuffer] Execution error: requestId={}", requestId, e);
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "锁座处理失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "请求被中断");
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    /**
     * 完成请求（由 Consumer 回调）
     */
    public void completeRequest(String requestId, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pendingRequests.get(requestId);
        if (future != null) {
            future.complete(result);
            log.debug("[SeatBuffer] Request completed: requestId={}", requestId);
        } else {
            // Future 已超时被清理，将结果写入 Redis 以便客户端轮询
            if (stringRedisTemplate != null) {
                try {
                    String key = CacheConstants.SEAT_REQUEST_RESULT_PREFIX + requestId;
                    stringRedisTemplate.opsForValue().set(key, "SUCCESS",
                            CacheConstants.SEAT_REQUEST_RESULT_TTL, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("[SeatBuffer] Failed to cache result to Redis", e);
                }
            }
        }
        decrementQueueDepth();
    }

    /**
     * 请求异常完成（由 Consumer 回调）
     */
    public void failRequest(String requestId, Exception ex) {
        CompletableFuture<Map<String, Object>> future = pendingRequests.get(requestId);
        if (future != null) {
            future.completeExceptionally(ex);
            log.debug("[SeatBuffer] Request failed: requestId={}", requestId);
        }
        decrementQueueDepth();
    }

    /**
     * 检查是否 MQ 可用（可作为降级判断条件）
     */
    public boolean isMQAvailable() {
        return rocketMQTemplate != null;
    }

    /**
     * 获取当前排队人数
     */
    public long getQueueDepth() {
        if (stringRedisTemplate == null) return 0;
        try {
            String val = stringRedisTemplate.opsForValue().get(CacheConstants.SEAT_QUEUE_DEPTH_KEY);
            return val != null ? Long.parseLong(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // =================== 内部方法 ===================

    private boolean checkQueueDepth() {
        long depth = getQueueDepth();
        if (depth >= CacheConstants.SEAT_QUEUE_MAX_DEPTH) {
            log.warn("[SeatBuffer] Queue full! depth={}, max={}", depth, CacheConstants.SEAT_QUEUE_MAX_DEPTH);
            return false;
        }
        return true;
    }

    private void incrementQueueDepth() {
        if (stringRedisTemplate == null) return;
        try {
            stringRedisTemplate.opsForValue().increment(CacheConstants.SEAT_QUEUE_DEPTH_KEY);
            // 设置过期时间防止计数泄漏
            stringRedisTemplate.expire(CacheConstants.SEAT_QUEUE_DEPTH_KEY, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[SeatBuffer] Failed to increment queue depth", e);
        }
    }

    private void decrementQueueDepth() {
        if (stringRedisTemplate == null) return;
        try {
            stringRedisTemplate.opsForValue().decrement(CacheConstants.SEAT_QUEUE_DEPTH_KEY);
        } catch (Exception e) {
            log.warn("[SeatBuffer] Failed to decrement queue depth", e);
        }
    }
}
