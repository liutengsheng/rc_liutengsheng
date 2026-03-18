package com.rightcapital.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notification.config.DeliveryProperties;
import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import com.rightcapital.notification.repository.NotificationRepository;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryWorker 单元测试")
class DeliveryWorkerTest {

    private MockWebServer mockWebServer;
    private OkHttpClient realHttpClient;

    @Mock
    private NotificationRepository repository;

    @Mock
    private RetryStrategy retryStrategy;

    @Mock
    private DeliveryProperties deliveryProperties;

    private DeliveryWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        realHttpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();

        worker = new DeliveryWorker(
                repository,
                realHttpClient,
                retryStrategy,
                deliveryProperties,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // -------------------------------------------------------------------------
    // 辅助：记录每次 save() 调用时的状态快照
    // 原因：captor 捕获的是对象引用，同一对象状态被后续修改后，
    //       captor.getAllValues().get(0) 和 get(1) 会指向同一最终状态
    // -------------------------------------------------------------------------

    private List<NotificationStatus> captureStatusSnapshots(Notification n) {
        List<NotificationStatus> snapshots = new ArrayList<>();
        when(repository.save(any())).thenAnswer(inv -> {
            Notification saved = inv.getArgument(0);
            snapshots.add(saved.getStatus());
            return saved;
        });
        return snapshots;
    }

    // -------------------------------------------------------------------------
    // 投递成功
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deliver: 外部 API 返回 200 时应标记为 SUCCESS")
    void deliver_shouldMarkSuccessOn200() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        Notification n = buildNotification("id-1", 0, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());
        List<NotificationStatus> snapshots = captureStatusSnapshots(n);

        worker.deliver(n);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(snapshots.get(1)).isEqualTo(NotificationStatus.SUCCESS);
        assertThat(n.getLastError()).isNull();
    }

    @Test
    @DisplayName("deliver: 外部 API 返回 201 时应标记为 SUCCESS")
    void deliver_shouldMarkSuccessOn201() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(201));

        Notification n = buildNotification("id-2", 0, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());
        List<NotificationStatus> snapshots = captureStatusSnapshots(n);

        worker.deliver(n);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(snapshots.get(1)).isEqualTo(NotificationStatus.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // 投递失败 → 重试
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deliver: 外部 API 返回 500 时应安排重试")
    void deliver_shouldScheduleRetryOn500() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        Notification n = buildNotification("id-3", 0, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());

        LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(1);
        when(retryStrategy.nextRetryAt(0)).thenReturn(nextRetry);
        List<NotificationStatus> snapshots = captureStatusSnapshots(n);

        worker.deliver(n);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(snapshots.get(1)).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getRetryCount()).isEqualTo(1);
        assertThat(n.getLastError()).contains("HTTP 500");
        assertThat(n.getNextRetryAt()).isEqualTo(nextRetry);
    }

    @Test
    @DisplayName("deliver: 超过最大重试次数时应标记为 DEAD_LETTER")
    void deliver_shouldMarkDeadLetterWhenMaxRetriesExceeded() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));

        // 已重试 5 次，maxRetries=5，再失败一次（retryCount 变为 6 > maxRetries）进入 DEAD_LETTER
        Notification n = buildNotification("id-4", 5, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());
        List<NotificationStatus> snapshots = captureStatusSnapshots(n);

        worker.deliver(n);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(snapshots.get(1)).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getRetryCount()).isEqualTo(6);
        assertThat(n.getNextRetryAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // 网络异常
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deliver: 连接被拒绝时应安排重试")
    void deliver_shouldHandleConnectionRefused() {
        // 使用不存在的端口，触发 ConnectException
        Notification n = buildNotification("id-5", 0, 5);
        n.setTargetUrl("http://localhost:19999/webhook");

        LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(1);
        when(retryStrategy.nextRetryAt(0)).thenReturn(nextRetry);
        List<NotificationStatus> snapshots = captureStatusSnapshots(n);

        worker.deliver(n);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(snapshots.get(1)).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getLastError()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // 请求内容验证
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deliver: 应正确传递自定义 Header")
    void deliver_shouldSendCustomHeaders() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        Notification n = buildNotification("id-6", 0, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());
        n.setHeaders("{\"Authorization\":\"Bearer test-token\",\"X-Custom\":\"value123\"}");
        when(repository.save(any())).thenReturn(n);

        worker.deliver(n);

        RecordedRequest recorded = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(recorded.getHeader("X-Custom")).isEqualTo("value123");
    }

    @Test
    @DisplayName("deliver: 应正确透传 body")
    void deliver_shouldSendBodyAsIs() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        String body = "{\"userId\":42,\"event\":\"purchase\"}";
        Notification n = buildNotification("id-7", 0, 5);
        n.setTargetUrl(mockWebServer.url("/webhook").toString());
        n.setBody(body);
        when(repository.save(any())).thenReturn(n);

        worker.deliver(n);

        RecordedRequest recorded = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getBody().readUtf8()).isEqualTo(body);
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private Notification buildNotification(String id, int retryCount, int maxRetries) {
        return Notification.builder()
                .id(id)
                .targetUrl("https://example.com/webhook")
                .httpMethod("POST")
                .status(NotificationStatus.PENDING)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
