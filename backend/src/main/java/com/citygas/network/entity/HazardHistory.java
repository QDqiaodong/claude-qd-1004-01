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
 * 隐患处理历史：登记 / 提交整改 / 复核通过 / 驳回 全部留痕，只追加不修改。
 * 驳回后回到整改中再次提交，历史依旧完整保留。
 */
@Entity
@Table(name = "inspect_hazard_history")
@Getter
@Setter
public class HazardHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "hazard_id", nullable = false)
    private Long hazardId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 登记 / 提交整改 / 复核通过 / 驳回 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
