package com.maoyan.domain.model.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 抢座请求事件 — 通过 MQ 异步缓冲处理（削峰填谷）
 *
 * <h3>流转过程：</h3>
 * <pre>
 * Controller 接收 HTTP 请求
 *     ↓ 令牌桶限流（拦截恶意刷票）
 * 发布 SeatLockRequestEvent 到 MQ
 *     ↓ 队列缓冲（削峰）
 * SeatLockConsumer 消费 → 调用 SeatService.lockSeats()
 *     ↓ 结果写入 Redis
 * Controller 轮询 Redis 获取结果（或超时降级）
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatLockRequestEvent implements Serializable {

    /** 请求唯一标识（用于关联异步结果） */
    private String requestId;

    /** 用户 ID */
    private Long userId;

    /** 场次 ID */
    private Long scheduleId;

    /** 选座列表 */
    private List<SeatPos> seats;

    /** 请求时间戳 */
    private long timestamp;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatPos implements Serializable {
        private Integer row;
        private Integer col;
    }
}
