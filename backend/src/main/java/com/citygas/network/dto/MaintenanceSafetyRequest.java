package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 受影响用户安全确认入参：逐用户确认（如入户安检、阀门状态确认） */
@Getter
@Setter
public class MaintenanceSafetyRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;

    @Size(max = 512, message = "确认说明最长 512 个字符")
    private String note;

    private String requestId;
}
