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
 * 检修范围管段：检修单纳入的一段待修管线（{@code (tenant_id, order_id, segment_id)} 唯一）。
 *
 * <p>{@code segCode}/{@code segName} 是纳入时的快照，发布后不随管段档案改写；
 * 每段管段在「待验收」环节逐一验收，全部通过检修单才推进到「待复气」。</p>
 */
@Entity
@Table(name = "maint_order_segment")
@Getter
@Setter
public class MaintenanceSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    /** 管段编号快照 */
    @Column(name = "seg_code", nullable = false, length = 24)
    private String segCode;

    /** 起止点快照：起点 → 终点 */
    @Column(name = "seg_name", nullable = false, length = 160)
    private String segName;

    /** 待验收 / 已通过 */
    @Column(name = "accept_status", nullable = false, length = 16)
    private String acceptStatus;

    @Column(name = "accept_note", length = 512)
    private String acceptNote;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by", length = 32)
    private String acceptedBy;
}
