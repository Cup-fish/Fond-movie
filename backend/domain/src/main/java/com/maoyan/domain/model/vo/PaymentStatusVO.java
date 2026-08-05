package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 支付轮询状态对象（支付页每 3s 轮询）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentStatusVO implements Serializable {

    /** 支付单状态：0=待支付 1=已支付 2=已关闭 */
    private Integer paymentStatus;

    /** 订单状态：0=待支付 1=已支付 2=已取消 3=已退款 */
    private Integer orderStatus;

    private String orderStatusDesc;

    private String payTime;

    /** 支付后剩余积分（支付成功时返回） */
    private Integer remainingPoints;
}
