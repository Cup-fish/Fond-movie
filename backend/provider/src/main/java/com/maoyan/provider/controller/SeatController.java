package com.maoyan.provider.controller;

import com.maoyan.common.annotation.RateLimit;
import com.maoyan.common.enums.RateLimitAlgorithm;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.SeatLayoutVO;
import com.maoyan.service.SeatService;
import com.maoyan.service.infrastructure.SeatRequestBufferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 座位接口 — 选座+锁座核心（高并发安全 · 面试亮点）
 *
 * <h3>抢座流量治理架构：</h3>
 * <pre>
 *                       ┌─────────────────────────────────────────────────┐
 *     用户请求           │ 第一层: 令牌桶限流 (@RateLimit TOKEN_BUCKET)     │
 *        │              │   → 拦截恶意刷票，单用户维度，桶容量5/速率2每秒   │
 *        ↓              └──────────────────────┬──────────────────────────┘
 * ┌──────────────┐                             │
 * │ SeatController│──────────────────────────── ↓
 * └──────┬───────┘                    MQ 可用？
 *        │                          ┌────┴────┐
 *        │                         YES        NO
 *        │                          │         │
 *        │           ┌──────────────↓──┐   ┌──↓─────────────┐
 *        │           │ 第二层: MQ 缓冲  │   │ 降级: 直接同步锁座│
 *        │           │ 削峰填谷，排队处理│   │ (Redisson 分布式锁)│
 *        │           └────────┬────────┘   └────────────────┘
 *        │                    ↓
 *        │     ┌──────────────────────────────┐
 *        │     │ 第三层: 消费端令牌桶限速        │
 *        │     │ 全局维度，保护 DB，10请求/秒    │
 *        │     └──────────────┬───────────────┘
 *        │                    ↓
 *        │         SeatService.lockSeats()
 *        │         (Redisson 分布式锁 + DB 唯一索引)
 *        │                    │
 *        ↓                    ↓
 *    返回结果 ←───── CompletableFuture 回填
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/seat")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;
    private final SeatRequestBufferService bufferService;

    /**
     * 获取影厅座位布局（含实时锁定/已售状态）
     */
    @GetMapping("/layout")
    public Result<SeatLayoutVO> getSeatLayout(
            @RequestParam Long scheduleId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(seatService.getSeatLayout(scheduleId, userId != null ? userId : 0L));
    }

    /**
     * 锁定座位（高并发保护 — 令牌桶限流 + MQ 削峰）
     *
     * <p>限流策略：令牌桶算法，桶容量5（最大突发），每秒补充2个令牌</p>
     * <p>MQ 可用时走异步缓冲，不可用时降级到同步锁座</p>
     */
    @PostMapping("/lock")
    @RateLimit(key = "seat:lock", algorithm = RateLimitAlgorithm.TOKEN_BUCKET, capacity = 5, refillRate = 2)
    public Result<Map<String, Object>> lockSeats(
            @Valid @RequestBody LockSeatsDTO dto,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }

        // MQ 可用 → 走异步缓冲（削峰填谷）
        if (Boolean.getBoolean("maoyan.seat.buffered") && bufferService.isMQAvailable()) {
            return lockSeatsAsync(userId, dto);
        }

        // MQ 不可用 → 降级到直接同步调用（Redisson 分布式锁兜底）
        log.info("[Seat] Sync lock request: userId={}", userId);
        return Result.ok(seatService.lockSeats(userId, dto));
    }

    /**
     * 查询排队状态（前端轮询用）
     */
    @GetMapping("/queue-info")
    public Result<Map<String, Object>> getQueueInfo() {
        long depth = bufferService.getQueueDepth();
        return Result.ok(Map.of(
                "queueDepth", depth,
                "estimateWaitSeconds", depth / 10 + 1  // 每秒处理 10 个
        ));
    }

    /**
     * 释放座位锁定
     */
    @PostMapping("/unlock")
    public Result<Void> unlockSeats(
            @RequestParam Long scheduleId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        seatService.unlockSeats(userId, scheduleId);
        return Result.ok(null);
    }

    // =================== 内部方法 ===================

    /**
     * 异步锁座：提交到 MQ → 等待结果
     */
    private Result<Map<String, Object>> lockSeatsAsync(Long userId, LockSeatsDTO dto) {
        // 1. 提交到 MQ 缓冲队列
        String requestId = bufferService.submitLockRequest(userId, dto);

        // 2. 同步等待消费端处理结果（最多 5 秒）
        Map<String, Object> result = bufferService.waitForResult(requestId);

        if (result != null) {
            return Result.ok(result);
        } else {
            // 超时：请求仍在排队，提示用户稍后重试
            return Result.fail(408, "系统繁忙，您的请求正在排队处理中，请稍后重试");
        }
    }
}
