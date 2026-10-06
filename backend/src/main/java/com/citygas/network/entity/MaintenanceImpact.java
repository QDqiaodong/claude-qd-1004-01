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
 * 检修影响清单（发布即冻结的快照行）：沿检修管段起止点自动关联出的
 * 门站 / 调压站 / 燃气用户，以及人工补充说明的边界对象。
 *
 * <p>{@code (tenant_id, order_id, object_type, object_id)} 唯一；
 * {@code source=AUTO} 的行由「重新评估影响」整体重建，{@code source=MANUAL} 的行
 * 必须带人工说明、只能逐条增删。发布后任何一行都不再改写，档案变化只形成差异提示。</p>
 *
 * <p>燃气用户行需要「安全确认」（复气放行的闸门之一），场站行无需确认。</p>
 */
@Entity
@Table(name = "maint_order_impact")
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

    /** GATE_STATION / REGULATOR_STATION / GAS_USER */
    @Column(name = "object_type", nullable = false, length = 24)
    private String objectType;

    @Column(name = "object_id", nullable = false)
    private Long objectId;

    /** 对象编号快照：station_code / user_no */
    @Column(name = "object_code", nullable = false, length = 24)
    private String objectCode;

    /** 对象名称快照 */
    @Column(name = "object_name", nullable = false, length = 96)
    private String objectName;

    /** AUTO 拓扑自动关联 / MANUAL 人工边界补充 */
    @Column(name = "source", nullable = false, length = 8)
    private String source;

    /** 关联来源说明，如「管段PS-A01·起点」；人工补充行为「人工补充」 */
    @Column(name = "via_desc", length = 128)
    private String viaDesc;

    /** 人工说明：MANUAL 行必填，记录无法自动判定的边界理由 */
    @Column(name = "manual_note", length = 512)
    private String manualNote;

    /** 待确认 / 已确认 / 无需确认（仅燃气用户需要安全确认） */
    @Column(name = "confirm_status", nullable = false, length = 16)
    private String confirmStatus;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;
}
