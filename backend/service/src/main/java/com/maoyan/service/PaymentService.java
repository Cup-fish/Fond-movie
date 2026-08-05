package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.common.constants.CommonConstants;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.PaymentTradeMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.PaymentTradePO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.MockOrderInfoVO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.domain.model.vo.PaymentStatusVO;
import com.maoyan.domain.model.vo.PaymentTradeVO;
import com.maoyan.service.infrastructure.OutboxService;
import com.maoyan.service.infrastructure.QueueService;
import com.maoyan.service.infrastructure.SeatLockScriptService;
import com.maoyan.service.infrastructure.SeatSoldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付服务 — 模拟支付网关（扫码支付）
 *
 * <h3>支付链路（与真实网关对齐）：</h3>
 * <pre>
 * 创建支付单（幂等） → 用户扫码进入收银台 → 确认付款 → 网关回调 mockNotify
 *   → 验签（可选）→ settleOrder 落账：CAS 扣积分 → orders WAIT_PAY→PAID
 *   → seat_lock 1→2 → outbox ORDER_PAID → Redis 投影 → 支付单置已支付
 *   → 支付页轮询 /status 检测到已支付 → 展示支付成功
 * </pre>
 *
 * <p>支付单（payment_trade）与业务订单（ticket_order）分离，生命周期独立；
 * 未来接入真实微信/支付宝，仅需替换 mock 回调为官方 SDK 回调 + 官方验签。</p>
 */
@Slf4j
@Service
public class PaymentService {

    private final OrderMapper orderMapper;
    private final SeatLockMapper seatLockMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final UserMapper userMapper;
    private final PaymentTradeMapper paymentTradeMapper;
    private final SeatLockScriptService lockScriptService;
    private final SeatSoldService soldService;
    private final OutboxService outboxService;
    private final QueueService queueService;
    private final OrderService orderService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PAY_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public PaymentService(OrderMapper orderMapper,
                          SeatLockMapper seatLockMapper,
                          OrderSeatMapper orderSeatMapper,
                          UserMapper userMapper,
                          PaymentTradeMapper paymentTradeMapper,
                          SeatLockScriptService lockScriptService,
                          SeatSoldService soldService,
                          OutboxService outboxService,
                          QueueService queueService,
                          OrderService orderService) {
        this.orderMapper = orderMapper;
        this.seatLockMapper = seatLockMapper;
        this.orderSeatMapper = orderSeatMapper;
        this.userMapper = userMapper;
        this.paymentTradeMapper = paymentTradeMapper;
        this.lockScriptService = lockScriptService;
        this.soldService = soldService;
        this.outboxService = outboxService;
        this.queueService = queueService;
        this.orderService = orderService;
    }

    // =================== 扫码支付：创建 / 轮询 / 回调 ===================

