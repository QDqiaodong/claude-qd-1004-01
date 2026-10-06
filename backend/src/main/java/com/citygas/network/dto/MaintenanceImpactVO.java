package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.citygas.network.entity.MaintenanceImpact;

import lombok.Getter;
import lombok.Setter;

/**
 * 检修影响清单项 VO：冻结快照字段 + 与当前基础档案/拓扑比对出的差异提示。
 *
 * <p>发布后 {@code targetCode/targetName} 等快照字段永不变更；差异只通过
 * {@code currentXxx} 与 {@code diffs} 提示给运营人员核对，绝不静默改写。</p>
 */
@Getter
@Setter
public class MaintenanceImpactVO {

    private Long id;
    private String targetType;
    private Long targetId;
    private String targetCode;
    private String targetName;
    private String source;
    private String manualNote;

    /** 管段维度：未验收 / 验收通过 / 验收不通过 */
    private String acceptanceStatus;
    private LocalDateTime acceptedAt;
    private String acceptedBy;

    /** 燃气用户维度：未确认 / 已确认 */
    private String safetyStatus;
    private LocalDateTime confirmedAt;
    private String confirmedBy;

    // ---- 与当前基础档案比对（只读提示，不回写快照） ----
    /** 当前档案是否仍在当前租户内可见（false 即已删除或跨租户） */
    private Boolean present;
    /** 档案当前状态（管段/门站/调压站/燃气用户），取不到为 null */
    private String currentStatus;
    private String currentCode;
    private String currentName;
    /** 差异明细（档案停用 / 已删除 / 跨租户 / 信息变更 / 拓扑脱钩） */
    private List<DiffItem> diffs;

    /** 单条差异：level=BLOCK 发布拦截项；WARN 仅提示，不改变冻结范围 */
    @Getter
    @Setter
    public static class DiffItem {
        private String level;
        private String kind;
        private String message;

        public DiffItem() {
        }

        public DiffItem(String level, String kind, String message) {
            this.level = level;
            this.kind = kind;
            this.message = message;
        }
    }

    public static MaintenanceImpactVO of(MaintenanceImpact i) {
        MaintenanceImpactVO v = new MaintenanceImpactVO();
        v.id = i.getId();
        v.targetType = i.getTargetType();
        v.targetId = i.getTargetId();
        v.targetCode = i.getTargetCode();
        v.targetName = i.getTargetName();
        v.source = i.getSource();
        v.manualNote = i.getManualNote();
        v.acceptanceStatus = i.getAcceptanceStatus();
        v.acceptedAt = i.getAcceptedAt();
        v.acceptedBy = i.getAcceptedBy();
        v.safetyStatus = i.getSafetyStatus();
        v.confirmedAt = i.getConfirmedAt();
        v.confirmedBy = i.getConfirmedBy();
        v.diffs = new java.util.ArrayList<>();
        return v;
    }
}
