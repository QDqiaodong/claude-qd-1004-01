package com.citygas.network.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.AcceptRequest;
import com.citygas.network.dto.BoundaryRequest;
import com.citygas.network.dto.ConfirmRequest;
import com.citygas.network.dto.MaintActionRequest;
import com.citygas.network.dto.MaintCreateRequest;
import com.citygas.network.dto.MaintDetailVO;
import com.citygas.network.dto.MaintUpdateRequest;
import com.citygas.network.dto.PageResponse;
import com.citygas.network.dto.VersionRequest;
import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.MaintenanceSegment;
import com.citygas.network.service.MaintenanceService;

import jakarta.validation.Valid;

/**
 * 管段检修影响评估与停复气联动接口。
 *
 * <pre>
 * 检修单：POST /maintenance-orders                          建档（自动关联影响清单）
 *         GET  /maintenance-orders                          分页筛选（状态/关键字）
 *         GET  /maintenance-orders/{id}                     详情（范围/清单/差异提示）
 *         PUT  /maintenance-orders/{id}                     编辑草稿（校验版本）
 *         POST /maintenance-orders/{id}/refresh-impact      重新评估影响（重建自动行）
 *         POST /maintenance-orders/{id}/boundaries          补充人工边界对象（必填人工说明）
 *         DELETE /maintenance-orders/{id}/boundaries/{impactId} 移除人工边界对象
 * 状态机：POST /maintenance-orders/{id}/publish             发布并冻结快照（并发 409，可带 requestId）
 *         POST /maintenance-orders/{id}/stop-gas            停气开工（停气后才可开工）
 *         POST /maintenance-orders/{id}/finish-work         完工报验
 *         POST /maintenance-orders/{id}/segments/{linkId}/accept  逐段验收（全过自动推进待复气）
 *         POST /maintenance-orders/{id}/impacts/{impactId}/confirm 受影响用户安全确认
 *         POST /maintenance-orders/{id}/restore-gas         复气放行（双闸门校验，并发 409，可带 requestId）
 * </pre>
 */
@RestController
@RequestMapping("/api/maintenance-orders")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping
    public ApiResponse<PageResponse<MaintenanceOrder>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Page<MaintenanceOrder> result = maintenanceService.search(status, keyword, page, size);
        return ApiResponse.success(PageResponse.of(result));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaintDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(maintenanceService.detail(id));
    }

    @PostMapping
    public ApiResponse<MaintenanceOrder> create(@Valid @RequestBody MaintCreateRequest req) {
        return ApiResponse.success("检修单已创建，影响清单已按当前拓扑自动关联", maintenanceService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaintenanceOrder> update(@PathVariable Long id,
                                                @Valid @RequestBody MaintUpdateRequest req) {
        return ApiResponse.success("检修单已更新，自动影响已重建", maintenanceService.update(id, req));
    }

    @PostMapping("/{id}/refresh-impact")
    public ApiResponse<MaintDetailVO> refreshImpact(@PathVariable Long id,
                                                    @Valid @RequestBody VersionRequest req) {
        return ApiResponse.success("已按当前拓扑重新评估影响", maintenanceService.refreshImpact(id, req));
    }

    @PostMapping("/{id}/boundaries")
    public ApiResponse<MaintenanceImpact> addBoundary(@PathVariable Long id,
                                                      @Valid @RequestBody BoundaryRequest req) {
        return ApiResponse.success("人工边界对象已补充", maintenanceService.addBoundary(id, req));
    }

    @DeleteMapping("/{id}/boundaries/{impactId}")
    public ApiResponse<Void> removeBoundary(@PathVariable Long id, @PathVariable Long impactId,
                                            @Valid @RequestBody VersionRequest req) {
        maintenanceService.removeBoundary(id, impactId, req);
        return ApiResponse.success("人工边界对象已移除", null);
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<MaintenanceOrder> publish(@PathVariable Long id,
                                                 @Valid @RequestBody MaintActionRequest req) {
        return ApiResponse.success("检修单已发布，影响快照已冻结", maintenanceService.publish(id, req));
    }

    @PostMapping("/{id}/stop-gas")
    public ApiResponse<MaintenanceOrder> stopGas(@PathVariable Long id,
                                                 @Valid @RequestBody MaintActionRequest req) {
        return ApiResponse.success("已停气，检修开工", maintenanceService.stopGas(id, req));
    }

    @PostMapping("/{id}/finish-work")
    public ApiResponse<MaintenanceOrder> finishWork(@PathVariable Long id,
                                                    @Valid @RequestBody MaintActionRequest req) {
        return ApiResponse.success("施工已完工，进入验收环节", maintenanceService.finishWork(id, req));
    }

    @PostMapping("/{id}/segments/{linkId}/accept")
    public ApiResponse<MaintenanceSegment> accept(@PathVariable("id") Long orderId,
                                                  @PathVariable Long linkId,
                                                  @Valid @RequestBody AcceptRequest req) {
        return ApiResponse.success("管段验收通过", maintenanceService.accept(orderId, linkId, req));
    }

    @PostMapping("/{id}/impacts/{impactId}/confirm")
    public ApiResponse<MaintenanceImpact> confirm(@PathVariable("id") Long orderId,
                                                  @PathVariable Long impactId,
                                                  @Valid @RequestBody ConfirmRequest req) {
        return ApiResponse.success("用户安全确认完成", maintenanceService.confirmUser(orderId, impactId, req));
    }

    @PostMapping("/{id}/restore-gas")
    public ApiResponse<MaintenanceOrder> restoreGas(@PathVariable Long id,
                                                    @Valid @RequestBody MaintActionRequest req) {
        return ApiResponse.success("复气放行，检修单已完成", maintenanceService.restoreGas(id, req));
    }
}
