package com.citygas.network.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.BatchCreateRequest;
import com.citygas.network.dto.BatchDetailVO;
import com.citygas.network.dto.InspectRequest;
import com.citygas.network.dto.OperatorRequest;
import com.citygas.network.dto.PageResponse;
import com.citygas.network.dto.TargetResultVO;
import com.citygas.network.entity.InspectionBatch;
import com.citygas.network.service.InspectionService;

import jakarta.validation.Valid;

/**
 * 安全巡检批次与隐患闭环接口。
 *
 * <pre>
 * 批次：POST /inspection-batches                       创建（编号租户内唯一、对象真实且同租户）
 *       GET  /inspection-batches                       分页筛选（状态/关键字/只看过期）
 *       GET  /inspection-batches/{id}                  详情（各对象最新结果 + 隐患历史）
 *       POST /inspection-batches/{id}/start            开始执行（过期批次也必须补执行）
 *       POST /inspection-batches/{id}/finish           完成现场执行 → 待整改
 *       POST /inspection-batches/{id}/request-review   申请复核 → 待复核
 *       POST /inspection-batches/{id}/close            复核收口 → 已关闭（并发 409）
 * 巡检：POST /inspection-batches/{id}/targets/{linkId}/inspect  登记对象结果（可带 requestId）
 * 隐患：POST /hazards/{id}/rectify                     提交整改（逾期无效，可带 requestId）
 *       POST /hazards/{id}/review                      复核通过/驳回（驳回回整改中，可带 requestId）
 * </pre>
 */
@RestController
@RequestMapping("/api/inspection-batches")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    // ---------------- 批次 ----------------

    @GetMapping
    public ApiResponse<PageResponse<InspectionBatch>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Page<InspectionBatch> result = inspectionService.search(status, keyword, overdueOnly, page, size);
        return ApiResponse.success(PageResponse.of(result));
    }

    @GetMapping("/{id}")
    public ApiResponse<BatchDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(inspectionService.detail(id));
    }

    @PostMapping
    public ApiResponse<InspectionBatch> create(@Valid @RequestBody BatchCreateRequest req) {
        return ApiResponse.success("巡检批次已创建", inspectionService.create(req));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<InspectionBatch> start(@PathVariable Long id,
                                              @Valid @RequestBody OperatorRequest req) {
        return ApiResponse.success("批次已开始执行", inspectionService.start(id, req.getOperator()));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<InspectionBatch> finish(@PathVariable Long id,
                                               @Valid @RequestBody OperatorRequest req) {
        return ApiResponse.success("现场执行已完成，进入整改环节",
                inspectionService.finishExecution(id, req.getOperator()));
    }

    @PostMapping("/{id}/request-review")
    public ApiResponse<InspectionBatch> requestReview(@PathVariable Long id,
                                                      @Valid @RequestBody OperatorRequest req) {
        return ApiResponse.success("已申请复核", inspectionService.requestReview(id, req.getOperator()));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<InspectionBatch> close(@PathVariable Long id,
                                              @Valid @RequestBody OperatorRequest req) {
        return ApiResponse.success("批次已关闭", inspectionService.close(id, req.getOperator()));
    }

    // ---------------- 对象巡检 ----------------

    @PostMapping("/{id}/targets/{linkId}/inspect")
    public ApiResponse<TargetResultVO> inspect(@PathVariable("id") Long batchId,
                                               @PathVariable Long linkId,
                                               @Valid @RequestBody InspectRequest req) {
        return ApiResponse.success("巡检结果已登记",
                inspectionService.inspect(batchId, linkId, req));
    }
}
