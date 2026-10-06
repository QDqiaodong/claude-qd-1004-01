package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 创建巡检批次入参 */
@Getter
@Setter
public class BatchCreateRequest {

    @NotBlank(message = "批次编号不能为空")
    @Size(max = 40, message = "批次编号最长 40 个字符")
    private String batchNo;

    @NotNull(message = "计划开始时间不能为空")
    private LocalDateTime planStartAt;

    private LocalDateTime planEndAt;

    @NotBlank(message = "负责人不能为空")
    @Size(max = 32, message = "负责人最长 32 个字符")
    private String ownerName;

    @NotBlank(message = "巡检说明不能为空")
    @Size(max = 512, message = "巡检说明最长 512 个字符")
    private String remark;

    /** 关联对象，至少一个，且必须真实存在、与当前租户一致 */
    @NotEmpty(message = "至少关联一个巡检对象")
    @Valid
    private List<TargetRef> targets;

    /** 关联对象引用 */
    @Getter
    @Setter
    public static class TargetRef {

        @NotBlank(message = "对象类型不能为空")
        private String targetType;

        @NotNull(message = "对象 ID 不能为空")
        private Long targetId;
    }
}
