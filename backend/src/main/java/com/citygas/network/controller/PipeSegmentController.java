package com.citygas.network.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.service.PipeSegmentService;

/** 管网管段接口 */
@RestController
@RequestMapping("/api/pipe-segments")
public class PipeSegmentController {

    private final PipeSegmentService segmentService;

    public PipeSegmentController(PipeSegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @GetMapping
    public ApiResponse<List<PipeSegment>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String pressureLevel) {
        return ApiResponse.success(segmentService.list(status, pressureLevel));
    }

    @GetMapping("/{id}")
    public ApiResponse<PipeSegment> get(@PathVariable Long id) {
        return ApiResponse.success(segmentService.get(id));
    }

    @PostMapping
    public ApiResponse<PipeSegment> create(@RequestBody PipeSegment input) {
        return ApiResponse.success("管段已建档", segmentService.create(input));
    }

    /** 登记巡检 */
    @PostMapping("/{id}/patrol")
    public ApiResponse<PipeSegment> patrol(@PathVariable Long id) {
        return ApiResponse.success("巡检已登记", segmentService.patrol(id));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<PipeSegment> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", segmentService.transfer(id, target));
    }
}
