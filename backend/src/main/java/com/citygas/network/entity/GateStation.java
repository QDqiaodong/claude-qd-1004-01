package com.citygas.network.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
 * 门站：长输管线进城的接收端，负责进站计量、调压与加臭。
 *
 * <p>核心规则：进站压力不得低于 {@code MIN_INLET_PRESSURE}、加臭剂单耗不得低于
 * {@code MIN_ODORANT}。状态：运行 ↔ 检修；停用需先检修。</p>
 *
 * <p>{@code tenantId} 是 {@code @TenantId}：查询自动带租户条件、保存自动填值。</p>
 */
@Entity
@Table(name = "gate_station")
@Getter
@Setter
public class GateStation {

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

    /** 进站压力（MPa） */
    @Column(name = "inlet_pressure_mpa", nullable = false, precision = 6, scale = 3)
    private BigDecimal inletPressureMpa;

    /** 出站压力（MPa） */
    @Column(name = "outlet_pressure_mpa", nullable = false, precision = 6, scale = 3)
    private BigDecimal outletPressureMpa;

    /** 日输气量（m³） */
    @Column(name = "daily_flow_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal dailyFlowM3;

    /** 加臭剂单耗（mg/m³） */
    @Column(name = "odorant_mg", nullable = false, precision = 6, scale = 2)
    private BigDecimal odorantMg;

    /** 运行 / 检修 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 进气是否充足，派生字段 */
    @Transient
    public boolean isInletLow() {
        return inletPressureMpa != null
                && inletPressureMpa.compareTo(com.citygas.network.common.Dict.MIN_INLET_PRESSURE) < 0;
    }

    /** 加臭是否不足，派生字段 */
    @Transient
    public boolean isOdorantLow() {
        return odorantMg != null
                && odorantMg.compareTo(com.citygas.network.common.Dict.MIN_ODORANT) < 0;
    }

    /** 日输气量折合万方，派生字段 */
    @Transient
    public BigDecimal getDailyFlowWan() {
        return dailyFlowM3 == null ? BigDecimal.ZERO
                : dailyFlowM3.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
    }
}
