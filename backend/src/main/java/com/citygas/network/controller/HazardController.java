package com.citygas.network.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.HazardVO;
import com.citygas.network.dto.RectifyRequest;
import com.citygas.network.dto.ReviewRequest;
import com.citygas.network.service.InspectionService;

import jakarta.validation.Valid;

/**
 * 隐患整改与复核接口（驳回后必须回到整改中，处理历史完整保留）。
 */
@RestController
@RequestMapping("/api/hazards")
public class HazardController {

    private final InspectionService inspectionService;

    public HazardController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    /** 责任人提交整改：逾期一律拒绝，requestId 支持网络重试幂等 */
    @PostMapping("/{id}/rectify")
    public ApiResponse<HazardVO> rectify(@PathVariable Long id,
                                         @Valid @RequestBody RectifyRequest req) {
        return ApiResponse.success("整改已提交，等待复核", inspectionService.rectify(id, req));
    }

    /** 复核人复核：passed=true 通过；passed=false 驳回（隐患回整改中、批次回待整改） */
    @PostMapping("/{id}/review")
    public ApiResponse<HazardVO> review(@PathVariable Long id,
                                        @Valid @RequestBody ReviewRequest req) {
        String msg = Boolean.TRUE.equals(req.getPassed()) ? "复核通过，隐患已闭环" : "已驳回，隐患退回整改中";
        return ApiResponse.success(msg, inspectionService.review(id, req));
    }
}
