package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 管段验收入参：逐段验收，全部通过后检修单自动推进到「待复气」 */
@Getter
@Setter
public class AcceptRequest {

    @NotBlank(message = "验收人不能为空")
    @Size(max = 32, message = "验收人最长 32 个字符")
    private String operator;

    @Size(max = 512, message = "验收意见最长 512 个字符")
    private String note;

    @NotNull(message = "版本号不能为空，请刷新后携带最新版本再操作")
    private Long version;
}
