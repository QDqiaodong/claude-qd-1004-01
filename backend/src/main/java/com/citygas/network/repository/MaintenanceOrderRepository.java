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

    boolean existsByOrderNo(String orderNo);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select o from MaintenanceOrder o where o.id = :id")
    Optional<MaintenanceOrder> findScopedById(@Param("id") Long id);

    /** 分页筛选：状态 + 单号/施工负责人模糊关键字。条件为 null 时不参与过滤。 */
    @Query("""
            select o from MaintenanceOrder o
            where (:status is null or o.status = :status)
              and (:keyword is null
                   or o.orderNo like concat('%', :keyword, '%')
                   or o.leaderName like concat('%', :keyword, '%'))
            order by o.id desc
            """)
    Page<MaintenanceOrder> search(@Param("status") String status,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);

    /**
     * 发布 / 停气 / 完工 / 验收 / 复气用的行级悲观锁：两名操作员并发推进同一检修单时
     * 在拿锁阶段串行化，后到者在事务内复查状态与版本——只可能一人推进，绝不覆盖先到者结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select o from MaintenanceOrder o where o.id = :id")
    Optional<MaintenanceOrder> findByIdForUpdate(@Param("id") Long id);
}
