package com.maoyan.common.constants;

/**
 * RocketMQ 常量定义
 *
 * <p>RocketMQ 模型：Topic + Tag + ConsumerGroup</p>
 * <p>命名规范：{业务域}_{动作}_{类型}</p>
 */
public final class MQConstants {

    private MQConstants() {}

    // =================== Topic ===================
    /** 订单事件 Topic（创建/支付/取消/退款） */
    public static final String ORDER_TOPIC = "maoyan_order_event";

    /** 想看写回 Topic */
    public static final String WISH_TOPIC = "maoyan_wish_writeback";

    /** 缓存失效 Topic */
    public static final String CACHE_TOPIC = "maoyan_cache_invalidate";

    /** 抢座请求缓冲 Topic（MQ 削峰填谷 + 令牌桶限速消费） */
    public static final String SEAT_LOCK_TOPIC = "maoyan_seat_lock_request";

    // =================== Tag ===================
    public static final String TAG_ORDER_CREATED = "ORDER_CREATED";
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    public static final String TAG_ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String TAG_ORDER_REFUNDED = "ORDER_REFUNDED";
    public static final String TAG_WISH_WRITEBACK = "WISH_WRITEBACK";
    public static final String TAG_CACHE_INVALIDATE = "CACHE_INVALIDATE";
    public static final String TAG_SEAT_LOCK = "SEAT_LOCK";

    // =================== Consumer Group ===================
    public static final String ORDER_CONSUMER_GROUP = "maoyan_order_consumer_group";
    public static final String WISH_CONSUMER_GROUP = "maoyan_wish_consumer_group";
    public static final String CACHE_CONSUMER_GROUP = "maoyan_cache_consumer_group";
    public static final String SEAT_LOCK_CONSUMER_GROUP = "maoyan_seat_lock_consumer_group";
}
