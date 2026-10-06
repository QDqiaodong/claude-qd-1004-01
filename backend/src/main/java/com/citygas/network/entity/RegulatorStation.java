package com.citygas.network.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import com.citygas.network.common.Dict;

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
 * 调压站 / 调压箱：把上一级压力降到用户可用的压力。
 *
 * <p>核心规则：出口压力不得超过 {@code MAX_OUTLET_PRESSURE}(0.4MPa)；
 * 距上次检验超过 {@code INSPECT_PERIOD_MONTHS} 个月不允许投运。</p>
 */
@Entity
@Table(name = "regulator_station")
@Getter
@Setter
public class RegulatorStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "station_code", nullable = false, unique = true, length = 24)
    private String stationCode;

    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /** 区域调压站 / 楼栋调压箱 / 专用调压站 */
    @Column(name = "station_type", nullable = false, length = 24)
    private String stationType;

    @Column(name = "inlet_pressure_mpa", nullable = false, precision = 6, scale = 3)
    private BigDecimal inPressureMpa;

    /** 出口压力，不得超过 0.4MPa */
    @Column(name = "outlet_pressure_mpa", nullable = false, precision = 6, scale = 3)
    private BigDecimal outletPressureMpa;

    /** 运行 / 待检 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_inspect_at")
    private LocalDateTime lastInspectAt;

    /** 是否超压，派生字段 */
    @Transient
    public boolean isOverPressure() {
        return outletPressureMpa != null
                && outletPressureMpa.compareTo(Dict.MAX_OUTLET_PRESSURE) > 0;
    }

    /** 检验是否超期，派生字段 */
    @Transient
    public boolean isInspectOverdue() {
        return Dict.isInspectOverdue(lastInspectAt == null ? null : lastInspectAt.toLocalDate());
    }

    /** 距上次检验天数，派生字段 */
    @Transient
    public Long getDaysSinceInspect() {
        if (lastInspectAt == null) {
            return null;
        }
        return java.time.Duration.between(lastInspectAt, LocalDateTime.now()).toDays();
    }
}
