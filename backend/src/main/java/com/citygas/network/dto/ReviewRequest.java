package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 隐患复核入参。
 *
 * @param passed    true 复核通过；false 驳回（必须填写驳回原因，隐患回到整改中）
 * @param note      复核意见，空白直接拒绝
 * @param requestId 网络重试幂等键
 */
@Getter
@Setter
public class ReviewRequest {

    @NotNull(message = "复核结论不能为空")
    private Boolean passed;

    @NotBlank(message = "复核意见不能为空")
    @Size(max = 512, message = "复核意见最长 512 个字符")
    private String note;

    @Size(max = 64, message = "requestId 最长 64 个字符")
    private String requestId;

    @NotBlank(message = "复核人不能为空")
    @Size(max = 32, message = "复核人最长 32 个字符")
    private String operator;
}
