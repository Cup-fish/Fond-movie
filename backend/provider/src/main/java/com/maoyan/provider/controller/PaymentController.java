package com.maoyan.provider.controller;

import com.maoyan.common.annotation.RateLimit;
import com.maoyan.common.enums.RateLimitAlgorithm;
import com.maoyan.domain.model.vo.MockOrderInfoVO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.domain.model.vo.PaymentStatusVO;
import com.maoyan.domain.model.vo.PaymentTradeVO;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付接口 — 模拟支付网关（扫码支付）
 *
 * <pre>
 * - POST /api/payment/create           → 创建支付单（登录态）
 * - GET  /api/payment/status           → 支付状态轮询（登录态）
 * - POST /api/payment/mock/notify      → 模拟网关回调（无用户态，验签）
 * - GET  /api/payment/mock/order-info  → 收银台订单摘要（无用户态）
 * - GET  /api/payment/orderDetail      → 订单详情（登录态）
 * </pre>
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 创建支付单（幂等）— 扫码支付入口
     */
    @PostMapping("/create")
    @RateLimit(key = "payment:create", algorithm = RateLimitAlgorithm.TOKEN_BUCKET, capacity = 5, refillRate = 2)
    public Result<PaymentTradeVO> create(
            @RequestParam String orderNo,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(paymentService.createPayment(userId, orderNo));
    }

    /**
     * 支付状态轮询（支付页每 3s 调用）
     */
    @GetMapping("/status")
    @RateLimit(key = "payment:status", algorithm = RateLimitAlgorithm.TOKEN_BUCKET, capacity = 5, refillRate = 2)
    public Result<PaymentStatusVO> status(
            @RequestParam String orderNo,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(paymentService.getPaymentStatus(userId, orderNo));
    }

    /**
     * 模拟支付网关回调（无用户态）
     *
     * <p>演示模式：sign 可选（HmacSHA256(orderNo, 密钥)），未传则放行。
     * 真实环境此端点替换为支付宝/微信官方回调，走官方验签。</p>
     */
    @PostMapping("/mock/notify")
    public Result<Void> mockNotify(@RequestBody(required = false) Map<String, String> body) {
        String orderNo = body != null ? body.get("orderNo") : null;
        String paymentNo = body != null ? body.get("paymentNo") : null;
        String sign = body != null ? body.get("sign") : null;
        if (orderNo == null || orderNo.isBlank()) {
            return Result.fail(400, "orderNo不能为空");
        }
        paymentService.mockNotify(orderNo, paymentNo, sign);
        return Result.ok();
    }

    /**
     * 收银台订单摘要（无用户态，扫码打开的收银台页面调用）
     */
    @GetMapping("/mock/order-info")
    public Result<MockOrderInfoVO> mockOrderInfo(@RequestParam String orderNo) {
        return Result.ok(paymentService.getMockOrderInfo(orderNo));
    }

    /**
     * 查询订单详情（含座位信息）
     */
    @GetMapping("/orderDetail")
    public Result<OrderVO> getOrderDetail(
            @RequestParam String orderNo,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(paymentService.getOrderDetail(userId, orderNo));
    }
}
