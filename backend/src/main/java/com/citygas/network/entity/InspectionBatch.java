package com.citygas.network.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 安全巡检批次。
 *
 * <p>状态机：待执行 → 执行中 → 待整改 → 待复核 → 已关闭。
 * 批次编号在租户内唯一（{@code uk_inspect_batch_no(tenant_id, batch_no)}）；
 * 计划开始时间已过且仍为「待执行」的批次属于过期批次，必须先补执行，禁止跳过执行直接关闭。</p>
 */
@Entity
@Table(name = "inspect_batch")
@Getter
@Setter
public class InspectionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    /** 批次编号，租户内唯一 */
    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "plan_start_at", nullable = false)
    private LocalDateTime planStartAt;

    @Column(name = "plan_end_at")
    private LocalDateTime planEndAt;

    /** 负责人 */
    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    /** 巡检说明，不允许空白 */
    @Column(name = "remark", nullable = false, length = 512)
    private String remark;

    /** 待执行 / 执行中 / 待整改 / 待复核 / 已关闭 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "review_requested_at")
    private LocalDateTime reviewRequestedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 32)
    private String closedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
