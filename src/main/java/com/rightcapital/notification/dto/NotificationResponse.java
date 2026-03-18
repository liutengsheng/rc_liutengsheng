package com.rightcapital.notification.dto;

import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知状态响应 DTO
 */
@Getter
@Builder
public class NotificationResponse {

    private String id;
    private String targetUrl;
    private String httpMethod;
    private NotificationStatus status;
    private int retryCount;
    private int maxRetries;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .targetUrl(n.getTargetUrl())
                .httpMethod(n.getHttpMethod())
                .status(n.getStatus())
                .retryCount(n.getRetryCount())
                .maxRetries(n.getMaxRetries())
                .nextRetryAt(n.getNextRetryAt())
                .lastError(n.getLastError())
                .createdAt(n.getCreatedAt())
                .updatedAt(n.getUpdatedAt())
                .build();
    }
}
