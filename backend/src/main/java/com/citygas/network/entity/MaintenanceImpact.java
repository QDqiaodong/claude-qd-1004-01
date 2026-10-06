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
 * 检修影响清单项（影响快照行）：沿待修管段起止点关联出的门站 / 调压站 / 燃气用户，
 * 以及运营人员人工说明的边界对象 {@code MANUAL}。
 *
 * <p>草稿阶段（检修单「待发布」）按拓扑重新关联、可增删；发布后整表即为冻结快照，
 * 之后基础档案变化只在详情里输出差异提示，绝不回写这些快照字段。</p>
 *
 * <p>{@code acceptanceStatus} 仅对管段行有意义；{@code safetyStatus} 仅对燃气用户行有意义。
 * 复气放行必须同时满足：所有管段行验收通过 + 所有燃气用户行安全确认完成。</p>
 */
@Entity
@Table(name = "maintenance_impact")
@Getter
@Setter
public class MaintenanceImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** PIPE_SEGMENT / GATE_STATION / REGULATOR_STATION / GAS_USER / MANUAL */
    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    /** 对象 ID 快照；MANUAL 人工边界为 null */
    @Column(name = "target_id")
    private Long targetId;

    /** 对象编号快照：seg_code / station_code / user_no / 人工边界 */
    @Column(name = "target_code", nullable = false, length = 64)
    private String targetCode;

    /** 对象名称 / 位置快照 */
    @Column(name = "target_name", nullable = false, length = 128)
    private String targetName;

    /** AUTO 拓扑自动关联 / MANUAL 人工边界说明 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** 人工无法自动判定的边界对象说明 */
    @Column(name = "manual_note", length = 512)
    private String manualNote;

    /** 未验收 / 验收通过 / 验收不通过（管段行） */
    @Column(name = "acceptance_status", nullable = false, length = 16)
    private String acceptanceStatus;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by", length = 32)
    private String acceptedBy;

    /** 未确认 / 已确认（燃气用户行） */
    @Column(name = "safety_status", nullable = false, length = 16)
    private String safetyStatus;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
