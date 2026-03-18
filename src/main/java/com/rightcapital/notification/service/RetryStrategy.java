package com.rightcapital.notification.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 指数退避重试策略
 *
 * 重试间隔（第 N 次失败后等待时间）：
 *   第 1 次失败 → 等待  1 分钟
 *   第 2 次失败 → 等待  5 分钟
 *   第 3 次失败 → 等待 15 分钟
 *   第 4 次失败 → 等待  1 小时
 *   第 5 次失败 → 等待  6 小时
 *
 * 设计取舍：
 * - 未采用纯指数退避（2^n 分钟），而是预定义间隔表，更直观、可控
 * - 间隔上限设为 6 小时，避免长时间无法感知的失败
 */
@Component
public class RetryStrategy {

    /**
     * 预定义退避间隔（分钟），索引对应第几次失败（从 0 开始）
     */
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60, 360};

    /**
     * 根据当前已重试次数，计算下次重试时间
     *
     * @param retryCount 当前已重试次数（失败之前的值）
     * @return 下次重试时间
     */
    public LocalDateTime nextRetryAt(int retryCount) {
        int idx = Math.min(retryCount, BACKOFF_MINUTES.length - 1);
        long delayMinutes = BACKOFF_MINUTES[idx];
        return LocalDateTime.now().plusMinutes(delayMinutes);
    }
}
