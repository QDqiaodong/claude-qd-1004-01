package com.citygas.network.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.MaintenanceAcceptRequest;
import com.citygas.network.dto.MaintenanceActionRequest;
import com.citygas.network.dto.MaintenanceDetailVO;
import com.citygas.network.dto.MaintenanceImpactVO;
import com.citygas.network.dto.MaintenanceOrderRequest;
import com.citygas.network.dto.MaintenanceSafetyRequest;
import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.MaintenanceIdemRecord;
import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasUserRepository;
import com.citygas.network.repository.GateStationRepository;
import com.citygas.network.repository.MaintenanceIdemRecordRepository;
import com.citygas.network.repository.MaintenanceImpactRepository;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.repository.PipeSegmentRepository;
import com.citygas.network.repository.RegulatorStationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 管段检修影响评估与停复气联动核心业务。
 *
 * <p>检修单状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成：
 * <ul>
 *   <li>草稿（待发布）：选择计划时段、施工负责人、检修范围（待修管段）与人工边界；
 *       系统沿管段起止点（含相邻连通管段）自动关联同租户门站、调压站、燃气用户，
 *       形成可核对的影响清单；草稿可反复编辑、可手动「重新核对拓扑」；</li>
 *   <li>发布：事务内加行锁，逐对象校验停用 / 跨租户 / 删除、以及快照与当前拓扑是否冲突，
 *       任一拦截项成立即整体回滚——不冻结、不推进状态、已编辑内容原样保留；
 *       校验通过才冻结影响快照（frozen=1），之后基础档案变化只提示差异，绝不静默改写；</li>
 *   <li>停气后才可开工；施工结束进入待验收，逐管段验收；全部管段验收通过才能申请复气；</li>
 *   <li>待复气阶段逐户完成安全确认；复气放行同时满足「全部管段验收通过 + 全部用户确认」，
 *       行锁 + @Version 双保险：并发发布 / 复气只一人成功，后来者 409，无半冻结快照。</li>
 * </ul>
 * 所有写动作支持 requestId 幂等：同租户同 requestId 重试回放首次结果。
 * 读写均经 {@code @TenantId} 自动隔离，跨租户对象一律视为不存在（并在差异里显式标注）。</p>
 */
@Service
public class MaintenanceService {

    /** 幂等动作 → 首次响应回放类型 */
    private static final Map<String, Class<?>> IDEM_RESPONSE_TYPES = Map.of(
            Dict.IDEM_PUBLISH, MaintenanceOrder.class,
            Dict.IDEM_CUTOFF, MaintenanceOrder.class,
            Dict.IDEM_START_WORK, MaintenanceOrder.class,
            Dict.IDEM_FINISH_WORK, MaintenanceOrder.class,
            Dict.IDEM_ACCEPT, MaintenanceImpactVO.class,
            Dict.IDEM_CONFIRM_SAFETY, MaintenanceImpactVO.class,
            Dict.IDEM_REQUEST_RESTORE, MaintenanceOrder.class,
            Dict.IDEM_RESTORE, MaintenanceOrder.class);

    private static final Map<String, String> TARGET_TABLES = Map.of(
            Dict.TARGET_PIPE_SEGMENT, "pipe_segment",
            Dict.TARGET_GATE_STATION, "gate_station",
            Dict.TARGET_REGULATOR_STATION, "regulator_station",
            Dict.TARGET_GAS_USER, "gas_user");

