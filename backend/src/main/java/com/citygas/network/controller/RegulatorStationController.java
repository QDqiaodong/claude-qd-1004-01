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
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.service.RegulatorStationService;

/** 调压站接口 */
@RestController
@RequestMapping("/api/regulator-stations")
public class RegulatorStationController {

    private final RegulatorStationService regulatorService;

    public RegulatorStationController(RegulatorStationService regulatorService) {
        this.regulatorService = regulatorService;
    }

    @GetMapping
    public ApiResponse<List<RegulatorStation>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String stationType) {
        return ApiResponse.success(regulatorService.list(status, stationType));
    }

    @GetMapping("/{id}")
    public ApiResponse<RegulatorStation> get(@PathVariable Long id) {
        return ApiResponse.success(regulatorService.get(id));
    }

    @PostMapping
    public ApiResponse<RegulatorStation> create(@RequestBody RegulatorStation input) {
        return ApiResponse.success("调压站已建档", regulatorService.create(input));
    }

    /** 调整出口压力，超过 0.4MPa 会被拦 */
    @PostMapping("/{id}/outlet")
    public ApiResponse<RegulatorStation> setOutlet(@PathVariable Long id, @RequestParam BigDecimal outletMpa) {
        return ApiResponse.success("出口压力已调整", regulatorService.setOutlet(id, outletMpa));
    }

    @PostMapping("/{id}/inspect")
    public ApiResponse<RegulatorStation> inspect(@PathVariable Long id) {
        return ApiResponse.success("检验已登记", regulatorService.inspect(id));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<RegulatorStation> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", regulatorService.transfer(id, target));
    }
}
