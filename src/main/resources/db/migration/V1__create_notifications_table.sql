-- 通知记录表
CREATE TABLE notifications (
    id              VARCHAR(36)  NOT NULL,
    target_url      TEXT         NOT NULL,
    http_method     VARCHAR(10)  NOT NULL DEFAULT 'POST',
    headers         TEXT,                          -- JSON 格式的自定义请求头
    body            TEXT,                          -- 请求体（任意格式，透明透传）
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMP,                     -- 下次可重试时间
    last_error      TEXT,                          -- 最近一次失败原因
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT chk_status CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED','DEAD_LETTER'))
);

CREATE INDEX idx_notifications_status_next_retry
    ON notifications (status, next_retry_at);

CREATE INDEX idx_notifications_created_at
    ON notifications (created_at DESC);
