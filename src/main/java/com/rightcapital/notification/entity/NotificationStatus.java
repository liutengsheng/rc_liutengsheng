package com.rightcapital.notification.entity;

/**
 * 通知状态枚举
 *
 * 状态流转：
 *   PENDING → PROCESSING → SUCCESS
 *                        → FAILED → (重试) → PENDING
 *                                 → (超限) → DEAD_LETTER
 */
public enum NotificationStatus {

    /**
     * 待投递（初始状态，或重试等待中）
     */
    PENDING,

    /**
     * 投递中（Worker 已取出，正在发送 HTTP 请求）
     */
    PROCESSING,

    /**
     * 投递成功（收到外部系统 2xx 响应）
     */
    SUCCESS,

    /**
     * 投递失败（本次失败，等待重试）
     */
    FAILED,

    /**
     * 死信（超过最大重试次数，停止自动重试，需人工介入）
     */
    DEAD_LETTER
}
