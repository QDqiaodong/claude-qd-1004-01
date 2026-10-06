package com.citygas.network.dto;

import java.util.List;

import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.MaintenanceSegment;

import lombok.Getter;
import lombok.Setter;

/**
 * 检修单详情：主单 + 检修范围管段（含验收状态）+ 冻结影响清单 + 影响差异提示。
 * 待发布状态下差异为空（清单本身就可编辑）；发布后差异只提示不改写。
 */
@Getter
@Setter
public class MaintDetailVO {

    private MaintenanceOrder order;
    private List<MaintenanceSegment> segments;
    private List<MaintenanceImpact> impacts;
    private List<ImpactDiffVO> diffs;
    /** 尚未验收通过的管段数；为 0 是复气放行的闸门之一 */
    private long pendingAcceptCount;
    /** 未完成安全确认的受影响用户数；为 0 是复气放行的另一道闸门 */
    private long pendingConfirmCount;
}
