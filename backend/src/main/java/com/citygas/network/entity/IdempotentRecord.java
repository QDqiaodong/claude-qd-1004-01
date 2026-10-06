package com.citygas.network.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 幂等记录：巡检登记 / 提交整改 / 复核动作携带 {@code requestId}。
 * {@code (tenant_id, request_id)} 唯一；重试时直接回放首次响应，不重复产生副作用。
 */
@Entity
@Table(name = "inspect_idem")
@Getter
@Setter
public class IdempotentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** REGISTER / RECTIFY / REVIEW */
    @Column(name = "action", nullable = false, length = 24)
    private String action;

    /** 首次成功响应 JSON 快照 */
    @Column(name = "response_body", nullable = false, length = 2048)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
