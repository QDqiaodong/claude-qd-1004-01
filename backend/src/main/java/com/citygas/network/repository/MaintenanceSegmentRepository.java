package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.MaintenanceSegment;

/** 检修范围管段 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface MaintenanceSegmentRepository extends JpaRepository<MaintenanceSegment, Long> {

    List<MaintenanceSegment> findByOrderIdOrderByIdAsc(Long orderId);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select l from MaintenanceSegment l where l.id = :id")
    Optional<MaintenanceSegment> findScopedById(@Param("id") Long id);

    /** 尚未验收通过的管段数；为 0 时检修单自动推进到「待复气」 */
    long countByOrderIdAndAcceptStatusNot(Long orderId, String acceptStatus);

    /** 编辑检修范围时整单重建（派生删除逐条 remove，调用方随后 flush 再插入） */
    void deleteByOrderId(Long orderId);
}
