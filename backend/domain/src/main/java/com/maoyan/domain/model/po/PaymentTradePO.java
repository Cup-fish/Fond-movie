package com.maoyan.domain.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maoyan.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单持久化对象（模拟真实支付网关的交易单）
 *
 * <p>支付单与业务订单分离，生命周期独立：
 * 待支付(0) → 已支付(1) / 已关闭(2)</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_trade")
public class PaymentTradePO extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 支付单号（网关侧，分布式唯一） */
    private String paymentNo;

    /** 关联业务订单号 */
    private String orderNo;

    /** 用户ID */
    private Long userId;

    /** 支付金额（积分） */
    private BigDecimal totalPrice;

    /**
     * 支付单状态
     * <ul>
     *   <li>0 - 待支付</li>
     *   <li>1 - 已支付</li>
     *   <li>2 - 已关闭</li>
     * </ul>
     */
    private Integer status;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 过期时间（与订单支付截止时间对齐） */
    private LocalDateTime expireTime;
}
