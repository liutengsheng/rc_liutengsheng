package com.rightcapital.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 投递相关配置，从 application.yml 中 notification.delivery.* 读取
 */
@Component
@ConfigurationProperties(prefix = "notification.delivery")
@Getter
@Setter
public class DeliveryProperties {

    /** 定时投递轮询间隔（毫秒） */
    private long pollIntervalMs = 5000;

    /** 单次最多取出条数 */
    private int batchSize = 20;

    /** 默认最大重试次数 */
    private int maxRetries = 5;

    /** HTTP 连接超时（秒） */
    private long connectTimeoutSeconds = 10;

    /** HTTP 读取超时（秒） */
    private long readTimeoutSeconds = 30;
}
