package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.GateStation;

/** 门站 Repository：租户过滤由实体上 @TenantId 自动完成 */
public interface GateStationRepository extends JpaRepository<GateStation, Long> {

    Optional<GateStation> findByStationCode(String stationCode);

    List<GateStation> findByStatus(String status);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select g from GateStation g where g.id = :id")
    Optional<GateStation> findScopedById(@Param("id") Long id);
}
