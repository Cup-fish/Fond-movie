package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付单展示对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentTradeVO implements Serializable {

    private String paymentNo;
    private String orderNo;
    private BigDecimal totalPrice;
    /** 0=待支付 1=已支付 2=已关闭 */
    private Integer status;
    private String statusDesc;
    private String createTime;
    private String payTime;
    private String expireTime;
}
