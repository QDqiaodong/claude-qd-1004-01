package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.MaintenanceImpact;

/** 检修影响清单 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface MaintenanceImpactRepository extends JpaRepository<MaintenanceImpact, Long> {

    List<MaintenanceImpact> findByOrderIdOrderByIdAsc(Long orderId);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select i from MaintenanceImpact i where i.id = :id")
    Optional<MaintenanceImpact> findScopedById(@Param("id") Long id);

    /** 未完成安全确认的受影响用户数；复气放行前必须为 0 */
    long countByOrderIdAndObjectTypeAndConfirmStatusNot(Long orderId, String objectType, String confirmStatus);

    /** 「重新评估影响」只重建自动关联行，人工边界行保留 */
    void deleteByOrderIdAndSource(Long orderId, String source);
}
