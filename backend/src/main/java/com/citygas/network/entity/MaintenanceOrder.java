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
 * 管段检修单：影响评估与停复气联动的主对象。
 *
 * <p>状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成。
 * 「待发布」为草稿，可反复调整检修范围与人工边界；发布时冻结影响快照（{@code frozen=1}），
 * 冻结后基础档案变化只能在详情里提示差异，绝不静默改写已发布范围。</p>
 *
 * <p>{@code version} 为 JPA 乐观锁：两名操作员并发发布 / 复气时，只有一人的更新能落库，
 * 后来者收到 409 冲突，且不会留下半冻结快照或部分状态。</p>
 */
@Entity
@Table(name = "maintenance_order")
@Getter
@Setter
public class MaintenanceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    /** 检修单编号，租户内唯一 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @Column(name = "plan_start_at", nullable = false)
    private LocalDateTime planStartAt;

    @Column(name = "plan_end_at")
    private LocalDateTime planEndAt;

    /** 施工负责人 */
    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    @Column(name = "remark", nullable = false, length = 512)
    private String remark;

    /** 待发布 / 待停气 / 施工中 / 待验收 / 待复气 / 已完成 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 影响快照是否已冻结：false 草稿，true 已发布冻结 */
    @Column(name = "frozen", nullable = false)
    private Boolean frozen;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by", length = 32)
    private String publishedBy;

    @Column(name = "cut_off_at")
    private LocalDateTime cutOffAt;

    @Column(name = "cut_off_by", length = 32)
    private String cutOffBy;

    /** 实际开工时间（停气后才允许开工） */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "work_done_at")
    private LocalDateTime workDoneAt;

    @Column(name = "restore_requested_at")
    private LocalDateTime restoreRequestedAt;

    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    @Column(name = "restored_by", length = 32)
    private String restoredBy;

    /** 乐观锁版本：并发发布 / 复气只允许一人推进状态 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
