package com.rightcapital.notification.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notification.dto.CreateNotificationRequest;
import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import com.rightcapital.notification.repository.NotificationRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 集成测试
 *
 * 使用 MockWebServer 模拟外部 API，验证从 HTTP 提交到最终投递的完整流程。
 * 使用内存 H2 数据库，无需任何外部依赖。
 * 使用 Awaitility 等待异步投递完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("通知系统集成测试")
class NotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository repository;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        repository.deleteAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    // -------------------------------------------------------------------------
    // 正常投递流程
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("完整流程：提交通知 → Worker 异步投递成功 → 状态变为 SUCCESS")
    void fullFlow_submitAndDeliverSuccess() throws Exception {
        // 1. 模拟外部 API 返回 200
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        // 2. 提交通知请求
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl(mockWebServer.url("/webhook").toString());
        request.setHttpMethod("POST");
        request.setBody("{\"event\":\"user_registered\"}");
        request.setHeaders(Map.of("Content-Type", "application/json"));

        MvcResult result = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String id = objectMapper.readTree(responseBody).get("id").asText();

        // 3. 等待异步投递完成（最多 10 秒）
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification n = repository.findById(id).orElseThrow();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
        });

        // 4. 通过查询接口验证最终状态
        mockMvc.perform(get("/api/notifications/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.retryCount").value(0));
    }

    @Test
    @DisplayName("完整流程：外部 API 失败后进入重试，恢复后最终成功")
    void fullFlow_retryThenSuccess() throws Exception {
        // 先返回 500，再返回 200
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl(mockWebServer.url("/webhook").toString());
        request.setMaxRetries(3);

        MvcResult result = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // 等待第一次失败（状态变为 PENDING/FAILED，retryCount=1）
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification n = repository.findById(id).orElseThrow();
            assertThat(n.getRetryCount()).isGreaterThanOrEqualTo(1);
        });

        // 手动重置 nextRetryAt 为 null，触发立即重试（跳过等待时间）
        repository.findById(id).ifPresent(n -> {
            n.setNextRetryAt(null);
            n.setStatus(NotificationStatus.PENDING);
            repository.save(n);
        });

        // 等待最终成功
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification n = repository.findById(id).orElseThrow();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
        });
    }

    // -------------------------------------------------------------------------
    // Dead Letter 流程
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("超过最大重试次数后进入 DEAD_LETTER")
    void fullFlow_exceedMaxRetries_deadLetter() throws Exception {
        // 所有请求都返回 500
        for (int i = 0; i < 5; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        }

        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl(mockWebServer.url("/webhook").toString());
        request.setMaxRetries(2); // 设置较小的重试次数，加速测试

        MvcResult result = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // 多次强制重置 nextRetryAt，让 Worker 快速重试
        for (int attempt = 0; attempt < 5; attempt++) {
            Thread.sleep(1500);
            repository.findById(id).ifPresent(n -> {
                if (n.getStatus() == NotificationStatus.PENDING) {
                    n.setNextRetryAt(null);
                    repository.save(n);
                }
            });
        }

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification n = repository.findById(id).orElseThrow();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
            assertThat(n.getRetryCount()).isGreaterThan(2);
        });
    }

    // -------------------------------------------------------------------------
    // 手动重试
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DEAD_LETTER 状态可通过手动重试接口重置为 PENDING")
    void manualRetry_shouldResetDeadLetterToPending() throws Exception {
        // 直接在 DB 中创建一条 DEAD_LETTER 记录
        Notification deadLetter = Notification.builder()
                .id("dead-letter-test")
                .targetUrl("https://example.com/webhook")
                .httpMethod("POST")
                .status(NotificationStatus.DEAD_LETTER)
                .retryCount(6)
                .maxRetries(5)
                .lastError("HTTP 503 Service Unavailable")
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
        repository.save(deadLetter);

        // 调用手动重试接口
        mockMvc.perform(post("/api/notifications/dead-letter-test/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 验证 DB 状态
        Notification updated = repository.findById("dead-letter-test").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(updated.getNextRetryAt()).isNull();
        assertThat(updated.getLastError()).isNull();
    }

    // -------------------------------------------------------------------------
    // 输入校验
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("targetUrl 为空时应返回 400")
    void submit_shouldReturn400WhenTargetUrlBlank() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("");

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetUrl 非 http/https 时应返回 400")
    void submit_shouldReturn400WhenTargetUrlInvalid() throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("ftp://invalid.com/path");

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("查询不存在的 ID 应返回 404")
    void getById_shouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/notifications/non-existent-id"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // 健康检查
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("健康检查接口应返回 UP")
    void healthCheck_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
