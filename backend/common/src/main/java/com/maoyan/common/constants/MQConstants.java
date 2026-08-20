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
    /** 订单事件 Topic（创建/支付/取消/超时关单） */
    public static final String ORDER_TOPIC = "maoyan_order_event";

    /** 想看写回 Topic */
    public static final String WISH_TOPIC = "maoyan_wish_writeback";

    // =================== Tag ===================
    public static final String TAG_WISH_WRITEBACK = "WISH_WRITEBACK";

    // =================== Consumer Group ===================
    public static final String ORDER_CONSUMER_GROUP = "maoyan_order_consumer_group";
    public static final String WISH_CONSUMER_GROUP = "maoyan_wish_consumer_group";
}
