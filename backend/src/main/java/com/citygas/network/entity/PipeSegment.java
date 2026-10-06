package com.citygas.network.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 管网管段：两个节点之间的一段管线。
 *
 * <p>核心规则：报废管段不允许再派巡检；铸铁管且埋设年份早于 1990 直接提示待修；
 * 长度必须大于 0。</p>
 */
@Entity
@Table(name = "pipe_segment")
@Getter
@Setter
public class PipeSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "seg_code", nullable = false, unique = true, length = 24)
    private String segCode;

    @Column(name = "start_point", nullable = false, length = 64)
    private String startPoint;

    @Column(name = "end_point", nullable = false, length = 64)
    private String endPoint;

    /** 钢管 / PE管 / 铸铁管 */
    @Column(name = "material", nullable = false, length = 16)
    private String material;

    /** 高压 / 次高压 / 中压 / 低压 */
    @Column(name = "pressure_level", nullable = false, length = 16)
    private String pressureLevel;

    @Column(name = "length_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal lengthM;

    @Column(name = "buried_year")
    private Integer buriedYear;

    /** 正常 / 待修 / 报废 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_patrol_at")
    private LocalDateTime lastPatrolAt;

    /** 管龄（年），派生字段 */
    @Transient
    public Integer getAgeYears() {
        return buriedYear == null ? null : java.time.Year.now().getValue() - buriedYear;
    }

    /** 距上次巡检天数，派生字段 */
    @Transient
    public Long getDaysSincePatrol() {
        if (lastPatrolAt == null) {
            return null;
        }
        return java.time.Duration.between(lastPatrolAt, LocalDateTime.now()).toDays();
    }

    /** 老旧铸铁管（1990 年前埋设），派生字段 */
    @Transient
    public boolean isAgedCastIron() {
        return "铸铁管".equals(material) && buriedYear != null && buriedYear < 1990;
    }
}
