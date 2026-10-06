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
import com.citygas.network.dto.BatchCreateRequest;
import com.citygas.network.dto.BatchDetailVO;
import com.citygas.network.dto.HazardVO;
import com.citygas.network.dto.InspectRequest;
import com.citygas.network.dto.RectifyRequest;
import com.citygas.network.dto.ReviewRequest;
import com.citygas.network.dto.TargetResultVO;
import com.citygas.network.entity.InspectionBatch;
import com.citygas.network.entity.InspectionHazard;
import com.citygas.network.entity.InspectionTarget;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.repository.InspectionBatchRepository;
import com.citygas.network.repository.InspectionHazardRepository;
import com.citygas.network.repository.InspectionTargetRepository;
import com.citygas.network.service.InspectionService;

class InspectionLifecycleTest extends BaseIntegrationTest {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private InspectionBatchRepository batchRepository;
    @Autowired
    private InspectionTargetRepository targetRepository;
    @Autowired
    private InspectionHazardRepository hazardRepository;

    private static final String TENANT_A = "A片区";
    private static final String TENANT_B = "B片区";

    private PipeSegment prepareSegment(String tenant, String code) {
        useTenant(tenant);
        return newSegment(tenant, code);
    }

    private InspectionBatch createBatch(String tenant, String no, LocalDateTime planStart,
                                        List<BatchCreateRequest.TargetRef> targets) {
        useTenant(tenant);
        return inspectionService.create(
                batchRequest(no, planStart, "张工", "秋季安全巡检", targets));
    }

    private InspectRequest normalInspection(String requestId) {
        InspectRequest req = new InspectRequest();
        req.setResult("正常");
        req.setOperator("巡检员甲");
        req.setRequestId(requestId);
        return req;
    }

    private InspectRequest hazardInspection(String requestId, LocalDateTime dueAt) {
        InspectRequest req = new InspectRequest();
        req.setResult("隐患");
        req.setOperator("巡检员甲");
        req.setRequestId(requestId);
        InspectRequest.HazardInfo h = new InspectRequest.HazardInfo();
        h.setProblem("法兰渗漏");
        h.setRiskLevel("高");
        h.setRectifyRequire("更换密封垫并复紧");
        h.setOwnerName("李工");
        h.setDueAt(dueAt);
        req.setHazard(h);
        return req;
    }

    // ==================== 创建与租户隔离 ====================

    @Test
    void create_batch_should_snapshot_targets_and_start_pending() {
        PipeSegment seg = prepareSegment(TENANT_A, "PS-T01");

        InspectionBatch batch = createBatch(TENANT_A, "BATCH-001",
                LocalDateTime.now().plusDays(1), List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));

        assertThat(batch.getStatus()).isEqualTo(Dict.BATCH_PENDING);
        assertThat(batch.getTenantId()).isEqualTo(TENANT_A);

