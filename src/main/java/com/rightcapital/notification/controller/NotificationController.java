package com.rightcapital.notification.controller;

import com.rightcapital.notification.dto.CreateNotificationRequest;
import com.rightcapital.notification.dto.NotificationResponse;
import com.rightcapital.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通知 REST API
 *
 * 接口设计原则：
 * - POST /api/notifications        → 202 Accepted（接受不代表已投递）
 * - GET  /api/notifications/{id}   → 查询状态
 * - POST /api/notifications/{id}/retry → 手动重试
 * - GET  /api/health               → 健康检查
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 提交通知请求
     * 业务系统调用此接口，立即返回 202，实际投递异步完成
     */
    @PostMapping("/notifications")
    public ResponseEntity<NotificationResponse> submit(
            @Valid @RequestBody CreateNotificationRequest request) {
        NotificationResponse response = notificationService.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 查询通知状态
     */
    @GetMapping("/notifications/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable String id) {
        NotificationResponse response = notificationService.getById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 手动重试（针对 DEAD_LETTER 或 FAILED 状态的通知）
     */
    @PostMapping("/notifications/{id}/retry")
    public ResponseEntity<NotificationResponse> retry(@PathVariable String id) {
        NotificationResponse response = notificationService.manualRetry(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 简单健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    // -------------------------------------------------------------------------
    // 异常处理
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }
}
