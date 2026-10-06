package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 检修单状态动作入参：停气 / 开工 / 完工 / 申请复气 / 复气放行等。
 * 每个写动作必须留操作人；{@code requestId} 用于网络重试幂等。
 */
@Getter
@Setter
public class MaintenanceActionRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;

    /** 网络重试幂等键：同租户同 requestId 重试回放首次结果 */
    private String requestId;
}
