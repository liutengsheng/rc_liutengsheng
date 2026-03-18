package com.rightcapital.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notification.config.DeliveryProperties;
import com.rightcapital.notification.dto.CreateNotificationRequest;
import com.rightcapital.notification.dto.NotificationResponse;
import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import com.rightcapital.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final DeliveryProperties deliveryProperties;
    private final ObjectMapper objectMapper;

    /**
     * 接收业务系统提交的通知请求，持久化后立即返回。
     * 实际投递由 DeliveryWorker 异步完成。
     *
     * 返回 202 Accepted 语义：已接受，不代表已投递。
     */
    @Transactional
    public NotificationResponse submit(CreateNotificationRequest request) {
        int maxRetries = request.getMaxRetries() != null
                ? request.getMaxRetries()
                : deliveryProperties.getMaxRetries();

        Notification notification = Notification.builder()
                .id(UUID.randomUUID().toString())
                .targetUrl(request.getTargetUrl())
                .httpMethod(request.getHttpMethod() != null ? request.getHttpMethod() : "POST")
                .headers(serializeHeaders(request.getHeaders()))
                .body(request.getBody())
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .maxRetries(maxRetries)
                .nextRetryAt(null)  // 立即可投递
                .build();

        Notification saved = repository.save(notification);
        log.info("Notification submitted: id={}, targetUrl={}", saved.getId(), saved.getTargetUrl());
        return NotificationResponse.from(saved);
    }

    /**
     * 查询通知状态
     */
    @Transactional(readOnly = true)
    public NotificationResponse getById(String id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));
        return NotificationResponse.from(notification);
    }

    /**
     * 手动触发重试（针对 DEAD_LETTER 或 FAILED 状态）
     * 业务价值：外部系统恢复后，运维人员可以手动重新触发，而不用让业务系统重新提交
     */
    @Transactional
    public NotificationResponse manualRetry(String id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));

        if (notification.getStatus() == NotificationStatus.SUCCESS) {
            throw new IllegalStateException("Cannot retry a successfully delivered notification: " + id);
        }
        if (notification.getStatus() == NotificationStatus.PROCESSING) {
            throw new IllegalStateException("Notification is currently being processed: " + id);
        }

        notification.setStatus(NotificationStatus.PENDING);
        notification.setNextRetryAt(null);
        notification.setLastError(null);

        Notification saved = repository.save(notification);
        log.info("Notification manually queued for retry: id={}", id);
        return NotificationResponse.from(saved);
    }

    // -------------------------------------------------------------------------
    // 内部工具方法
    // -------------------------------------------------------------------------

    private String serializeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers, storing as null", e);
            return null;
        }
    }
}
