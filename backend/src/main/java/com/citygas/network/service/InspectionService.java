package com.citygas.network.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.HazardHistory;
import com.citygas.network.entity.IdempotentRecord;
import com.citygas.network.entity.InspectionBatch;
import com.citygas.network.entity.InspectionHazard;
import com.citygas.network.entity.InspectionTarget;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasUserRepository;
import com.citygas.network.repository.HazardHistoryRepository;
import com.citygas.network.repository.IdempotentRecordRepository;
import com.citygas.network.repository.InspectionBatchRepository;
import com.citygas.network.repository.InspectionHazardRepository;
import com.citygas.network.repository.InspectionTargetRepository;
import com.citygas.network.repository.PipeSegmentRepository;
import com.citygas.network.repository.RegulatorStationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 安全巡检批次与隐患闭环核心业务。
 *
 * <p>批次状态机：待执行 → 执行中 → 待整改 → 待复核 → 已关闭：
 * <ul>
 *   <li>过期（计划开始时间已过）的「待执行」批次也必须先 start 补执行，禁止任何方式跳过执行；</li>
 *   <li>执行中逐对象登记巡检结果；发现隐患即建隐患单（同批次同对象同轮唯一）；</li>
 *   <li>有隐患：执行完 → 待整改，责任人整改/复核人逐条复核；驳回必回整改中且历史保留；</li>
 *   <li>无隐患：执行完可直接申请复核跳到待复核；</li>
 *   <li>关闭在事务内加行锁并再次确认全部隐患已通过；并发关闭只有一人成功，后来者 409。</li>
 * </ul>
 * 巡检登记 / 提交整改 / 隐患复核支持 requestId 幂等，网络重试回放首次结果。
 * 所有读写经由 {@code @TenantId} 自动隔离，跨租户对象一律视为不存在。</p>
 */
@Service
public class InspectionService {

    /** 幂等动作 → 首次响应回放类型 */
    private static final Map<String, Class<?>> IDEM_RESPONSE_TYPES = Map.of(
            "REGISTER", TargetResultVO.class,
            "RECTIFY", HazardVO.class,
            "REVIEW", HazardVO.class);

    private final InspectionBatchRepository batchRepository;
    private final InspectionTargetRepository targetRepository;
    private final InspectionHazardRepository hazardRepository;
    private final HazardHistoryRepository historyRepository;
    private final IdempotentRecordRepository idemRepository;
    private final PipeSegmentRepository segmentRepository;
    private final RegulatorStationRepository regulatorRepository;
    private final GasUserRepository gasUserRepository;
    private final ObjectMapper objectMapper;