    private final MaintenanceOrderRepository orderRepository;
    private final MaintenanceImpactRepository impactRepository;
    private final MaintenanceIdemRecordRepository idemRepository;
    private final PipeSegmentRepository segmentRepository;
    private final GateStationRepository gateRepository;
    private final RegulatorStationRepository regulatorRepository;
    private final GasUserRepository gasUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MaintenanceService(MaintenanceOrderRepository orderRepository,
                              MaintenanceImpactRepository impactRepository,
                              MaintenanceIdemRecordRepository idemRepository,
                              PipeSegmentRepository segmentRepository,
                              GateStationRepository gateRepository,
                              RegulatorStationRepository regulatorRepository,
                              GasUserRepository gasUserRepository,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.impactRepository = impactRepository;
        this.idemRepository = idemRepository;
        this.segmentRepository = segmentRepository;
        this.gateRepository = gateRepository;
        this.regulatorRepository = regulatorRepository;
        this.gasUserRepository = gasUserRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Page<MaintenanceOrder> search(String status, String keyword, int page, int size) {
        if (status != null) {
            status = status.trim();
            if (status.isEmpty()) {
                status = null;
            }
        }
        if (status != null && !Dict.isValidMaintenanceStatus(status)) {
            throw new BizException("检修单状态不合法：" + status);
        }
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;
            }
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return orderRepository.search(status, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public MaintenanceDetailVO detail(Long id) {
        MaintenanceOrder order = getOrder(id);
        return buildDetail(order, impactRepository.findByOrderIdOrderByIdAsc(id));
    }

    // ==================== 草稿：创建 / 编辑 / 重新核对 ====================

    @Transactional
    public MaintenanceOrder create(MaintenanceOrderRequest req) {
        String orderNo = req.getOrderNo().trim();
        if (orderRepository.existsByOrderNo(orderNo)) {
            throw new BizException("检修单编号已存在：" + orderNo);
        }
        validateRequest(req);

        LocalDateTime now = LocalDateTime.now();
        MaintenanceOrder order = new MaintenanceOrder();
        order.setOrderNo(orderNo);
        order.setPlanStartAt(req.getPlanStartAt());
        order.setPlanEndAt(req.getPlanEndAt());
        order.setOwnerName(req.getOwnerName().trim());
        order.setRemark(req.getRemark() == null ? "" : req.getRemark().trim());
        order.setStatus(Dict.MO_DRAFT);
        order.setFrozen(false);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);

        rebuildImpacts(order, req.getSegmentIds(), req.getManualBoundaries(), now);
        return order;
    }

    /** 编辑草稿：仅「待发布」且未冻结可改；编辑内容（含人工边界）原样保留到下次发布。 */
    @Transactional
    public MaintenanceOrder update(Long id, MaintenanceOrderRequest req) {
        MaintenanceOrder order = getOrder(id);
        requireDraft(order);
        validateRequest(req);

        order.setPlanStartAt(req.getPlanStartAt());
        order.setPlanEndAt(req.getPlanEndAt());
        order.setOwnerName(req.getOwnerName().trim());
        order.setRemark(req.getRemark() == null ? "" : req.getRemark().trim());
        order.setUpdatedAt(LocalDateTime.now());
        order = orderRepository.save(order);

        rebuildImpacts(order, req.getSegmentIds(), req.getManualBoundaries(), LocalDateTime.now());
        return order;
    }

    /**
     * 手动重新核对拓扑（仅草稿）：丢弃旧的自动关联结果，按当前拓扑重新生成影响清单；
     * 人工边界说明原样保留。当「快照与当前拓扑冲突」拦截发布时，运营人员核对差异后走这一步。
     */
    @Transactional
    public MaintenanceDetailVO recompute(Long id) {
        MaintenanceOrder order = getOrder(id);
        requireDraft(order);
        List<Long> segmentIds = impactRepository.findByOrderIdOrderByIdAsc(id).stream()
                .filter(i -> Dict.SOURCE_AUTO.equals(i.getSource())
                        && Dict.TARGET_PIPE_SEGMENT.equals(i.getTargetType()))
                .map(MaintenanceImpact::getTargetId)
                .toList();
        if (segmentIds.isEmpty()) {
            throw new BizException("检修范围为空，无法重新核对，请先编辑检修范围");
        }
        // 人工边界保留
        List<MaintenanceOrderRequest.ManualBoundary> manuals = impactRepository
                .findByOrderIdOrderByIdAsc(id).stream()
                .filter(i -> Dict.TARGET_MANUAL.equals(i.getTargetType()))
                .map(i -> {
                    MaintenanceOrderRequest.ManualBoundary m = new MaintenanceOrderRequest.ManualBoundary();
                    m.setCode(i.getTargetCode());
                    m.setName(i.getTargetName());
                    m.setNote(i.getManualNote());
                    return m;
                })
                .toList();
        rebuildImpacts(order, segmentIds, manuals, LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    private void validateRequest(MaintenanceOrderRequest req) {
        if (req.getOwnerName() == null || req.getOwnerName().isBlank()) {
            throw new BizException("施工负责人不能为空");
        }
        if (req.getPlanEndAt() != null && req.getPlanEndAt().isBefore(req.getPlanStartAt())) {
            throw new BizException("计划结束时间不能早于计划开始时间");
        }
        if (req.getSegmentIds() == null || req.getSegmentIds().isEmpty()) {
            throw new BizException("检修范围至少选择一条待修管段");
        }
        if (req.getManualBoundaries() != null) {
            for (MaintenanceOrderRequest.ManualBoundary m : req.getManualBoundaries()) {
                if (m.getCode() == null || m.getCode().isBlank()) {
                    throw new BizException("人工边界对象编号/标识不能为空");
                }
                if (m.getName() == null || m.getName().isBlank()) {
                    throw new BizException("人工边界对象名称/位置不能为空");
                }
                if (m.getNote() == null || m.getNote().isBlank()) {
                    throw new BizException("边界说明不能为空");
                }
            }
        }
    }

    private void requireDraft(MaintenanceOrder order) {
        if (Boolean.TRUE.equals(order.getFrozen()) || !Dict.MO_DRAFT.equals(order.getStatus())) {
            throw new BizException("检修单已发布冻结，检修范围与人工边界不能再修改，当前状态："
                    + order.getStatus());
        }
    }

    /** 重建草稿影响清单：删除旧行 → 按当前拓扑自动关联 → 追加人工边界。 */
    private void rebuildImpacts(MaintenanceOrder order, List<Long> segmentIds,
                                List<MaintenanceOrderRequest.ManualBoundary> manuals,
                                LocalDateTime now) {
        List<PipeSegment> scope = loadScopeSegments(segmentIds);

        // 实体删除：保证与 @TenantId 行为一致（不写裸 JPQL 批量删除）
        List<MaintenanceImpact> old = impactRepository.findByOrderIdOrderByIdAsc(order.getId());
        impactRepository.deleteAll(old);
        impactRepository.flush();

        Set<String> dedup = new HashSet<>();
        for (MaintenanceImpact row : deriveAutoImpacts(order.getId(), scope, now)) {
            if (dedup.add(row.getTargetType() + "#" + row.getTargetId())) {
                impactRepository.save(row);
            }
        }
        if (manuals != null) {
            Set<String> manualDedup = new HashSet<>();
            for (MaintenanceOrderRequest.ManualBoundary m : manuals) {
                String code = m.getCode().trim();
                if (!manualDedup.add(code)) {
                    throw new BizException("人工边界对象重复：" + code);
                }
                MaintenanceImpact row = newImpactRow(order.getId(), now);
                row.setTargetType(Dict.TARGET_MANUAL);
                row.setTargetId(null);
                row.setTargetCode(truncate(code, 64));
                row.setTargetName(truncate(m.getName().trim(), 128));
                row.setSource(Dict.SOURCE_MANUAL);
                row.setManualNote(truncate(m.getNote().trim(), 512));
                impactRepository.save(row);
            }
        }
    }

    private List<PipeSegment> loadScopeSegments(List<Long> segmentIds) {
        Set<Long> ids = new LinkedHashSet<>(segmentIds);
        List<PipeSegment> scope = new ArrayList<>();
        for (Long segId : ids) {
            PipeSegment s = segmentRepository.findScopedById(segId)
                    .orElseThrow(() -> new BizException(
                            "检修管段不存在或不属于当前租户：" + segId));
            scope.add(s);
        }
        return scope;
    }

    /**
     * 沿管段起止点自动关联：从检修管段的起止节点出发，沿「起止点同名」的相邻管段扩展，
     * 收齐连通管段；门站 / 调压站按站名等于节点名精确关联，燃气用户按地址包含节点名关联。
     * 全部只取当前租户数据（@TenantId 自动过滤）。
     */
    private List<MaintenanceImpact> deriveAutoImpacts(Long orderId, List<PipeSegment> scope,
                                                      LocalDateTime now) {
        List<PipeSegment> allSegments = segmentRepository.findAll();
        Set<Long> visitedSegIds = new LinkedHashSet<>();
        Set<String> nodes = new LinkedHashSet<>();
        for (PipeSegment s : scope) {
            visitedSegIds.add(s.getId());
            nodes.add(s.getStartPoint());
            nodes.add(s.getEndPoint());
        }
        // 沿起止点同名扩展到相邻管段（连通分量），直到没有新增
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PipeSegment s : allSegments) {
                if (visitedSegIds.contains(s.getId())) {
                    continue;
                }
                if (nodes.contains(s.getStartPoint()) || nodes.contains(s.getEndPoint())) {
                    visitedSegIds.add(s.getId());
                    nodes.add(s.getStartPoint());
                    nodes.add(s.getEndPoint());
                    changed = true;
                }
            }
        }

        List<MaintenanceImpact> rows = new ArrayList<>();
        for (Long segId : visitedSegIds) {
            PipeSegment s = allSegments.stream().filter(x -> x.getId().equals(segId)).findFirst().orElseThrow();
            MaintenanceImpact row = newImpactRow(orderId, now);
            row.setTargetType(Dict.TARGET_PIPE_SEGMENT);
            row.setTargetId(s.getId());
            row.setTargetCode(s.getSegCode());
            row.setTargetName(s.getStartPoint() + " → " + s.getEndPoint());
            row.setSource(Dict.SOURCE_AUTO);
            rows.add(row);
        }
        for (GateStation g : gateRepository.findAll()) {
            if (nodes.contains(g.getStationName())) {
                MaintenanceImpact row = newImpactRow(orderId, now);
                row.setTargetType(Dict.TARGET_GATE_STATION);
                row.setTargetId(g.getId());
                row.setTargetCode(g.getStationCode());
                row.setTargetName(g.getStationName());
                row.setSource(Dict.SOURCE_AUTO);
                rows.add(row);
            }
        }
        for (RegulatorStation r : regulatorRepository.findAll()) {
            if (nodes.contains(r.getStationName())) {
                MaintenanceImpact row = newImpactRow(orderId, now);
                row.setTargetType(Dict.TARGET_REGULATOR_STATION);
                row.setTargetId(r.getId());
                row.setTargetCode(r.getStationCode());
                row.setTargetName(r.getStationName());
                row.setSource(Dict.SOURCE_AUTO);
                rows.add(row);
            }
        }
        for (GasUser u : gasUserRepository.findAll()) {
            String address = u.getAddress() == null ? "" : u.getAddress();
            if (nodes.stream().anyMatch(address::contains)) {
                MaintenanceImpact row = newImpactRow(orderId, now);
                row.setTargetType(Dict.TARGET_GAS_USER);
                row.setTargetId(u.getId());
                row.setTargetCode(u.getUserNo());
                row.setTargetName(u.getUserName());
                row.setSource(Dict.SOURCE_AUTO);
                rows.add(row);
            }
        }
        return rows;
    }

    private MaintenanceImpact newImpactRow(Long orderId, LocalDateTime now) {
        MaintenanceImpact row = new MaintenanceImpact();
        row.setOrderId(orderId);
        row.setAcceptanceStatus(Dict.ACCEPT_PENDING);
        row.setSafetyStatus(Dict.SAFETY_PENDING);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    // ==================== 发布（冻结快照） ====================

    /**
     * 发布检修单：行锁内复查状态并逐项校验，任一拦截项成立整体回滚，
     * 绝不留下半冻结快照或部分状态；校验通过才冻结快照并推进到「待停气」。
     * 并发发布时只有一个请求推进状态，重复请求直接返回首次发布结果。
     */
    @Transactional
    public MaintenanceOrder publish(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_PUBLISH, () -> {
            MaintenanceOrder order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("检修单不存在：" + id));
            if (Boolean.TRUE.equals(order.getFrozen())) {
                // 并发/重复发布：先到者已冻结推进，后来请求原样返回首次结果，不再推进、不覆盖
                return order;
            }
            if (!Dict.MO_DRAFT.equals(order.getStatus())) {
                throw new BizException("非法状态跳转：当前为「" + order.getStatus()
                        + "」，不能发布");
            }

            // 发布前完整校验：停用 / 跨租户 / 删除 / 拓扑冲突——任一成立即拒绝并保留编辑内容
            MaintenanceDetailVO check = buildDetail(
                    order, impactRepository.findByOrderIdOrderByIdAsc(id));
            if (check.getBlockCount() > 0) {
                throw new BizException("发布被拦截，已保留全部编辑内容，请核对后重试："
                        + collectBlockMessages(check));
            }
            if (impactRepository.countByOrderIdAndTargetType(id, Dict.TARGET_PIPE_SEGMENT) == 0) {
                throw new BizException("发布被拦截，已保留全部编辑内容：检修范围为空");
            }

            LocalDateTime now = LocalDateTime.now();
            order.setFrozen(true);
            order.setStatus(Dict.MO_WAIT_CUTOFF);
            order.setPublishedAt(now);
            order.setPublishedBy(req.getOperator().trim());
            order.setUpdatedAt(now);
            return orderRepository.save(order);
        });
    }

