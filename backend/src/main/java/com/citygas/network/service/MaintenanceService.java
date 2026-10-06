package com.citygas.network.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.AcceptRequest;
import com.citygas.network.dto.BoundaryRequest;
import com.citygas.network.dto.ConfirmRequest;
import com.citygas.network.dto.ImpactDiffVO;
import com.citygas.network.dto.MaintActionRequest;
import com.citygas.network.dto.MaintCreateRequest;
import com.citygas.network.dto.MaintDetailVO;
import com.citygas.network.dto.MaintUpdateRequest;
import com.citygas.network.dto.VersionRequest;
import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.IdempotentRecord;
import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.MaintenanceSegment;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasUserRepository;
import com.citygas.network.repository.GateStationRepository;
import com.citygas.network.repository.IdempotentRecordRepository;
import com.citygas.network.repository.MaintenanceImpactRepository;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.repository.MaintenanceSegmentRepository;
import com.citygas.network.repository.PipeSegmentRepository;
import com.citygas.network.repository.RegulatorStationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 管段检修影响评估与停复气联动核心业务。
 *
 * <p>检修单状态机：待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成：
 * <ul>
 *   <li>待发布：沿检修管段起止点自动关联当前租户的门站 / 调压站 / 燃气用户形成影响清单，
 *       无法自动判定的边界对象可人工补充（必须填写人工说明）；此阶段可编辑、可重新评估；</li>
 *   <li>发布：冻结影响快照。关键关联对象已停用、对象跨租户（查无此物）或清单与当前拓扑
 *       冲突时拒绝发布并保留编辑内容；发布后档案变化只在详情里提示差异，绝不静默改写；</li>
 *   <li>停气后才可开工：待停气 --停气开工--> 施工中 --完工报验--> 待验收；</li>
 *   <li>待验收：逐段验收，全部通过自动推进待复气；受影响用户逐户安全确认；</li>
 *   <li>复气放行：行锁内复查「全部管段验收通过 + 用户安全确认完成」双闸门，
 *       并发发布 / 复气只可能一人推进，重复请求（同 requestId）回放首次结果，
 *       未成功的请求事务整体回滚，不留半冻结快照或部分状态。</li>
 * </ul>
 * 所有写操作统一校验租户（@TenantId）、状态机与乐观锁版本。</p>
 */
@Service
public class MaintenanceService {

    /** 幂等动作 → 首次响应回放类型 */
    private static final Map<String, Class<?>> IDEM_RESPONSE_TYPES = Map.of(
            "MAINT_PUBLISH", MaintenanceOrder.class,
            "MAINT_RESTORE", MaintenanceOrder.class);

    private final MaintenanceOrderRepository orderRepository;
    private final MaintenanceSegmentRepository segmentLinkRepository;
    private final MaintenanceImpactRepository impactRepository;
    private final PipeSegmentRepository segmentRepository;
    private final GateStationRepository gateRepository;
    private final RegulatorStationRepository regulatorRepository;
    private final GasUserRepository gasUserRepository;
    private final IdempotentRecordRepository idemRepository;
    private final ObjectMapper objectMapper;

