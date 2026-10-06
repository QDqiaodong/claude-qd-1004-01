package com.citygas.network.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.MaintenanceAcceptRequest;
import com.citygas.network.dto.MaintenanceActionRequest;
import com.citygas.network.dto.MaintenanceDetailVO;
import com.citygas.network.dto.MaintenanceImpactVO;
import com.citygas.network.dto.MaintenanceOrderRequest;
import com.citygas.network.dto.MaintenanceSafetyRequest;
import com.citygas.network.dto.PageResponse;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.service.MaintenanceService;

import jakarta.validation.Valid;

/**
 * 管段检修影响评估与停复气联动接口。
 *
 * <pre>
 * 检修单：POST /maintenance-orders                         创建草稿（自动沿拓扑关联影响清单）
 *         GET  /maintenance-orders                         分页筛选（状态/关键字）
 *         GET  /maintenance-orders/{id}                    详情（影响清单 + 差异提示 + 计数）
 *         PUT  /maintenance-orders/{id}                    编辑草稿（已发布冻结不可改）
 *         POST /maintenance-orders/{id}/recompute          草稿重新核对拓扑（保留人工边界）
 *         POST /maintenance-orders/{id}/publish            发布冻结（拦截失败回滚，保留编辑内容）
 *         POST /maintenance-orders/{id}/cutoff             停气登记
 *         POST /maintenance-orders/{id}/start-work         开工（必须已停气）
 *         POST /maintenance-orders/{id}/finish-work        施工完成 → 待验收
 *         POST /maintenance-orders/{id}/impacts/{iid}/accept   管段验收（通过/不通过）
 *         POST /maintenance-orders/{id}/request-restore    申请复气（全部管段验收通过才放行）
 *         POST /maintenance-orders/{id}/impacts/{iid}/safety   受影响用户安全确认
 *         POST /maintenance-orders/{id}/restore            复气放行（双条件 + 并发收口）
 * </pre>
 * 写动作请求体可携带 requestId：同租户同 requestId 重试回放首次结果。
 * 租户由 X-Tenant 头 / ?tenant= 传入，后端统一校验，跨租户对象一律视为不存在。
 */
@RestController
@RequestMapping("/api/maintenance-orders")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    // ---------------- 检修单 ----------------

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
    public ApiResponse<MaintenanceDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(maintenanceService.detail(id));
    }

    @PostMapping
    public ApiResponse<MaintenanceOrder> create(@Valid @RequestBody MaintenanceOrderRequest req) {
        return ApiResponse.success("检修单已创建（草稿）", maintenanceService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaintenanceOrder> update(@PathVariable Long id,
                                                @Valid @RequestBody MaintenanceOrderRequest req) {
        return ApiResponse.success("检修单草稿已更新", maintenanceService.update(id, req));
    }

    @PostMapping("/{id}/recompute")
    public ApiResponse<MaintenanceDetailVO> recompute(@PathVariable Long id) {
        return ApiResponse.success("已按当前拓扑重新核对影响清单（人工边界保留）",
                maintenanceService.recompute(id));
    }

    // ---------------- 状态推进 ----------------

    @PostMapping("/{id}/publish")
    public ApiResponse<MaintenanceOrder> publish(@PathVariable Long id,
                                                 @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("检修单已发布，影响范围已冻结", maintenanceService.publish(id, req));
    }

    @PostMapping("/{id}/cutoff")
    public ApiResponse<MaintenanceOrder> cutoff(@PathVariable Long id,
                                                @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("停气已登记", maintenanceService.cutoff(id, req));
    }

    @PostMapping("/{id}/start-work")
    public ApiResponse<MaintenanceOrder> startWork(@PathVariable Long id,
                                                   @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("已开工（停气已确认）", maintenanceService.startWork(id, req));
    }

    @PostMapping("/{id}/finish-work")
    public ApiResponse<MaintenanceOrder> finishWork(@PathVariable Long id,
                                                    @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("施工已完成，进入待验收", maintenanceService.finishWork(id, req));
    }

    @PostMapping("/{id}/request-restore")
    public ApiResponse<MaintenanceOrder> requestRestore(@PathVariable Long id,
                                                        @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("已申请复气", maintenanceService.requestRestore(id, req));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaintenanceOrder> restore(@PathVariable Long id,
                                                 @Valid @RequestBody MaintenanceActionRequest req) {
        return ApiResponse.success("复气放行完成，检修单已闭环", maintenanceService.restore(id, req));
    }

    // ---------------- 管段验收 / 用户安全确认 ----------------

    @PostMapping("/{id}/impacts/{impactId}/accept")
    public ApiResponse<MaintenanceImpactVO> accept(@PathVariable("id") Long orderId,
                                                   @PathVariable Long impactId,
                                                   @Valid @RequestBody MaintenanceAcceptRequest req) {
        String msg = Boolean.TRUE.equals(req.getPassed()) ? "管段验收通过" : "管段验收不通过已登记";
        return ApiResponse.success(msg, maintenanceService.accept(orderId, impactId, req));
    }

    @PostMapping("/{id}/impacts/{impactId}/safety")
    public ApiResponse<MaintenanceImpactVO> safety(@PathVariable("id") Long orderId,
                                                   @PathVariable Long impactId,
                                                   @Valid @RequestBody MaintenanceSafetyRequest req) {
        return ApiResponse.success("用户安全确认完成",
                maintenanceService.confirmSafety(orderId, impactId, req));
    }
}
