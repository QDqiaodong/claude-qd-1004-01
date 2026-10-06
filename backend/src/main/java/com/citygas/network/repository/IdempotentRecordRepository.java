package com.citygas.network.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citygas.network.entity.IdempotentRecord;

/** 幂等记录 Repository：(tenant_id, request_id) 唯一 */
public interface IdempotentRecordRepository extends JpaRepository<IdempotentRecord, Long> {

    Optional<IdempotentRecord> findByRequestId(String requestId);
}
