package com.rightcapital.notification.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttpClient Bean 配置
 * 单例复用，内部维护连接池，避免每次创建
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient(DeliveryProperties props) {
        return new OkHttpClient.Builder()
                .connectTimeout(props.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(props.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(props.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                // 失败不自动重试（我们自己管理重试逻辑）
                .retryOnConnectionFailure(false)
                .build();
    }
}
