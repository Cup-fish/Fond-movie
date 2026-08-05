package com.maoyan.domain.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 收银台订单摘要对象（模拟网关侧，无用户态查询）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MockOrderInfoVO implements Serializable {

    private String orderNo;
    private String movieName;
    private String cinemaName;
    private String hallName;
    private String showTime;
    private String seatsInfo;
    private BigDecimal totalPrice;
    private String expireTime;
    /** 0=待支付 1=已支付 2=已取消 3=已退款 */
    private Integer orderStatus;
}
