-- 测试库（H2, MySQL 兼容模式）：结构与生产 schema.sql 保持一致，
-- 只去掉 COMMENT/ENGINE/字符集等 MySQL 专有子句。每个 @Transactional 测试自动回滚。

CREATE TABLE IF NOT EXISTS gate_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(16) NOT NULL,
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    inlet_pressure_mpa  DECIMAL(6,3) NOT NULL,
    outlet_pressure_mpa DECIMAL(6,3) NOT NULL,
    daily_flow_m3       DECIMAL(12,2) NOT NULL DEFAULT 0,
    odorant_mg          DECIMAL(6,2) NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS regulator_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(16) NOT NULL,
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    station_type        VARCHAR(24) NOT NULL,
    inlet_pressure_mpa  DECIMAL(6,3) NOT NULL,
    outlet_pressure_mpa DECIMAL(6,3) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    last_inspect_at     DATETIME
);

CREATE TABLE IF NOT EXISTS pipe_segment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      VARCHAR(16) NOT NULL,
    seg_code       VARCHAR(24) NOT NULL UNIQUE,
    start_point    VARCHAR(64) NOT NULL,
    end_point      VARCHAR(64) NOT NULL,
    material       VARCHAR(16) NOT NULL,
    pressure_level VARCHAR(16) NOT NULL,
    length_m       DECIMAL(10,2) NOT NULL,
    buried_year    INT,
    status         VARCHAR(16) NOT NULL,
    last_patrol_at DATETIME
);

CREATE TABLE IF NOT EXISTS gas_user (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      VARCHAR(16) NOT NULL,
    user_no        VARCHAR(24) NOT NULL UNIQUE,
    user_name      VARCHAR(48) NOT NULL,
    address        VARCHAR(96) NOT NULL,
    meter_no       VARCHAR(24) NOT NULL,
    last_reading   DECIMAL(12,2) NOT NULL DEFAULT 0,
    current_reading DECIMAL(12,2) NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL,
    last_check_at  DATETIME,
    check_result   VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS inspect_batch (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id            VARCHAR(16) NOT NULL,
    batch_no             VARCHAR(40) NOT NULL,
    plan_start_at        DATETIME NOT NULL,
    plan_end_at          DATETIME,
    owner_name           VARCHAR(32) NOT NULL,
    remark               VARCHAR(512) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    started_at           DATETIME,
    review_requested_at  DATETIME,
    closed_at            DATETIME,
    closed_by            VARCHAR(32),
    created_at           DATETIME NOT NULL,
    updated_at           DATETIME NOT NULL,
    CONSTRAINT uk_inspect_batch_no UNIQUE (tenant_id, batch_no)
);

CREATE TABLE IF NOT EXISTS inspect_batch_target (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(16) NOT NULL,
    batch_id      BIGINT NOT NULL,
    target_type   VARCHAR(24) NOT NULL,
    target_id     BIGINT NOT NULL,
    target_code   VARCHAR(24) NOT NULL,
    target_name   VARCHAR(64) NOT NULL,
    round_no      INT NOT NULL DEFAULT 1,
    latest_result VARCHAR(16) NOT NULL DEFAULT '待检',
    checked_at    DATETIME,
    CONSTRAINT uk_batch_target_round UNIQUE (tenant_id, batch_id, target_type, target_id, round_no)
);

CREATE TABLE IF NOT EXISTS inspect_hazard (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id        VARCHAR(16) NOT NULL,
    batch_id         BIGINT NOT NULL,
    target_type      VARCHAR(24) NOT NULL,
    target_id        BIGINT NOT NULL,
    round_no         INT NOT NULL,
    problem          VARCHAR(512) NOT NULL,
    risk_level       VARCHAR(8) NOT NULL,
    rectify_require  VARCHAR(512) NOT NULL,
    owner_name       VARCHAR(32) NOT NULL,
    due_at           DATETIME NOT NULL,
    status           VARCHAR(16) NOT NULL,
    rectify_note     VARCHAR(512),
    rectified_at     DATETIME,
    reviewed_at      DATETIME,
    reviewed_by      VARCHAR(32),
    created_at       DATETIME NOT NULL,
    updated_at       DATETIME NOT NULL,
    CONSTRAINT uk_hazard_round UNIQUE (tenant_id, batch_id, target_type, target_id, round_no)
);

CREATE TABLE IF NOT EXISTS inspect_hazard_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(16) NOT NULL,
    hazard_id     BIGINT NOT NULL,
    batch_id      BIGINT NOT NULL,
    action        VARCHAR(16) NOT NULL,
    from_status   VARCHAR(16),
    to_status     VARCHAR(16),
    operator_name VARCHAR(32) NOT NULL,
    note          VARCHAR(512),
    created_at    DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS inspect_idem (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(16) NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    action        VARCHAR(24) NOT NULL,
    response_body VARCHAR(2048) NOT NULL,
    created_at    DATETIME NOT NULL,
    CONSTRAINT uk_inspect_idem UNIQUE (tenant_id, request_id)
);

-- =====================================================================
-- 管段检修影响评估与停复气联动（结构与生产 schema.sql 保持一致，H2 MySQL 模式）
-- =====================================================================

CREATE TABLE IF NOT EXISTS maintenance_order (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id            VARCHAR(16) NOT NULL,
    order_no             VARCHAR(40) NOT NULL,
    plan_start_at        DATETIME NOT NULL,
    plan_end_at          DATETIME,
    owner_name           VARCHAR(32) NOT NULL,
    remark               VARCHAR(512) NOT NULL DEFAULT '',
    status               VARCHAR(16) NOT NULL,
    frozen               TINYINT NOT NULL DEFAULT 0,
    published_at         DATETIME,
    published_by         VARCHAR(32),
    cut_off_at           DATETIME,
    cut_off_by           VARCHAR(32),
    started_at           DATETIME,
    work_done_at         DATETIME,
    restore_requested_at DATETIME,
    restored_at          DATETIME,
    restored_by          VARCHAR(32),
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           DATETIME NOT NULL,
    updated_at           DATETIME NOT NULL,
    CONSTRAINT uk_maintenance_order_no UNIQUE (tenant_id, order_no)
);

CREATE TABLE IF NOT EXISTS maintenance_impact (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id          VARCHAR(16) NOT NULL,
    order_id           BIGINT NOT NULL,
    target_type        VARCHAR(24) NOT NULL,
    target_id          BIGINT,
    target_code        VARCHAR(64) NOT NULL,
    target_name        VARCHAR(128) NOT NULL,
    source             VARCHAR(16) NOT NULL,
    manual_note        VARCHAR(512),
    acceptance_status  VARCHAR(16) NOT NULL DEFAULT '未验收',
    accepted_at        DATETIME,
    accepted_by        VARCHAR(32),
    safety_status      VARCHAR(16) NOT NULL DEFAULT '未确认',
    confirmed_at       DATETIME,
    confirmed_by       VARCHAR(32),
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL,
    CONSTRAINT uk_maintenance_impact UNIQUE (tenant_id, order_id, target_type, target_id)
);

CREATE TABLE IF NOT EXISTS maintenance_idem (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(16) NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    action        VARCHAR(24) NOT NULL,
    response_body VARCHAR(2048) NOT NULL,
    created_at    DATETIME NOT NULL,
    CONSTRAINT uk_maintenance_idem UNIQUE (tenant_id, request_id)
);
