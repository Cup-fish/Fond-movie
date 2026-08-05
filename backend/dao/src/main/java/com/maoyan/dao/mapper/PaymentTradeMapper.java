package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.PaymentTradePO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 支付单 Mapper（模拟支付网关交易单）
 */
public interface PaymentTradeMapper extends BaseMapper<PaymentTradePO> {

    @Select("SELECT * FROM payment_trade WHERE order_no = #{orderNo} AND deleted = 0 LIMIT 1")
    PaymentTradePO selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM payment_trade WHERE payment_no = #{paymentNo} AND deleted = 0 LIMIT 1")
    PaymentTradePO selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /**
     * CAS：待支付 → 已支付（幂等，重复回调只会成功一次）
     */
    @Update("UPDATE payment_trade SET status = 1, pay_time = #{now}, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_no = #{orderNo} AND status = 0 AND deleted = 0")
    int markPaid(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    /**
     * 关单联动：待支付 → 已关闭（订单超时/取消时同步关闭支付单）
     */
    @Update("UPDATE payment_trade SET status = 2, update_time = CURRENT_TIMESTAMP " +
            "WHERE order_no = #{orderNo} AND status = 0 AND deleted = 0")
    int closeByOrderNo(@Param("orderNo") String orderNo);
}
