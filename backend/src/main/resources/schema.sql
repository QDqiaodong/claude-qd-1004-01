SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 城市燃气 · 门站调压与入户安检管理
-- 库名 citygas_network；application.yml 里 ddl-auto: none，表结构由本文件在镜像构建期导入。
-- 多租户：每张业务表都有 tenant_id 列（A片区 / B片区），由实体上的 @TenantId 承载，
-- Hibernate 查询时自动带租户条件、写入时自动填值，业务代码不用手写 where tenant_id。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS citygas_network
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE citygas_network;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS maintenance_idem;
DROP TABLE IF EXISTS maintenance_impact;
DROP TABLE IF EXISTS maintenance_order;
DROP TABLE IF EXISTS inspect_idem;
DROP TABLE IF EXISTS inspect_hazard_history;
DROP TABLE IF EXISTS inspect_hazard;
DROP TABLE IF EXISTS inspect_batch_target;
DROP TABLE IF EXISTS inspect_batch;
DROP TABLE IF EXISTS gas_user;
DROP TABLE IF EXISTS pipe_segment;
DROP TABLE IF EXISTS regulator_station;
DROP TABLE IF EXISTS gate_station;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 门站 ----------------
CREATE TABLE gate_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(16) NOT NULL COMMENT 'A片区/B片区（@TenantId）',
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    inlet_pressure_mpa  DECIMAL(6,3) NOT NULL COMMENT '进站压力',
    outlet_pressure_mpa DECIMAL(6,3) NOT NULL COMMENT '出站压力',
    daily_flow_m3       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '日输气量',
    odorant_mg          DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '加臭剂单耗 mg/m3',
    status              VARCHAR(16) NOT NULL COMMENT '运行/检修/停用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 调压站 ----------------
CREATE TABLE regulator_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(16) NOT NULL,
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    station_type        VARCHAR(24) NOT NULL COMMENT '区域调压站/楼栋调压箱/专用调压站',
    inlet_pressure_mpa  DECIMAL(6,3) NOT NULL,
    outlet_pressure_mpa DECIMAL(6,3) NOT NULL COMMENT '不得超过 0.4MPa',
    status              VARCHAR(16) NOT NULL COMMENT '运行/待检/停用',
    last_inspect_at     DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 管网管段 ----------------
CREATE TABLE pipe_segment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      VARCHAR(16) NOT NULL,
    seg_code       VARCHAR(24) NOT NULL UNIQUE,
    start_point    VARCHAR(64) NOT NULL,
    end_point      VARCHAR(64) NOT NULL,
    material       VARCHAR(16) NOT NULL COMMENT '钢管/PE管/铸铁管',
    pressure_level VARCHAR(16) NOT NULL COMMENT '高压/次高压/中压/低压',
    length_m       DECIMAL(10,2) NOT NULL,
    buried_year    INT,
    status         VARCHAR(16) NOT NULL COMMENT '正常/待修/报废',
    last_patrol_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 用户与抄表安检 ----------------