    public MaintenanceService(MaintenanceOrderRepository orderRepository,
                              MaintenanceSegmentRepository segmentLinkRepository,
                              MaintenanceImpactRepository impactRepository,
                              PipeSegmentRepository segmentRepository,
                              GateStationRepository gateRepository,
                              RegulatorStationRepository regulatorRepository,
                              GasUserRepository gasUserRepository,
                              IdempotentRecordRepository idemRepository,
                              ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.segmentLinkRepository = segmentLinkRepository;
        this.impactRepository = impactRepository;
        this.segmentRepository = segmentRepository;
        this.gateRepository = gateRepository;
        this.regulatorRepository = regulatorRepository;
        this.gasUserRepository = gasUserRepository;
        this.idemRepository = idemRepository;
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
        if (status != null && !Dict.isValidMaintStatus(status)) {
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
    public MaintDetailVO detail(Long id) {
        MaintenanceOrder order = getOrder(id);
        List<MaintenanceSegment> links = segmentLinkRepository.findByOrderIdOrderByIdAsc(id);
        List<MaintenanceImpact> impacts = impactRepository.findByOrderIdOrderByIdAsc(id);

        MaintDetailVO vo = new MaintDetailVO();
        vo.setOrder(order);
        vo.setSegments(links);
        vo.setImpacts(impacts);
        vo.setPendingAcceptCount(links.stream()
                .filter(l -> !Dict.ACCEPT_PASSED.equals(l.getAcceptStatus())).count());
        vo.setPendingConfirmCount(impacts.stream()
                .filter(i -> Dict.IMPACT_GAS_USER.equals(i.getObjectType())
                        && !Dict.CONFIRM_DONE.equals(i.getConfirmStatus())).count());
        // 已发布的单才计算差异：快照已冻结，档案/拓扑变化只提示不改写
        vo.setDiffs(Dict.MAINT_DRAFT.equals(order.getStatus())
                ? List.of() : computeDiffs(links, impacts));
        return vo;
    }

    // ==================== 建档与编辑（待发布） ====================

    @Transactional
    public MaintenanceOrder create(MaintCreateRequest req) {
        String orderNo = req.getOrderNo() == null ? "" : req.getOrderNo().trim();
        if (orderNo.isBlank()) {
            throw new BizException("检修单号不能为空");
        }
        validateHeader(req.getPlanStartAt(), req.getPlanEndAt(), req.getLeaderName(),
                req.getScopeRemark(), req.getSegmentIds());
        if (orderRepository.existsByOrderNo(orderNo)) {
            throw new BizException("检修单号已存在：" + orderNo);
        }
        List<PipeSegment> segments = loadScopeSegments(req.getSegmentIds());

        LocalDateTime now = LocalDateTime.now();
        MaintenanceOrder order = new MaintenanceOrder();
        order.setOrderNo(orderNo);
        order.setPlanStartAt(req.getPlanStartAt());
        order.setPlanEndAt(req.getPlanEndAt());
        order.setLeaderName(req.getLeaderName().trim());
        order.setScopeRemark(req.getScopeRemark().trim());
        order.setStatus(Dict.MAINT_DRAFT);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);

        insertSegmentLinks(order.getId(), segments);
        insertAutoImpacts(order.getId(), segments);
        return order;
    }

    /** 编辑草稿：全量替换基本信息与检修范围，自动影响随之重建，人工边界保留 */
    @Transactional
    public MaintenanceOrder update(Long id, MaintUpdateRequest req) {
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        requireDraft(order, "编辑");
        validateHeader(req.getPlanStartAt(), req.getPlanEndAt(), req.getLeaderName(),
                req.getScopeRemark(), req.getSegmentIds());
        List<PipeSegment> segments = loadScopeSegments(req.getSegmentIds());

        order.setPlanStartAt(req.getPlanStartAt());
        order.setPlanEndAt(req.getPlanEndAt());
        order.setLeaderName(req.getLeaderName().trim());
        order.setScopeRemark(req.getScopeRemark().trim());
        order.setUpdatedAt(LocalDateTime.now());

        // 重建范围与自动影响（派生删除后先 flush 再插入，避免同键先插后删触发唯一冲突）
        segmentLinkRepository.deleteByOrderId(id);
        impactRepository.deleteByOrderIdAndSource(id, Dict.IMPACT_SOURCE_AUTO);
        segmentLinkRepository.flush();
        impactRepository.flush();
        insertSegmentLinks(id, segments);
        insertAutoImpacts(id, segments);
        return orderRepository.save(order);
    }

    /** 重新评估影响：按当前拓扑重建自动关联行（人工边界保留），并刷新管段快照名 */
    @Transactional
    public MaintDetailVO refreshImpact(Long id, VersionRequest req) {
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        requireDraft(order, "重新评估影响");
        List<MaintenanceSegment> links = segmentLinkRepository.findByOrderIdOrderByIdAsc(id);
        if (links.isEmpty()) {
            throw new BizException("检修范围不能为空，请先编辑检修单选择待修管段");
        }
        List<PipeSegment> segments = new ArrayList<>();
        for (MaintenanceSegment link : links) {
            PipeSegment s = segmentRepository.findScopedById(link.getSegmentId())
                    .orElseThrow(() -> new BizException(
                            "检修管段不存在或不属于当前租户：" + link.getSegCode()));
            if ("报废".equals(s.getStatus())) {
                throw new BizException("管段 " + s.getSegCode() + " 已报废，请先调整检修范围");
            }
            link.setSegCode(s.getSegCode());
            link.setSegName(s.getStartPoint() + " → " + s.getEndPoint());
            segmentLinkRepository.save(link);
            segments.add(s);
        }
        impactRepository.deleteByOrderIdAndSource(id, Dict.IMPACT_SOURCE_AUTO);
        impactRepository.flush();
        insertAutoImpacts(id, segments);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return detail(id);
    }

    // ==================== 人工边界对象 ====================

    @Transactional
    public MaintenanceImpact addBoundary(Long id, BoundaryRequest req) {
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        requireDraft(order, "补充人工边界对象");
        if (!Dict.isValidImpactType(req.getObjectType())) {
            throw new BizException("边界对象类型不合法：" + req.getObjectType());
        }
        if (req.getNote() == null || req.getNote().isBlank()) {
            throw new BizException("人工说明不能为空：无法自动判定的边界对象必须填写说明");
        }
        List<MaintenanceImpact> impacts = impactRepository.findByOrderIdOrderByIdAsc(id);
        boolean duplicated = impacts.stream().anyMatch(i ->
                i.getObjectType().equals(req.getObjectType()) && i.getObjectId().equals(req.getObjectId()));
        if (duplicated) {
            throw new BizException("该对象已在影响清单中，请勿重复补充");
        }
        ImpactSeed seed = resolveImpactObject(req.getObjectType(), req.getObjectId());

        MaintenanceImpact impact = new MaintenanceImpact();
        impact.setOrderId(id);
        impact.setObjectType(seed.objectType());
        impact.setObjectId(seed.objectId());
        impact.setObjectCode(seed.objectCode());
        impact.setObjectName(seed.objectName());
        impact.setSource(Dict.IMPACT_SOURCE_MANUAL);
        impact.setViaDesc("人工补充");
        impact.setManualNote(req.getNote().trim());
        impact.setConfirmStatus(Dict.IMPACT_GAS_USER.equals(seed.objectType())
                ? Dict.CONFIRM_PENDING : Dict.CONFIRM_NA);
        impact = impactRepository.save(impact);

        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
        return impact;
    }

    @Transactional
    public void removeBoundary(Long id, Long impactId, VersionRequest req) {
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        requireDraft(order, "移除人工边界对象");
        MaintenanceImpact impact = impactRepository.findScopedById(impactId)
                .orElseThrow(() -> new BizException("影响对象不存在：" + impactId));
        if (!impact.getOrderId().equals(id)) {
            throw new BizException("影响对象不属于检修单：" + order.getOrderNo());
        }
        if (!Dict.IMPACT_SOURCE_MANUAL.equals(impact.getSource())) {
            throw new BizException("自动关联对象不能删除，请通过「重新评估影响」或调整检修范围变更");
        }
        impactRepository.delete(impact);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);
    }

