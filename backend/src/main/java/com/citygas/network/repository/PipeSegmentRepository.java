package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.PipeSegment;

/** 管段 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface PipeSegmentRepository extends JpaRepository<PipeSegment, Long> {

    Optional<PipeSegment> findBySegCode(String segCode);

    List<PipeSegment> findByStatus(String status);

    List<PipeSegment> findByPressureLevel(String pressureLevel);

    /**
     * 按主键的显式 JPQL 读取：{@code EntityManager#find}（即默认 findById）在当前
     * Hibernate 版本下不会叠加 @TenantId 条件，跨租户按 id 读取会漏过滤；
     * 走查询翻译通道才能保证「读不存在或别的租户的对象时返回空」。
     */
    @Query("select s from PipeSegment s where s.id = :id")
    Optional<PipeSegment> findScopedById(@Param("id") Long id);
}
