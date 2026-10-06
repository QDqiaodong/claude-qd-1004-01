package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.InspectionHazard;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** 隐患 Repository */
public interface InspectionHazardRepository extends JpaRepository<InspectionHazard, Long> {

    List<InspectionHazard> findByBatchIdOrderByIdAsc(Long batchId);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select h from InspectionHazard h where h.id = :id")
    Optional<InspectionHazard> findScopedById(@Param("id") Long id);

    /** 同批次同对象同轮的隐患——业务上去重的唯一来源 */
    Optional<InspectionHazard> findByBatchIdAndTargetTypeAndTargetIdAndRoundNo(
            Long batchId, String targetType, Long targetId, Integer roundNo);

    /** 批次下指定状态的隐患数（@TenantId 自动加租户条件） */
    long countByBatchIdAndStatus(Long batchId, String status);

    /** 批次下尚未复核通过的隐患 */
    List<InspectionHazard> findByBatchIdAndStatusNotOrderByIdAsc(Long batchId, String status);

    long countByBatchIdAndStatusNot(Long batchId, String status);

    /**
     * 关闭收口时锁读未通过隐患：与批次行悲观锁同在一个事务内，
     * 确保「再次确认全部隐患已通过」期间不会有复核结果被改回去。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select h from InspectionHazard h where h.batchId = :batchId and h.status <> '已通过'")
    List<InspectionHazard> findOpenForUpdate(@Param("batchId") Long batchId);
}
