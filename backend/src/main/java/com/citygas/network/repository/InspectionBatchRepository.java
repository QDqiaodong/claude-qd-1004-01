package com.citygas.network.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.InspectionBatch;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** 巡检批次 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface InspectionBatchRepository extends JpaRepository<InspectionBatch, Long> {

    Optional<InspectionBatch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findScopedById(@Param("id") Long id);

    /** 分页筛选：状态 + 编号/负责人模糊关键字。条件为 null 时不参与过滤；租户条件由 Hibernate 自动追加。 */
    @Query("""
            select b from InspectionBatch b
            where (:status is null or b.status = :status)
              and (:keyword is null
                   or b.batchNo like concat('%', :keyword, '%')
                   or b.ownerName like concat('%', :keyword, '%'))
            order by b.id desc
            """)
    Page<InspectionBatch> search(@Param("status") String status,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    /**
     * 仅「待执行」且计划开始时间已过（过期未执行）的批次，供列表的「只看过期」筛选。
     * 与状态筛选取交集：勾选「只看过期」同时又选了非「待执行」状态时结果为空，而不是悄悄丢掉状态条件。
     */
    @Query("""
            select b from InspectionBatch b
            where b.status = '待执行' and b.planStartAt < :now
              and (:status is null or b.status = :status)
              and (:keyword is null
                   or b.batchNo like concat('%', :keyword, '%')
                   or b.ownerName like concat('%', :keyword, '%'))
            order by b.id desc
            """)
    Page<InspectionBatch> searchOverduePending(@Param("status") String status,
                                               @Param("keyword") String keyword,
                                               @Param("now") java.time.LocalDateTime now,
                                               Pageable pageable);

    /**
     * 关闭收口用的行级悲观锁：两名操作员并发关闭时，后到者在拿锁阶段即被串行化，
     * 拿到锁后在事务内复查状态——批次已被对方关闭则抛冲突，绝不覆盖先到者结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findByIdForUpdate(@Param("id") Long id);
}
