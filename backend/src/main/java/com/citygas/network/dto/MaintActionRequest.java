package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 检修单状态推进入参（发布 / 停气开工 / 完工报验 / 复气放行）。
 *
 * @param version   详情页携带的乐观锁版本，后端统一校验，不一致即 409
 * @param requestId 网络重试幂等键（发布 / 复气支持）：重试直接回放首次结果
 */
@Getter
@Setter
public class MaintActionRequest {

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;

    @NotNull(message = "版本号不能为空，请刷新后携带最新版本再操作")
    private Long version;

    @Size(max = 64, message = "requestId 最长 64 个字符")
    private String requestId;
}
