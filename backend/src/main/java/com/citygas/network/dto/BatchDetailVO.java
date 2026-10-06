package com.citygas.network.dto;

import java.util.List;

import com.citygas.network.entity.InspectionBatch;

import lombok.Getter;
import lombok.Setter;

/** 批次详情：批次本体 + 各关联对象最新结果 + 全部隐患（含处理历史） */
@Getter
@Setter
public class BatchDetailVO {

    private InspectionBatch batch;
    private List<TargetResultVO> targets;
    private List<HazardVO> hazards;
    /** 尚未闭环（未通过）的隐患数；为 0 才允许申请复核/关闭 */
    private long openHazardCount;
}
