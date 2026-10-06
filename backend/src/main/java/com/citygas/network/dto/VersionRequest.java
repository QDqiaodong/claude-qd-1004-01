package com.citygas.network.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 仅校验版本的入参：重新评估影响 / 移除人工边界对象 */
@Getter
@Setter
public class VersionRequest {

    @NotNull(message = "版本号不能为空，请刷新后携带最新版本再操作")
    private Long version;
}
