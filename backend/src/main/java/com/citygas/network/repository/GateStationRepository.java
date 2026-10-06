package com.citygas.network.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citygas.network.entity.GateStation;

/** 门站 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface GateStationRepository extends JpaRepository<GateStation, Long> {

    Optional<GateStation> findByStationCode(String stationCode);

    List<GateStation> findByStatus(String status);
}