    private String collectBlockMessages(MaintenanceDetailVO detail) {
        List<String> messages = new ArrayList<>();
        for (MaintenanceImpactVO vo : detail.getImpacts()) {
            for (MaintenanceImpactVO.DiffItem d : vo.getDiffs()) {
                if ("BLOCK".equals(d.getLevel())) {
                    messages.add("[" + typeLabel(vo.getTargetType()) + " " + vo.getTargetCode() + "] "
                            + d.getMessage());
                }
            }
        }
        // 拓扑新增对象等挂在详情级别的拦截项
        for (MaintenanceImpactVO.DiffItem d : detail.getOrderLevelDiffs()) {
            if ("BLOCK".equals(d.getLevel())) {
                messages.add(d.getMessage());
            }
        }
        return String.join("；", messages);
    }

    // ==================== 停气 / 开工 / 完工 ====================

    /** 停气登记：停气后才允许开工。重复停气返回首次结果，不重复写时间/操作人。 */
    @Transactional
    public MaintenanceOrder cutoff(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_CUTOFF, () -> {
            MaintenanceOrder order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("检修单不存在：" + id));
            if (Dict.MO_DONE.equals(order.getStatus())) {
                throw new BizException("检修单已完成，不能再停气");
            }
            if (!Dict.MO_WAIT_CUTOFF.equals(order.getStatus())) {
                throw new BizException("仅「待停气」的检修单可以执行停气，当前为「"
                        + order.getStatus() + "」");
            }
            if (order.getCutOffAt() != null) {
                // 并发/重复停气：返回首次停气结果，不覆盖首次的停气时间与操作人
                return order;
            }
            LocalDateTime now = LocalDateTime.now();
            order.setCutOffAt(now);
            order.setCutOffBy(req.getOperator().trim());
            order.setUpdatedAt(now);
            return orderRepository.save(order);
        });
    }

    /** 开工：必须已停气；待停气 → 施工中。 */
    @Transactional
    public MaintenanceOrder startWork(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_START_WORK, () -> {
            MaintenanceOrder order = getOrder(id);
            if (!Dict.MO_WAIT_CUTOFF.equals(order.getStatus())) {
                throw new BizException("非法状态跳转：仅「待停气」的检修单可以开工，当前为「"
                        + order.getStatus() + "」");
            }
            if (order.getCutOffAt() == null) {
                // 停气后才可开工——硬规则
                throw new BizException("尚未完成停气，禁止开工；请先执行停气操作");
            }
            return transfer(order, Dict.MO_WORKING, req.getOperator(),
                    o -> o.setStartedAt(LocalDateTime.now()));
        });
    }

    /** 施工完成：施工中 → 待验收。 */
    @Transactional
    public MaintenanceOrder finishWork(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_FINISH_WORK, () -> {
            MaintenanceOrder order = getOrder(id);
            if (!Dict.MO_WORKING.equals(order.getStatus())) {
                throw new BizException("非法状态跳转：仅「施工中」的检修单可以完工，当前为「"
                        + order.getStatus() + "」");
            }
            return transfer(order, Dict.MO_WAIT_ACCEPT, req.getOperator(),
                    o -> o.setWorkDoneAt(LocalDateTime.now()));
        });
    }

    // ==================== 管段验收 ====================

    @Transactional
    public MaintenanceImpactVO accept(Long id, Long impactId, MaintenanceAcceptRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_ACCEPT, () -> {
            MaintenanceOrder order = getOrder(id);
            if (!Dict.MO_WAIT_ACCEPT.equals(order.getStatus())) {
                throw new BizException("仅「待验收」阶段可以登记管段验收，当前为「"
                        + order.getStatus() + "」");
            }
            MaintenanceImpact impact = impactRepository.findScopedById(impactId)
                    .orElseThrow(() -> new BizException("影响对象不存在：" + impactId));
            if (!impact.getOrderId().equals(id)) {
                throw new BizException("影响对象不属于本检修单");
            }
            if (!Dict.TARGET_PIPE_SEGMENT.equals(impact.getTargetType())) {
                throw new BizException("仅管段对象需要验收：" + impact.getTargetCode());
            }
            if (req.getPassed() == null) {
                throw new BizException("验收结论不能为空");
            }
            // 已验收通过的管段：重复请求直接回放首次验收结果（验收不通过允许整改后重新验收）
            if (Dict.ACCEPT_PASSED.equals(impact.getAcceptanceStatus())
                    && Boolean.TRUE.equals(req.getPassed())) {
                return MaintenanceImpactVO.of(impact);
            }
            if (Boolean.FALSE.equals(req.getPassed())
                    && (req.getNote() == null || req.getNote().isBlank())) {
                throw new BizException("验收不通过时必须填写原因");
            }
            LocalDateTime now = LocalDateTime.now();
            impact.setAcceptanceStatus(
                    Boolean.TRUE.equals(req.getPassed()) ? Dict.ACCEPT_PASSED : Dict.ACCEPT_FAILED);
            impact.setAcceptedAt(now);
            impact.setAcceptedBy(req.getOperator().trim());
            impact.setUpdatedAt(now);
            impact = impactRepository.save(impact);
            return MaintenanceImpactVO.of(impact);
        });
    }

    // ==================== 申请复气（验收全部通过拦截） ====================

    /** 申请复气：待验收 → 待复气；所有管段必须验收通过，否则拦截。 */
    @Transactional
    public MaintenanceOrder requestRestore(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_REQUEST_RESTORE, () -> {
            MaintenanceOrder order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("检修单不存在：" + id));
            if (!Dict.MO_WAIT_ACCEPT.equals(order.getStatus())) {
                throw new BizException("非法状态跳转：仅「待验收」的检修单可以申请复气，当前为「"
                        + order.getStatus() + "」");
            }
            List<MaintenanceImpact> segments = impactRepository.findByOrderIdOrderByIdAsc(id).stream()
                    .filter(i -> Dict.TARGET_PIPE_SEGMENT.equals(i.getTargetType()))
                    .toList();
            if (segments.isEmpty()) {
                throw new BizException("检修范围没有管段，状态异常，请联系管理员");
            }
            long pending = segments.stream()
                    .filter(i -> !Dict.ACCEPT_PASSED.equals(i.getAcceptanceStatus())).count();
            if (pending > 0) {
                // 验收未完成拦截：不得进入待复气
                throw new BizException("尚有 " + pending + " 条管段未验收通过，不能申请复气；"
                        + "验收不通过的管段请整改后重新验收");
            }
            LocalDateTime now = LocalDateTime.now();
            order.setRestoreRequestedAt(now);
            return transfer(order, Dict.MO_WAIT_RESTORE, req.getOperator(), o -> { });
        });
    }

    // ==================== 用户安全确认 ====================

    @Transactional
    public MaintenanceImpactVO confirmSafety(Long id, Long impactId, MaintenanceSafetyRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_CONFIRM_SAFETY, () -> {
            MaintenanceOrder order = getOrder(id);
            if (!Dict.MO_WAIT_RESTORE.equals(order.getStatus())) {
                throw new BizException("仅「待复气」阶段可以登记用户安全确认，当前为「"
                        + order.getStatus() + "」");
            }
            MaintenanceImpact impact = impactRepository.findScopedById(impactId)
                    .orElseThrow(() -> new BizException("影响对象不存在：" + impactId));
            if (!impact.getOrderId().equals(id)) {
                throw new BizException("影响对象不属于本检修单");
            }
            if (!Dict.TARGET_GAS_USER.equals(impact.getTargetType())) {
                throw new BizException("仅燃气用户对象需要安全确认：" + impact.getTargetCode());
            }
            if (Dict.SAFETY_CONFIRMED.equals(impact.getSafetyStatus())) {
                throw new BizException("用户 " + impact.getTargetCode() + " 已完成安全确认，请勿重复提交");
            }
            LocalDateTime now = LocalDateTime.now();
            impact.setSafetyStatus(Dict.SAFETY_CONFIRMED);
            impact.setConfirmedAt(now);
            impact.setConfirmedBy(req.getOperator().trim());
            impact.setUpdatedAt(now);
            impact = impactRepository.save(impact);
            return MaintenanceImpactVO.of(impact);
        });
    }

    // ==================== 复气放行（双条件 + 并发收口） ====================

    /**
     * 复气放行：行锁 + @Version 双保险。两名操作员并发复气时只一人推进到「已完成」，
     * 重复请求直接返回首次放行结果；必须同时满足全部管段验收通过与全部用户安全确认完成。
     */
    @Transactional
    public MaintenanceOrder restore(Long id, MaintenanceActionRequest req) {
        requireOperator(req.getOperator());
        return withIdempotency(req.getRequestId(), Dict.IDEM_RESTORE, () -> {
            MaintenanceOrder order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("检修单不存在：" + id));
            if (Dict.MO_DONE.equals(order.getStatus())) {
                // 并发/重复复气：先到者已放行，后来请求原样返回首次结果，不重复推进、不覆盖
                return order;
            }
            if (!Dict.MO_WAIT_RESTORE.equals(order.getStatus())) {
                throw new BizException("非法状态跳转：仅「待复气」的检修单可以复气放行，当前为「"
                        + order.getStatus() + "」");
            }
            // 锁内复查双条件
            long pendingAccept = impactRepository
                    .countByOrderIdAndTargetTypeAndAcceptanceStatusNot(
                            id, Dict.TARGET_PIPE_SEGMENT, Dict.ACCEPT_PASSED);
            if (pendingAccept > 0) {
                throw new BizException("尚有 " + pendingAccept + " 条管段未验收通过，不能复气放行");
            }
            long pendingSafety = impactRepository
                    .countByOrderIdAndTargetTypeAndSafetyStatusNot(
                            id, Dict.TARGET_GAS_USER, Dict.SAFETY_CONFIRMED);
            if (pendingSafety > 0) {
                // 安全确认未完成拦截
                throw new BizException("尚有 " + pendingSafety + " 户受影响用户未完成安全确认，不能复气放行");
            }
            LocalDateTime now = LocalDateTime.now();
            order.setRestoredAt(now);
            order.setRestoredBy(req.getOperator().trim());
            order.setStatus(Dict.MO_DONE);
            order.setUpdatedAt(now);
            return orderRepository.save(order);
        });
    }

    // ==================== 差异比对（只读，不回写快照） ====================

    private MaintenanceDetailVO buildDetail(MaintenanceOrder order, List<MaintenanceImpact> rows) {
        boolean frozen = Boolean.TRUE.equals(order.getFrozen());
        TopologyNow now = TopologyNow.load(segmentRepository, gateRepository,
                regulatorRepository, gasUserRepository);

        // 当前拓扑自动关联结果，用于拓扑冲突 / 脱钩 / 新增提示
        List<PipeSegment> scope = new ArrayList<>();
        for (MaintenanceImpact row : rows) {
            if (Dict.TARGET_PIPE_SEGMENT.equals(row.getTargetType())
                    && Dict.SOURCE_AUTO.equals(row.getSource()) && row.getTargetId() != null) {
                now.segmentsById.getOrDefault(row.getTargetId(), Optional.empty()).ifPresent(scope::add);
            }
        }
        Set<String> currentKeys = new HashSet<>();
        if (!scope.isEmpty()) {
            for (MaintenanceImpact derived : deriveAutoImpacts(order.getId(), scope, LocalDateTime.now())) {
                currentKeys.add(derived.getTargetType() + "#" + derived.getTargetId());
            }
        }

        List<MaintenanceImpactVO> vos = new ArrayList<>();
        Set<String> snapshotKeys = new HashSet<>();
        for (MaintenanceImpact row : rows) {
            MaintenanceImpactVO vo = MaintenanceImpactVO.of(row);
            if (Dict.TARGET_MANUAL.equals(row.getTargetType())) {
                vo.setPresent(true);
            } else {
                fillDiffs(order, vo, now, currentKeys);
                snapshotKeys.add(row.getTargetType() + "#" + row.getTargetId());
            }
            vos.add(vo);
        }

        MaintenanceDetailVO detail = MaintenanceDetailVO.of(order, vos);

        // 当前拓扑新增（快照里没有）的关联对象。
        // 草稿阶段这是发布拦截项（快照与当前拓扑冲突，需要重新核对拓扑）；
        // 冻结后只提示差异，绝不改变已发布范围。
        if (!scope.isEmpty()) {
            for (String key : currentKeys) {
                if (!snapshotKeys.contains(key)) {
                    String[] parts = key.split("#", 2);
                    String label = describeCurrent(parts[0], Long.valueOf(parts[1]), now);
                    String level = frozen ? "WARN" : "BLOCK";
                    String message = frozen
                            ? "当前拓扑新增关联对象（不改变已冻结范围）：" + label
                            : "当前拓扑新增关联对象，影响清单需重新核对：" + label;
                    detail.getOrderLevelDiffs().add(
                            new MaintenanceImpactVO.DiffItem(level, "TOPOLOGY_EXTRA", message));
                }
            }
            // 行级差异已在 of() 中统计；这里把单级差异叠加进汇总（仅一次，不重复计数）
            detail.setBlockCount(detail.getBlockCount()
                    + detail.getOrderLevelDiffs().stream().filter(d -> "BLOCK".equals(d.getLevel())).count());
            detail.setWarnCount(detail.getWarnCount()
                    + detail.getOrderLevelDiffs().stream().filter(d -> "WARN".equals(d.getLevel())).count());
        }
        return detail;
    }

    private void fillDiffs(MaintenanceOrder order, MaintenanceImpactVO vo, TopologyNow now,
                           Set<String> currentKeys) {
        boolean frozen = Boolean.TRUE.equals(order.getFrozen());
        String type = vo.getTargetType();
        Long targetId = vo.getTargetId();

        String currentCode = null;
        String currentName = null;
        String currentStatus = null;
        Object current = null;
        if (Dict.TARGET_PIPE_SEGMENT.equals(type)) {
            Optional<PipeSegment> opt = targetId == null ? Optional.empty()
                    : now.segmentsById.getOrDefault(targetId, Optional.empty());
            if (opt.isPresent()) {
                PipeSegment s = opt.get();
                current = s;
                currentCode = s.getSegCode();
                currentName = s.getStartPoint() + " → " + s.getEndPoint();
                currentStatus = s.getStatus();
            }
        } else if (Dict.TARGET_GATE_STATION.equals(type)) {
            Optional<GateStation> opt = targetId == null ? Optional.empty()
                    : now.gatesById.getOrDefault(targetId, Optional.empty());
            if (opt.isPresent()) {
                GateStation g = opt.get();
                current = g;
                currentCode = g.getStationCode();
                currentName = g.getStationName();
                currentStatus = g.getStatus();
            }
        } else if (Dict.TARGET_REGULATOR_STATION.equals(type)) {
            Optional<RegulatorStation> opt = targetId == null ? Optional.empty()
                    : now.regulatorsById.getOrDefault(targetId, Optional.empty());
            if (opt.isPresent()) {
                RegulatorStation r = opt.get();
                current = r;
                currentCode = r.getStationCode();
                currentName = r.getStationName();
                currentStatus = r.getStatus();
            }
        } else if (Dict.TARGET_GAS_USER.equals(type)) {
            Optional<GasUser> opt = targetId == null ? Optional.empty()
                    : now.usersById.getOrDefault(targetId, Optional.empty());
            if (opt.isPresent()) {
                GasUser u = opt.get();
                current = u;
                currentCode = u.getUserNo();
                currentName = u.getUserName();
                currentStatus = u.getStatus();
            }
        }

        if (current == null) {
            vo.setPresent(false);
            // 跨租户（对象还在，但 tenant_id 已不是当前租户）与已删除区分提示
            String otherTenant = targetId == null ? null : findTenantOf(type, targetId);
            if (otherTenant != null) {
                vo.getDiffs().add(new MaintenanceImpactVO.DiffItem("BLOCK", "CROSS_TENANT",
                        "对象已不属于当前租户（现属：" + otherTenant + "），禁止按当前租户发布"));
            } else {
                vo.getDiffs().add(new MaintenanceImpactVO.DiffItem(
                        frozen ? "WARN" : "BLOCK", "DELETED",
                        frozen ? "对象已从当前租户档案中删除（不改变已冻结范围）"
                               : "对象已从当前租户档案中删除，请重新核对检修范围"));
            }
            return;
        }

        vo.setPresent(true);
        vo.setCurrentCode(currentCode);
        vo.setCurrentName(currentName);
        vo.setCurrentStatus(currentStatus);

        // 停用 / 报废：草稿发布拦截；已冻结只提示
        boolean disabled = Dict.TARGET_PIPE_SEGMENT.equals(type)
                ? "报废".equals(currentStatus)
                : "停用".equals(currentStatus);
        if (disabled) {
            vo.getDiffs().add(new MaintenanceImpactVO.DiffItem(
                    frozen ? "WARN" : "BLOCK", "DISABLED",
                    frozen ? "关键关联对象当前已" + currentStatus + "（不改变已冻结范围）"
                           : "关键关联对象已" + currentStatus + "，发布前请先处理"));
        }

        // 基础档案变更只提示，绝不静默改写快照
        if (currentCode != null && !currentCode.equals(vo.getTargetCode())) {
            vo.getDiffs().add(new MaintenanceImpactVO.DiffItem("WARN", "CODE_CHANGED",
                    "编号已由「" + vo.getTargetCode() + "」变更为「" + currentCode + "」（快照保留原值）"));
        }
        if (currentName != null && !currentName.equals(vo.getTargetName())) {
            vo.getDiffs().add(new MaintenanceImpactVO.DiffItem("WARN", "NAME_CHANGED",
                    "名称/位置已由「" + vo.getTargetName() + "」变更为「" + currentName + "」（快照保留原值）"));
        }

        // 拓扑脱钩：当前拓扑已不再关联该对象
        if (!currentKeys.contains(type + "#" + targetId)) {
            vo.getDiffs().add(new MaintenanceImpactVO.DiffItem(
                    frozen ? "WARN" : "BLOCK", "TOPOLOGY_GONE",
                    frozen ? "当前拓扑已不再关联该对象（不改变已冻结范围）"
                           : "快照与当前拓扑冲突：该对象已不在影响范围内，请重新核对"));
        }
    }

    /** 绕过租户鉴别器查对象当前归属租户（原生 SQL），用于区分「跨租户」与「已删除」。 */
    private String findTenantOf(String type, Long targetId) {
        String table = TARGET_TABLES.get(type);
        if (table == null) {
            return null;
        }
        try {
            List<String> tenants = jdbcTemplate.queryForList(
                    "SELECT tenant_id FROM " + table + " WHERE id = ?", String.class, targetId);
            return tenants.isEmpty() ? null : tenants.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String describeCurrent(String type, Long id, TopologyNow now) {
        if (Dict.TARGET_PIPE_SEGMENT.equals(type)) {
            return now.segmentsById.getOrDefault(id, Optional.empty())
                    .map(s -> "管段 " + s.getSegCode()).orElse("管段#" + id);
        }
        if (Dict.TARGET_GATE_STATION.equals(type)) {
            return now.gatesById.getOrDefault(id, Optional.empty())
                    .map(g -> "门站 " + g.getStationName()).orElse("门站#" + id);
        }
        if (Dict.TARGET_REGULATOR_STATION.equals(type)) {
            return now.regulatorsById.getOrDefault(id, Optional.empty())
                    .map(r -> "调压站 " + r.getStationName()).orElse("调压站#" + id);
        }
        if (Dict.TARGET_GAS_USER.equals(type)) {
            return now.usersById.getOrDefault(id, Optional.empty())
                    .map(u -> "用户 " + u.getUserNo() + " " + u.getUserName()).orElse("用户#" + id);
        }
        return type + "#" + id;
    }

    // ==================== 公共辅助 ====================

    private MaintenanceOrder getOrder(Long id) {
        return orderRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("检修单不存在：" + id));
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    private MaintenanceOrder transfer(MaintenanceOrder order, String target, String operator,
                                      java.util.function.Consumer<MaintenanceOrder> extra) {
        if (!Dict.canTransferMaintenance(order.getStatus(), target)) {
            throw new BizException("非法状态跳转：" + order.getStatus() + " → " + target);
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(target);
        order.setUpdatedAt(now);
        extra.accept(order);
        return orderRepository.save(order);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String typeLabel(String type) {
        return switch (type) {
            case Dict.TARGET_PIPE_SEGMENT -> "管段";
            case Dict.TARGET_GATE_STATION -> "门站";
            case Dict.TARGET_REGULATOR_STATION -> "调压站";
            case Dict.TARGET_GAS_USER -> "燃气用户";
            case Dict.TARGET_MANUAL -> "人工边界";
            default -> type;
        };
    }

    /**
     * requestId 幂等：同租户同 requestId 重试直接回放首次响应。
     * 业务失败时不写幂等记录（随事务回滚），不会出现「首次失败、重试却回放失败」。
     */
    private <T> T withIdempotency(String requestId, String action, java.util.function.Supplier<T> supplier) {
        if (requestId == null || requestId.isBlank()) {
            return supplier.get();
        }
        String rid = requestId.trim();
        var existed = idemRepository.findByRequestId(rid).orElse(null);
        if (existed != null) {
            if (!action.equals(existed.getAction())) {
                throw new BizException("requestId 已用于其他类型的操作，请更换后重试");
            }
            return replay(existed.getResponseBody(), IDEM_RESPONSE_TYPES.get(action));
        }

        T result = supplier.get();
        MaintenanceIdemRecord record = new MaintenanceIdemRecord();
        record.setRequestId(rid);
        record.setAction(action);
        record.setCreatedAt(LocalDateTime.now());
        try {
            record.setResponseBody(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new BizException("幂等响应序列化失败，请稍后重试");
        }
        try {
            idemRepository.save(record);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发提交同一 requestId：对方先落库，本次回放其首次结果
            MaintenanceIdemRecord winner = idemRepository.findByRequestId(rid)
                    .orElseThrow(() -> new BizException("请求正在处理中，请勿重复提交"));
            return replay(winner.getResponseBody(), IDEM_RESPONSE_TYPES.get(action));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T replay(String json, Class<?> type) {
        try {
            return (T) objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BizException("幂等响应回放失败，请稍后重试");
        }
    }

    // ==================== 当前档案快照（差异比对用） ====================

    private record TopologyNow(
            Map<Long, Optional<PipeSegment>> segmentsById,
            Map<Long, Optional<GateStation>> gatesById,
            Map<Long, Optional<RegulatorStation>> regulatorsById,
            Map<Long, Optional<GasUser>> usersById) {

        static TopologyNow load(PipeSegmentRepository segmentRepository,
                                GateStationRepository gateRepository,
                                RegulatorStationRepository regulatorRepository,
                                GasUserRepository gasUserRepository) {
            Map<Long, Optional<PipeSegment>> segs = new LinkedHashMap<>();
            for (PipeSegment s : segmentRepository.findAll()) {
                segs.put(s.getId(), Optional.of(s));
            }
            Map<Long, Optional<GateStation>> gates = new LinkedHashMap<>();
            for (GateStation g : gateRepository.findAll()) {
                gates.put(g.getId(), Optional.of(g));
            }
            Map<Long, Optional<RegulatorStation>> regs = new LinkedHashMap<>();
            for (RegulatorStation r : regulatorRepository.findAll()) {
                regs.put(r.getId(), Optional.of(r));
            }
            Map<Long, Optional<GasUser>> users = new LinkedHashMap<>();
            for (GasUser u : gasUserRepository.findAll()) {
                users.put(u.getId(), Optional.of(u));
            }
            return new TopologyNow(segs, gates, regs, users);
        }
    }
}