CREATE TABLE gas_user (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      VARCHAR(16) NOT NULL,
    user_no        VARCHAR(24) NOT NULL UNIQUE,
    user_name      VARCHAR(48) NOT NULL,
    address        VARCHAR(96) NOT NULL,
    meter_no       VARCHAR(24) NOT NULL COMMENT '表号',
    last_reading   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '上期读数',
    current_reading DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '本期读数',
    status         VARCHAR(16) NOT NULL COMMENT '正常/欠费/停用',
    last_check_at  DATETIME COMMENT '上次入户安检',
    check_result   VARCHAR(16) COMMENT '合格/限期整改/隐患停用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据（两个租户各一份，用来看出 @TenantId 自动过滤的效果）
-- =====================================================================

-- A片区（城东运营所）
INSERT INTO gate_station (tenant_id, station_code, station_name, inlet_pressure_mpa, outlet_pressure_mpa, daily_flow_m3, odorant_mg, status) VALUES
('A片区', 'GS-A01', '城东门站',   1.600, 0.380, 186000.00, 24.50, '运行'),
('A片区', 'GS-A02', '滨海门站',   0.650, 0.360,  92000.00, 18.20, '运行'),
('A片区', 'GS-A03', '老城门站',   1.200, 0.350,  64000.00, 22.00, '检修');

INSERT INTO regulator_station (tenant_id, station_code, station_name, station_type, inlet_pressure_mpa, outlet_pressure_mpa, status, last_inspect_at) VALUES
('A片区', 'RS-A01', '兴业路调压站', '区域调压站', 0.380, 0.120, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('A片区', 'RS-A02', '花园小区调压箱', '楼栋调压箱', 0.120, 0.030, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY),
('A片区', 'RS-A03', '玻璃厂专用站', '专用调压站', 0.380, 0.450, '待检', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
('A片区', 'RS-A04', '临江调压站',   '区域调压站', 0.400, 0.150, '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 400 DAY);

INSERT INTO pipe_segment (tenant_id, seg_code, start_point, end_point, material, pressure_level, length_m, buried_year, status, last_patrol_at) VALUES
('A片区', 'PS-A01', '城东门站',   '兴业路调压站', '钢管',  '次高压', 3200.00, 2008, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('A片区', 'PS-A02', '兴业路调压站','花园小区',    'PE管',  '中压',   1850.00, 2015, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
('A片区', 'PS-A03', '滨海门站',   '临江调压站',   '钢管',  '高压',   4600.00, 1998, '待修', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('A片区', 'PS-A04', '老城门站',   '玻璃厂',      '铸铁管','低压',    900.00, 1985, '报废', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 300 DAY);

INSERT INTO gas_user (tenant_id, user_no, user_name, address, meter_no, last_reading, current_reading, status, last_check_at, check_result) VALUES
('A片区', 'U-A001', '王建国', '花园小区1栋301', 'M-A1001', 12450.00, 12680.00, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY,  '合格'),
('A片区', 'U-A002', '李秀兰', '花园小区2栋502', 'M-A1002',  8600.00,  8730.00, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 420 DAY, '合格'),
('A片区', 'U-A003', '张伟',   '兴业路18号',     'M-A1003', 15200.00, 15310.00, '欠费', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY,  '限期整改'),
('A片区', 'U-A004', '陈静',   '临江路9号',      'M-A1004',  3200.00,  3260.00, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 200 DAY, '合格');

-- B片区（城西运营所）
INSERT INTO gate_station (tenant_id, station_code, station_name, inlet_pressure_mpa, outlet_pressure_mpa, daily_flow_m3, odorant_mg, status) VALUES
('B片区', 'GS-B01', '城西门站', 1.450, 0.370, 142000.00, 23.80, '运行'),
('B片区', 'GS-B02', '高新门站', 1.100, 0.390,  78000.00, 21.40, '运行');

INSERT INTO regulator_station (tenant_id, station_code, station_name, station_type, inlet_pressure_mpa, outlet_pressure_mpa, status, last_inspect_at) VALUES
('B片区', 'RS-B01', '科技园调压站', '区域调压站', 0.370, 0.110, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('B片区', 'RS-B02', '学院路调压箱', '楼栋调压箱', 0.110, 0.028, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 DAY);

INSERT INTO pipe_segment (tenant_id, seg_code, start_point, end_point, material, pressure_level, length_m, buried_year, status, last_patrol_at) VALUES
('B片区', 'PS-B01', '城西门站',   '科技园调压站', '钢管', '次高压', 2750.00, 2012, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('B片区', 'PS-B02', '科技园调压站','学院路',      'PE管', '中压',   1600.00, 2019, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY);

INSERT INTO gas_user (tenant_id, user_no, user_name, address, meter_no, last_reading, current_reading, status, last_check_at, check_result) VALUES
('B片区', 'U-B001', '赵敏', '学院路5号2单元', 'M-B2001',  5400.00,  5520.00, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY, '合格'),
('B片区', 'U-B002', '孙涛', '科技园公寓A座',  'M-B2002',  9100.00,  9240.00, '正常', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 380 DAY, '合格');

-- =====================================================================
-- 安全巡检批次与隐患闭环
-- 批次状态机：待执行 → 执行中 → 待整改 → 待复核 → 已关闭
--   无隐患批次：执行中 --申请复核--> 待复核 --关闭--> 已关闭
--   有隐患批次：执行中 --提交执行结果--> 待整改；整改完成后 待整改 --申请复核--> 待复核
-- 所有新表同样带 tenant_id 鉴别器，读写强制租户隔离。
-- =====================================================================

-- ---------------- 巡检批次 ----------------
CREATE TABLE inspect_batch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(16) NOT NULL COMMENT 'A片区/B片区（@TenantId）',
    batch_no        VARCHAR(40) NOT NULL COMMENT '批次编号，租户内唯一',
    plan_start_at   DATETIME NOT NULL COMMENT '计划开始时间，过期未开始不可跳过执行',
    plan_end_at     DATETIME COMMENT '计划结束时间',
    owner_name      VARCHAR(32) NOT NULL COMMENT '负责人',
    remark          VARCHAR(512) NOT NULL COMMENT '巡检说明，不允许空白',
    status          VARCHAR(16) NOT NULL COMMENT '待执行/执行中/待整改/待复核/已关闭',
    started_at      DATETIME COMMENT '实际开始执行时间',
    review_requested_at DATETIME COMMENT '最近一次申请复核时间',
    closed_at       DATETIME COMMENT '关闭时间',
    closed_by       VARCHAR(32) COMMENT '关闭操作人',
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    UNIQUE KEY uk_inspect_batch_no (tenant_id, batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 批次关联对象（管段/调压站/燃气用户） ----------------
CREATE TABLE inspect_batch_target (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(16) NOT NULL,
    batch_id        BIGINT NOT NULL,
    target_type     VARCHAR(24) NOT NULL COMMENT 'PIPE_SEGMENT/REGULATOR_STATION/GAS_USER',
    target_id       BIGINT NOT NULL,
    target_code     VARCHAR(24) NOT NULL COMMENT '对象编号快照（seg_code/station_code/user_no）',
    target_name     VARCHAR(64) NOT NULL COMMENT '对象名称快照',
    round_no        INT NOT NULL DEFAULT 1 COMMENT '巡检轮次，整改驳回后为 2、3…',
    latest_result   VARCHAR(16) NOT NULL DEFAULT '待检' COMMENT '待检/正常/隐患',
    checked_at      DATETIME COMMENT '最近巡检时间',
    UNIQUE KEY uk_batch_target_round (tenant_id, batch_id, target_type, target_id, round_no),
    KEY idx_batch_target_batch (tenant_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 隐患 ----------------
CREATE TABLE inspect_hazard (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(16) NOT NULL,
    batch_id        BIGINT NOT NULL,
    target_type     VARCHAR(24) NOT NULL,
    target_id       BIGINT NOT NULL,
    round_no        INT NOT NULL COMMENT '所属巡检轮次',
    problem         VARCHAR(512) NOT NULL COMMENT '问题描述，不允许空白',
    risk_level      VARCHAR(8) NOT NULL COMMENT '高/中/低',
    rectify_require VARCHAR(512) NOT NULL COMMENT '整改要求',
    owner_name      VARCHAR(32) NOT NULL COMMENT '责任人',
    due_at          DATETIME NOT NULL COMMENT '整改期限，逾期提交整改一律驳回',
    status          VARCHAR(16) NOT NULL COMMENT '整改中/待复核/已通过',
    rectify_note    VARCHAR(512) COMMENT '最近一次整改说明',
    rectified_at    DATETIME COMMENT '最近一次提交整改时间',
    reviewed_at     DATETIME COMMENT '复核通过时间',
    reviewed_by     VARCHAR(32) COMMENT '复核人',
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    -- 同批次同对象同轮只能有一条隐患：既做业务去重，也托底并发重试
    UNIQUE KEY uk_hazard_round (tenant_id, batch_id, target_type, target_id, round_no),
    KEY idx_hazard_batch (tenant_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 隐患处理历史（驳回/再整改全程留痕） ----------------
CREATE TABLE inspect_hazard_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(16) NOT NULL,
    hazard_id       BIGINT NOT NULL,
    batch_id        BIGINT NOT NULL,
    action          VARCHAR(16) NOT NULL COMMENT '登记/提交整改/复核通过/驳回',
    from_status     VARCHAR(16) COMMENT '动作前状态',
    to_status       VARCHAR(16) COMMENT '动作后状态',
    operator_name   VARCHAR(32) NOT NULL COMMENT '操作人',
    note            VARCHAR(512) COMMENT '处理说明',
    created_at      DATETIME NOT NULL,
    KEY idx_hiz_hazard (tenant_id, hazard_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 网络重试幂等记录表 ----------------
-- 巡检登记 / 提交整改 / 复核动作携带 requestId：同租户同 requestId 重试直接回放首次结果，
-- 不产生第二条隐患、不重复推进状态。
CREATE TABLE inspect_idem (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(16) NOT NULL,
    request_id      VARCHAR(64) NOT NULL,
    action          VARCHAR(24) NOT NULL COMMENT 'REGISTER/RECTIFY/REVIEW',
    response_body   VARCHAR(2048) NOT NULL COMMENT '首次成功响应 JSON 快照',
    created_at      DATETIME NOT NULL,
    UNIQUE KEY uk_inspect_idem (tenant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 管段检修影响评估与停复气联动
-- 检修单状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成
--   待发布：草稿，可改检修范围与人工边界；发布时冻结影响快照，失败整体回滚、编辑内容保留
--   待停气：已发布（快照冻结），按计划执行停气；停气后方可开工
--   施工中：已停气开工；施工结束申请验收
--   待验收：逐管段验收；全部管段验收通过后可申请复气
--   待复气：全部受影响燃气用户安全确认完成后才能复气放行
--   已完成：复气放行，闭环
-- 发布后基础档案（改名/停用/拓扑）变化只能在详情里提示差异，绝不静默改写已冻结范围。
-- =====================================================================

-- ---------------- 检修单 ----------------
CREATE TABLE maintenance_order (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id            VARCHAR(16) NOT NULL COMMENT 'A片区/B片区（@TenantId）',
    order_no             VARCHAR(40) NOT NULL COMMENT '检修单编号，租户内唯一',
    plan_start_at        DATETIME NOT NULL COMMENT '计划停气/施工开始时间',
    plan_end_at          DATETIME COMMENT '计划复气/施工结束时间',
    owner_name           VARCHAR(32) NOT NULL COMMENT '施工负责人',
    remark               VARCHAR(512) NOT NULL DEFAULT '' COMMENT '检修说明',
    status               VARCHAR(16) NOT NULL COMMENT '待发布/待停气/施工中/待验收/待复气/已完成',
    frozen               TINYINT NOT NULL DEFAULT 0 COMMENT '影响快照是否已冻结：0 草稿，1 已发布冻结',
    published_at         DATETIME COMMENT '发布冻结时间',
    published_by         VARCHAR(32) COMMENT '发布操作人',
    cut_off_at           DATETIME COMMENT '停气时间',
    cut_off_by           VARCHAR(32) COMMENT '停气操作人',
    started_at           DATETIME COMMENT '实际开工时间（必须在停气之后）',
    work_done_at         DATETIME COMMENT '施工完成时间',
    restore_requested_at DATETIME COMMENT '申请复气时间',
    restored_at          DATETIME COMMENT '复气放行时间',
    restored_by          VARCHAR(32) COMMENT '复气操作人',
    version              BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本，并发发布/复气只允许一人推进',
    created_at           DATETIME NOT NULL,
    updated_at           DATETIME NOT NULL,
    UNIQUE KEY uk_maintenance_order_no (tenant_id, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 检修影响清单（草稿可编辑，发布即冻结） ----------------
CREATE TABLE maintenance_impact (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id          VARCHAR(16) NOT NULL,
    order_id           BIGINT NOT NULL,
    target_type        VARCHAR(24) NOT NULL COMMENT 'PIPE_SEGMENT/GATE_STATION/REGULATOR_STATION/GAS_USER/MANUAL',
    target_id          BIGINT COMMENT '对象 ID 快照；MANUAL 人工边界为空',
    target_code        VARCHAR(64) NOT NULL COMMENT '对象编号快照（seg_code/station_code/user_no/人工边界）',
    target_name        VARCHAR(128) NOT NULL COMMENT '对象名称/位置快照',
    source             VARCHAR(16) NOT NULL COMMENT 'AUTO 拓扑自动关联 / MANUAL 人工边界说明',
    manual_note        VARCHAR(512) COMMENT '人工无法自动判定的边界对象说明',
    -- 管段维度
    acceptance_status  VARCHAR(16) NOT NULL DEFAULT '未验收' COMMENT '未验收/验收通过/验收不通过',
    accepted_at        DATETIME,
    accepted_by        VARCHAR(32),
    -- 燃气用户维度
    safety_status      VARCHAR(16) NOT NULL DEFAULT '未确认' COMMENT '未确认/已确认',
    confirmed_at       DATETIME,
    confirmed_by       VARCHAR(32),
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL,
    UNIQUE KEY uk_maintenance_impact (tenant_id, order_id, target_type, target_id),
    KEY idx_maintenance_impact_order (tenant_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 检修动作网络重试幂等记录表 ----------------
-- 发布 / 停气 / 开工 / 完工 / 验收 / 安全确认 / 复气携带 requestId：
-- 同租户同 requestId 重试直接回放首次结果，不重复推进状态、不留半冻结快照。
CREATE TABLE maintenance_idem (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(16) NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    action        VARCHAR(24) NOT NULL COMMENT 'PUBLISH/CUTOFF/START_WORK/FINISH_WORK/ACCEPT/CONFIRM_SAFETY/RESTORE',
    response_body VARCHAR(2048) NOT NULL COMMENT '首次成功响应 JSON 快照',
    created_at    DATETIME NOT NULL,
    UNIQUE KEY uk_maintenance_idem (tenant_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
