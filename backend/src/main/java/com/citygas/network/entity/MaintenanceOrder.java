package com.citygas.network.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 管段检修单：一次「管段检修影响评估与停复气联动」的主单。
 *
 * <p>状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成。
 * 发布时冻结影响快照（影响清单行此后只读，档案变化只在详情里提示差异）；
 * 停气后才可开工；复气放行要求全部管段验收通过且受影响用户安全确认完成。</p>
 *
 * <p>{@code version} 是乐观锁版本：前端详情携带，所有写操作统一校验，
 * 两人并发改同一单时后到者收到 409 而不是覆盖先到者。</p>
 */
@Entity
@Table(name = "maint_order")
@Getter
@Setter
public class MaintenanceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    /** 检修单号，租户内唯一 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @Column(name = "plan_start_at", nullable = false)
    private LocalDateTime planStartAt;

    @Column(name = "plan_end_at")
    private LocalDateTime planEndAt;

    /** 施工负责人 */
    @Column(name = "leader_name", nullable = false, length = 32)
    private String leaderName;

    /** 检修范围说明，不允许空白 */
    @Column(name = "scope_remark", nullable = false, length = 512)
    private String scopeRemark;

    /** 待发布 / 待停气 / 施工中 / 待验收 / 待复气 / 已完成 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 乐观锁版本：JPA 自动递增，写操作入参必须携带最新版本 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by", length = 32)
    private String publishedBy;

    /** 停气时间（停气后才可开工） */
    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Column(name = "stopped_by", length = 32)
    private String stoppedBy;

    @Column(name = "work_finished_at")
    private LocalDateTime workFinishedAt;

    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    @Column(name = "restored_by", length = 32)
    private String restoredBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
