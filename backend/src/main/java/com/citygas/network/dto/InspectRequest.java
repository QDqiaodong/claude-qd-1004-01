package com.citygas.network.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 对象巡检登记入参。
 *
 * @param result    本次结果：正常 / 隐患
 * @param requestId 网络重试幂等键（同租户内唯一）；为空时由服务端按业务键兜底
 */
@Getter
@Setter
public class InspectRequest {

    @NotBlank(message = "巡检结果不能为空")
    private String result;

    /** 结果为「隐患」时必填的隐患信息 */
    private HazardInfo hazard;

    @Size(max = 64, message = "requestId 最长 64 个字符")
    private String requestId;

    @NotBlank(message = "巡检人不能为空")
    @Size(max = 32, message = "巡检人最长 32 个字符")
    private String operator;

    /** 隐患登记信息 */
    @Getter
    @Setter
    public static class HazardInfo {

        @NotBlank(message = "隐患问题描述不能为空")
        @Size(max = 512, message = "问题描述最长 512 个字符")
        private String problem;

        @NotBlank(message = "风险等级不能为空")
        private String riskLevel;

        @NotBlank(message = "整改要求不能为空")
        @Size(max = 512, message = "整改要求最长 512 个字符")
        private String rectifyRequire;

        @NotBlank(message = "责任人不能为空")
        @Size(max = 32, message = "责任人最长 32 个字符")
        private String ownerName;

        @NotNull(message = "整改期限不能为空")
        private LocalDateTime dueAt;
    }
}
