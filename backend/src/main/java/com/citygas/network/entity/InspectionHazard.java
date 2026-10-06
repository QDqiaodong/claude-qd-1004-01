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
 * 巡检隐患。
 *
 * <p>生命周期：登记即「整改中」→ 责任人提交整改进「待复核」→ 复核人「复核通过」；
 * 复核不通过「驳回」，必须回到「整改中」，处理历史全程保留。</p>
 *
 * <p>去重：{@code (tenant_id, batch_id, target_type, target_id, round_no)} 唯一，
 * 同批次同对象同轮不得重复登记，网络重试在数据库层也只会命中一条。</p>
 */
@Entity
@Table(name = "inspect_hazard")
@Getter
@Setter
public class InspectionHazard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 问题描述，不允许空白 */
    @Column(name = "problem", nullable = false, length = 512)
    private String problem;

    /** 风险等级：高 / 中 / 低 */
    @Column(name = "risk_level", nullable = false, length = 8)
    private String riskLevel;

    /** 整改要求 */
    @Column(name = "rectify_require", nullable = false, length = 512)
    private String rectifyRequire;

    /** 责任人 */
    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    /** 整改期限；逾期提交整改一律判无效 */
    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    /** 整改中 / 待复核 / 已通过 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 最近一次整改说明 */
    @Column(name = "rectify_note", length = 512)
    private String rectifyNote;

    @Column(name = "rectified_at")
    private LocalDateTime rectifiedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