    // ==================== 发布（冻结影响快照） ====================

    @Transactional
    public MaintenanceOrder publish(Long id, MaintActionRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = lockOrder(id);
        return withIdempotency(req.getRequestId(), "MAINT_PUBLISH", () -> doPublish(order, req));
    }

    private MaintenanceOrder doPublish(MaintenanceOrder order, MaintActionRequest req) {
        requireVersion(order, req.getVersion());
        if (!Dict.MAINT_DRAFT.equals(order.getStatus())) {
            throw new ConflictException("检修单 " + order.getOrderNo() + " 已由「"
                    + order.getPublishedBy() + "」发布（当前「" + order.getStatus()
                    + "」），本次发布未生效，请勿覆盖已发布范围");
        }
        List<MaintenanceSegment> links = segmentLinkRepository.findByOrderIdOrderByIdAsc(order.getId());
        if (links.isEmpty()) {
            throw new BizException("检修范围不能为空，请先编辑检修单选择待修管段");
        }
        List<MaintenanceImpact> impacts = impactRepository.findByOrderIdOrderByIdAsc(order.getId());

        // ① 关键关联对象校验：跨租户（查无此物）/ 已停用，一律拒绝发布
        List<PipeSegment> segments = new ArrayList<>();
        for (MaintenanceSegment link : links) {
            PipeSegment s = segmentRepository.findScopedById(link.getSegmentId())
                    .orElseThrow(() -> new BizException(
                            "检修管段不存在或不属于当前租户：" + link.getSegCode() + "，发布被拒绝"));
            if ("报废".equals(s.getStatus())) {
                throw new BizException("检修管段 " + s.getSegCode()
                        + " 已报废（关键关联对象已停用），发布被拒绝，请先调整检修范围");
            }
            segments.add(s);
        }
        for (MaintenanceImpact impact : impacts) {
            assertImpactObjectAvailable(impact);
        }

        // ② 快照与当前拓扑冲突校验：按当前档案重新推导，与清单自动行逐一对齐
        Set<String> freshKeys = deriveImpacts(segments).stream().map(ImpactSeed::key)
                .collect(Collectors.toSet());
        Set<String> autoKeys = impacts.stream()
                .filter(i -> Dict.IMPACT_SOURCE_AUTO.equals(i.getSource()))
                .map(i -> i.getObjectType() + "#" + i.getObjectId())
                .collect(Collectors.toSet());
        if (!freshKeys.equals(autoKeys)) {
            throw new BizException("影响清单与当前拓扑不一致（档案或管段起止点已变化），发布被拒绝；"
                    + "请先「重新评估影响」核对差异后再发布");
        }

        // ③ 冻结快照：状态推进后影响清单只读，档案变化仅形成差异提示
        LocalDateTime now = LocalDateTime.now();
        order.setPublishedAt(now);
        order.setPublishedBy(req.getOperator().trim());
        return transfer(order, Dict.MAINT_PUBLISHED, now);
    }

