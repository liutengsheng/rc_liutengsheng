package com.rightcapital.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

@DisplayName("RetryStrategy 单元测试")
class RetryStrategyTest {

    private RetryStrategy retryStrategy;

    @BeforeEach
    void setUp() {
        retryStrategy = new RetryStrategy();
    }

    @ParameterizedTest(name = "第 {0} 次失败后应等待约 {1} 分钟")
    @CsvSource({
            "0, 1",
            "1, 5",
            "2, 15",
            "3, 60",
            "4, 360",
            "5, 360",   // 超出索引时取最大值
            "99, 360"   // 极端值
    })
    @DisplayName("指数退避：根据重试次数返回正确等待时间")
    void shouldReturnCorrectBackoffDelay(int retryCount, long expectedMinutes) {
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime nextRetry = retryStrategy.nextRetryAt(retryCount);
        LocalDateTime after = LocalDateTime.now();

        // 验证下次重试时间在预期范围内（允许 5 秒误差）
        LocalDateTime expectedMin = before.plusMinutes(expectedMinutes).minusSeconds(5);
        LocalDateTime expectedMax = after.plusMinutes(expectedMinutes).plusSeconds(5);

        assertThat(nextRetry)
                .isAfterOrEqualTo(expectedMin)
                .isBeforeOrEqualTo(expectedMax);
    }

    @Test
    @DisplayName("返回时间应总是在未来")
    void shouldAlwaysReturnFutureTime() {
        for (int i = 0; i <= 10; i++) {
            assertThat(retryStrategy.nextRetryAt(i)).isAfter(LocalDateTime.now());
        }
    }
}