    public InspectionService(InspectionBatchRepository batchRepository,
                             InspectionTargetRepository targetRepository,
                             InspectionHazardRepository hazardRepository,
                             HazardHistoryRepository historyRepository,
                             IdempotentRecordRepository idemRepository,
                             PipeSegmentRepository segmentRepository,
                             RegulatorStationRepository regulatorRepository,
                             GasUserRepository gasUserRepository,
                             ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.targetRepository = targetRepository;
        this.hazardRepository = hazardRepository;
        this.historyRepository = historyRepository;
        this.idemRepository = idemRepository;
        this.segmentRepository = segmentRepository;
        this.regulatorRepository = regulatorRepository;
        this.gasUserRepository = gasUserRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 批次查询 ====================

    @Transactional(readOnly = true)
    public Page<InspectionBatch> search(String status, String keyword, boolean overdueOnly, int page, int size) {
        if (status != null) {
            status = status.trim();
            if (status.isEmpty()) {
                status = null;
            }
        }
        if (status != null && !Dict.isValidBatchStatus(status)) {
            throw new BizException("批次状态不合法：" + status);
        }
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;
            }
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        // 「只看过期未执行」与状态/关键字取交集，租户条件由 @TenantId 自动追加——
        // 任何组合都只可能命中当前租户的数据。
        return overdueOnly
                ? batchRepository.searchOverduePending(status, keyword, java.time.LocalDateTime.now(), pageable)
                : batchRepository.search(status, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public BatchDetailVO detail(Long id) {
        InspectionBatch batch = getBatch(id);
        List<InspectionTarget> links = targetRepository.findByBatchIdOrderByIdAsc(id);
        List<InspectionHazard> hazards = hazardRepository.findByBatchIdOrderByIdAsc(id);

        BatchDetailVO vo = new BatchDetailVO();
        vo.setBatch(batch);
        vo.setTargets(links.stream().map(TargetResultVO::of).toList());
        List<HazardVO> hazardVOs = new ArrayList<>();
        for (InspectionHazard h : hazards) {
            hazardVOs.add(HazardVO.of(h, historyRepository.findByHazardIdOrderByIdAsc(h.getId())));
        }
        vo.setHazards(hazardVOs);
        vo.setOpenHazardCount(hazardRepository.countByBatchIdAndStatusNot(id, Dict.HAZARD_PASSED));
        return vo;
    }

    // ==================== 批次生命周期 ====================

    @Transactional
    public InspectionBatch create(BatchCreateRequest req) {
        String batchNo = req.getBatchNo().trim();
        if (batchNo.isBlank()) {
            throw new BizException("批次编号不能为空");
        }
        if (req.getOwnerName() == null || req.getOwnerName().isBlank()) {
            throw new BizException("负责人不能为空");
        }
        if (req.getRemark() == null || req.getRemark().isBlank()) {
            throw new BizException("巡检说明不能为空");
        }
        if (req.getTargets() == null || req.getTargets().isEmpty()) {
            throw new BizException("至少关联一个巡检对象");
        }
        if (batchRepository.existsByBatchNo(batchNo)) {
            throw new BizException("批次编号已存在：" + batchNo);
        }
        if (req.getPlanEndAt() != null && req.getPlanEndAt().isBefore(req.getPlanStartAt())) {
            throw new BizException("计划结束时间不能早于计划开始时间");
        }

        LocalDateTime now = LocalDateTime.now();
        InspectionBatch batch = new InspectionBatch();
        batch.setBatchNo(batchNo);
        batch.setPlanStartAt(req.getPlanStartAt());
        batch.setPlanEndAt(req.getPlanEndAt());
        batch.setOwnerName(req.getOwnerName().trim());
        batch.setRemark(req.getRemark().trim());
        batch.setStatus(Dict.BATCH_PENDING);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch = batchRepository.save(batch);

        // 关联对象：类型合法、真实存在、同租户、批次内不重复——任一不满足整批回滚
        Set<String> dedupe = new HashSet<>();
        for (BatchCreateRequest.TargetRef ref : req.getTargets()) {
            String type = ref.getTargetType();
            if (!Dict.isValidTargetType(type)) {
                throw new BizException("关联对象类型不合法：" + type);
            }
            String key = type + "#" + ref.getTargetId();
            if (!dedupe.add(key)) {
                throw new BizException("关联对象重复：" + key);
            }
            String[] codeAndName = resolveTargetCodeName(type, ref.getTargetId());

            InspectionTarget link = new InspectionTarget();
            link.setBatchId(batch.getId());
            link.setTargetType(type);
            link.setTargetId(ref.getTargetId());
            link.setTargetCode(codeAndName[0]);
            link.setTargetName(codeAndName[1]);
            link.setRoundNo(1);
            link.setLatestResult("待检");
            targetRepository.save(link);
        }
        return batch;
    }

    /** 开始执行：待执行 → 执行中。过期批次同样必须走这一步补执行。 */
    @Transactional
    public InspectionBatch start(Long id, String operator) {
        requireOperator(operator);
        InspectionBatch batch = getBatch(id);
        if (Dict.BATCH_CLOSED.equals(batch.getStatus())) {
            throw new ConflictException("批次已关闭，不能再开始执行");
        }
        if (!Dict.BATCH_PENDING.equals(batch.getStatus())) {
            throw new BizException("批次当前为「" + batch.getStatus() + "」，不能开始执行");
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setStatus(Dict.BATCH_RUNNING);
        batch.setStartedAt(now);
        if (batch.getPlanStartAt().isBefore(now)) {
            // 过期批次允许补执行，但必须留下明确痕迹：更新时间变化，列表仍可查到其曾过期
            batch.setUpdatedAt(now);
        }
        return batchRepository.save(batch);
    }

    /** 完成现场执行：执行中 → 待整改（必须有隐患；无隐患请直接申请复核） */
    @Transactional
    public InspectionBatch finishExecution(Long id, String operator) {
        requireOperator(operator);
        InspectionBatch batch = getBatch(id);
        guardNotSkippingExecution(batch);
        if (!Dict.BATCH_RUNNING.equals(batch.getStatus())) {
            throw new BizException("非法状态跳转：仅「执行中」的批次可以完成现场执行，当前为「"
                    + batch.getStatus() + "」");
        }
        assertAllTargetsChecked(batch.getId());
        long openCount = hazardRepository.countByBatchIdAndStatusNot(batch.getId(), Dict.HAZARD_PASSED);
        if (openCount == 0) {
            throw new BizException("本批次未登记隐患，请使用「申请复核」直接收口，无需进入整改");
        }
        return transfer(batch, Dict.BATCH_RECTIFYING, LocalDateTime.now());
    }

    /** 申请复核：执行中（无隐患）或待整改（隐患均已提交整改）→ 待复核 */
    @Transactional
    public InspectionBatch requestReview(Long id, String operator) {
        requireOperator(operator);
        InspectionBatch batch = getBatch(id);
        guardNotSkippingExecution(batch);

        LocalDateTime now = LocalDateTime.now();
        if (Dict.BATCH_RUNNING.equals(batch.getStatus())) {
            assertAllTargetsChecked(batch.getId());
            long openCount = hazardRepository.countByBatchIdAndStatusNot(batch.getId(), Dict.HAZARD_PASSED);
            if (openCount > 0) {
                throw new BizException("批次尚有 " + openCount + " 项隐患未整改，请先完成现场执行并整改");
            }
        } else if (Dict.BATCH_RECTIFYING.equals(batch.getStatus())) {
            long rectifying = hazardRepository.countByBatchIdAndStatus(batch.getId(), Dict.HAZARD_RECTIFYING);
            if (rectifying > 0) {
                throw new BizException("还有 " + rectifying + " 项隐患停留在「整改中」，责任人提交整改后才能申请复核");
            }
            if (hazardRepository.findByBatchIdOrderByIdAsc(id).isEmpty()) {
                throw new BizException("批次没有隐患，状态异常，请联系管理员");
            }
        } else if (Dict.BATCH_REVIEWING.equals(batch.getStatus())) {
            throw new BizException("批次已在待复核，等待复核人逐条复核后关闭");
        } else if (Dict.BATCH_CLOSED.equals(batch.getStatus())) {
            throw new ConflictException("批次已关闭，不能再申请复核");
        } else {
            throw new BizException("非法状态跳转：当前为「" + batch.getStatus() + "」");
        }
        batch.setReviewRequestedAt(now);
        return transfer(batch, Dict.BATCH_REVIEWING, now);
    }

    /**
     * 隐患复核收口：待复核 → 已关闭。
     * 事务内先对批次行加悲观锁，再用锁读重新统计未通过隐患——
     * 两名操作员并发关闭时只可能一人成功，后来者收到 409 冲突且绝不覆盖先到者结果。
     */
    @Transactional
    public InspectionBatch close(Long id, String operator) {
        requireOperator(operator);
        // 加行级悲观锁：并发关闭在此串行化
        InspectionBatch batch = batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("批次不存在：" + id));
        if (Dict.BATCH_CLOSED.equals(batch.getStatus())) {
            throw new ConflictException("批次 " + batch.getBatchNo() + " 已由「"
                    + batch.getClosedBy() + "」关闭，您的关闭请求未生效，请勿覆盖关闭结果");
        }
        if (!Dict.BATCH_REVIEWING.equals(batch.getStatus())) {
            throw new BizException("非法状态跳转：仅「待复核」的批次可以关闭，当前为「"
                    + batch.getStatus() + "」");
        }
        // 锁读再次确认：全部隐患必须已通过（有未通过隐患不得关闭）
        List<InspectionHazard> open = hazardRepository.findOpenForUpdate(batch.getId());
        if (!open.isEmpty()) {
            throw new BizException("尚有 " + open.size() + " 项隐患未复核通过，不得关闭批次");
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setStatus(Dict.BATCH_CLOSED);
        batch.setClosedAt(now);
        batch.setClosedBy(operator.trim());
        batch.setUpdatedAt(now);
        return batchRepository.save(batch);
    }

    // ==================== 对象巡检登记 ====================

    @Transactional
    public TargetResultVO inspect(Long batchId, Long linkId, InspectRequest req) {
        requireOperator(req.getOperator());
        InspectionBatch batch = getBatch(batchId);
        if (!Dict.BATCH_RUNNING.equals(batch.getStatus())) {
            throw new BizException("仅「执行中」的批次可登记巡检结果，当前为「" + batch.getStatus() + "」");
        }
        InspectionTarget link = targetRepository.findScopedById(linkId)
                .orElseThrow(() -> new BizException("巡检对象不存在：" + linkId));
        if (!link.getBatchId().equals(batchId)) {
            // 租户条件由 @TenantId 兜底，这里再拦一次跨批次
            throw new BizException("巡检对象不属于批次：" + batch.getBatchNo());
        }
        String result = req.getResult().trim();
        if (!"正常".equals(result) && !"隐患".equals(result)) {
            throw new BizException("巡检结果不合法：" + result + "（仅支持 正常/隐患）");
        }
        return withIdempotency(req.getRequestId(), "REGISTER", () -> doInspect(batch, link, result, req));
    }

    private TargetResultVO doInspect(InspectionBatch batch, InspectionTarget link, String result, InspectRequest req) {
        LocalDateTime now = LocalDateTime.now();
        if ("正常".equals(result)) {
            if (!"待检".equals(link.getLatestResult())) {
                throw new BizException("对象 " + link.getTargetCode() + " 本轮已登记为「"
                        + link.getLatestResult() + "」，请勿重复提交");
            }
            link.setLatestResult("正常");
            link.setCheckedAt(now);
            targetRepository.save(link);
            return TargetResultVO.of(link);
        }

        // 结果为隐患：同批次同对象同轮唯一（先查后插 + 唯一索引双保险）
        InspectionHazard existing = hazardRepository
                .findByBatchIdAndTargetTypeAndTargetIdAndRoundNo(
                        batch.getId(), link.getTargetType(), link.getTargetId(), link.getRoundNo())
                .orElse(null);
        if (existing != null) {
            throw new BizException("对象 " + link.getTargetCode() + " 本轮已登记隐患，不能重复登记"
                    + "（如需重试请携带同一 requestId）");
        }
        InspectRequest.HazardInfo info = req.getHazard();
        if (info == null) {
            throw new BizException("巡检结果为隐患时，必须填写隐患问题、风险等级、整改要求、责任人和期限");
        }
        if (info.getProblem() == null || info.getProblem().isBlank()) {
            throw new BizException("隐患问题描述不能为空");
        }
        if (info.getRectifyRequire() == null || info.getRectifyRequire().isBlank()) {
            throw new BizException("整改要求不能为空");
        }
        if (info.getOwnerName() == null || info.getOwnerName().isBlank()) {
            throw new BizException("责任人不能为空");
        }
        if (!Dict.isValidRiskLevel(info.getRiskLevel().trim())) {
            throw new BizException("风险等级不合法：" + info.getRiskLevel());
        }
        if (info.getDueAt().isBefore(now)) {
            throw new BizException("整改期限不能早于当前时间");
        }

        InspectionHazard h = new InspectionHazard();
        h.setBatchId(batch.getId());
        h.setTargetType(link.getTargetType());
        h.setTargetId(link.getTargetId());
        h.setRoundNo(link.getRoundNo());
        h.setProblem(info.getProblem().trim());
        h.setRiskLevel(info.getRiskLevel().trim());
        h.setRectifyRequire(info.getRectifyRequire().trim());
        h.setOwnerName(info.getOwnerName().trim());
        h.setDueAt(info.getDueAt());
        h.setStatus(Dict.HAZARD_RECTIFYING);
        h.setCreatedAt(now);
        h.setUpdatedAt(now);
        h = hazardRepository.save(h);
        appendHistory(h, Dict.ACTION_REGISTER, null, Dict.HAZARD_RECTIFYING,
                req.getOperator().trim(), "登记隐患：" + h.getProblem());

        link.setLatestResult("隐患");
        link.setCheckedAt(now);
        targetRepository.save(link);
        return TargetResultVO.of(link);
    }

    // ==================== 隐患整改 / 复核 ====================

    @Transactional
    public HazardVO rectify(Long hazardId, RectifyRequest req) {
        requireOperator(req.getOperator());
        InspectionHazard h = getHazard(hazardId);
        return withIdempotency(req.getRequestId(), "RECTIFY", () -> doRectify(h, req));
    }

    private HazardVO doRectify(InspectionHazard h, RectifyRequest req) {
        if (req.getNote() == null || req.getNote().isBlank()) {
            throw new BizException("整改说明不能为空");
        }
        if (Dict.HAZARD_PASSED.equals(h.getStatus())) {
            throw new BizException("隐患已复核通过并闭环，不能再提交整改");
        }
        if (!Dict.HAZARD_RECTIFYING.equals(h.getStatus())) {
            throw new BizException("隐患已提交整改，正在等待复核，请勿重复提交");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(h.getDueAt())) {
            // 过期整改：状态保持「整改中」，历史不追加，明确拒绝
            throw new BizException("已超过整改期限（" + h.getDueAt() + "），本次整改无效，"
                    + "请走逾期延期流程后再提交");
        }
        String from = h.getStatus();
        h.setRectifyNote(req.getNote().trim());
        h.setRectifiedAt(now);
        h.setStatus(Dict.HAZARD_WAIT_REVIEW);
        h.setUpdatedAt(now);
        h = hazardRepository.save(h);
        appendHistory(h, Dict.ACTION_RECTIFY, from, Dict.HAZARD_WAIT_REVIEW,
                req.getOperator().trim(), req.getNote().trim());
        return toHazardVO(h);
    }

    @Transactional
    public HazardVO review(Long hazardId, ReviewRequest req) {
        requireOperator(req.getOperator());
        InspectionHazard h = getHazard(hazardId);
        return withIdempotency(req.getRequestId(), "REVIEW", () -> doReview(h, req));
    }

    private HazardVO doReview(InspectionHazard h, ReviewRequest req) {
        if (req.getNote() == null || req.getNote().isBlank()) {
            throw new BizException("复核意见不能为空");
        }
        if (req.getPassed() == null) {
            throw new BizException("复核结论不能为空");
        }
        InspectionBatch batch = getBatch(h.getBatchId());
        if (!Dict.BATCH_REVIEWING.equals(batch.getStatus())) {
            throw new BizException("仅「待复核」状态的批次可以复核隐患，批次当前为「"
                    + batch.getStatus() + "」");
        }
        if (!Dict.HAZARD_WAIT_REVIEW.equals(h.getStatus())) {
            throw new BizException("仅「待复核」的隐患可以复核，当前为「" + h.getStatus() + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        String from = h.getStatus();
        String operator = req.getOperator().trim();
        String note = req.getNote().trim();

        if (Boolean.TRUE.equals(req.getPassed())) {
            h.setStatus(Dict.HAZARD_PASSED);
            h.setReviewedAt(now);
            h.setReviewedBy(operator);
            h.setUpdatedAt(now);
            h = hazardRepository.save(h);
            appendHistory(h, Dict.ACTION_PASS, from, Dict.HAZARD_PASSED, operator, note);
        } else {
            // 驳回：隐患必须回到整改中，处理历史保留；批次同步退回待整改
            h.setStatus(Dict.HAZARD_RECTIFYING);
            h.setUpdatedAt(now);
            h = hazardRepository.save(h);
            appendHistory(h, Dict.ACTION_REJECT, from, Dict.HAZARD_RECTIFYING, operator, note);
            if (Dict.BATCH_REVIEWING.equals(batch.getStatus())) {
                transfer(batch, Dict.BATCH_RECTIFYING, now);
            }
        }
        return toHazardVO(h);
    }

    // ==================== 公共辅助 ====================

    private InspectionBatch getBatch(Long id) {
        return batchRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("批次不存在：" + id));
    }

    private InspectionHazard getHazard(Long id) {
        return hazardRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("隐患不存在：" + id));
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    /** 过期批次不能跳过执行：任何非开始动作打到「待执行」批次都拒绝。 */
    private void guardNotSkippingExecution(InspectionBatch batch) {
        if (Dict.BATCH_PENDING.equals(batch.getStatus())) {
            if (batch.getPlanStartAt().isBefore(LocalDateTime.now())) {
                throw new BizException("批次 " + batch.getBatchNo() + " 已超过计划开始时间（"
                        + batch.getPlanStartAt() + "），必须先补开始执行，不能跳过执行环节");
            }
            throw new BizException("批次尚未开始执行，请先开始执行");
        }
    }

    private void assertAllTargetsChecked(Long batchId) {
        List<InspectionTarget> links = targetRepository.findByBatchIdOrderByIdAsc(batchId);
        if (links.isEmpty()) {
            throw new BizException("批次没有关联任何巡检对象");
        }
        long pending = links.stream().filter(l -> "待检".equals(l.getLatestResult())).count();
        if (pending > 0) {
            throw new BizException("还有 " + pending + " 个对象未完成巡检，不能进入下一环节");
        }
    }

    private InspectionBatch transfer(InspectionBatch batch, String target, LocalDateTime now) {
        if (!Dict.canTransferBatch(batch.getStatus(), target)) {
            throw new BizException("非法状态跳转：" + batch.getStatus() + " → " + target);
        }
        batch.setStatus(target);
        batch.setUpdatedAt(now);
        return batchRepository.save(batch);
    }

    /** 校验关联对象真实存在且同租户（@TenantId 自动过滤，跨租户即查不到），返回 [编号, 名称] 快照。 */
    private String[] resolveTargetCodeName(String type, Long targetId) {
        if (Dict.TARGET_PIPE_SEGMENT.equals(type)) {
            PipeSegment s = segmentRepository.findScopedById(targetId)
                    .orElseThrow(() -> new BizException("关联管段不存在或不属于当前租户：" + targetId));
            return new String[] {s.getSegCode(), s.getStartPoint() + " → " + s.getEndPoint()};
        }
        if (Dict.TARGET_REGULATOR_STATION.equals(type)) {
            RegulatorStation r = regulatorRepository.findScopedById(targetId)
                    .orElseThrow(() -> new BizException("关联调压站不存在或不属于当前租户：" + targetId));
            return new String[] {r.getStationCode(), r.getStationName()};
        }
        GasUser u = gasUserRepository.findScopedById(targetId)
                .orElseThrow(() -> new BizException("关联燃气用户不存在或不属于当前租户：" + targetId));
        return new String[] {u.getUserNo(), u.getUserName()};
    }

    private void appendHistory(InspectionHazard h, String action, String from, String to,
                               String operator, String note) {
        HazardHistory history = new HazardHistory();
        history.setHazardId(h.getId());
        history.setBatchId(h.getBatchId());
        history.setAction(action);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setOperatorName(operator);
        history.setNote(note);
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);
    }

    private HazardVO toHazardVO(InspectionHazard h) {
        return HazardVO.of(h, historyRepository.findByHazardIdOrderByIdAsc(h.getId()));
    }

    /**
     * requestId 幂等：同租户同 requestId 的重试直接回放首次响应，不重复登记隐患、不重复推进状态。
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
