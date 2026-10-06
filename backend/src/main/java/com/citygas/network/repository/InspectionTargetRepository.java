package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.InspectionTarget;

/** 批次关联对象 Repository */
public interface InspectionTargetRepository extends JpaRepository<InspectionTarget, Long> {

    List<InspectionTarget> findByBatchIdOrderByIdAsc(Long batchId);

    Optional<InspectionTarget> findByBatchIdAndTargetTypeAndTargetId(Long batchId, String targetType, Long targetId);

    /** 当前轮次（取最大 round_no） */
    Optional<InspectionTarget> findTopByBatchIdAndTargetTypeAndTargetIdOrderByRoundNoDesc(
            Long batchId, String targetType, Long targetId);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select t from InspectionTarget t where t.id = :id")
    Optional<InspectionTarget> findScopedById(@Param("id") Long id);
}
