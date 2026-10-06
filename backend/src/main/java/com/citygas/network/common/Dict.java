package com.citygas.network.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 城市燃气输配词典与业务规则参数表：压力级制、管材、调压设施类型、
 * 门站与调压站状态、管段状态、用户状态与安检结论，以及压力 / 加臭 / 安检周期阈值。
 */
public final class Dict {

    private Dict() {
    }

    /** 租户：片区运营所（@TenantId 的取值域） */
    public static final List<String> TENANTS = List.of("A片区", "B片区");

    // ---------------- 门站 ----------------
    public static final List<String> GATE_STATUSES = List.of("运行", "检修", "停用");

    private static final Map<String, List<String>> GATE_NEXT = Map.of(
            "运行", List.of("检修", "停用"),
            "检修", List.of("运行", "停用"),
            "停用", List.of("检修"));

    // ---------------- 调压站 ----------------
    /** 区域调压站 / 楼栋调压箱 / 专用调压站 */
    public static final List<String> REGULATOR_TYPES = List.of("区域调压站", "楼栋调压箱", "专用调压站");

    public static final List<String> REGULATOR_STATUSES = List.of("运行", "待检", "停用");

    private static final Map<String, List<String>> REGULATOR_NEXT = Map.of(
            "运行", List.of("待检", "停用"),
            "待检", List.of("运行", "停用"),
            "停用", List.of("待检"));

    // ---------------- 管网管段 ----------------
    /** 压力级制 */
    public static final List<String> PRESSURE_LEVELS = List.of("高压", "次高压", "中压", "低压");

    /** 管材 */
    public static final List<String> MATERIALS = List.of("钢管", "PE管", "铸铁管");

    public static final List<String> SEGMENT_STATUSES = List.of("正常", "待修", "报废");

    // ---------------- 用户与安检 ----------------
    public static final List<String> USER_STATUSES = List.of("正常", "欠费", "停用");

    /** 安检结论 */
    public static final List<String> CHECK_RESULTS = List.of("合格", "限期整改", "隐患停用");

    // ---------------- 安全巡检批次 ----------------
    /** 批次状态机：待执行 → 执行中 → 待整改 → 待复核 → 已关闭 */
    public static final String BATCH_PENDING = "待执行";
    public static final String BATCH_RUNNING = "执行中";
    public static final String BATCH_RECTIFYING = "待整改";
    public static final String BATCH_REVIEWING = "待复核";
    public static final String BATCH_CLOSED = "已关闭";

    public static final List<String> BATCH_STATUSES =
            List.of(BATCH_PENDING, BATCH_RUNNING, BATCH_RECTIFYING, BATCH_REVIEWING, BATCH_CLOSED);

    /** 批次允许的相邻跳转（关闭单独收口，不在此列） */
    private static final Map<String, List<String>> BATCH_NEXT = Map.of(
            BATCH_PENDING, List.of(BATCH_RUNNING),
            BATCH_RUNNING, List.of(BATCH_RECTIFYING, BATCH_REVIEWING),
            BATCH_RECTIFYING, List.of(BATCH_REVIEWING),
            BATCH_REVIEWING, List.of(BATCH_CLOSED, BATCH_RECTIFYING),
            BATCH_CLOSED, List.of());

    /** 关联对象类型（巡检） */
    public static final String TARGET_PIPE_SEGMENT = "PIPE_SEGMENT";
    public static final String TARGET_REGULATOR_STATION = "REGULATOR_STATION";
    public static final String TARGET_GAS_USER = "GAS_USER";

    /** 检修影响对象类型 */
    public static final String TARGET_GATE_STATION = "GATE_STATION";
    /** 人工边界对象（无法沿拓扑自动判定，由运营人员说明） */
    public static final String TARGET_MANUAL = "MANUAL";

    public static final List<String> TARGET_TYPES =
            List.of(TARGET_PIPE_SEGMENT, TARGET_REGULATOR_STATION, TARGET_GAS_USER);

    /** 检修影响清单允许的对象类型（含门站、人工边界） */
    public static final List<String> MAINTENANCE_TARGET_TYPES = List.of(
            TARGET_PIPE_SEGMENT, TARGET_GATE_STATION, TARGET_REGULATOR_STATION,
            TARGET_GAS_USER, TARGET_MANUAL);

    public static boolean isValidTargetType(String t) {
        return t != null && TARGET_TYPES.contains(t);
    }

    public static boolean isValidMaintenanceTargetType(String t) {
        return t != null && MAINTENANCE_TARGET_TYPES.contains(t);
    }

    // ---------------- 管段检修与停复气联动 ----------------
    /** 检修单状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成 */
    public static final String MO_DRAFT = "待发布";
    public static final String MO_WAIT_CUTOFF = "待停气";
    public static final String MO_WORKING = "施工中";
    public static final String MO_WAIT_ACCEPT = "待验收";
    public static final String MO_WAIT_RESTORE = "待复气";
    public static final String MO_DONE = "已完成";

    public static final List<String> MO_STATUSES = List.of(
            MO_DRAFT, MO_WAIT_CUTOFF, MO_WORKING, MO_WAIT_ACCEPT, MO_WAIT_RESTORE, MO_DONE);

    /** 检修单允许的相邻状态跳转（发布与复气收口单独加锁处理，不在此列） */
    private static final Map<String, List<String>> MO_NEXT = Map.of(
            MO_DRAFT, List.of(MO_WAIT_CUTOFF),
            MO_WAIT_CUTOFF, List.of(MO_WORKING),
            MO_WORKING, List.of(MO_WAIT_ACCEPT),
            MO_WAIT_ACCEPT, List.of(MO_WAIT_RESTORE),
            MO_WAIT_RESTORE, List.of(MO_DONE),
            MO_DONE, List.of());

