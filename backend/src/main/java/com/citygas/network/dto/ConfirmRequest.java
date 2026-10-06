package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 受影响用户安全确认入参：复气放行前，影响清单里的燃气用户必须逐户确认完成 */
@Getter
@Setter
public class ConfirmRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;
}
