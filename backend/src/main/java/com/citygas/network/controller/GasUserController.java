package com.citygas.network.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.entity.GasUser;
import com.citygas.network.service.GasUserService;
import com.citygas.network.tenant.TenantContext;

/** 用户抄表与入户安检接口 */
@RestController
@RequestMapping("/api/gas-users")
public class GasUserController {

    private final GasUserService userService;

    public GasUserController(GasUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<GasUser>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(userService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<GasUser> get(@PathVariable Long id) {
        return ApiResponse.success(userService.get(id));
    }

    @PostMapping
    public ApiResponse<GasUser> create(@RequestBody GasUser input) {
        return ApiResponse.success("用户已建档", userService.create(input));
    }

    /** 抄表：读数倒走 / 用量超上限都会被拦 */
    @PostMapping("/{id}/read-meter")
    public ApiResponse<GasUser> readMeter(@PathVariable Long id, @RequestParam BigDecimal reading) {
        return ApiResponse.success("抄表已登记", userService.readMeter(id, reading));
    }

    /** 入户安检 */
    @PostMapping("/{id}/check")
    public ApiResponse<GasUser> check(@PathVariable Long id, @RequestParam String result) {
        return ApiResponse.success("安检已登记", userService.check(id, result));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<GasUser> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", userService.transfer(id, target));
    }

    /** 当前生效的租户，便于前端展示「现在看的是哪个片区」 */
    @GetMapping("/current-tenant")
    public ApiResponse<String> currentTenant() {
        return ApiResponse.success(TenantContext.get());
    }
}
