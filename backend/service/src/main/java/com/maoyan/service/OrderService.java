package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.PaymentTradeMapper;
import com.maoyan.dao.mapper.ScheduleMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.OutboxService;
import com.maoyan.service.infrastructure.QueueService;
import com.maoyan.service.infrastructure.SeatLockScriptService;
import com.maoyan.service.infrastructure.TransactionSynchronizationUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务 — 目标架构简化版
 *
 * <p>建单已合并到 SeatService.lockSeatsAndCreateOrder()。
 * 本服务只负责：取消订单、查询订单、超时关单兜底。</p>
 */
@Slf4j
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final ScheduleMapper scheduleMapper;
    private final SeatLockMapper seatLockMapper;
    private final PaymentTradeMapper paymentTradeMapper;
    private final OutboxService outboxService;
    private final SeatLockScriptService lockScriptService;
    private final QueueService queueService;
    private final TransactionTemplate transactionTemplate;

    private static final DateTimeFormatter VO_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public OrderService(OrderMapper orderMapper,
                        ScheduleMapper scheduleMapper,
                        SeatLockMapper seatLockMapper,
                        PaymentTradeMapper paymentTradeMapper,
                        OutboxService outboxService,
                        SeatLockScriptService lockScriptService,
                        QueueService queueService,
                        PlatformTransactionManager transactionManager) {
        this.orderMapper = orderMapper;
        this.scheduleMapper = scheduleMapper;
        this.seatLockMapper = seatLockMapper;
        this.paymentTradeMapper = paymentTradeMapper;
        this.outboxService = outboxService;
        this.lockScriptService = lockScriptService;
        this.queueService = queueService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 用户主动取消订单
     */
    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public void cancelOrder(Long userId, String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getUserId, userId);
        OrderPO order = orderMapper.selectOne(wrapper);

        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "当前订单状态不允许取消");
        }

        closePendingOrder(order, "USER_CANCEL");
    }

    /**
     * 定时兜底：关闭已过期的待支付订单（每 60s）
     *
     * <p>正常情况延时消息已处理，这里是兜底</p>
     */
    @Scheduled(fixedDelay = 60000)
    public void cancelExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<OrderPO> expired = orderMapper.selectExpiredPendingOrders(now, 100);
        if (expired.isEmpty()) return;

        // 每个订单独立事务，避免单个失败影响整批，也避免长事务锁住大量行
        for (OrderPO order : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> closePendingOrder(order, "TIMEOUT"));
            } catch (Exception e) {
                log.error("[Order] Failed to close expired order: orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    /**
     * 查询用户订单列表
     */
    public List<OrderVO> getUserOrders(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        return orderMapper.selectByUserIdWithPage(userId, offset, size).stream()
                .map(this::toVO)
                .toList();
    }

    // =================== 内部方法 ===================

    /**
     * 关闭待支付订单 — 回滚库存 + 释放锁 + 关支付单 + outbox 事件
     *
     * <p>public 供 PaymentService 复用（扫码支付链路中懒过期关单走同一实现，
     * 避免双份逻辑漂移）；事务边界由调用方（cancelOrder / cancelExpiredOrders /
     * PaymentService.mockNotify 等 @Transactional 方法）承担。</p>
     */
    public void closePendingOrder(OrderPO order, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int closed = orderMapper.closePendingOrder(order.getOrderNo(), now);
        if (closed == 0) return;

        Long scheduleId = order.getScheduleId();
        Long userId = order.getUserId();
        int seatCount = order.getSeatCount();
        String orderNo = order.getOrderNo();

        // 捕获当前 DB 锁座记录，供事务提交后释放 Redis 锁
        List<SeatLockPO> locks = seatLockMapper.selectLocksByOrderNo(orderNo);
        List<LockSeatsDTO.SeatPos> positions = locks.stream().map(lock -> {
            LockSeatsDTO.SeatPos pos = new LockSeatsDTO.SeatPos();
            pos.setRow(lock.getRowNum());
            pos.setCol(lock.getColNum());
            return pos;
        }).toList();

        // 回滚库存
        scheduleMapper.rollbackStock(scheduleId, seatCount);

        // 释放座位锁（DB）
        seatLockMapper.releaseOrderLocks(orderNo);

        // ★ 关单联动：支付单同步关闭
        paymentTradeMapper.closeByOrderNo(orderNo);

        // outbox 事件
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("type", "ORDER_CANCELLED");
        eventPayload.put("orderNo", orderNo);
        eventPayload.put("userId", userId);
        eventPayload.put("scheduleId", scheduleId);
        eventPayload.put("seatCount", seatCount);
        eventPayload.put("reason", reason);
        eventPayload.put("timestamp", System.currentTimeMillis());
        outboxService.writeEvent("ORDER_CANCELLED", eventPayload);

        // 事务提交后再释放 Redis 锁 + 排队名额，避免 DB 回滚后状态不一致
        TransactionSynchronizationUtils.afterCommit(() -> {
            releaseRedisLocks(scheduleId, positions, userId);
            queueService.leave(scheduleId, userId);
        });

        log.info("[Order] Closed: orderNo={}, reason={}, seatsReturned={}",
                orderNo, reason, seatCount);
    }

    /**
     * 释放 Redis 座位锁（best-effort，事务提交后调用）
     */
    private void releaseRedisLocks(Long scheduleId, List<LockSeatsDTO.SeatPos> positions, Long userId) {
        try {
            if (positions != null && !positions.isEmpty()) {
                lockScriptService.releaseSeats(scheduleId, positions, userId);
                log.info("[Order] Released {} Redis locks for scheduleId={}, userId={}",
                        positions.size(), scheduleId, userId);
            }
        } catch (Exception e) {
            log.error("[Order] Failed to release Redis locks for scheduleId={}, userId={}",
                    scheduleId, userId, e);
        }
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
            vo.setCreateTime(po.getCreateTime().format(VO_TIME_FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(VO_TIME_FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(VO_TIME_FMT));
        }
        return vo;
    }
}
