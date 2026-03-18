package com.rightcapital.notification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 目标投递地址
     */
    @Column(name = "target_url", nullable = false, columnDefinition = "TEXT")
    private String targetUrl;

    /**
     * HTTP 方法，默认 POST
     */
    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    /**
     * 自定义请求头（JSON 字符串存储）
     * 例：{"Authorization":"Bearer xxx","Content-Type":"application/json"}
     */
    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    /**
     * 请求体（透明透传，不做任何解析）
     */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /**
     * 当前状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    /**
     * 已重试次数
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * 最大重试次数
     */
    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    /**
     * 下次可重试时间（null 表示立即可投递）
     */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * 最近一次失败原因
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
