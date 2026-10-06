package com.citygas.network.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.MaintenanceOrder;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** 检修单 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {

    Optional<MaintenanceOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select o from MaintenanceOrder o where o.id = :id")
    Optional<MaintenanceOrder> findScopedById(@Param("id") Long id);

    /** 状态 + 编号/负责人模糊关键字分页筛选，租户条件由 Hibernate 自动追加 */
    @Query("""
            select o from MaintenanceOrder o
            where (:status is null or o.status = :status)
              and (:keyword is null
                   or o.orderNo like concat('%', :keyword, '%')
                   or o.ownerName like concat('%', :keyword, '%'))
            order by o.id desc
            """)
    Page<MaintenanceOrder> search(@Param("status") String status,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);

    /**
     * 发布 / 复气收口用的行级悲观锁：两名操作员并发推进同一检修单时，
     * 后到者在拿锁阶段即被串行化；配合实体上的 @Version 乐观锁双保险，
     * 未成功的请求只会收到冲突，绝不会留下半冻结快照或部分状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select o from MaintenanceOrder o where o.id = :id")
    Optional<MaintenanceOrder> findByIdForUpdate(@Param("id") Long id);
}
