package com.rightcapital.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notification.config.DeliveryProperties;
import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import com.rightcapital.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 异步投递 Worker
 *
 * 核心职责：
 * 1. 定时轮询数据库，取出待投递通知
 * 2. 发送 HTTP 请求到目标地址
 * 3. 根据结果更新状态，或按退避策略安排重试
 *
 * 设计决策：
 * - 使用 DB 轮询而非消息队列（MQ），减少依赖，MVP 阶段足够
 * - 投递语义：至少一次（at-least-once），外部系统应幂等处理
 * - OkHttpClient 关闭自动重试，重试逻辑完全由本 Worker 控制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryWorker {

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType MEDIA_TYPE_TEXT = MediaType.parse("text/plain; charset=utf-8");

    private final NotificationRepository repository;
    private final OkHttpClient okHttpClient;
    private final RetryStrategy retryStrategy;
    private final DeliveryProperties deliveryProperties;
    private final ObjectMapper objectMapper;

    /**
     * 主投递循环，fixedDelay 保证上次执行完成后再等待，避免并发投递同一条记录
     */
    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval-ms:5000}")
    public void deliverPendingNotifications() {
        List<Notification> pending = repository.findDeliverableNotifications(
                LocalDateTime.now(),
                deliveryProperties.getBatchSize()
        );

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Found {} pending notifications to deliver", pending.size());
        for (Notification notification : pending) {
            deliver(notification);
        }
    }

    /**
     * 服务启动时，将卡在 PROCESSING 状态的记录重置为 PENDING。
     * 场景：Worker 上次投递途中宕机，记录没有回写状态。
     * 阈值设为 10 分钟（远大于最长 HTTP 超时），避免误重置正在处理的记录。
     */
    @Scheduled(initialDelay = 5000, fixedDelay = 300_000)
    @Transactional
    public void recoverStalledNotifications() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusMinutes(10);
        int count = repository.resetStalledNotifications(now, staleThreshold);
        if (count > 0) {
            log.warn("Recovered {} stalled PROCESSING notifications", count);
        }
    }

    // -------------------------------------------------------------------------
    // 单条投递逻辑
    // -------------------------------------------------------------------------

    @Transactional
    public void deliver(Notification notification) {
        // 标记为 PROCESSING，防止重复投递
        notification.setStatus(NotificationStatus.PROCESSING);
        repository.save(notification);

        try {
            Request request = buildHttpRequest(notification);
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    handleSuccess(notification);
                } else {
                    String errorMsg = "HTTP " + response.code() + " " + response.message();
                    handleFailure(notification, errorMsg);
                }
            }
        } catch (IOException e) {
            handleFailure(notification, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error delivering notification id={}", notification.getId(), e);
            handleFailure(notification, "Unexpected: " + e.getMessage());
        }
    }

    private void handleSuccess(Notification notification) {
        notification.setStatus(NotificationStatus.SUCCESS);
        notification.setLastError(null);
        repository.save(notification);
        log.info("Notification delivered successfully: id={}, url={}",
                notification.getId(), notification.getTargetUrl());
    }

    private void handleFailure(Notification notification, String errorMsg) {
        int newRetryCount = notification.getRetryCount() + 1;
        notification.setRetryCount(newRetryCount);
        notification.setLastError(truncate(errorMsg, 1000));

        if (newRetryCount > notification.getMaxRetries()) {
            notification.setStatus(NotificationStatus.DEAD_LETTER);
            notification.setNextRetryAt(null);
            log.error("Notification moved to DEAD_LETTER after {} retries: id={}, lastError={}",
                    newRetryCount, notification.getId(), errorMsg);
        } else {
            notification.setStatus(NotificationStatus.PENDING);
            notification.setNextRetryAt(retryStrategy.nextRetryAt(newRetryCount - 1));
            log.warn("Notification delivery failed (attempt {}/{}): id={}, nextRetryAt={}, error={}",
                    newRetryCount, notification.getMaxRetries(),
                    notification.getId(), notification.getNextRetryAt(), errorMsg);
        }
        repository.save(notification);
    }

    // -------------------------------------------------------------------------
    // HTTP 请求构建
    // -------------------------------------------------------------------------

    private Request buildHttpRequest(Notification notification) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(notification.getTargetUrl());

        // 添加自定义 Header
        Map<String, String> headers = parseHeaders(notification.getHeaders());
        headers.forEach(builder::addHeader);

        // 构建请求体
        RequestBody requestBody = buildRequestBody(notification, headers);

        // 设置 HTTP 方法
        String method = notification.getHttpMethod().toUpperCase();
        switch (method) {
            case "GET"    -> builder.get();
            case "DELETE" -> builder.delete(requestBody);
            case "POST"   -> builder.post(requestBody != null ? requestBody : RequestBody.create("", MEDIA_TYPE_TEXT));
            case "PUT"    -> builder.put(requestBody != null ? requestBody : RequestBody.create("", MEDIA_TYPE_TEXT));
            case "PATCH"  -> builder.patch(requestBody != null ? requestBody : RequestBody.create("", MEDIA_TYPE_TEXT));
            default       -> builder.post(RequestBody.create("", MEDIA_TYPE_TEXT));
        }

        return builder.build();
    }

    private RequestBody buildRequestBody(Notification notification, Map<String, String> headers) {
        if (notification.getBody() == null) {
            return null;
        }
        // 根据 Content-Type 决定 MediaType（默认按 JSON 处理）
        String contentType = headers.getOrDefault("Content-Type",
                headers.getOrDefault("content-type", "application/json"));
        MediaType mediaType = MediaType.parse(contentType);
        if (mediaType == null) {
            mediaType = MEDIA_TYPE_JSON;
        }
        return RequestBody.create(notification.getBody(), mediaType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse headers JSON, sending without custom headers: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
