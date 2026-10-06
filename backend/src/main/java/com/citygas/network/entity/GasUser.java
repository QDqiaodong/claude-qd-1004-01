package com.citygas.network.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 燃气用户与抄表安检。
 *
 * <p>核心规则：本期读数不得小于上期（不能倒走）；单次用量超过
 * {@code MAX_READING_GAP} 判为可疑；安检超期（12 个月）需重新入户。</p>
 */
@Entity
@Table(name = "gas_user")
@Getter
@Setter
public class GasUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "user_no", nullable = false, unique = true, length = 24)
    private String userNo;

    @Column(name = "user_name", nullable = false, length = 48)
    private String userName;

    @Column(name = "address", nullable = false, length = 96)
    private String address;

    /** 表号 */
    @Column(name = "meter_no", nullable = false, length = 24)
    private String meterNo;

    /** 上期读数（m³） */
    @Column(name = "last_reading", nullable = false, precision = 12, scale = 2)
    private BigDecimal lastReading;

    /** 本期读数（m³） */
    @Column(name = "current_reading", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentReading;

    /** 正常 / 欠费 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_check_at")
    private LocalDateTime lastCheckAt;

    /** 合格 / 限期整改 / 隐患停用 */
    @Column(name = "check_result", length = 16)
    private String checkResult;

    /** 本期用气量（m³），派生字段 */
    @Transient
    public BigDecimal getUsage() {
        if (currentReading == null || lastReading == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal u = currentReading.subtract(lastReading);
        return u.signum() > 0 ? u : BigDecimal.ZERO;
    }

    /** 本期气费（元），派生字段 */
    @Transient
    public BigDecimal getFee() {
        return getUsage().multiply(Dict.GAS_PRICE).setScale(2, RoundingMode.HALF_UP);
    }

    /** 安检是否超期，派生字段 */
    @Transient
    public boolean isCheckOverdue() {
        return Dict.isCheckOverdue(lastCheckAt == null ? null : lastCheckAt.toLocalDate());
    }

    /** 距上次安检天数，派生字段 */
    @Transient
    public Long getDaysSinceCheck() {
        if (lastCheckAt == null) {
            return null;
        }
        return java.time.Duration.between(lastCheckAt, LocalDateTime.now()).toDays();
    }
}
