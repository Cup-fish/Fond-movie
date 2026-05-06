package com.maoyan.service.mq;

import com.maoyan.common.constants.MQConstants;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.event.SeatLockRequestEvent;
import com.maoyan.service.SeatService;
import com.maoyan.service.infrastructure.RateLimiterService;
import com.maoyan.service.infrastructure.SeatRequestBufferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 抢座请求消费者 — RocketMQ 消费端（面试核心亮点）
 *
 * <h3>消费端限速架构（令牌桶保护DB）：</h3>
 * <pre>
 * RocketMQ Topic → SeatLockConsumer
 *                     │
 *              消费端令牌桶限速（10请求/秒）
 *                     │
 *              SeatService.lockSeats()
 *                     │
 *              CompletableFuture 回填结果
 * </pre>
 *
 * <h3>RocketMQ 优势（vs RabbitMQ）：</h3>
 * <ul>
 *   <li>单机 10万+ TPS，百万消息堆积不影响性能</li>
 *   <li>顺序消费模式（ConsumeMode.ORDERLY）保证同一场次的请求按序处理</li>
 *   <li>自动重试机制（默认重试16次，指数退避）</li>
 *   <li>死信队列（%DLQ%）自动路由处理失败的消息</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = MQConstants.SEAT_LOCK_TOPIC,
        consumerGroup = MQConstants.SEAT_LOCK_CONSUMER_GROUP,
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 3
)
public class SeatLockConsumer implements RocketMQListener<SeatLockRequestEvent> {

    private final SeatService seatService;
    private final SeatRequestBufferService bufferService;
    private final RateLimiterService rateLimiterService;

    private static final String CONSUMER_RATE_KEY = "seat:consumer";
    private static final String CONSUMER_RATE_ID = "global";
    private static final int CONSUMER_BUCKET_CAPACITY = 15;
    private static final int CONSUMER_REFILL_RATE = 10;

    @Override
    public void onMessage(SeatLockRequestEvent event) {
        String requestId = event.getRequestId();
        long age = System.currentTimeMillis() - event.getTimestamp();

        // 超龄检查：超过 10 秒的请求直接丢弃
        if (age > 10_000) {
            bufferService.failRequest(requestId, new BizException(408, "请求已超时，请重新选座"));
            return;
        }

        // 消费端令牌桶限速（保护 DB）
        if (!rateLimiterService.isAllowedTokenBucket(
                CONSUMER_RATE_KEY, CONSUMER_RATE_ID,
                CONSUMER_BUCKET_CAPACITY, CONSUMER_REFILL_RATE)) {
            // RocketMQ 抛异常会自动重试（指数退避）
            throw new RuntimeException("消费端令牌桶限速，等待重试");
        }

        try {
            LockSeatsDTO dto = convertToDTO(event);
            Map<String, Object> result = seatService.lockSeats(event.getUserId(), dto);
            bufferService.completeRequest(requestId, result);
            log.debug("[SeatConsumer] Success: requestId={}, userId={}", requestId, event.getUserId());
        } catch (BizException e) {
            // 业务异常不重试，直接回填失败
            bufferService.failRequest(requestId, e);
        } catch (Exception e) {
            // 系统异常：抛出让 RocketMQ 自动重试
            log.error("[SeatConsumer] Error: requestId={}", requestId, e);
            bufferService.failRequest(requestId, e);
            throw new RuntimeException("锁座处理异常，触发重试", e);
        }
    }

    private LockSeatsDTO convertToDTO(SeatLockRequestEvent event) {
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setScheduleId(event.getScheduleId());
        dto.setSeats(event.getSeats().stream()
                .map(s -> { LockSeatsDTO.SeatPos pos = new LockSeatsDTO.SeatPos(); pos.setRow(s.getRow()); pos.setCol(s.getCol()); return pos; })
                .toList());
        return dto;
    }
}
