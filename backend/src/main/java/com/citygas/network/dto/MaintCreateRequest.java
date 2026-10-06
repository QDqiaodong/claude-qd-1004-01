package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 新建检修单入参：计划时段 + 施工负责人 + 检修范围（待修管段集合） */
@Getter
@Setter
public class MaintCreateRequest {

    @NotBlank(message = "检修单号不能为空")
    @Size(max = 40, message = "检修单号最长 40 个字符")
    private String orderNo;

    @NotNull(message = "计划开始时间不能为空")
    private LocalDateTime planStartAt;

    private LocalDateTime planEndAt;

    @NotBlank(message = "施工负责人不能为空")
    @Size(max = 32, message = "施工负责人最长 32 个字符")
    private String leaderName;

    @NotBlank(message = "检修范围说明不能为空")
    @Size(max = 512, message = "检修范围说明最长 512 个字符")
    private String scopeRemark;

    /** 检修范围：待修管段 id 集合，至少一段，且必须真实存在、与当前租户一致 */
    @NotEmpty(message = "至少选择一段待修管段")
    private List<@NotNull(message = "管段 ID 不能为空") Long> segmentIds;
}
