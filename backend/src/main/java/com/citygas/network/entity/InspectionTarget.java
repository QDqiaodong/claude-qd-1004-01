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
 * 批次关联对象：管段 {@code PIPE_SEGMENT} / 调压站 {@code REGULATOR_STATION} /
 * 燃气用户 {@code GAS_USER}。
 *
 * <p>{@code (tenant_id, batch_id, target_type, target_id, round_no)} 唯一；
 * {@code round_no} 为巡检轮次，隐患被驳回重新整改时同对象仍是同批次，轮次随批次巡检推进，
 * 隐患的同批次同对象同轮去重以此列配合完成。{@code latestResult} 是详情页「各对象最新结果」的数据源。</p>
 */
@Entity
@Table(name = "inspect_batch_target")
@Getter
@Setter
public class InspectionTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** PIPE_SEGMENT / REGULATOR_STATION / GAS_USER（最长 17，列宽 24） */
    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 对象编号快照：seg_code / station_code / user_no */
    @Column(name = "target_code", nullable = false, length = 24)
    private String targetCode;

    /** 对象名称快照 */
    @Column(name = "target_name", nullable = false, length = 64)
    private String targetName;

    /** 巡检轮次，首检为 1 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 待检 / 正常 / 隐患 */
    @Column(name = "latest_result", nullable = false, length = 16)
    private String latestResult;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
}
