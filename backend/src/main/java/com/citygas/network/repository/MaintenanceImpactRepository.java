package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.MaintenanceImpact;

/** 检修影响清单 Repository：(@TenantId 自动追加租户条件 */
public interface MaintenanceImpactRepository extends JpaRepository<MaintenanceImpact, Long> {

    List<MaintenanceImpact> findByOrderIdOrderByIdAsc(Long orderId);

    Optional<MaintenanceImpact> findByOrderIdAndTargetTypeAndTargetId(
            Long orderId, String targetType, Long targetId);

    /** 管段行数量（检修范围里必须至少一条） */
    long countByOrderIdAndTargetType(Long orderId, String targetType);

    /** 尚未验收通过的管段行数：复气放行时必须为 0 */
    long countByOrderIdAndTargetTypeAndAcceptanceStatusNot(
            Long orderId, String targetType, String acceptanceStatus);

    /** 尚未完成安全确认的燃气用户行数：复气放行时必须为 0 */
    long countByOrderIdAndTargetTypeAndSafetyStatusNot(
            Long orderId, String targetType, String safetyStatus);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select i from MaintenanceImpact i where i.id = :id")
    Optional<MaintenanceImpact> findScopedById(@Param("id") Long id);
}
