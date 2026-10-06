package com.citygas.network.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citygas.network.entity.HazardHistory;

/** 隐患处理历史 Repository：只追加，按时间正序回放 */
public interface HazardHistoryRepository extends JpaRepository<HazardHistory, Long> {

    List<HazardHistory> findByHazardIdOrderByIdAsc(Long hazardId);

    List<HazardHistory> findByBatchIdOrderByIdAsc(Long batchId);
}
