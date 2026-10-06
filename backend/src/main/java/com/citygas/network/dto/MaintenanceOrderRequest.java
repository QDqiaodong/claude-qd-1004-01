package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建 / 编辑检修单入参。
 *
 * <p>检修范围是一组待修管段 {@code segmentIds}；门站 / 调压站 / 燃气用户
 * 由后端沿管段起止点自动关联，不由前端直接传入。{@code manualBoundaries}
 * 承载无法自动判定、必须人工说明的边界对象。</p>
 */
@Getter
@Setter
public class MaintenanceOrderRequest {

    @NotBlank(message = "检修单编号不能为空")
    @Size(max = 40, message = "检修单编号最长 40 个字符")
    private String orderNo;

    @NotNull(message = "计划开始时间不能为空")
    private LocalDateTime planStartAt;

    private LocalDateTime planEndAt;

    @NotBlank(message = "施工负责人不能为空")
    @Size(max = 32, message = "施工负责人最长 32 个字符")
    private String ownerName;

    @Size(max = 512, message = "检修说明最长 512 个字符")
    private String remark;

    /** 检修范围：待修管段 ID，至少一条，且必须真实存在、与当前租户一致 */
    @NotEmpty(message = "检修范围至少选择一条待修管段")
    private List<Long> segmentIds;

    /** 人工边界对象：系统无法沿拓扑自动判定时由运营人员补充说明 */
    @Valid
    private List<ManualBoundary> manualBoundaries;

    /** 人工边界说明 */
    @Getter
    @Setter
    public static class ManualBoundary {

        @NotBlank(message = "边界对象编号/标识不能为空")
        @Size(max = 64, message = "边界对象编号最长 64 个字符")
        private String code;

        @NotBlank(message = "边界对象名称/位置不能为空")
        @Size(max = 128, message = "边界对象名称最长 128 个字符")
        private String name;

        @NotBlank(message = "边界说明不能为空")
        @Size(max = 512, message = "边界说明最长 512 个字符")
        private String note;
    }
}
