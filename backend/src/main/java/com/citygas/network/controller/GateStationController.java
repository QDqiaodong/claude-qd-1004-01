package com.citygas.network.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.entity.GateStation;
import com.citygas.network.service.GateStationService;

/** 门站接口（列表自动只返回当前租户的数据） */
@RestController
@RequestMapping("/api/gate-stations")
public class GateStationController {

    private final GateStationService gateService;

    public GateStationController(GateStationService gateService) {
        this.gateService = gateService;
    }

    @GetMapping
    public ApiResponse<List<GateStation>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(gateService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<GateStation> get(@PathVariable Long id) {
        return ApiResponse.success(gateService.get(id));
    }

    @PostMapping
    public ApiResponse<GateStation> create(@RequestBody GateStation input) {
        return ApiResponse.success("门站已建档", gateService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<GateStation> update(@PathVariable Long id, @RequestBody GateStation input) {
        return ApiResponse.success("已更新", gateService.update(id, input));
    }

    /** 投运 / 转检修 / 停用 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<GateStation> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", gateService.transfer(id, target));
    }
}
