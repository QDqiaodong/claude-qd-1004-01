package com.citygas.network.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citygas.network.entity.MaintenanceIdemRecord;

/** 检修动作幂等记录 Repository：(tenant_id, request_id) 唯一 */
public interface MaintenanceIdemRecordRepository extends JpaRepository<MaintenanceIdemRecord, Long> {

    Optional<MaintenanceIdemRecord> findByRequestId(String requestId);
}