    /** 影响来源：拓扑自动关联 / 人工边界说明 */
    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    public static final String ACCEPT_PENDING = "未验收";
    public static final String ACCEPT_PASSED = "验收通过";
    public static final String ACCEPT_FAILED = "验收不通过";

    public static final List<String> ACCEPT_RESULTS = List.of(ACCEPT_PASSED, ACCEPT_FAILED);

    public static final String SAFETY_PENDING = "未确认";
    public static final String SAFETY_CONFIRMED = "已确认";

    /** 幂等动作 */
    public static final String IDEM_PUBLISH = "PUBLISH";
    public static final String IDEM_CUTOFF = "CUTOFF";
    public static final String IDEM_START_WORK = "START_WORK";
    public static final String IDEM_FINISH_WORK = "FINISH_WORK";
    public static final String IDEM_ACCEPT = "ACCEPT";
    public static final String IDEM_CONFIRM_SAFETY = "CONFIRM_SAFETY";
    public static final String IDEM_REQUEST_RESTORE = "REQUEST_RESTORE";
    public static final String IDEM_RESTORE = "RESTORE";

    public static boolean isValidMaintenanceStatus(String s) {
        return s != null && MO_STATUSES.contains(s);
    }

    public static boolean canTransferMaintenance(String from, String to) {
        return MO_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidAcceptResult(String s) {
        return s != null && ACCEPT_RESULTS.contains(s);
    }

    /** 对象巡检结果 */
    public static final List<String> TARGET_RESULTS = List.of("待检", "正常", "隐患");

    // ---------------- 隐患 ----------------
    /** 隐患状态：整改中 → 待复核 →（驳回回整改中）→ 已通过 */
    public static final String HAZARD_RECTIFYING = "整改中";
    public static final String HAZARD_WAIT_REVIEW = "待复核";
    public static final String HAZARD_PASSED = "已通过";

    public static final List<String> HAZARD_STATUSES =
            List.of(HAZARD_RECTIFYING, HAZARD_WAIT_REVIEW, HAZARD_PASSED);

    /** 风险等级 */
    public static final List<String> RISK_LEVELS = List.of("高", "中", "低");

    public static boolean isValidRiskLevel(String r) {
        return r != null && RISK_LEVELS.contains(r);
    }

    /** 隐患处理历史动作 */
    public static final String ACTION_REGISTER = "登记";
    public static final String ACTION_RECTIFY = "提交整改";
    public static final String ACTION_PASS = "复核通过";
    public static final String ACTION_REJECT = "驳回";

    public static boolean isValidBatchStatus(String s) {
        return s != null && BATCH_STATUSES.contains(s);
    }

    public static boolean canTransferBatch(String from, String to) {
        return BATCH_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    // ---------------- 业务阈值 ----------------
    /** 门站进站压力下限（MPa），低于此值判进气不足 */
    public static final BigDecimal MIN_INLET_PRESSURE = BigDecimal.valueOf(0.80);

    /** 调压站出口压力上限（MPa），超过即超压，不允许投运 */
    public static final BigDecimal MAX_OUTLET_PRESSURE = BigDecimal.valueOf(0.40);

    /** 加臭剂单耗下限（mg/m³），低于此值判加臭不足 */
    public static final BigDecimal MIN_ODORANT = BigDecimal.valueOf(20);

    /** 调压设施检验周期（月） */
    public static final int INSPECT_PERIOD_MONTHS = 6;

    /** 入户安检周期（月） */
    public static final int CHECK_PERIOD_MONTHS = 12;

    /** 单次抄表用量上限（m³），超过判为可疑抄表 */
    public static final BigDecimal MAX_READING_GAP = BigDecimal.valueOf(2000);

    /** 燃气单价（元/m³） */
    public static final BigDecimal GAS_PRICE = BigDecimal.valueOf(2.68);

    // ---------------- 取值方法 ----------------

    public static boolean isValidTenant(String t) {
        return t != null && TENANTS.contains(t);
    }

    public static boolean isValidGateStatus(String s) {
        return s != null && GATE_STATUSES.contains(s);
    }

    public static boolean canTransferGate(String from, String to) {
        return GATE_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidRegulatorType(String t) {
        return t != null && REGULATOR_TYPES.contains(t);
    }

    public static boolean isValidRegulatorStatus(String s) {
        return s != null && REGULATOR_STATUSES.contains(s);
    }

    public static boolean canTransferRegulator(String from, String to) {
        return REGULATOR_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidPressureLevel(String p) {
        return p != null && PRESSURE_LEVELS.contains(p);
    }

    public static boolean isValidMaterial(String m) {
        return m != null && MATERIALS.contains(m);
    }

    public static boolean isValidSegmentStatus(String s) {
        return s != null && SEGMENT_STATUSES.contains(s);
    }

    public static boolean isValidUserStatus(String s) {
        return s != null && USER_STATUSES.contains(s);
    }

    public static boolean isValidCheckResult(String r) {
        return r != null && CHECK_RESULTS.contains(r);
    }

    /** 距上次检验是否超期 */
    public static boolean isInspectOverdue(LocalDate last) {
        if (last == null) {
            return true;
        }
        return last.plusMonths(INSPECT_PERIOD_MONTHS).isBefore(LocalDate.now());
    }

    /** 距上次安检是否超期 */
    public static boolean isCheckOverdue(LocalDate last) {
        if (last == null) {
            return true;
        }
        return last.plusMonths(CHECK_PERIOD_MONTHS).isBefore(LocalDate.now());
    }
}
