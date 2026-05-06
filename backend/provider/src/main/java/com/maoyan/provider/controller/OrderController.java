package com.maoyan.provider.controller;

import com.maoyan.biz.OrderBiz;
import com.maoyan.common.annotation.RateLimit;
import com.maoyan.common.enums.RateLimitAlgorithm;
import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.domain.model.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单接口（全部需要登录）
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderBiz orderBiz;

    /**
     * 创建订单（防超卖核心接口）
     */
    @PostMapping("/create")
    @RateLimit(key = "order:create", algorithm = RateLimitAlgorithm.TOKEN_BUCKET, capacity = 10, refillRate = 3)
    public Result<OrderVO> createOrder(@Valid @RequestBody CreateOrderDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(orderBiz.createOrder(userId, dto));
    }

    /**
     * 取消订单
     */
    @PostMapping("/cancel/{orderNo}")
    public Result<Void> cancelOrder(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        orderBiz.cancelOrder(userId, orderNo);
        return Result.ok(null);
    }

    /**
     * 查询用户订单列表
     */
    @GetMapping("/list")
    public Result<List<OrderVO>> getUserOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(orderBiz.getUserOrders(userId, page, size));
    }
}