    // ==================== 停气开工 / 完工报验 ====================

    /** 停气开工：待停气 → 施工中。停气是开工的前置，只能从这里进入施工中。 */
    @Transactional
    public MaintenanceOrder stopGas(Long id, MaintActionRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        if (!Dict.MAINT_PUBLISHED.equals(order.getStatus())) {
            if (Dict.MAINT_DRAFT.equals(order.getStatus())) {
                throw new BizException("检修单尚未发布，不能停气开工");
            }
            throw new ConflictException("检修单已停气开工（当前「" + order.getStatus() + "」），请勿重复操作");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStoppedAt(now);
        order.setStoppedBy(req.getOperator().trim());
        return transfer(order, Dict.MAINT_WORKING, now);
    }

    /** 完工报验：施工中 → 待验收 */
    @Transactional
    public MaintenanceOrder finishWork(Long id, MaintActionRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = lockOrder(id);
        requireVersion(order, req.getVersion());
        if (!Dict.MAINT_WORKING.equals(order.getStatus())) {
            if (Dict.MAINT_DRAFT.equals(order.getStatus()) || Dict.MAINT_PUBLISHED.equals(order.getStatus())) {
                throw new BizException("检修单尚未停气开工，不能完工报验（停气后才可开工）");
            }
            throw new ConflictException("检修单已完工报验（当前「" + order.getStatus() + "」），请勿重复操作");
        }
        order.setWorkFinishedAt(LocalDateTime.now());
        return transfer(order, Dict.MAINT_ACCEPTING, LocalDateTime.now());
    }

    // ==================== 管段验收 / 用户安全确认 ====================

    /** 管段验收：逐段通过；最后一段通过时检修单自动推进到「待复气」 */
    @Transactional
    public MaintenanceSegment accept(Long orderId, Long linkId, AcceptRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = lockOrder(orderId);
        requireVersion(order, req.getVersion());
        if (!Dict.MAINT_ACCEPTING.equals(order.getStatus())) {
            throw new BizException("仅「待验收」状态可登记管段验收，当前为「" + order.getStatus() + "」");
        }
        MaintenanceSegment link = segmentLinkRepository.findScopedById(linkId)
                .orElseThrow(() -> new BizException("检修管段不存在：" + linkId));
        if (!link.getOrderId().equals(orderId)) {
            throw new BizException("检修管段不属于检修单：" + order.getOrderNo());
        }
        if (Dict.ACCEPT_PASSED.equals(link.getAcceptStatus())) {
            throw new BizException("管段 " + link.getSegCode() + " 已验收通过，请勿重复登记");
        }
        LocalDateTime now = LocalDateTime.now();
        link.setAcceptStatus(Dict.ACCEPT_PASSED);
        link.setAcceptNote(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim());
        link.setAcceptedAt(now);
        link.setAcceptedBy(req.getOperator().trim());
        link = segmentLinkRepository.save(link);

        if (segmentLinkRepository.countByOrderIdAndAcceptStatusNot(orderId, Dict.ACCEPT_PASSED) == 0) {
            transfer(order, Dict.MAINT_RESTORING, now);
        }
        return link;
    }

    /** 受影响用户安全确认：发布后、完成前逐户确认，是复气放行的闸门之一 */
    @Transactional
    public MaintenanceImpact confirmUser(Long orderId, Long impactId, ConfirmRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = getOrder(orderId);
        if (Dict.MAINT_DRAFT.equals(order.getStatus()) || Dict.MAINT_DONE.equals(order.getStatus())) {
            throw new BizException("当前状态「" + order.getStatus() + "」不可进行用户安全确认");
        }
        MaintenanceImpact impact = impactRepository.findScopedById(impactId)
                .orElseThrow(() -> new BizException("影响对象不存在：" + impactId));
        if (!impact.getOrderId().equals(orderId)) {
            throw new BizException("影响对象不属于检修单：" + order.getOrderNo());
        }
        if (!Dict.IMPACT_GAS_USER.equals(impact.getObjectType())) {
            throw new BizException("仅燃气用户需要安全确认，场站类对象无需确认");
        }
        if (Dict.CONFIRM_DONE.equals(impact.getConfirmStatus())) {
            throw new BizException("用户 " + impact.getObjectCode() + " 已完成安全确认，请勿重复操作");
        }
        impact.setConfirmStatus(Dict.CONFIRM_DONE);
        impact.setConfirmedAt(LocalDateTime.now());
        impact.setConfirmedBy(req.getOperator().trim());
        return impactRepository.save(impact);
    }

    // ==================== 复气放行 ====================

    @Transactional
    public MaintenanceOrder restoreGas(Long id, MaintActionRequest req) {
        requireOperator(req.getOperator());
        MaintenanceOrder order = lockOrder(id);
        return withIdempotency(req.getRequestId(), "MAINT_RESTORE", () -> doRestore(order, req));
    }

    private MaintenanceOrder doRestore(MaintenanceOrder order, MaintActionRequest req) {
        requireVersion(order, req.getVersion());
        if (Dict.MAINT_DONE.equals(order.getStatus())) {
            throw new ConflictException("检修单 " + order.getOrderNo() + " 已由「"
                    + order.getRestoredBy() + "」复气放行，您的请求未生效，请勿重复复气");
        }
        if (!Dict.MAINT_RESTORING.equals(order.getStatus())) {
            throw new BizException("非法状态跳转：仅「待复气」的检修单可以复气放行，当前为「"
                    + order.getStatus() + "」");
        }
        // 双闸门缺一不可：全部管段验收通过 + 受影响用户安全确认完成
        long unaccepted = segmentLinkRepository.countByOrderIdAndAcceptStatusNot(
                order.getId(), Dict.ACCEPT_PASSED);
        if (unaccepted > 0) {
            throw new BizException("还有 " + unaccepted + " 段检修管段未验收通过，不得复气放行");
        }
        long unconfirmed = impactRepository.countByOrderIdAndObjectTypeAndConfirmStatusNot(
                order.getId(), Dict.IMPACT_GAS_USER, Dict.CONFIRM_DONE);
        if (unconfirmed > 0) {
            throw new BizException("还有 " + unconfirmed + " 户受影响用户未完成安全确认，不得复气放行");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setRestoredAt(now);
        order.setRestoredBy(req.getOperator().trim());
        order.setCompletedAt(now);
        return transfer(order, Dict.MAINT_DONE, now);
    }

    // ==================== 拓扑推导与差异 ====================

    /** 影响种子：一次拓扑推导命中的对象及其来源说明 */
    private record ImpactSeed(String objectType, Long objectId, String objectCode,
                              String objectName, String viaDesc) {
        String key() {
            return objectType + "#" + objectId;
        }
    }

    private record Endpoint(PipeSegment segment, String point, String endLabel) {
    }

    /**
     * 沿管段起止点关联当前租户的影响对象（结果按 (类型, id) 去重，先命中者优先）：
     * 门站 / 调压站——站名与任一起止点互相包含即关联；燃气用户——地址包含任一起止点即关联。
     */
    private List<ImpactSeed> deriveImpacts(Collection<PipeSegment> segments) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (PipeSegment s : segments) {
            endpoints.add(new Endpoint(s, s.getStartPoint(), "起点"));
            endpoints.add(new Endpoint(s, s.getEndPoint(), "终点"));
        }
        Map<String, ImpactSeed> result = new LinkedHashMap<>();
        for (GateStation g : gateRepository.findAll()) {
            for (Endpoint ep : endpoints) {
                if (matchesStation(g.getStationName(), ep.point())) {
                    result.putIfAbsent(Dict.IMPACT_GATE_STATION + "#" + g.getId(),
                            new ImpactSeed(Dict.IMPACT_GATE_STATION, g.getId(),
                                    g.getStationCode(), g.getStationName(), viaOf(ep)));
                    break;
                }
            }
        }
        for (RegulatorStation r : regulatorRepository.findAll()) {
            for (Endpoint ep : endpoints) {
                if (matchesStation(r.getStationName(), ep.point())) {
                    result.putIfAbsent(Dict.IMPACT_REGULATOR_STATION + "#" + r.getId(),
                            new ImpactSeed(Dict.IMPACT_REGULATOR_STATION, r.getId(),
                                    r.getStationCode(), r.getStationName(), viaOf(ep)));
                    break;
                }
            }
        }
        for (GasUser u : gasUserRepository.findAll()) {
            for (Endpoint ep : endpoints) {
                if (ep.point() != null && !ep.point().isBlank()
                        && u.getAddress() != null && u.getAddress().contains(ep.point())) {
                    result.putIfAbsent(Dict.IMPACT_GAS_USER + "#" + u.getId(),
                            new ImpactSeed(Dict.IMPACT_GAS_USER, u.getId(),
                                    u.getUserNo(), u.getUserName(), viaOf(ep)));
                    break;
                }
            }
        }
        return List.copyOf(result.values());
    }

    private String viaOf(Endpoint ep) {
        return "管段" + ep.segment().getSegCode() + "·" + ep.endLabel();
    }

    /** 站名与起止点互相包含即视为拓扑关联（如「花园小区调压箱」↔ 起止点「花园小区」） */
    private boolean matchesStation(String stationName, String point) {
        if (stationName == null || stationName.isBlank() || point == null || point.isBlank()) {
            return false;
        }
        return stationName.contains(point) || point.contains(stationName);
    }

    /**
     * 发布后差异提示：冻结快照 vs 当前拓扑 / 档案。
     * 只读计算，绝不回写影响清单。
     */
    private List<ImpactDiffVO> computeDiffs(List<MaintenanceSegment> links,
                                            List<MaintenanceImpact> impacts) {
        List<ImpactDiffVO> diffs = new ArrayList<>();
        Map<Long, PipeSegment> currentSegments = new LinkedHashMap<>();
        for (MaintenanceSegment link : links) {
            var current = segmentRepository.findScopedById(link.getSegmentId());
            if (current.isEmpty()) {
                diffs.add(new ImpactDiffVO(Dict.DIFF_UNAVAILABLE, "PIPE_SEGMENT",
                        link.getSegCode(), link.getSegName(), "检修管段已删除或不属于当前租户"));
            } else {
                PipeSegment s = current.get();
                currentSegments.put(s.getId(), s);
                if ("报废".equals(s.getStatus())) {
                    diffs.add(new ImpactDiffVO(Dict.DIFF_UNAVAILABLE, "PIPE_SEGMENT",
                            s.getSegCode(), link.getSegName(), "检修管段已报废"));
                }
            }
        }

        List<ImpactSeed> fresh = deriveImpacts(currentSegments.values());
        Set<String> snapshotKeys = impacts.stream()
                .map(i -> i.getObjectType() + "#" + i.getObjectId())
                .collect(Collectors.toSet());
        // 新增：当前拓扑关联到、但发布快照未纳入的对象
        for (ImpactSeed seed : fresh) {
            if (!snapshotKeys.contains(seed.key())) {
                diffs.add(new ImpactDiffVO(Dict.DIFF_ADDED, seed.objectType(),
                        seed.objectCode(), seed.objectName(),
                        "当前拓扑新关联的影响对象（" + seed.viaDesc() + "），未纳入已发布快照"));
            }
        }

        Set<String> freshKeys = fresh.stream().map(ImpactSeed::key).collect(Collectors.toSet());
        for (MaintenanceImpact impact : impacts) {
            String key = impact.getObjectType() + "#" + impact.getObjectId();
            // 偏离：自动关联行在当前拓扑中已推导不出来（档案改名/删除或起止点变化）
            if (Dict.IMPACT_SOURCE_AUTO.equals(impact.getSource()) && !freshKeys.contains(key)) {
                diffs.add(new ImpactDiffVO(Dict.DIFF_REMOVED, impact.getObjectType(),
                        impact.getObjectCode(), impact.getObjectName(),
                        "快照对象已不在当前自动关联范围（档案或拓扑已变化）"));
            }
            // 失效：快照对象当前已停用或已删除
            ObjectStatus status = currentObjectStatus(impact.getObjectType(), impact.getObjectId());
            if (status.missing()) {
                diffs.add(new ImpactDiffVO(Dict.DIFF_UNAVAILABLE, impact.getObjectType(),
                        impact.getObjectCode(), impact.getObjectName(), "快照对象已删除或不属于当前租户"));
            } else if (status.disabled()) {
                diffs.add(new ImpactDiffVO(Dict.DIFF_UNAVAILABLE, impact.getObjectType(),
                        impact.getObjectCode(), impact.getObjectName(), "快照对象已停用"));
            }
        }
        return diffs;
    }

    private record ObjectStatus(boolean missing, boolean disabled) {
    }

    /** 影响对象当前状态：查无此物（跨租户/已删除）或已停用 */
    private ObjectStatus currentObjectStatus(String type, Long objectId) {
        if (Dict.IMPACT_GATE_STATION.equals(type)) {
            return gateRepository.findScopedById(objectId)
                    .map(g -> new ObjectStatus(false, "停用".equals(g.getStatus())))
                    .orElse(new ObjectStatus(true, false));
        }
        if (Dict.IMPACT_REGULATOR_STATION.equals(type)) {
            return regulatorRepository.findScopedById(objectId)
                    .map(r -> new ObjectStatus(false, "停用".equals(r.getStatus())))
                    .orElse(new ObjectStatus(true, false));
        }
        return gasUserRepository.findScopedById(objectId)
                .map(u -> new ObjectStatus(false, "停用".equals(u.getStatus())))
                .orElse(new ObjectStatus(true, false));
    }

    /** 发布前校验：影响对象必须存在（同租户）且未停用 */
    private void assertImpactObjectAvailable(MaintenanceImpact impact) {
        ObjectStatus status = currentObjectStatus(impact.getObjectType(), impact.getObjectId());
        if (status.missing()) {
            throw new BizException("影响对象 " + impact.getObjectCode()
                    + "（" + impact.getObjectName() + "）不存在或不属于当前租户，发布被拒绝");
        }
        if (status.disabled()) {
            throw new BizException("影响对象 " + impact.getObjectCode()
                    + "（" + impact.getObjectName() + "）已停用（关键关联对象已停用），发布被拒绝");
        }
    }

    // ==================== 公共辅助 ====================

    private void validateHeader(LocalDateTime planStartAt, LocalDateTime planEndAt,
                                String leaderName, String scopeRemark, List<Long> segmentIds) {
        if (planStartAt == null) {
            throw new BizException("计划开始时间不能为空");
        }
        if (planEndAt != null && planEndAt.isBefore(planStartAt)) {
            throw new BizException("计划结束时间不能早于计划开始时间");
        }
        if (leaderName == null || leaderName.isBlank()) {
            throw new BizException("施工负责人不能为空");
        }
        if (scopeRemark == null || scopeRemark.isBlank()) {
            throw new BizException("检修范围说明不能为空");
        }
        if (segmentIds == null || segmentIds.isEmpty()) {
            throw new BizException("至少选择一段待修管段");
        }
    }

    /** 检修范围管段：真实存在、同租户、不重复、未报废（报废管段走报废流程而非检修） */
    private List<PipeSegment> loadScopeSegments(List<Long> segmentIds) {
        Set<Long> dedupe = new LinkedHashSet<>(segmentIds);
        if (dedupe.size() != segmentIds.size()) {
            throw new BizException("检修管段重复选择");
        }
        List<PipeSegment> result = new ArrayList<>();
        for (Long segId : dedupe) {
            PipeSegment s = segmentRepository.findScopedById(segId)
                    .orElseThrow(() -> new BizException("检修管段不存在或不属于当前租户：" + segId));
            if ("报废".equals(s.getStatus())) {
                throw new BizException("管段 " + s.getSegCode() + " 已报废，不能纳入检修范围");
            }
            result.add(s);
        }
        return result;
    }

    private void insertSegmentLinks(Long orderId, List<PipeSegment> segments) {
        for (PipeSegment s : segments) {
            MaintenanceSegment link = new MaintenanceSegment();
            link.setOrderId(orderId);
            link.setSegmentId(s.getId());
            link.setSegCode(s.getSegCode());
            link.setSegName(s.getStartPoint() + " → " + s.getEndPoint());
            link.setAcceptStatus(Dict.ACCEPT_PENDING);
            segmentLinkRepository.save(link);
        }
    }

    private void insertAutoImpacts(Long orderId, List<PipeSegment> segments) {
        for (ImpactSeed seed : deriveImpacts(segments)) {
            MaintenanceImpact impact = new MaintenanceImpact();
            impact.setOrderId(orderId);
            impact.setObjectType(seed.objectType());
            impact.setObjectId(seed.objectId());
            impact.setObjectCode(seed.objectCode());
            impact.setObjectName(seed.objectName());
            impact.setSource(Dict.IMPACT_SOURCE_AUTO);
            impact.setViaDesc(seed.viaDesc());
            impact.setConfirmStatus(Dict.IMPACT_GAS_USER.equals(seed.objectType())
                    ? Dict.CONFIRM_PENDING : Dict.CONFIRM_NA);
            impactRepository.save(impact);
        }
    }

    /** 人工边界对象解析：真实存在、同租户、未停用，返回快照字段 */
    private ImpactSeed resolveImpactObject(String type, Long objectId) {
        if (Dict.IMPACT_GATE_STATION.equals(type)) {
            GateStation g = gateRepository.findScopedById(objectId)
                    .orElseThrow(() -> new BizException("边界对象不存在或不属于当前租户：" + objectId));
            if ("停用".equals(g.getStatus())) {
                throw new BizException("门站 " + g.getStationCode() + " 已停用，不能纳入影响清单");
            }
            return new ImpactSeed(type, g.getId(), g.getStationCode(), g.getStationName(), "人工补充");
        }
        if (Dict.IMPACT_REGULATOR_STATION.equals(type)) {
            RegulatorStation r = regulatorRepository.findScopedById(objectId)
                    .orElseThrow(() -> new BizException("边界对象不存在或不属于当前租户：" + objectId));
            if ("停用".equals(r.getStatus())) {
                throw new BizException("调压站 " + r.getStationCode() + " 已停用，不能纳入影响清单");
            }
            return new ImpactSeed(type, r.getId(), r.getStationCode(), r.getStationName(), "人工补充");
        }
        GasUser u = gasUserRepository.findScopedById(objectId)
                .orElseThrow(() -> new BizException("边界对象不存在或不属于当前租户：" + objectId));
        if ("停用".equals(u.getStatus())) {
            throw new BizException("燃气用户 " + u.getUserNo() + " 已停用，不能纳入影响清单");
        }
        return new ImpactSeed(type, u.getId(), u.getUserNo(), u.getUserName(), "人工补充");
    }

    private MaintenanceOrder getOrder(Long id) {
        return orderRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("检修单不存在：" + id));
    }

