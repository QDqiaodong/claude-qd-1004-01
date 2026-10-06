package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 编辑检修单入参（仅「待发布」可编辑）：全量替换基本信息与检修范围，必须携带最新版本 */
@Getter
@Setter
public class MaintUpdateRequest {

    @NotNull(message = "计划开始时间不能为空")
    private LocalDateTime planStartAt;

    private LocalDateTime planEndAt;

    @NotBlank(message = "施工负责人不能为空")
    @Size(max = 32, message = "施工负责人最长 32 个字符")
    private String leaderName;

    @NotBlank(message = "检修范围说明不能为空")
    @Size(max = 512, message = "检修范围说明最长 512 个字符")
    private String scopeRemark;

    @NotEmpty(message = "至少选择一段待修管段")
    private List<@NotNull(message = "管段 ID 不能为空") Long> segmentIds;

    @NotNull(message = "版本号不能为空，请刷新后携带最新版本再操作")
    private Long version;
}
