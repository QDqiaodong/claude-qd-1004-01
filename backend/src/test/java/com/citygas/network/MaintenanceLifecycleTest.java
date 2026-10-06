package com.citygas.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.MaintenanceSegment;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.MaintenanceImpactRepository;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.repository.MaintenanceSegmentRepository;
import com.citygas.network.service.MaintenanceService;

/**
 * 管段检修影响评估与停复气联动 · 全生命周期测试。
 *
 * <p>覆盖：拓扑自动关联、人工边界、发布冻结与拓扑冲突回滚、关键对象停用拦截、
 * 差异提示（快照不被静默改写）、停气后才可开工、验收/确认双闸门、复气成功、
 * 版本冲突、租户隔离与 requestId 幂等回放。</p>
 */
class MaintenanceLifecycleTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private MaintenanceOrderRepository orderRepository;
    @Autowired
    private MaintenanceSegmentRepository segmentLinkRepository;
    @Autowired
    private MaintenanceImpactRepository impactRepository;

    // ---------------- 拓扑与请求构造 ----------------

    /**
     * 标准拓扑：门站「青云门站」— 管段 PS-M01（青云门站 → 绿谷小区）— 绿谷小区侧调压箱与两户居民；
     * U-M03 在远郊路，不在任何起止点上，用于人工边界。
     */
    private record Topo(PipeSegment seg, Long gateId, Long regulatorId,
                        GasUser user1, GasUser user2, GasUser outsider) {
    }

    private Topo prepareTopo() {
        useTenant(A);
        var gate = newGateStation(A, "GS-M01", "青云门站");
        var regulator = newRegulator(A, "RS-M01", "绿谷小区调压箱");
        PipeSegment seg = newSegment(A, "PS-M01", "青云门站", "绿谷小区");
        GasUser u1 = newUser(A, "U-M01", "绿谷小区1栋101");
        GasUser u2 = newUser(A, "U-M02", "绿谷小区2栋202");
        GasUser outsider = newUser(A, "U-M03", "远郊路9号");
        return new Topo(seg, gate.getId(), regulator.getId(), u1, u2, outsider);
    }

    private MaintCreateRequest createRequest(String orderNo, List<Long> segmentIds) {
        MaintCreateRequest req = new MaintCreateRequest();
        req.setOrderNo(orderNo);
        req.setPlanStartAt(LocalDateTime.now().plusDays(1));
        req.setPlanEndAt(LocalDateTime.now().plusDays(2));
        req.setLeaderName("施工队长王");
        req.setScopeRemark("更换老旧管段并复压");
        req.setSegmentIds(segmentIds);
        return req;
    }

    private MaintActionRequest action(String operator, Long version, String requestId) {
        MaintActionRequest req = new MaintActionRequest();
        req.setOperator(operator);
        req.setVersion(version);
        req.setRequestId(requestId);
        return req;
    }

    private MaintenanceOrder createOrder(String orderNo, List<Long> segmentIds) {
        useTenant(A);
        return maintenanceService.create(createRequest(orderNo, segmentIds));
    }

    private MaintenanceOrder reload(Long orderId) {
        useTenant(A);
        return orderRepository.findScopedById(orderId).orElseThrow();
    }

    // ==================== 拓扑自动关联 ====================

    @Test
    void create_should_derive_impacts_along_segment_endpoints() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-DERIVE", List.of(topo.seg().getId()));

        assertThat(order.getStatus()).isEqualTo(Dict.MAINT_DRAFT);
        assertThat(order.getVersion()).isZero();
        assertThat(order.getTenantId()).isEqualTo(A);

        MaintDetailVO detail = maintenanceService.detail(order.getId());
        assertThat(detail.getSegments()).hasSize(1);
        MaintenanceSegment link = detail.getSegments().get(0);
        assertThat(link.getSegCode()).isEqualTo("PS-M01");
        assertThat(link.getSegName()).isEqualTo("青云门站 → 绿谷小区");
        assertThat(link.getAcceptStatus()).isEqualTo(Dict.ACCEPT_PENDING);

        // 自动关联：门站（起点）、调压箱（终点包含）、两户居民（地址含终点）；远郊路用户不纳入
        assertThat(detail.getImpacts()).hasSize(4);
        assertThat(detail.getImpacts()).allMatch(i -> Dict.IMPACT_SOURCE_AUTO.equals(i.getSource()));
        assertThat(detail.getImpacts()).extracting(MaintenanceImpact::getObjectCode)
                .containsExactlyInAnyOrder("GS-M01", "RS-M01", "U-M01", "U-M02");
        assertThat(detail.getImpacts())
                .filteredOn(i -> Dict.IMPACT_GAS_USER.equals(i.getObjectType()))
                .allMatch(i -> Dict.CONFIRM_PENDING.equals(i.getConfirmStatus()));
        assertThat(detail.getImpacts())
                .filteredOn(i -> !Dict.IMPACT_GAS_USER.equals(i.getObjectType()))
                .allMatch(i -> Dict.CONFIRM_NA.equals(i.getConfirmStatus()));
        assertThat(detail.getImpacts())
                .filteredOn(i -> "GS-M01".equals(i.getObjectCode()))
                .first().extracting(MaintenanceImpact::getViaDesc).isEqualTo("管段PS-M01·起点");
        // 待发布阶段不计算差异
        assertThat(detail.getDiffs()).isEmpty();
        assertThat(detail.getPendingAcceptCount()).isEqualTo(1);
        assertThat(detail.getPendingConfirmCount()).isEqualTo(2);
    }

    @Test
    void create_validation_and_cross_tenant_segment_should_fail() {
        Topo topo = prepareTopo();
        useTenant(A);
        // 空施工负责人
        MaintCreateRequest blankLeader = createRequest("MO-BAD1", List.of(topo.seg().getId()));
        blankLeader.setLeaderName("  ");
        assertThatThrownBy(() -> maintenanceService.create(blankLeader))
                .isInstanceOf(BizException.class).hasMessageContaining("施工负责人不能为空");
        // 空检修范围
        MaintCreateRequest noSegment = createRequest("MO-BAD2", List.of());
        assertThatThrownBy(() -> maintenanceService.create(noSegment))
                .isInstanceOf(BizException.class).hasMessageContaining("至少选择一段待修管段");
        // 重复单号
        createOrder("MO-DUP", List.of(topo.seg().getId()));
        assertThatThrownBy(() -> maintenanceService.create(createRequest("MO-DUP", List.of(topo.seg().getId()))))
                .isInstanceOf(BizException.class).hasMessageContaining("检修单号已存在");
        // B 租户管段，A 租户建单 → 查无此物
        PipeSegment segB = newSegment(B, "PS-M99", "城西起点", "城西终点");
        useTenant(A);
        assertThatThrownBy(() -> maintenanceService.create(
                createRequest("MO-CROSS", List.of(segB.getId()))))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在或不属于当前租户");
        // 报废管段不能纳入检修范围
        PipeSegment scrapped = newSegment(A, "PS-M98", "青云门站", "绿谷小区");
        useTenant(A);
        scrapped.setStatus("报废");
        segmentRepository.saveAndFlush(scrapped);
        assertThatThrownBy(() -> maintenanceService.create(
                createRequest("MO-SCRAP", List.of(scrapped.getId()))))
                .isInstanceOf(BizException.class).hasMessageContaining("已报废");
    }

    // ==================== 人工边界 ====================

    @Test
    void manual_boundary_should_require_note_and_be_removable_only_in_draft() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-BOUND", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        // 人工说明必填
        BoundaryRequest noNote = new BoundaryRequest();
        noNote.setObjectType(Dict.IMPACT_GAS_USER);
        noNote.setObjectId(topo.outsider().getId());
        noNote.setNote("   ");
        noNote.setVersion(0L);
        assertThatThrownBy(() -> maintenanceService.addBoundary(orderId, noNote))
                .isInstanceOf(BizException.class).hasMessageContaining("人工说明不能为空");

        // 正常补充：远郊路用户无法自动判定，人工核对纳入
        BoundaryRequest add = new BoundaryRequest();
        add.setObjectType(Dict.IMPACT_GAS_USER);
        add.setObjectId(topo.outsider().getId());
        add.setNote("远郊路支线与用户同沟敷设，自动拓扑无法判定，人工核对纳入");
        add.setVersion(0L);
        MaintenanceImpact manual = maintenanceService.addBoundary(orderId, add);
        assertThat(manual.getSource()).isEqualTo(Dict.IMPACT_SOURCE_MANUAL);
        assertThat(manual.getViaDesc()).isEqualTo("人工补充");
        assertThat(manual.getConfirmStatus()).isEqualTo(Dict.CONFIRM_PENDING);

        // 重复补充（已在自动清单中的对象）
        BoundaryRequest dup = new BoundaryRequest();
        dup.setObjectType(Dict.IMPACT_GAS_USER);
        dup.setObjectId(topo.user1().getId());
        dup.setNote("重复");
        dup.setVersion(reload(orderId).getVersion());
        assertThatThrownBy(() -> maintenanceService.addBoundary(orderId, dup))
                .isInstanceOf(BizException.class).hasMessageContaining("已在影响清单中");

        // 自动行不能删除
        MaintenanceImpact autoRow = impactRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(i -> Dict.IMPACT_SOURCE_AUTO.equals(i.getSource())).findFirst().orElseThrow();
        VersionRequest rmAuto = new VersionRequest();
        rmAuto.setVersion(reload(orderId).getVersion());
        assertThatThrownBy(() -> maintenanceService.removeBoundary(orderId, autoRow.getId(), rmAuto))
                .isInstanceOf(BizException.class).hasMessageContaining("自动关联对象不能删除");

        // 人工行可删除
        VersionRequest rm = new VersionRequest();
        rm.setVersion(reload(orderId).getVersion());
        maintenanceService.removeBoundary(orderId, manual.getId(), rm);
        assertThat(impactRepository.findByOrderIdOrderByIdAsc(orderId))
                .allMatch(i -> Dict.IMPACT_SOURCE_AUTO.equals(i.getSource()));

        // 发布后清单冻结：补充/移除一律拒绝
        MaintenanceOrder published = maintenanceService.publish(orderId,
                action("调度甲", reload(orderId).getVersion(), null));
        BoundaryRequest afterPublish = new BoundaryRequest();
        afterPublish.setObjectType(Dict.IMPACT_GAS_USER);
        afterPublish.setObjectId(topo.outsider().getId());
        afterPublish.setNote("发布后补录应被拒绝");
        afterPublish.setVersion(published.getVersion());
        assertThatThrownBy(() -> maintenanceService.addBoundary(orderId, afterPublish))
                .isInstanceOf(BizException.class).hasMessageContaining("已发布");
    }

    // ==================== 发布：拓扑冲突回滚 / 关键对象停用 ====================

    @Test
    void publish_should_reject_and_keep_draft_when_topology_conflicts() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-CONFLICT", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        // 拓扑变更：绿谷小区新迁入一户，当前拓扑与草稿清单不一致
        newUser(A, "U-M09", "绿谷小区9栋901");

        useTenant(A);
        assertThatThrownBy(() -> maintenanceService.publish(orderId, action("调度甲", 0L, "REQ-CONF-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("影响清单与当前拓扑不一致");

        // 发布回滚：不留半冻结快照——状态、清单、版本全部保持发布前
        MaintenanceOrder after = reload(orderId);
        assertThat(after.getStatus()).isEqualTo(Dict.MAINT_DRAFT);
        assertThat(after.getPublishedAt()).isNull();
        assertThat(after.getVersion()).isZero();
        assertThat(impactRepository.findByOrderIdOrderByIdAsc(orderId)).hasSize(4);

        // 失败的发布不写幂等记录：修正后可用同一 requestId 重新发布
        MaintDetailVO refreshed = maintenanceService.refreshImpact(orderId, versionOf(after.getVersion()));
        assertThat(refreshed.getImpacts()).hasSize(5);
        MaintenanceOrder published = maintenanceService.publish(orderId,
                action("调度甲", refreshed.getOrder().getVersion(), "REQ-CONF-1"));
        assertThat(published.getStatus()).isEqualTo(Dict.MAINT_PUBLISHED);
        assertThat(published.getPublishedBy()).isEqualTo("调度甲");
        assertThat(published.getPublishedAt()).isNotNull();
    }

    private VersionRequest versionOf(Long version) {
        VersionRequest req = new VersionRequest();
        req.setVersion(version);
        return req;
    }

    @Test
    void publish_should_reject_when_key_object_disabled() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-DISABLED", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        // 关键调压站停用 → 拒绝发布
        useTenant(A);
        RegulatorStation regulator = regulatorRepository.findScopedById(topo.regulatorId()).orElseThrow();
        regulator.setStatus("停用");
        regulatorRepository.saveAndFlush(regulator);
        assertThatThrownBy(() -> maintenanceService.publish(orderId, action("调度甲", 0L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已停用");
        assertThat(reload(orderId).getStatus()).isEqualTo(Dict.MAINT_DRAFT);

        // 恢复调压站，再让检修管段报废 → 同样拒绝
        regulator.setStatus("运行");
        regulatorRepository.saveAndFlush(regulator);
        PipeSegment seg = segmentRepository.findScopedById(topo.seg().getId()).orElseThrow();
        seg.setStatus("报废");
        segmentRepository.saveAndFlush(seg);
        assertThatThrownBy(() -> maintenanceService.publish(orderId, action("调度甲", 0L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已报废");

        // 全部恢复后发布成功
        seg.setStatus("待修");
        segmentRepository.saveAndFlush(seg);
        MaintenanceOrder published = maintenanceService.publish(orderId, action("调度甲", 0L, null));
        assertThat(published.getStatus()).isEqualTo(Dict.MAINT_PUBLISHED);
    }

    // ==================== 快照冻结与差异提示 ====================

    @Test
    void published_snapshot_should_freeze_and_report_diffs_without_rewrite() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-DIFF", List.of(topo.seg().getId()));
        Long orderId = order.getId();
        maintenanceService.publish(orderId, action("调度甲", 0L, null));

        // 发布后档案变化：新用户迁入、调压箱改名脱离拓扑、老用户停用
        newUser(A, "U-M10", "绿谷小区10栋102");
        useTenant(A);
        RegulatorStation regulator = regulatorRepository.findScopedById(topo.regulatorId()).orElseThrow();
        regulator.setStationName("绿谷调压箱");
        regulatorRepository.saveAndFlush(regulator);
        GasUser u2 = gasUserRepository.findScopedById(topo.user2().getId()).orElseThrow();
        u2.setStatus("停用");
        gasUserRepository.saveAndFlush(u2);

        MaintDetailVO detail = maintenanceService.detail(orderId);
        // 差异三类齐备：新增未纳入 / 快照偏离拓扑 / 快照对象停用
        assertThat(detail.getDiffs()).extracting(ImpactDiffVO::getDiffType)
                .contains(Dict.DIFF_ADDED, Dict.DIFF_REMOVED, Dict.DIFF_UNAVAILABLE);
        assertThat(detail.getDiffs())
                .filteredOn(d -> Dict.DIFF_ADDED.equals(d.getDiffType()))
                .extracting(ImpactDiffVO::getObjectCode).contains("U-M10");
        assertThat(detail.getDiffs())
                .filteredOn(d -> Dict.DIFF_REMOVED.equals(d.getDiffType()))
                .extracting(ImpactDiffVO::getObjectCode).contains("RS-M01");
        assertThat(detail.getDiffs())
                .filteredOn(d -> Dict.DIFF_UNAVAILABLE.equals(d.getDiffType()))
                .extracting(ImpactDiffVO::getObjectCode).contains("U-M02");

        // 已发布范围不被静默改写：清单仍是发布时的 4 行、名称快照保持原样
        assertThat(detail.getImpacts()).hasSize(4);
        assertThat(detail.getImpacts())
                .filteredOn(i -> "RS-M01".equals(i.getObjectCode()))
                .first().extracting(MaintenanceImpact::getObjectName).isEqualTo("绿谷小区调压箱");
    }

    // ==================== 状态机：停气后才可开工 ====================

    @Test
    void gas_must_be_stopped_before_work_starts() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-FLOW", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        useTenant(A);
        assertThatThrownBy(() -> maintenanceService.stopGas(orderId, action("调度甲", 0L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未发布");
        assertThatThrownBy(() -> maintenanceService.finishWork(orderId, action("施工队长王", 0L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("停气后才可开工");

        MaintenanceOrder published = maintenanceService.publish(orderId, action("调度甲", 0L, null));
        // 未停气直接完工报验：拒绝（停气后才可开工）
        assertThatThrownBy(() -> maintenanceService.finishWork(orderId,
                action("施工队长王", published.getVersion(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("停气后才可开工");

        MaintenanceOrder working = maintenanceService.stopGas(orderId,
                action("调度甲", published.getVersion(), null));
        assertThat(working.getStatus()).isEqualTo(Dict.MAINT_WORKING);
        assertThat(working.getStoppedAt()).isNotNull();
        assertThat(working.getStoppedBy()).isEqualTo("调度甲");
    }

    // ==================== 验收未完成拦截 & 复气成功 ====================

    /** 推进到「待复气」前的公共路径：发布 → 停气 → 完工 */
    private MaintenanceOrder driveToAccepting(Long orderId) {
        MaintenanceOrder o = maintenanceService.publish(orderId,
                action("调度甲", reload(orderId).getVersion(), null));
        o = maintenanceService.stopGas(orderId, action("调度甲", o.getVersion(), null));
        return maintenanceService.finishWork(orderId, action("施工队长王", o.getVersion(), null));
    }

    private AcceptRequest accept(String operator, Long version) {
        AcceptRequest req = new AcceptRequest();
        req.setOperator(operator);
        req.setNote("验收合格");
        req.setVersion(version);
        return req;
    }

    @Test
    void restore_should_be_blocked_until_all_accepted_and_all_confirmed() {
        Topo topo = prepareTopo();
        // 第二段管段：绿谷小区 → 绿谷小学（拓扑对象不变）
        PipeSegment seg2 = newSegment(A, "PS-M02", "绿谷小区", "绿谷小学");
        MaintenanceOrder order = createOrder("MO-GATES", List.of(topo.seg().getId(), seg2.getId()));
        Long orderId = order.getId();
        driveToAccepting(orderId);

        useTenant(A);
        // 待验收直接复气：非法状态跳转
        MaintenanceOrder accepting = reload(orderId);
        assertThatThrownBy(() -> maintenanceService.restoreGas(orderId,
                action("调度甲", accepting.getVersion(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("非法状态跳转");

        // 只验收一段：另一段未过，单仍在待验收，复气依旧被拦
        List<MaintenanceSegment> links = segmentLinkRepository.findByOrderIdOrderByIdAsc(orderId);
        maintenanceService.accept(orderId, links.get(0).getId(),
                accept("验收员赵", accepting.getVersion()));
        assertThat(reload(orderId).getStatus()).isEqualTo(Dict.MAINT_ACCEPTING);
        assertThatThrownBy(() -> maintenanceService.restoreGas(orderId,
                action("调度甲", reload(orderId).getVersion(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("非法状态跳转");

        // 全部验收通过 → 自动推进待复气；但用户未确认，复气被第二道闸门拦截
        maintenanceService.accept(orderId, links.get(1).getId(),
                accept("验收员赵", reload(orderId).getVersion()));
        MaintenanceOrder restoring = reload(orderId);
        assertThat(restoring.getStatus()).isEqualTo(Dict.MAINT_RESTORING);
        assertThatThrownBy(() -> maintenanceService.restoreGas(orderId,
                action("调度甲", restoring.getVersion(), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("受影响用户未完成安全确认");

        // 逐户安全确认后复气成功
        for (MaintenanceImpact impact : impactRepository.findByOrderIdOrderByIdAsc(orderId)) {
            if (Dict.IMPACT_GAS_USER.equals(impact.getObjectType())) {
                ConfirmRequest confirm = new ConfirmRequest();
                confirm.setOperator("安全员钱");
                maintenanceService.confirmUser(orderId, impact.getId(), confirm);
            }
        }
        MaintenanceOrder done = maintenanceService.restoreGas(orderId,
                action("调度甲", reload(orderId).getVersion(), "REQ-RESTORE-OK"));
        assertThat(done.getStatus()).isEqualTo(Dict.MAINT_DONE);
        assertThat(done.getRestoredBy()).isEqualTo("调度甲");
        assertThat(done.getRestoredAt()).isNotNull();
        assertThat(done.getCompletedAt()).isNotNull();

        MaintDetailVO detail = maintenanceService.detail(orderId);
        assertThat(detail.getPendingAcceptCount()).isZero();
        assertThat(detail.getPendingConfirmCount()).isZero();
    }

    @Test
    void full_lifecycle_should_complete_and_replay_same_request_id() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-FULL", List.of(topo.seg().getId()));
        Long orderId = order.getId();
        driveToAccepting(orderId);

        useTenant(A);
        MaintenanceSegment link = segmentLinkRepository.findByOrderIdOrderByIdAsc(orderId).get(0);
        maintenanceService.accept(orderId, link.getId(), accept("验收员赵", reload(orderId).getVersion()));
        assertThat(reload(orderId).getStatus()).isEqualTo(Dict.MAINT_RESTORING);

        for (MaintenanceImpact impact : impactRepository.findByOrderIdOrderByIdAsc(orderId)) {
            if (Dict.IMPACT_GAS_USER.equals(impact.getObjectType())) {
                ConfirmRequest confirm = new ConfirmRequest();
                confirm.setOperator("安全员钱");
                maintenanceService.confirmUser(orderId, impact.getId(), confirm);
            }
        }

        // 复气成功；同 requestId 重试回放首次结果，不重复推进
        MaintenanceOrder done = maintenanceService.restoreGas(orderId,
                action("调度甲", reload(orderId).getVersion(), "REQ-FULL-RESTORE"));
        MaintenanceOrder replay = maintenanceService.restoreGas(orderId,
                action("调度乙", 0L, "REQ-FULL-RESTORE"));
        assertThat(replay.getRestoredAt()).isEqualTo(done.getRestoredAt());
        assertThat(replay.getRestoredBy()).isEqualTo("调度甲");
        assertThat(reload(orderId).getVersion()).isEqualTo(done.getVersion());

        // 不同 requestId 的重复复气：明确冲突，不覆盖先到者
        assertThatThrownBy(() -> maintenanceService.restoreGas(orderId,
                action("调度乙", reload(orderId).getVersion(), "REQ-FULL-RESTORE-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已由");
    }

    @Test
    void publish_with_same_request_id_should_replay_first_result() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-IDEM", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        useTenant(A);
        MaintenanceOrder first = maintenanceService.publish(orderId, action("调度甲", 0L, "REQ-PUB-1"));
        // 网络重试：携带旧版本也无妨——幂等回放先于版本/状态校验
        MaintenanceOrder retry = maintenanceService.publish(orderId, action("调度乙", 0L, "REQ-PUB-1"));
        assertThat(retry.getPublishedAt()).isEqualTo(first.getPublishedAt());
        assertThat(retry.getPublishedBy()).isEqualTo("调度甲");
        assertThat(reload(orderId).getVersion()).isEqualTo(first.getVersion());

        // 另一操作员无 requestId 的并发重复发布：409 冲突
        assertThatThrownBy(() -> maintenanceService.publish(orderId,
                action("调度乙", reload(orderId).getVersion(), null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已由");
    }

    // ==================== 编辑草稿 / 版本冲突 / 租户隔离 ====================

    @Test
    void update_draft_should_rebuild_auto_impacts_and_keep_manual() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-EDIT", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        useTenant(A);
        BoundaryRequest add = new BoundaryRequest();
        add.setObjectType(Dict.IMPACT_GAS_USER);
        add.setObjectId(topo.outsider().getId());
        add.setNote("人工核对纳入");
        add.setVersion(0L);
        maintenanceService.addBoundary(orderId, add);

        // 改范围：换成 PS-M02（青云门站不再是端点），自动行重建、人工行保留
        PipeSegment seg2 = newSegment(A, "PS-M02", "绿谷小区", "绿谷小学");
        MaintUpdateRequest update = new MaintUpdateRequest();
        update.setPlanStartAt(LocalDateTime.now().plusDays(2));
        update.setLeaderName("施工队长李");
        update.setScopeRemark("调整后的检修范围");
        update.setSegmentIds(List.of(seg2.getId()));
        update.setVersion(reload(orderId).getVersion());
        maintenanceService.update(orderId, update);

        MaintDetailVO detail = maintenanceService.detail(orderId);
        assertThat(detail.getOrder().getLeaderName()).isEqualTo("施工队长李");
        assertThat(detail.getSegments()).extracting(MaintenanceSegment::getSegCode)
                .containsExactly("PS-M02");
        assertThat(detail.getImpacts()).extracting(MaintenanceImpact::getObjectCode)
                .containsExactlyInAnyOrder("RS-M01", "U-M01", "U-M02", "U-M03");
        assertThat(detail.getImpacts())
                .filteredOn(i -> "U-M03".equals(i.getObjectCode()))
                .first().extracting(MaintenanceImpact::getSource).isEqualTo(Dict.IMPACT_SOURCE_MANUAL);

        // 版本冲突：旧版本编辑 → 409
        update.setVersion(0L);
        MaintUpdateRequest stale = update;
        assertThatThrownBy(() -> maintenanceService.update(orderId, stale))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已被他人修改");

        // 发布后禁止编辑
        maintenanceService.publish(orderId, action("调度甲", reload(orderId).getVersion(), null));
        MaintUpdateRequest afterPublish = new MaintUpdateRequest();
        afterPublish.setPlanStartAt(LocalDateTime.now().plusDays(3));
        afterPublish.setLeaderName("施工队长李");
        afterPublish.setScopeRemark("发布后改单应被拒绝");
        afterPublish.setSegmentIds(List.of(seg2.getId()));
        afterPublish.setVersion(reload(orderId).getVersion());
        assertThatThrownBy(() -> maintenanceService.update(orderId, afterPublish))
                .isInstanceOf(BizException.class).hasMessageContaining("已发布");
    }

    @Test
    void tenant_isolation_should_hide_orders_across_tenants() {
        Topo topo = prepareTopo();
        MaintenanceOrder order = createOrder("MO-ISO", List.of(topo.seg().getId()));
        Long orderId = order.getId();

        useTenant(B);
        assertThatThrownBy(() -> maintenanceService.detail(orderId))
                .isInstanceOf(BizException.class).hasMessageContaining("检修单不存在");
        assertThat(maintenanceService.search(null, null, 0, 10).getTotalElements()).isZero();
        assertThatThrownBy(() -> maintenanceService.publish(orderId, action("调度乙", 0L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("检修单不存在");

        useTenant(A);
        assertThat(maintenanceService.detail(orderId).getOrder().getOrderNo()).isEqualTo("MO-ISO");
        assertThat(maintenanceService.search(Dict.MAINT_DRAFT, "MO-ISO", 0, 10).getTotalElements())
                .isEqualTo(1);
    }
}