    /** 行级悲观锁读取：并发推进同一检修单在此串行化 */
    private MaintenanceOrder lockOrder(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("检修单不存在：" + id));
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    /** 版本统一校验：前端必须携带详情页读到的最新版本，不一致即并发冲突 */
    private void requireVersion(MaintenanceOrder order, Long version) {
        if (version == null) {
            throw new BizException("版本号不能为空，请刷新后携带最新版本再操作");
        }
        if (!version.equals(order.getVersion())) {
            throw new ConflictException("检修单 " + order.getOrderNo()
                    + " 已被他人修改（当前版本 " + order.getVersion() + "），请刷新后重试");
        }
    }

    private void requireDraft(MaintenanceOrder order, String action) {
        if (!Dict.MAINT_DRAFT.equals(order.getStatus())) {
            throw new BizException("检修单已发布，影响快照已冻结，不能" + action
                    + "（当前「" + order.getStatus() + "」）；基础档案变化请查看差异提示");
        }
    }

    private MaintenanceOrder transfer(MaintenanceOrder order, String target, LocalDateTime now) {
        if (!Dict.canTransferMaint(order.getStatus(), target)) {
            throw new BizException("非法状态跳转：" + order.getStatus() + " → " + target);
        }
        order.setStatus(target);
        order.setUpdatedAt(now);
        return orderRepository.save(order);
    }

    /**
     * requestId 幂等：同租户同 requestId 的重试直接回放首次响应，不重复推进状态。
     * 与巡检模块共用 inspect_idem 表（动作前缀 MAINT_ 区分）。
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
        IdempotentRecord record = new IdempotentRecord();
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
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // 并发提交同一 requestId：对方先落库，本次直接回放其结果
            IdempotentRecord winner = idemRepository.findByRequestId(rid)
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
}
