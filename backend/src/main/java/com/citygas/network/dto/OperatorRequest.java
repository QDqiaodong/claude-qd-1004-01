package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 操作人入参：状态推进 / 关闭等写操作必须留操作人 */
@Getter
@Setter
public class OperatorRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;
}
