package com.maoyan.service.infrastructure;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务同步工具 — 将非事务资源（Redis、MQ 等）的副作用推迟到 DB 事务提交后执行，
 * 避免 DB 回滚后 Redis 状态已经变化造成的不一致。
 */
public final class TransactionSynchronizationUtils {

    private TransactionSynchronizationUtils() {
    }

    /**
     * 在事务提交后执行回调；若当前没有事务则立即执行。
     */
    public static void afterCommit(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    /**
     * 在事务回滚后执行回调（用于补偿 Redis 锁等非事务资源）。
     */
    public static void afterRollback(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    runnable.run();
                }
            }
        });
    }
}
