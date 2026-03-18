package com.rightcapital.notification.repository;

import com.rightcapital.notification.entity.Notification;
import com.rightcapital.notification.entity.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    /**
     * 查询可投递的通知：
     * - 状态为 PENDING
     * - next_retry_at 为 null 或已到达
     *
     * 用于 DeliveryWorker 轮询
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.status = 'PENDING'
              AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now)
            ORDER BY n.createdAt ASC
            LIMIT :limit
            """)
    List<Notification> findDeliverableNotifications(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /**
     * 将 PROCESSING 状态的通知重置为 PENDING
     * 用于服务重启恢复：防止 Worker 宕机导致任务永久卡在 PROCESSING
     */
    @Modifying
    @Query("""
            UPDATE Notification n
            SET n.status = 'PENDING', n.updatedAt = :now
            WHERE n.status = 'PROCESSING'
              AND n.updatedAt < :staleThreshold
            """)
    int resetStalledNotifications(
            @Param("now") LocalDateTime now,
            @Param("staleThreshold") LocalDateTime staleThreshold
    );

    /**
     * 按状态统计数量（用于监控/健康检查）
     */
    long countByStatus(NotificationStatus status);
}
