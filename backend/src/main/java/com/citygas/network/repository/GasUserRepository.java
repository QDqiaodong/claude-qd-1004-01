package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GasUser;

/** 用户 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface GasUserRepository extends JpaRepository<GasUser, Long> {

    Optional<GasUser> findByUserNo(String userNo);

    List<GasUser> findByStatus(String status);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select u from GasUser u where u.id = :id")
    Optional<GasUser> findScopedById(@Param("id") Long id);
}
