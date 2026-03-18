package com.rightcapital.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notification.config.DeliveryProperties;
import com.rightcapital.notification.dto.CreateNotificationRequest;
import com.rightcapital.notification.dto.NotificationResponse;
import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import com.rightcapital.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
// 使用 LENIENT 避免「不需要 getMaxRetries() 的测试」触发 UnnecessaryStubbingException
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationService 单元测试")
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    @Mock
    private DeliveryProperties deliveryProperties;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private NotificationService service;

    @BeforeEach
    void setUp() {
        // 设置默认值，部分测试不需要此 stub，用 LENIENT 避免报错
        when(deliveryProperties.getMaxRetries()).thenReturn(5);
    }

    // -------------------------------------------------------------------------
    // submit() 测试
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("submit: 正常提交应保存 PENDING 状态记录")
    void submit_shouldSavePendingNotification() {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("https://example.com/webhook");
        request.setHttpMethod("POST");
        request.setBody("{\"event\":\"user_registered\"}");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setCreatedAt(LocalDateTime.now());
            n.setUpdatedAt(LocalDateTime.now());
            return n;
        });

        NotificationResponse response = service.submit(request);

        Notification saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(saved.getTargetUrl()).isEqualTo("https://example.com/webhook");
        assertThat(saved.getHttpMethod()).isEqualTo("POST");
        assertThat(saved.getRetryCount()).isEqualTo(0);
        assertThat(saved.getMaxRetries()).isEqualTo(5);
        assertThat(saved.getNextRetryAt()).isNull();
        assertThat(saved.getId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("submit: 自定义 maxRetries 应覆盖默认值")
    void submit_shouldUseCustomMaxRetries() {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("https://example.com/webhook");
        request.setMaxRetries(3);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setCreatedAt(LocalDateTime.now());
            n.setUpdatedAt(LocalDateTime.now());
            return n;
        });

        service.submit(request);

        assertThat(captor.getValue().getMaxRetries()).isEqualTo(3);
        // 传入了自定义 maxRetries，不应调用 deliveryProperties.getMaxRetries()
        verify(deliveryProperties, never()).getMaxRetries();
    }

    @Test
    @DisplayName("submit: 自定义 headers 应被序列化为 JSON 存储")
    void submit_shouldSerializeHeaders() {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("https://example.com/webhook");
        request.setHeaders(Map.of("Authorization", "Bearer token123"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setCreatedAt(LocalDateTime.now());
            n.setUpdatedAt(LocalDateTime.now());
            return n;
        });

        service.submit(request);

        assertThat(captor.getValue().getHeaders()).contains("Authorization");
        assertThat(captor.getValue().getHeaders()).contains("Bearer token123");
    }

    @Test
    @DisplayName("submit: httpMethod 为 null 时应默认为 POST")
    void submit_shouldDefaultToPost() {
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setTargetUrl("https://example.com/webhook");
        request.setHttpMethod(null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setCreatedAt(LocalDateTime.now());
            n.setUpdatedAt(LocalDateTime.now());
            return n;
        });

        service.submit(request);

        assertThat(captor.getValue().getHttpMethod()).isEqualTo("POST");
    }

    // -------------------------------------------------------------------------
    // getById() 测试
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getById: 存在的 ID 应正确返回")
    void getById_shouldReturnNotification() {
        Notification n = buildNotification("test-id", NotificationStatus.SUCCESS);
        when(repository.findById("test-id")).thenReturn(Optional.of(n));

        NotificationResponse response = service.getById("test-id");

        assertThat(response.getId()).isEqualTo("test-id");
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
    }

    @Test
    @DisplayName("getById: 不存在的 ID 应抛出 IllegalArgumentException")
    void getById_shouldThrowWhenNotFound() {
        when(repository.findById("not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById("not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-exist");
    }

    // -------------------------------------------------------------------------
    // manualRetry() 测试
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("manualRetry: DEAD_LETTER 状态应能重置为 PENDING")
    void manualRetry_shouldResetDeadLetterToPending() {
        Notification n = buildNotification("dead-id", NotificationStatus.DEAD_LETTER);
        n.setLastError("connection timeout");
        when(repository.findById("dead-id")).thenReturn(Optional.of(n));
        when(repository.save(any())).thenReturn(n);

        NotificationResponse response = service.manualRetry("dead-id");

        assertThat(response.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getNextRetryAt()).isNull();
        assertThat(n.getLastError()).isNull();
    }

    @Test
    @DisplayName("manualRetry: SUCCESS 状态不允许重试")
    void manualRetry_shouldRejectSuccessStatus() {
        Notification n = buildNotification("success-id", NotificationStatus.SUCCESS);
        when(repository.findById("success-id")).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> service.manualRetry("success-id"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("manualRetry: PROCESSING 状态不允许重试")
    void manualRetry_shouldRejectProcessingStatus() {
        Notification n = buildNotification("processing-id", NotificationStatus.PROCESSING);
        when(repository.findById("processing-id")).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> service.manualRetry("processing-id"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private Notification buildNotification(String id, NotificationStatus status) {
        return Notification.builder()
                .id(id)
                .targetUrl("https://example.com/webhook")
                .httpMethod("POST")
                .status(status)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
