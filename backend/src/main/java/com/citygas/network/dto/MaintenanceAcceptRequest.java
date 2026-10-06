package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 管段验收入参：验收通过 / 验收不通过 + 验收意见（不通过时必须说明原因） */
@Getter
@Setter
public class MaintenanceAcceptRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;

    @NotNull(message = "验收结论不能为空")
    private Boolean passed;

    /** 验收意见 / 不通过原因 */
    @Size(max = 512, message = "验收意见最长 512 个字符")
    private String note;

    private String requestId;
}
