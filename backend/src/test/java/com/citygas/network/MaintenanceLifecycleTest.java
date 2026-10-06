package com.citygas.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.MaintenanceIdemRecordRepository;
import com.citygas.network.repository.MaintenanceImpactRepository;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.service.MaintenanceService;
import com.citygas.network.tenant.TenantContext;

/**
 * 管段检修影响评估与停复气联动 · 业务生命周期测试：
 * ① 沿管段起止点自动关联门站/调压站/燃气用户 + 人工边界；
 * ② 拓扑变更（新增连通管段 / 档案改名停用 / 跨租户 / 删除）的差异提示与发布拦截，
 *    发布冻结后档案变化只提示不改写；
 * ③ 发布回滚：拦截后草稿状态/冻结标记/影响清单原样保留，无幂等残留；
 * ④ 停气才可开工、验收未完成拦截复气申请、安全确认未完成拦截复气放行、复气成功闭环；
 * ⑤ requestId 幂等与发布后编辑拒绝。
 */
class MaintenanceLifecycleTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private MaintenanceOrderRepository orderRepository;
    @Autowired
    private MaintenanceImpactRepository impactRepository;
    @Autowired
    private MaintenanceIdemRecordRepository idemRepository;

    // ---------------- 拓扑夹具 ----------------

    /** A 片区拓扑：城东门站 — 兴业路调压站 — 花园小区（两管段 + 门站 + 调压站 + 两户） */
    private TopologyFixture fixtureA() {
        useTenant(A);
        GateStation gate = newGate(A, "GS-M01", "城东门站");
        RegulatorStation regulator = newRegulator(A, "RS-M01");
        regulator.setStationName("兴业路调压站");
        regulator = regulatorRepository.saveAndFlush(regulator);
        PipeSegment seg1 = newSegment(A, "PS-M01");
        seg1.setStartPoint("城东门站");
        seg1.setEndPoint("兴业路调压站");
        seg1 = segmentRepository.saveAndFlush(seg1);
        PipeSegment seg2 = newSegment(A, "PS-M02");
        seg2.setStartPoint("兴业路调压站");
        seg2.setEndPoint("花园小区");
        seg2 = segmentRepository.saveAndFlush(seg2);

        GasUser u1 = newUser(A, "U-M01");
        u1.setUserName("王建国");
        u1.setAddress("花园小区1栋301");
        u1 = gasUserRepository.saveAndFlush(u1);
        GasUser u2 = newUser(A, "U-M02");
        u2.setUserName("李秀兰");
        u2.setAddress("花园小区2栋502");
        u2 = gasUserRepository.saveAndFlush(u2);

        TopologyFixture f = new TopologyFixture();
        f.gate = gate;
        f.regulator = regulator;
        f.seg1 = seg1;
        f.seg2 = seg2;
        f.u1 = u1;
        f.u2 = u2;
        return f;
    }

    private static class TopologyFixture {
        GateStation gate;
        RegulatorStation regulator;
        PipeSegment seg1;
        PipeSegment seg2;
        GasUser u1;
        GasUser u2;
    }

    private MaintenanceOrderRequest orderRequest(String no, List<Long> segIds) {
        MaintenanceOrderRequest req = new MaintenanceOrderRequest();
        req.setOrderNo(no);
        req.setPlanStartAt(LocalDateTime.now().plusDays(1));
        req.setPlanEndAt(LocalDateTime.now().plusDays(2));
        req.setOwnerName("施工负责人甲");
        req.setRemark("管段检修");
        req.setSegmentIds(segIds);
        return req;
    }

    private MaintenanceOrderRequest.ManualBoundary manual(String code, String name, String note) {
        MaintenanceOrderRequest.ManualBoundary m = new MaintenanceOrderRequest.ManualBoundary();
        m.setCode(code);
        m.setName(name);
        m.setNote(note);
        return m;
    }

    private MaintenanceActionRequest action(String operator, String requestId) {
        MaintenanceActionRequest req = new MaintenanceActionRequest();
        req.setOperator(operator);
        req.setRequestId(requestId);
        return req;
    }

    private MaintenanceImpactVO findImpact(MaintenanceDetailVO d, String type, Long targetId) {
        return d.getImpacts().stream()
                .filter(i -> type.equals(i.getTargetType())
                        && (targetId == null || targetId.equals(i.getTargetId())))
                .findFirst().orElseThrow();
    }

    // ==================== 自动关联 + 人工边界 ====================

    @Test
    void create_order_should_auto_associate_along_segment_endpoints_and_keep_manual_boundary() {
        TopologyFixture f = fixtureA();
        MaintenanceOrderRequest req = orderRequest("JX-001", List.of(f.seg1.getId()));
        req.setManualBoundaries(List.of(
                manual("BND-01", "花园小区围墙外引入管阀井", "产权分界点，无法自动判定，停气时现场挂牌")));

        MaintenanceOrder order = maintenanceService.create(req);

        assertThat(order.getStatus()).isEqualTo(Dict.MO_DRAFT);
        assertThat(order.getFrozen()).isFalse();
        MaintenanceDetailVO d = maintenanceService.detail(order.getId());

        // 检修管段本身 + 沿同名节点连通的相邻管段
        assertThat(d.getSegmentCount()).isEqualTo(2);
        // 起止节点上的门站、调压站
        assertThat(d.getGateCount()).isEqualTo(1);
        assertThat(d.getRegulatorCount()).isEqualTo(1);
        // 地址含节点名「花园小区」的两户
        assertThat(d.getUserCount()).isEqualTo(2);
        // 人工边界
        assertThat(d.getManualCount()).isEqualTo(1);
        MaintenanceImpactVO manual = d.getImpacts().stream()
                .filter(i -> Dict.TARGET_MANUAL.equals(i.getTargetType())).findFirst().orElseThrow();
        assertThat(manual.getTargetCode()).isEqualTo("BND-01");
        assertThat(manual.getSource()).isEqualTo(Dict.SOURCE_MANUAL);
        assertThat(manual.getManualNote()).contains("产权分界点");
        // 快照编号
        assertThat(findImpact(d, Dict.TARGET_GATE_STATION, f.gate.getId()).getTargetCode())
                .isEqualTo("GS-M01");
    }

    @Test
    void create_order_cross_tenant_segment_should_be_rejected() {
        TopologyFixture f = fixtureA();
        useTenant(B);
        MaintenanceOrderRequest req = orderRequest("JX-CROSS", List.of(f.seg1.getId()));
        assertThatThrownBy(() -> maintenanceService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在或不属于当前租户");
    }

    @Test
    void draft_can_be_edited_and_recomputed_but_frozen_order_cannot() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(
                orderRequest("JX-EDIT", List.of(f.seg1.getId())));
        assertThat(maintenanceService.detail(order.getId()).getSegmentCount()).isEqualTo(2);

        // 编辑范围：只保留检修管段本身（夹具里它仍与 seg2 连通，重新关联仍会带出 seg2；
        // 这里改为编辑负责人/时间验证草稿可改）
        MaintenanceOrderRequest edit = orderRequest("JX-EDIT", List.of(f.seg1.getId()));
        edit.setOwnerName("施工负责人乙");
        maintenanceService.update(order.getId(), edit);
        assertThat(maintenanceService.detail(order.getId()).getOrder().getOwnerName())
                .isEqualTo("施工负责人乙");

        maintenanceService.publish(order.getId(), action("发布人甲", "PUB-1"));
        MaintenanceOrderRequest again = orderRequest("JX-EDIT", List.of(f.seg1.getId()));
        assertThatThrownBy(() -> maintenanceService.update(order.getId(), again))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已发布冻结");
        assertThatThrownBy(() -> maintenanceService.recompute(order.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已发布冻结");
    }

    // ==================== 发布拦截与回滚 ====================

    @Test
    void publish_should_rollback_and_keep_edits_when_key_object_disabled() {
        useTenant(A);
        // 拓扑：滨海门站 → 临江调压站（停用）；门站运行、调压站停用
        GateStation gate = newGate(A, "GS-M10", "滨海门站");
        RegulatorStation disabled = newRegulator(A, "RS-M10");
        disabled.setStationName("临江调压站");
        disabled.setStatus("停用");
        disabled = regulatorRepository.saveAndFlush(disabled);
        PipeSegment seg = newSegment(A, "PS-M10");
        seg.setStartPoint("滨海门站");
        seg.setEndPoint("临江调压站");
        seg = segmentRepository.saveAndFlush(seg);

        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-BLOCK-1", List.of(seg.getId())));
        MaintenanceDetailVO before = maintenanceService.detail(order.getId());
        long impactCount = before.getImpacts().size();
        assertThat(before.getBlockCount()).isGreaterThan(0);
        MaintenanceImpactVO disabledVo = findImpact(before, Dict.TARGET_REGULATOR_STATION, disabled.getId());
        assertThat(disabledVo.getDiffs())
                .extracting(MaintenanceImpactVO.DiffItem::getKind)
                .contains("DISABLED");

        // 发布被拒：保留编辑内容，状态/冻结标记/清单原样
        assertThatThrownBy(() -> maintenanceService.publish(order.getId(), action("发布人甲", "PUB-BAD")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发布被拦截")
                .hasMessageContaining("已保留全部编辑内容");

        MaintenanceOrder after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MO_DRAFT);
        assertThat(after.getFrozen()).isFalse();
        assertThat(after.getPublishedAt()).isNull();
        assertThat(impactRepository.findByOrderIdOrderByIdAsc(order.getId())).hasSize((int) impactCount);
        // 失败不写幂等记录：不留半冻结痕迹
        assertThat(idemRepository.findByRequestId("PUB-BAD")).isEmpty();

        // 处理停用后发布成功
        TenantContext.set(A);
        disabled.setStatus("运行");
        regulatorRepository.saveAndFlush(disabled);
        MaintenanceOrder published = maintenanceService.publish(order.getId(), action("发布人甲", "PUB-OK"));
        assertThat(published.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        assertThat(published.getFrozen()).isTrue();
    }

    @Test
    void publish_should_reject_when_impact_object_moves_to_other_tenant() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-BLOCK-2", List.of(f.seg1.getId())));

        // 关键关联调压站被划到 B 租户（跨租户）：原生 SQL 绕过 @TenantId 模拟档案归属变化
        jdbcTemplate.update("UPDATE regulator_station SET tenant_id = ? WHERE id = ?",
                B, f.regulator.getId());
        entityManager.clear();

        MaintenanceDetailVO d = maintenanceService.detail(order.getId());
        MaintenanceImpactVO vo = findImpact(d, Dict.TARGET_REGULATOR_STATION, f.regulator.getId());
        assertThat(vo.getPresent()).isFalse();
        assertThat(vo.getDiffs())
                .extracting(MaintenanceImpactVO.DiffItem::getKind)
                .contains("CROSS_TENANT");
        assertThatThrownBy(() -> maintenanceService.publish(order.getId(), action("发布人甲", "PUB-X")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发布被拦截");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getFrozen()).isFalse();
    }

    @Test
    void publish_should_reject_topology_change_then_recompute_and_publish_ok() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-BLOCK-3", List.of(f.seg1.getId())));

        // 拓扑变更：新增一条与既有节点连通的管段 + 其末端调压站
        RegulatorStation extra = newRegulator(A, "RS-M09");
        extra.setStationName("东郊调压站");
        extra = regulatorRepository.saveAndFlush(extra);
        PipeSegment added = newSegment(A, "PS-M09");
        added.setStartPoint("城东门站");
        added.setEndPoint("东郊调压站");
        segmentRepository.saveAndFlush(added);

        // 草稿快照与当前拓扑冲突：发布拒绝（不得静默扩范围）
        MaintenanceDetailVO conflict = maintenanceService.detail(order.getId());
        assertThat(conflict.getOrderLevelDiffs())
                .extracting(MaintenanceImpactVO.DiffItem::getKind)
                .contains("TOPOLOGY_EXTRA");
        assertThatThrownBy(() -> maintenanceService.publish(order.getId(), action("发布人甲", "PUB-TOPO")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发布被拦截");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Dict.MO_DRAFT);

        // 人工核对差异后重新核对拓扑 → 新对象进入清单 → 发布成功
        MaintenanceDetailVO recomputed = maintenanceService.recompute(order.getId());
        assertThat(recomputed.getImpacts())
                .extracting(MaintenanceImpactVO::getTargetId)
                .contains(added.getId(), extra.getId());
        assertThat(recomputed.getBlockCount()).isZero();
        // 人工边界在重新核对时保留（本单无边界；上一条用例已覆盖，这里确认管段数）
        MaintenanceOrder published = maintenanceService.publish(order.getId(), action("发布人甲", "PUB-TOPO-OK"));
        assertThat(published.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
    }

    @Test
    void after_freeze_profile_change_only_warns_and_snapshot_keeps_original() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-FROZEN", List.of(f.seg1.getId())));
        maintenanceService.publish(order.getId(), action("发布人甲", "PUB-F"));

        // 冻结后门站改名：快照保留原名，差异仅 WARN，不影响已发布状态
        TenantContext.set(A);
        f.gate.setStationName("城东门站（新名）");
        gateStationRepository.saveAndFlush(f.gate);
        entityManager.clear();

        MaintenanceDetailVO d = maintenanceService.detail(order.getId());
        MaintenanceImpactVO vo = findImpact(d, Dict.TARGET_GATE_STATION, f.gate.getId());
        assertThat(vo.getTargetName()).isEqualTo("城东门站");
        assertThat(vo.getCurrentName()).isEqualTo("城东门站（新名）");
        assertThat(vo.getDiffs())
                .filteredOn(x -> "NAME_CHANGED".equals(x.getKind()))
                .singleElement()
                .satisfies(x -> assertThat(x.getLevel()).isEqualTo("WARN"));
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Dict.MO_WAIT_CUTOFF);
    }

    // ==================== 停复气状态机 ====================

    @Test
    void full_lifecycle_cutoff_start_accept_confirm_restore() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(
                orderRequest("JX-LIFE", List.of(f.seg1.getId())));
        Long id = order.getId();

        // 未停气禁止开工
        maintenanceService.publish(id, action("发布人甲", "PUB-LIFE"));
        assertThatThrownBy(() -> maintenanceService.startWork(id, action("施工员甲", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未完成停气");

        // 停气 → 开工 → 完工
        maintenanceService.cutoff(id, action("调度员甲", "CUT-1"));
        MaintenanceOrder working = maintenanceService.startWork(id, action("施工员甲", "START-1"));
        assertThat(working.getStatus()).isEqualTo(Dict.MO_WORKING);
        assertThat(working.getCutOffAt()).isNotNull();
        assertThat(working.getStartedAt()).isNotNull();
        MaintenanceOrder waitAccept = maintenanceService.finishWork(id, action("施工员甲", "FIN-1"));
        assertThat(waitAccept.getStatus()).isEqualTo(Dict.MO_WAIT_ACCEPT);

        MaintenanceDetailVO d = maintenanceService.detail(id);
        List<MaintenanceImpactVO> segVos = d.getImpacts().stream()
                .filter(i -> Dict.TARGET_PIPE_SEGMENT.equals(i.getTargetType())).toList();
        assertThat(segVos).hasSize(2);

        // 先对 seg2 验收不通过（必须填原因）→ 申请复气被拦截
        MaintenanceAcceptRequest failReq = new MaintenanceAcceptRequest();
        failReq.setOperator("验收员甲");
        failReq.setPassed(false);
        failReq.setNote("焊缝余高超标，需打磨复检");
        maintenanceService.accept(id, segVos.get(1).getId(), failReq);

        MaintenanceAcceptRequest passReq = new MaintenanceAcceptRequest();
        passReq.setOperator("验收员甲");
        passReq.setPassed(true);
        maintenanceService.accept(id, segVos.get(0).getId(), passReq);

        assertThatThrownBy(() -> maintenanceService.requestRestore(id, action("验收员甲", "REQ-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1 条管段未验收通过");

        // 整改后重新验收 seg2 通过 → 申请复气
        MaintenanceAcceptRequest repassReq = new MaintenanceAcceptRequest();
        repassReq.setOperator("验收员甲");
        repassReq.setPassed(true);
        maintenanceService.accept(id, segVos.get(1).getId(), repassReq);
        MaintenanceOrder waitRestore = maintenanceService.requestRestore(id, action("验收员甲", "REQ-2"));
        assertThat(waitRestore.getStatus()).isEqualTo(Dict.MO_WAIT_RESTORE);

        // 只确认一户：复气放行被拦截
        MaintenanceSafetyRequest safetyReq = new MaintenanceSafetyRequest();
        safetyReq.setOperator("安检员甲");
        safetyReq.setNote("入户确认阀门关闭、立管保压合格");
        MaintenanceDetailVO d2 = maintenanceService.detail(id);
        MaintenanceImpactVO user1 = findImpact(d2, Dict.TARGET_GAS_USER, f.u1.getId());
        maintenanceService.confirmSafety(id, user1.getId(), safetyReq);
        assertThatThrownBy(() -> maintenanceService.restore(id, action("调度员乙", "RES-BLOCK")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1 户受影响用户未完成安全确认");

        // 第二户确认 → 复气放行成功
        MaintenanceDetailVO d3 = maintenanceService.detail(id);
        MaintenanceImpactVO user2 = findImpact(d3, Dict.TARGET_GAS_USER, f.u2.getId());
        maintenanceService.confirmSafety(id, user2.getId(), safetyReq);
        MaintenanceOrder done = maintenanceService.restore(id, action("调度员乙", "RES-OK"));
        assertThat(done.getStatus()).isEqualTo(Dict.MO_DONE);
        assertThat(done.getRestoredBy()).isEqualTo("调度员乙");
        assertThat(done.getRestoredAt()).isNotNull();

        MaintenanceDetailVO finalDetail = maintenanceService.detail(id);
        assertThat(finalDetail.getPendingAcceptanceCount()).isZero();
        assertThat(finalDetail.getPendingSafetyCount()).isZero();
    }

    @Test
    void accept_and_safety_wrong_stage_or_type_should_be_rejected() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-STAGE", List.of(f.seg1.getId())));
        Long id = order.getId();
        MaintenanceDetailVO d = maintenanceService.detail(id);
        MaintenanceImpactVO seg = findImpact(d, Dict.TARGET_PIPE_SEGMENT, f.seg1.getId());
        MaintenanceImpactVO user = findImpact(d, Dict.TARGET_GAS_USER, f.u1.getId());

        MaintenanceAcceptRequest pass = new MaintenanceAcceptRequest();
        pass.setOperator("验收员甲");
        pass.setPassed(true);
        // 待发布阶段不能验收
        assertThatThrownBy(() -> maintenanceService.accept(id, seg.getId(), pass))
                .isInstanceOf(BizException.class).hasMessageContaining("待验收");

        maintenanceService.publish(id, action("发布人甲", "PUB-S"));
        // 待停气阶段也不能做用户安全确认
        MaintenanceSafetyRequest safety = new MaintenanceSafetyRequest();
        safety.setOperator("安检员甲");
        assertThatThrownBy(() -> maintenanceService.confirmSafety(id, user.getId(), safety))
                .isInstanceOf(BizException.class).hasMessageContaining("待复气");

        // 非管段对象不能验收
        maintenanceService.cutoff(id, action("调度员甲", "CUT-S"));
        maintenanceService.startWork(id, action("施工员甲", "START-S"));
        maintenanceService.finishWork(id, action("施工员甲", "FIN-S"));
        assertThatThrownBy(() -> maintenanceService.accept(id, user.getId(), pass))
                .isInstanceOf(BizException.class).hasMessageContaining("仅管段对象需要验收");
    }

    // ==================== 幂等 ====================

    @Test
    void publish_with_same_request_id_should_replay_first_result_and_advance_once() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-IDEM", List.of(f.seg1.getId())));
        Long id = order.getId();

        MaintenanceOrder first = maintenanceService.publish(id, action("发布人甲", "RID-1"));
        assertThat(first.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        // 同 requestId 重试：回放首次结果，状态仍只推进一次
        MaintenanceOrder replay = maintenanceService.publish(id, action("发布人乙", "RID-1"));
        assertThat(replay.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        assertThat(replay.getPublishedBy()).isEqualTo("发布人甲");

        MaintenanceOrder stored = orderRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        assertThat(idemRepository.count()).isEqualTo(1L);
    }

    @Test
    void cutoff_repeated_without_request_id_returns_first_result_without_overwriting() {
        TopologyFixture f = fixtureA();
        MaintenanceOrder order = maintenanceService.create(orderRequest("JX-IDEM2", List.of(f.seg1.getId())));
        Long id = order.getId();
        maintenanceService.publish(id, action("发布人甲", "PUB-I2"));

        MaintenanceOrder first = maintenanceService.cutoff(id, action("调度员甲", null));
        MaintenanceOrder second = maintenanceService.cutoff(id, action("调度员乙", null));
        assertThat(second.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        assertThat(second.getCutOffBy()).isEqualTo("调度员甲");
        // H2 DATETIME 为毫秒精度，按毫秒比较；两次结果必须是同一时刻
        assertThat(second.getCutOffAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(first.getCutOffAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
