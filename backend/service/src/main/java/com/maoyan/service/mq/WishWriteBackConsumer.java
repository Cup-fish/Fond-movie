package com.maoyan.service.mq;

import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.MovieMapper;
import com.maoyan.dao.mapper.UserWishMapper;
import com.maoyan.domain.model.event.WishEvent;
import com.maoyan.domain.model.po.UserWishPO;
import com.maoyan.service.infrastructure.MqIdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 想看写回消费者 — RocketMQ 版
 *
 * <p>订阅 WISH_TOPIC，异步写回 DB，保证最终一致性</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = MQConstants.WISH_TOPIC,
        consumerGroup = MQConstants.WISH_CONSUMER_GROUP
)
public class WishWriteBackConsumer implements RocketMQListener<WishEvent> {

    private final MovieMapper movieMapper;
    private final UserWishMapper userWishMapper;
    private final MqIdempotencyService mqIdempotencyService;

    @Override
    public void onMessage(WishEvent event) {
        String eventId = event.getEventId();
        String idempotencyKey = eventId != null ? eventId
                : "wish:" + event.getUserId() + ":" + event.getMovieId() + ":" + event.getTimestamp();
        if (!mqIdempotencyService.tryProcess(idempotencyKey)) {
            log.info("[WishConsumer] Duplicate event skipped: eventId={}, userId={}, movieId={}",
                    eventId, event.getUserId(), event.getMovieId());
            return;
        }

        try {
            // 只有真正新增 user_wish 记录才累加计数，重复消息不会重复 +1
            UserWishPO wish = new UserWishPO();
            wish.setUserId(event.getUserId());
            wish.setMovieId(event.getMovieId());
            wish.setCreateTime(LocalDateTime.now());
            int inserted = userWishMapper.insert(wish);
            if (inserted > 0) {
                movieMapper.incrementWish(event.getMovieId());
            }
            mqIdempotencyService.markDone(idempotencyKey);
            log.debug("[WishConsumer] Writeback success: userId={}, movieId={}", event.getUserId(), event.getMovieId());
        } catch (Exception e) {
            // 唯一索引冲突 = 已写回过，不重复计数，视为成功
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                mqIdempotencyService.markDone(idempotencyKey);
                log.debug("[WishConsumer] Wish record already exists: userId={}, movieId={}",
                        event.getUserId(), event.getMovieId());
                return;
            }
            mqIdempotencyService.release(idempotencyKey);
            log.error("[WishConsumer] Writeback failed: userId={}, movieId={}", event.getUserId(), event.getMovieId(), e);
            throw new RuntimeException("想看写回处理失败，触发重试", e);
        }
    }
}
