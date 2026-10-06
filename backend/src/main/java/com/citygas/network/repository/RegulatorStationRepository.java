package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.RegulatorStation;

/** 调压站 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface RegulatorStationRepository extends JpaRepository<RegulatorStation, Long> {

    Optional<RegulatorStation> findByStationCode(String stationCode);

    List<RegulatorStation> findByStatus(String status);

    List<RegulatorStation> findByStationType(String stationType);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select r from RegulatorStation r where r.id = :id")
    Optional<RegulatorStation> findScopedById(@Param("id") Long id);
}
