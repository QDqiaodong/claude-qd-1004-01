package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 责任人提交整改入参。
 *
 * @param note      整改说明，空白直接拒绝
 * @param requestId 网络重试幂等键
 */
@Getter
@Setter
public class RectifyRequest {

    @NotBlank(message = "整改说明不能为空")
    @Size(max = 512, message = "整改说明最长 512 个字符")
    private String note;

    @Size(max = 64, message = "requestId 最长 64 个字符")
    private String requestId;

    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人最长 32 个字符")
    private String operator;
}
