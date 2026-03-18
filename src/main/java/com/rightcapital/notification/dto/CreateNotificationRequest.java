package com.rightcapital.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * 业务系统提交通知请求的 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateNotificationRequest {

    /**
     * 目标 URL，必须是 http 或 https
     */
    @NotBlank(message = "targetUrl must not be blank")
    @Pattern(
            regexp = "^https?://.*",
            message = "targetUrl must start with http:// or https://"
    )
    private String targetUrl;

    /**
     * HTTP 方法，默认 POST
     */
    @Pattern(
            regexp = "^(GET|POST|PUT|PATCH|DELETE)$",
            message = "httpMethod must be one of GET, POST, PUT, PATCH, DELETE"
    )
    private String httpMethod = "POST";

    /**
     * 自定义请求头（可选）
     * 例：{"Authorization": "Bearer xxx", "X-Custom-Header": "value"}
     */
    private Map<String, String> headers;

    /**
     * 请求体（任意格式，透明透传）
     */
    private String body;

    /**
     * 最大重试次数（可选，默认由配置决定）
     */
    private Integer maxRetries;
}