    /**
     * 创建支付单（幂等）— 扫码支付入口
     *
     * <p>事务保证懒过期关单（可能 5+ 个 DB 写 + outbox）与支付单创建原子提交</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public PaymentTradeVO createPayment(Long userId, String orderNo) {
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        checkPayable(order, now);

        // 前置校验积分充足（结算时仍会二次校验，此处提前告知）
        int pointsCost = order.getTotalPrice().setScale(0, RoundingMode.HALF_UP).intValue();
        UserPO user = userMapper.selectById(userId);
        if (user == null || user.getPoints() == null || user.getPoints() < pointsCost) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(),
                    "积分不足，需要" + pointsCost + "积分，当前" + (user != null ? user.getPoints() : 0) + "积分");
        }

        // 幂等：同一订单的支付单已存在则直接返回
        PaymentTradePO existing = paymentTradeMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            return toVO(existing);
        }

        PaymentTradePO po = new PaymentTradePO();
        po.setPaymentNo(generatePaymentNo(userId));
        po.setOrderNo(orderNo);
        po.setUserId(userId);
        po.setTotalPrice(order.getTotalPrice());
        po.setStatus(0);
        po.setExpireTime(order.getExpireTime());
        try {
            paymentTradeMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发创建同一订单的支付单 → order_no 唯一索引兜底，复用已存在的
            log.warn("[Payment] Duplicate payment trade for orderNo={}, reuse existing", orderNo);
            return toVO(paymentTradeMapper.selectByOrderNo(orderNo));
        }
        log.info("[Payment] Trade created: paymentNo={}, orderNo={}, amount={}",
                po.getPaymentNo(), orderNo, order.getTotalPrice());
        return toVO(po);
    }

    /**
     * 支付状态轮询（支付页每 3s 调用，懒过期检查）
     *
     * <p>事务保证懒过期关单（5+ DB 写 + outbox）原子提交</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public PaymentStatusVO getPaymentStatus(Long userId, String orderNo) {
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        // 懒过期：仅待支付订单过期时关单（支付单联动关闭）；已支付/已取消直接返回终态
        if (order.getStatus() == OrderStatusEnum.PENDING.getCode()
                && order.getExpireTime() != null && now.isAfter(order.getExpireTime())) {
            orderService.closePendingOrder(order, "PAYMENT_POLL_EXPIRE");
            order = selectUserOrder(userId, orderNo); // 刷新为已取消状态
        }

        PaymentStatusVO vo = new PaymentStatusVO();
        vo.setOrderStatus(order.getStatus());
        vo.setOrderStatusDesc(OrderStatusEnum.of(order.getStatus()).getDesc());
        PaymentTradePO pay = paymentTradeMapper.selectByOrderNo(orderNo);
        if (pay != null) {
            vo.setPaymentStatus(pay.getStatus());
            if (pay.getPayTime() != null) {
                vo.setPayTime(pay.getPayTime().format(FMT));
            }
        }
        // 支付成功时附带剩余积分，前端直接更新
        if (order.getStatus() == OrderStatusEnum.PAID.getCode()) {
            UserPO user = userMapper.selectById(userId);
            if (user != null) {
                vo.setRemainingPoints(user.getPoints());
            }
        }
        return vo;
    }

    /**
     * 模拟支付网关回调（无用户态，验签后落账）
     *
     * <p>演示模式：sign 可选，传入则校验（HmacSHA256），未传放行并记 warn。
     * 真实网关由支付平台签名，密钥不暴露给前端。</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public void mockNotify(String orderNo, String sign) {
        verifySign(orderNo, sign);

        PaymentTradePO pay = paymentTradeMapper.selectByOrderNo(orderNo);
        if (pay == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "支付单不存在");
        }
        // 幂等：已支付直接返回成功（订单状态机 CAS 保证不重复扣款）
        if (pay.getStatus() == 1) {
            log.info("[Payment] Mock notify ignored (already paid): orderNo={}", orderNo);
            return;
        }
        if (pay.getStatus() == 2) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "支付单已关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        if (pay.getExpireTime() != null && now.isAfter(pay.getExpireTime())) {
            // 完整关单：关订单 + 回滚库存 + 释放 Redis 锁 + 关支付单 + outbox（事务内原子）
            closeOrderOnExpired(orderNo, "PAYMENT_TRADE_EXPIRE");
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED.getCode(), "支付单已过期");
        }

        // 落账（内部 CAS 保证订单状态机只推进一次）
        settleOrder(pay.getUserId(), orderNo);

        int updated = paymentTradeMapper.markPaid(orderNo, now);
        if (updated == 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "支付单状态已变化");
        }
        log.info("[Payment] Mock notify processed: orderNo={}", orderNo);
    }

    /**
     * 收银台订单摘要（无用户态查询，供扫码打开的收银台展示）
     */
    public MockOrderInfoVO getMockOrderInfo(String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getDeleted, 0);
        OrderPO order = orderMapper.selectOne(wrapper);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }

        MockOrderInfoVO vo = new MockOrderInfoVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setMovieName(order.getMovieName());
        vo.setCinemaName(order.getCinemaName());
        vo.setHallName(order.getHallName());
        vo.setShowTime(order.getShowTime());
        vo.setSeatsInfo(order.getSeatsInfo());
        vo.setTotalPrice(order.getTotalPrice());
        vo.setOrderStatus(order.getStatus());
        if (order.getExpireTime() != null) {
            vo.setExpireTime(order.getExpireTime().format(FMT));
        }
        return vo;
    }

    /**
     * 查询订单详情（含座位信息）
     */
    public OrderVO getOrderDetail(Long userId, String orderNo) {
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        return toVO(order);
    }

    // =================== 落账核心（原 payOrder 提取，事务由调用方承担） ===================

    /**
     * 支付落账 — DB 强一致 + 懒过期
     *
     * <p>由 mockNotify 的事务调用；订单状态机 CAS（markOrderPaid）保证幂等，
     * 重复回调不会重复扣款。</p>
     */
    private OrderVO settleOrder(Long userId, String orderNo) {
        // 1. 查订单
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }

        // 2. 懒过期校验
        LocalDateTime now = LocalDateTime.now();
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单状态不允许支付");
        }
        if (order.getExpireTime() != null && now.isAfter(order.getExpireTime())) {
            orderService.closePendingOrder(order, "PAYMENT_SETTLE_EXPIRE");
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }

        // 3. 验证锁座记录
        List<SeatLockPO> locks = seatLockMapper.selectLocksByOrderNo(orderNo);
        if (locks.size() != order.getSeatCount()) {
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }

        // 4. CAS 扣积分
        int pointsCost = order.getTotalPrice().setScale(0, RoundingMode.HALF_UP).intValue();
        UserPO user = userMapper.selectById(userId);
        if (user == null || user.getPoints() == null || user.getPoints() < pointsCost) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(),
                    "积分不足，需要" + pointsCost + "积分，当前" + (user != null ? user.getPoints() : 0) + "积分");
        }
        int pointAffected = userMapper.deductPoints(userId, pointsCost);
        if (pointAffected == 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "积分扣减失败，请重试");
        }

        // 5. CAS 状态机推进 orders WAIT_PAY → PAID
        int paid = orderMapper.markOrderPaid(orderNo, now);
        if (paid == 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单状态已变化，请刷新后重试");
        }

        // 6. 写入已售座位明细 + 座位落定（DB 权威！）
        List<LockSeatsDTO.SeatPos> seatPositions = new ArrayList<>();
        for (SeatLockPO lock : locks) {
            OrderSeatPO seat = new OrderSeatPO();
            seat.setOrderId(order.getId());
            seat.setOrderNo(orderNo);
            seat.setScheduleId(order.getScheduleId());
            seat.setRowNum(lock.getRowNum());
            seat.setColNum(lock.getColNum());
            seat.setSeatLabel(lock.getRowNum() + "排" + lock.getColNum() + "座");
            seat.setCreateTime(now);
            orderSeatMapper.insert(seat);

            LockSeatsDTO.SeatPos pos = new LockSeatsDTO.SeatPos();
            pos.setRow(lock.getRowNum());
            pos.setCol(lock.getColNum());
            seatPositions.add(pos);
        }

        // seat_lock status=1→2（已售，DB 权威！）
        seatLockMapper.markAsPurchased(orderNo, now);

        // 7. 写 outbox（与业务同事务，保证事件最终发出）
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("type", "ORDER_PAID");
        eventPayload.put("orderNo", orderNo);
        eventPayload.put("userId", userId);
        eventPayload.put("scheduleId", order.getScheduleId());
        eventPayload.put("seatCount", order.getSeatCount());
        eventPayload.put("totalPrice", order.getTotalPrice());
        eventPayload.put("timestamp", System.currentTimeMillis());
        outboxService.writeEvent("ORDER_PAID", eventPayload);

        // 8. 事务后 best-effort：更新 Redis 投影
        updateRedisProjection(order.getScheduleId(), seatPositions, userId);

        // ★ 释放排队入场名额（用户已完成支付，名额让给排队者）
        queueService.leave(order.getScheduleId(), userId);

        log.info("[Payment] Order paid: orderNo={}, userId={}, total={}, pointsAfter={}",
                orderNo, userId, order.getTotalPrice(), user.getPoints() - pointsCost);

        OrderVO vo = toVO(order);
        vo.setStatus(OrderStatusEnum.PAID.getCode());
        vo.setStatusDesc(OrderStatusEnum.PAID.getDesc());
        vo.setPayTime(now.format(FMT));
        UserPO updatedUser = userMapper.selectById(userId);
        vo.setRemainingPoints(updatedUser != null ? updatedUser.getPoints() : 0);
        return vo;
    }

    /**
     * 支付前校验：订单状态 + 懒过期（过期则完整关单）
     */
    private void checkPayable(OrderPO order, LocalDateTime now) {
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单状态不允许支付");
        }
        if (order.getExpireTime() != null && now.isAfter(order.getExpireTime())) {
            orderService.closePendingOrder(order, "PAYMENT_CHECK_EXPIRE");
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }
    }

    /**
     * 支付单过期时按订单号完整关单（供 mockNotify 无用户态路径使用）
     */
    private void closeOrderOnExpired(String orderNo, String reason) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getDeleted, 0);
        OrderPO order = orderMapper.selectOne(wrapper);
        if (order != null) {
            orderService.closePendingOrder(order, reason);
        }
    }

    /**
     * 支付成功后更新 Redis 投影（best-effort，丢了对账重建）
     */
    private void updateRedisProjection(Long scheduleId, List<LockSeatsDTO.SeatPos> seats, Long userId) {
        try {
            // 释放锁 + 写入已售投影（Lua 脚本已包含 SADD，无需重复）
            lockScriptService.releaseLocksAndMarkSold(scheduleId, seats, userId);

            // 失效渲染缓存
            invalidateRenderCache(scheduleId);
        } catch (Exception e) {
            log.error("[Payment] Failed to update Redis projection: scheduleId={}", scheduleId, e);
        }
    }

    // =================== 内部方法 ===================

    private OrderPO selectUserOrder(Long userId, String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getUserId, userId);
        return orderMapper.selectOne(wrapper);
    }

    /**
     * 模拟网关验签 — 演示模式：sign 可选
     */
    private void verifySign(String orderNo, String sign) {
        if (sign == null || sign.isBlank()) {
            log.warn("[Payment] Mock notify without sign (demo mode): orderNo={}", orderNo);
            return;
        }
        String expected = hmacSha256Hex(CommonConstants.MOCK_PAYMENT_SECRET, orderNo);
        if (!expected.equalsIgnoreCase(sign)) {
            throw new BizException(ResponseCodeEnum.UNAUTHORIZED, "支付回调验签失败");
        }
    }

    private String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR);
        }
    }

    /**
     * 生成支付单号（网关侧）：PAY + 时间戳 + 用户尾号 + 随机6位
     */
    private String generatePaymentNo(Long userId) {
        return "PAY" + LocalDateTime.now().format(PAY_NO_FMT)
                + String.format("%04d", userId % 10000)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private void invalidateRenderCache(Long scheduleId) {
        // 渲染缓存自然过期即可（3-5s TTL），无需主动删除
    }

    private PaymentTradeVO toVO(PaymentTradePO po) {
        PaymentTradeVO vo = new PaymentTradeVO();
        vo.setPaymentNo(po.getPaymentNo());
        vo.setOrderNo(po.getOrderNo());
        vo.setTotalPrice(po.getTotalPrice());
        vo.setStatus(po.getStatus());
        vo.setStatusDesc(po.getStatus() == 0 ? "待支付" : po.getStatus() == 1 ? "已支付" : "已关闭");
        if (po.getCreateTime() != null) {
            vo.setCreateTime(po.getCreateTime().format(FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(FMT));
        }
        return vo;
    }

    private OrderVO toVO(OrderPO po) {
        OrderVO vo = new OrderVO();
        vo.setId(po.getId());
        vo.setOrderNo(po.getOrderNo());
        vo.setLockToken(po.getLockToken());
        vo.setMovieName(po.getMovieName());
        vo.setCinemaName(po.getCinemaName());
        vo.setHallName(po.getHallName());
        vo.setShowTime(po.getShowTime());
        vo.setSeatCount(po.getSeatCount());
        vo.setSeatsInfo(po.getSeatsInfo());
        vo.setUnitPrice(po.getUnitPrice());
        vo.setTotalPrice(po.getTotalPrice());
        vo.setStatus(po.getStatus());
        vo.setStatusDesc(OrderStatusEnum.of(po.getStatus()).getDesc());
        vo.setScheduleId(po.getScheduleId());
        if (po.getCreateTime() != null) {
            vo.setCreateTime(po.getCreateTime().format(FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(FMT));
        }
        return vo;
    }
}