        BatchDetailVO detail = inspectionService.detail(batch.getId());
        assertThat(detail.getTargets()).hasSize(1);
        TargetResultVO t = detail.getTargets().get(0);
        assertThat(t.getTargetCode()).isEqualTo("PS-T01");
        assertThat(t.getLatestResult()).isEqualTo("待检");
        assertThat(t.getRoundNo()).isEqualTo(1);
    }

    @Test
    void create_batch_with_regulator_and_user_targets_should_persist_17_char_type() {
        // REGULATOR_STATION 长 17 字符，曾超出 target_type VARCHAR(16)：多类型对象回归保护
        useTenant(TENANT_A);
        var regulator = newRegulator(TENANT_A, "RS-T01");
        var user = newUser(TENANT_A, "U-T01");
        InspectionBatch batch = createBatch(TENANT_A, "BATCH-MULTI",
                LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_REGULATOR_STATION, regulator.getId()),
                        ref(Dict.TARGET_GAS_USER, user.getId())));
        BatchDetailVO detail = inspectionService.detail(batch.getId());
        assertThat(detail.getTargets()).extracting(TargetResultVO::getTargetType)
                .containsExactlyInAnyOrder(Dict.TARGET_REGULATOR_STATION, Dict.TARGET_GAS_USER);
        assertThat(detail.getTargets()).extracting(TargetResultVO::getTargetCode)
                .containsExactlyInAnyOrder("RS-T01", "U-T01");
    }

    @Test
    void create_batch_duplicate_no_should_fail_even_same_number_in_other_tenant_ok() {
        PipeSegment segA = prepareSegment(TENANT_A, "PS-T02");
        PipeSegment segB = prepareSegment(TENANT_B, "PS-T52");

        createBatch(TENANT_A, "BATCH-DUP", LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segA.getId())));

        useTenant(TENANT_A);
        assertThatThrownBy(() -> inspectionService.create(batchRequest(
                "BATCH-DUP", LocalDateTime.now().plusDays(1), "张工", "重复编号",
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segA.getId())))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次编号已存在");

        // 同编号在另一租户下合法（编号只要求租户内唯一）
        InspectionBatch other = createBatch(TENANT_B, "BATCH-DUP",
                LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segB.getId())));
        assertThat(other.getTenantId()).isEqualTo(TENANT_B);
    }

    @Test
    void create_batch_with_other_tenant_target_should_fail() {
        // B 租户的管段，A 租户拿来关联——@TenantId 过滤后查不到
        PipeSegment segB = prepareSegment(TENANT_B, "PS-T99");

        useTenant(TENANT_A);
        assertThatThrownBy(() -> inspectionService.create(batchRequest(
                "BATCH-CROSS", LocalDateTime.now().plusDays(1), "张工", "跨租户关联应失败",
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segB.getId())))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在或不属于当前租户");
    }

    @Test
    void tenant_isolation_get_detail_should_hide_other_tenant_batch() {
        PipeSegment segA = prepareSegment(TENANT_A, "PS-T10");
        InspectionBatch batchA = createBatch(TENANT_A, "BATCH-ISO",
                LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segA.getId())));

        useTenant(TENANT_B);
        assertThatThrownBy(() -> inspectionService.detail(batchA.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次不存在");

        useTenant(TENANT_A);
        assertThat(inspectionService.detail(batchA.getId()).getBatch().getBatchNo())
                .isEqualTo("BATCH-ISO");
    }

    @Test
    void create_batch_blank_remark_empty_targets_duplicate_target_should_fail() {
        PipeSegment seg = prepareSegment(TENANT_A, "PS-T11");

        useTenant(TENANT_A);
        // 空说明
        assertThatThrownBy(() -> inspectionService.create(batchRequest(
                "BATCH-BLANK", LocalDateTime.now().plusDays(1), "张工", "   ",
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("巡检说明不能为空");

        // 空关联
        assertThatThrownBy(() -> inspectionService.create(batchRequest(
                "BATCH-NOTARGET", LocalDateTime.now().plusDays(1), "张工", "无对象", List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少关联一个");

        // 批次内重复关联同一对象
        assertThatThrownBy(() -> inspectionService.create(batchRequest(
                "BATCH-DUPTARGET", LocalDateTime.now().plusDays(1), "张工", "重复对象",
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId()),
                        ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("关联对象重复");
    }

    // ==================== 状态机：过期不能跳过执行 / 非法跳转 ====================

    @Test
    void expired_batch_must_start_before_any_next_step() {
        PipeSegment seg = prepareSegment(TENANT_A, "PS-T20");
        InspectionBatch batch = createBatch(TENANT_A, "BATCH-EXPIRED",
                LocalDateTime.now().minusDays(2),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));

        useTenant(TENANT_A);
        // 过期批次跳过执行直接收口：拒绝
        assertThatThrownBy(() -> inspectionService.requestReview(batch.getId(), "复核人"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须先补开始执行");
        assertThatThrownBy(() -> inspectionService.close(batch.getId(), "复核人"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态跳转");

        // 补开始执行后进入执行中
        InspectionBatch running = inspectionService.start(batch.getId(), "巡检员甲");
        assertThat(running.getStatus()).isEqualTo(Dict.BATCH_RUNNING);
        assertThat(running.getStartedAt()).isNotNull();
    }

    @Test
    void illegal_skip_from_pending_to_close_should_fail() {
        PipeSegment seg = prepareSegment(TENANT_A, "PS-T21");
        InspectionBatch batch = createBatch(TENANT_A, "BATCH-SKIP",
                LocalDateTime.now().plusDays(2),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));
        useTenant(TENANT_A);
        assertThatThrownBy(() -> inspectionService.finishExecution(batch.getId(), "甲"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未开始执行");
    }

    @Test
    void closed_batch_is_terminal() {
        InspectionBatch closed0 = noHazardBatch(TENANT_A, "BATCH-CLOSED", "PS-T30");
        useTenant(TENANT_A);
        InspectionBatch closed = inspectionService.close(closed0.getId(), "复核人");
        // 已关闭批次任何动作都被拒；再点「开始执行」给冲突
        assertThat(closed.getStatus()).isEqualTo(Dict.BATCH_CLOSED);
        assertThatThrownBy(() -> inspectionService.start(closed.getId(), "甲"))
                .isInstanceOf(ConflictException.class);
    }

    // ==================== 无隐患路径（并发关闭在 ConcurrentCloseTest 单独验证） ====================

    private InspectionBatch noHazardBatch(String tenant, String no, String segCode) {
        PipeSegment seg = prepareSegment(tenant, segCode);
        InspectionBatch batch = createBatch(tenant, no, LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));
        useTenant(tenant);
        InspectionTarget link = targetRepository.findByBatchIdOrderByIdAsc(batch.getId()).get(0);
        inspectionService.start(batch.getId(), "甲");
        inspectionService.inspect(batch.getId(), link.getId(), normalInspection("REQ-" + no + "-1"));
        inspectionService.requestReview(batch.getId(), "甲");
        return batch;
    }

    @Test
    void no_hazard_path_should_reach_review_then_close() {
        InspectionBatch batch = noHazardBatch(TENANT_A, "BATCH-NORISK", "PS-T31");
        useTenant(TENANT_A);
        InspectionBatch closed = inspectionService.close(batch.getId(), "复核人赵");
        assertThat(closed.getStatus()).isEqualTo(Dict.BATCH_CLOSED);
        assertThat(closed.getClosedBy()).isEqualTo("复核人赵");
        assertThat(closed.getClosedAt()).isNotNull();

        BatchDetailVO detail = inspectionService.detail(batch.getId());
        assertThat(detail.getTargets().get(0).getLatestResult()).isEqualTo("正常");
        assertThat(detail.getOpenHazardCount()).isZero();
    }

    // ==================== 有隐患路径 + 幂等 + 驳回再整改 ====================

    private HazardSetup setupHazardBatch(String tenant, String no, String segCode, LocalDateTime dueAt) {
        PipeSegment seg = prepareSegment(tenant, segCode);
        InspectionBatch batch = createBatch(tenant, no, LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));
        useTenant(tenant);
        InspectionTarget link = targetRepository.findByBatchIdOrderByIdAsc(batch.getId()).get(0);
        inspectionService.start(batch.getId(), "甲");
        inspectionService.inspect(batch.getId(), link.getId(),
                hazardInspection("REQ-" + no + "-REG", dueAt));
        InspectionHazard hazard = hazardRepository
                .findByBatchIdAndTargetTypeAndTargetIdAndRoundNo(
                        batch.getId(), Dict.TARGET_PIPE_SEGMENT, seg.getId(), 1)
                .orElseThrow();
        return new HazardSetup(batch, link, hazard);
    }

    private record HazardSetup(InspectionBatch batch, InspectionTarget link, InspectionHazard hazard) {
    }

    @Test
    void hazard_full_flow_with_reject_and_history_retained() {
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-HAZ1", "PS-T40",
                LocalDateTime.now().plusDays(7));
        Long batchId = setup.batch().getId();
        Long hazardId = setup.hazard().getId();
        useTenant(A_TENANT());

        // 执行完进入待整改
        InspectionBatch rectifying = inspectionService.finishExecution(batchId, "甲");
        assertThat(rectifying.getStatus()).isEqualTo(Dict.BATCH_RECTIFYING);

        // 责任人整改
        RectifyRequest rect = new RectifyRequest();
        rect.setOperator("李工");
        rect.setNote("已更换密封垫");
        rect.setRequestId("REQ-HAZ1-RECT");
        HazardVO afterRectify = inspectionService.rectify(hazardId, rect);
        assertThat(afterRectify.getStatus()).isEqualTo(Dict.HAZARD_WAIT_REVIEW);

        // 申请复核
        inspectionService.requestReview(batchId, "李工");
        assertThat(batchRepository.findById(batchId).orElseThrow().getStatus())
                .isEqualTo(Dict.BATCH_REVIEWING);

        // 复核驳回：隐患回整改中，批次回待整改，历史保留
        ReviewRequest reject = new ReviewRequest();
        reject.setPassed(false);
        reject.setOperator("复核人赵");
        reject.setNote("复紧力矩不足，请返工");
        reject.setRequestId("REQ-HAZ1-REJ");
        HazardVO rejected = inspectionService.review(hazardId, reject);
        assertThat(rejected.getStatus()).isEqualTo(Dict.HAZARD_RECTIFYING);
        assertThat(batchRepository.findById(batchId).orElseThrow().getStatus())
                .isEqualTo(Dict.BATCH_RECTIFYING);
        assertThat(rejected.getHistory()).extracting(HazardVO.HistoryVO::getAction)
                .containsExactly(Dict.ACTION_REGISTER, Dict.ACTION_RECTIFY, Dict.ACTION_REJECT);

        // 再次整改
        RectifyRequest rect2 = new RectifyRequest();
        rect2.setOperator("李工");
        rect2.setNote("已按力矩要求返工复紧");
        rect2.setRequestId("REQ-HAZ1-RECT2");
        assertThat(inspectionService.rectify(hazardId, rect2).getStatus())
                .isEqualTo(Dict.HAZARD_WAIT_REVIEW);
        inspectionService.requestReview(batchId, "李工");

        // 复核通过
        ReviewRequest pass = new ReviewRequest();
        pass.setPassed(true);
        pass.setOperator("复核人赵");
        pass.setNote("现场复验合格");
        pass.setRequestId("REQ-HAZ1-PASS");
        HazardVO passed = inspectionService.review(hazardId, pass);
        assertThat(passed.getStatus()).isEqualTo(Dict.HAZARD_PASSED);
        assertThat(passed.getHistory()).hasSize(5);
        assertThat(passed.getReviewedBy()).isEqualTo("复核人赵");

        // 关闭
        InspectionBatch closed = inspectionService.close(batchId, "复核人赵");
        assertThat(closed.getStatus()).isEqualTo(Dict.BATCH_CLOSED);
    }

    private String A_TENANT() {
        return TENANT_A;
    }

    @Test
    void cannot_close_while_hazard_not_passed() {
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-HAZ2", "PS-T41",
                LocalDateTime.now().plusDays(7));
        useTenant(TENANT_A);
        inspectionService.finishExecution(setup.batch().getId(), "甲");
        RectifyRequest rect = new RectifyRequest();
        rect.setOperator("李工");
        rect.setNote("已整改");
        inspectionService.rectify(setup.hazard().getId(), rect);
        inspectionService.requestReview(setup.batch().getId(), "李工");

        // 隐患还是「待复核」未通过，直接关闭必须拒绝（事务内二次确认的业务面）
        assertThatThrownBy(() -> inspectionService.close(setup.batch().getId(), "复核人赵"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未复核通过");
        assertThat(batchRepository.findById(setup.batch().getId()).orElseThrow().getStatus())
                .isEqualTo(Dict.BATCH_REVIEWING);
    }

    @Test
    void overdue_rectify_should_be_rejected_and_status_kept() {
        // 先以合法期限登记，再把期限回拨到过去，模拟「责任人拖到逾期才提交整改」
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-HAZ3", "PS-T42",
                LocalDateTime.now().plusDays(7));
        useTenant(TENANT_A);
        InspectionHazard hazard = hazardRepository.findById(setup.hazard().getId()).orElseThrow();
        hazard.setDueAt(LocalDateTime.now().minusMinutes(1));
        hazardRepository.saveAndFlush(hazard);

        inspectionService.finishExecution(setup.batch().getId(), "甲");

        RectifyRequest rect = new RectifyRequest();
        rect.setOperator("李工");
        rect.setNote("逾期才整改");
        assertThatThrownBy(() -> inspectionService.rectify(setup.hazard().getId(), rect))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过整改期限");
        assertThat(hazardRepository.findById(setup.hazard().getId()).orElseThrow().getStatus())
                .isEqualTo(Dict.HAZARD_RECTIFYING);
    }

    @Test
    void duplicate_hazard_same_target_same_round_should_fail() {
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-HAZ4", "PS-T43",
                LocalDateTime.now().plusDays(7));
        useTenant(TENANT_A);
        // 同一对象同轮再登记隐患（无 requestId 的裸重试/重复点击）
        assertThatThrownBy(() -> inspectionService.inspect(
                setup.batch().getId(), setup.link().getId(),
                hazardInspection(null, LocalDateTime.now().plusDays(7))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已登记隐患");
        assertThat(hazardRepository.findByBatchIdOrderByIdAsc(setup.batch().getId())).hasSize(1);
    }

    @Test
    void register_hazard_with_same_request_id_is_idempotent() {
        PipeSegment seg = prepareSegment(TENANT_A, "PS-T44");
        InspectionBatch batch = createBatch(TENANT_A, "BATCH-IDEM1",
                LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));
        useTenant(TENANT_A);
        InspectionTarget link = targetRepository.findByBatchIdOrderByIdAsc(batch.getId()).get(0);
        inspectionService.start(batch.getId(), "甲");

        InspectRequest first = hazardInspection("REQ-IDEM-REG", LocalDateTime.now().plusDays(7));
        TargetResultVO r1 = inspectionService.inspect(batch.getId(), link.getId(), first);
        TargetResultVO r2 = inspectionService.inspect(batch.getId(), link.getId(),
                hazardInspection("REQ-IDEM-REG", LocalDateTime.now().plusDays(3)));

        assertThat(r2.getLinkId()).as("重试回放首次结果").isEqualTo(r1.getLinkId());
        assertThat(hazardRepository.findByBatchIdOrderByIdAsc(batch.getId())).hasSize(1);
        // 期限必须是第一次提交的值，重试不得覆盖（H2 DATETIME 精度到毫秒，比较时忽略纳秒）
        assertThat(hazardRepository.findByBatchIdOrderByIdAsc(batch.getId()).get(0).getDueAt())
                .isEqualToIgnoringNanos(first.getHazard().getDueAt());
    }

    @Test
    void rectify_and_review_with_same_request_id_are_idempotent() {
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-IDEM2", "PS-T45",
                LocalDateTime.now().plusDays(7));
        useTenant(TENANT_A);
        inspectionService.finishExecution(setup.batch().getId(), "甲");

        RectifyRequest rect = new RectifyRequest();
        rect.setOperator("李工");
        rect.setNote("首次整改说明");
        rect.setRequestId("REQ-IDEM-RECT");
        HazardVO v1 = inspectionService.rectify(setup.hazard().getId(), rect);

        RectifyRequest rectRetry = new RectifyRequest();
        rectRetry.setOperator("李工");
        rectRetry.setNote("重试的整改说明——不应落库");
        rectRetry.setRequestId("REQ-IDEM-RECT");
        HazardVO v2 = inspectionService.rectify(setup.hazard().getId(), rectRetry);
        assertThat(v2.getId()).isEqualTo(v1.getId());
        assertThat(v2.getRectifyNote()).isEqualTo("首次整改说明");

        inspectionService.requestReview(setup.batch().getId(), "李工");
        ReviewRequest review = new ReviewRequest();
        review.setPassed(true);
        review.setOperator("赵");
        review.setNote("通过");
        review.setRequestId("REQ-IDEM-REV");
        inspectionService.review(setup.hazard().getId(), review);
        // 已通过后再用同一 requestId 重试：仍回放「已通过」，不报错、不重复写历史
        HazardVO retry = inspectionService.review(setup.hazard().getId(), review);
        assertThat(retry.getStatus()).isEqualTo(Dict.HAZARD_PASSED);
        assertThat(retry.getHistory()).filteredOn(h -> Dict.ACTION_PASS.equals(h.getAction())).hasSize(1);
    }

    @Test
    void blank_note_rectify_and_review_should_fail() {
        HazardSetup setup = setupHazardBatch(TENANT_A, "BATCH-HAZ5", "PS-T46",
                LocalDateTime.now().plusDays(7));
        useTenant(TENANT_A);
        inspectionService.finishExecution(setup.batch().getId(), "甲");

        RectifyRequest rect = new RectifyRequest();
        rect.setOperator("李工");
        rect.setNote("   ");
        assertThatThrownBy(() -> inspectionService.rectify(setup.hazard().getId(), rect))
                .hasMessageContaining("整改说明不能为空");
    }

    // ==================== 分页筛选 ====================

    @Test
    void list_should_page_and_filter_within_tenant() {
        prepareSegment(TENANT_A, "PS-T60");
        prepareSegment(TENANT_A, "PS-T61");
        createBatch(TENANT_A, "PAGE-A1", LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segmentRepository.findBySegCode("PS-T60").orElseThrow().getId())));
        createBatch(TENANT_A, "PAGE-A2", LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segmentRepository.findBySegCode("PS-T61").orElseThrow().getId())));
        PipeSegment segB = prepareSegment(TENANT_B, "PS-T62");
        createBatch(TENANT_B, "PAGE-B1", LocalDateTime.now().plusDays(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segB.getId())));

        useTenant(TENANT_A);
        assertThat(inspectionService.search(null, null, false, 0, 10).getTotalElements())
                .as("只能看到本租户批次").isEqualTo(2);
        assertThat(inspectionService.search(null, "A1", false, 0, 10).getContent())
                .hasSize(1).first().extracting(InspectionBatch::getBatchNo).isEqualTo("PAGE-A1");
        assertThat(inspectionService.search(Dict.BATCH_PENDING, null, false, 0, 10).getTotalElements())
                .isEqualTo(2);

        var paged = inspectionService.search(null, null, false, 0, 1);
        assertThat(paged.getTotalPages()).isEqualTo(2);
        assertThat(paged.getContent()).hasSize(1);

        // 过期未执行筛选
        createBatch(TENANT_A, "PAGE-A3", LocalDateTime.now().minusHours(1),
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, segmentRepository.findBySegCode("PS-T60").orElseThrow().getId())));
        assertThat(inspectionService.search(null, null, true, 0, 10).getContent())
                .extracting(InspectionBatch::getBatchNo).containsExactly("PAGE-A3");
    }
}
