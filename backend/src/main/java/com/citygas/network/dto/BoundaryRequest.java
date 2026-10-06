package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 人工边界对象入参：自动拓扑无法判定的边界门站 / 调压站 / 燃气用户，
 * 必须填写人工说明，便于核对与追溯。
 */
@Getter
@Setter
public class BoundaryRequest {

    @NotBlank(message = "边界对象类型不能为空")
    private String objectType;

    @NotNull(message = "边界对象 ID 不能为空")
    private Long objectId;

    @NotBlank(message = "人工说明不能为空：无法自动判定的边界对象必须填写说明")
    @Size(max = 512, message = "人工说明最长 512 个字符")
    private String note;

    @NotNull(message = "版本号不能为空，请刷新后携带最新版本再操作")
    private Long version;
}
