package com.citygas.network.dto;

import java.util.List;

import com.citygas.network.entity.MaintenanceOrder;

import lombok.Getter;
import lombok.Setter;

/**
 * 检修单详情：检修单本体 + 影响清单（含差异提示）+ 汇总计数。
 *
 * <p>差异汇总按级别区分：{@code blockCount} 为发布拦截项（停用/跨租户/拓扑冲突），
 * {@code warnCount} 为仅提示项（档案名称状态变更等）。冻结后任何差异都只提示、不改写。</p>
 */
@Getter
@Setter
public class MaintenanceDetailVO {

    private MaintenanceOrder order;
    private List<MaintenanceImpactVO> impacts;

    /** 影响对象按类型计数 */
    private long segmentCount;
    private long gateCount;
    private long regulatorCount;
    private long userCount;
    private long manualCount;

    /** 未验收通过的管段数 / 未完成安全确认的燃气用户数（复气放行双条件） */
    private long pendingAcceptanceCount;
    private long pendingSafetyCount;

    /** 差异提示汇总 */
    private long blockCount;
    private long warnCount;

    /** 检修单级别差异（当前拓扑新增关联对象等，不属于任何单行） */
    private List<MaintenanceImpactVO.DiffItem> orderLevelDiffs = new java.util.ArrayList<>();

    public static MaintenanceDetailVO of(MaintenanceOrder order, List<MaintenanceImpactVO> impacts) {
        MaintenanceDetailVO vo = new MaintenanceDetailVO();
        vo.order = order;
        vo.impacts = impacts;
        for (MaintenanceImpactVO i : impacts) {
            switch (i.getTargetType()) {
                case "PIPE_SEGMENT" -> vo.segmentCount++;
                case "GATE_STATION" -> vo.gateCount++;
                case "REGULATOR_STATION" -> vo.regulatorCount++;
                case "GAS_USER" -> vo.userCount++;
                case "MANUAL" -> vo.manualCount++;
                default -> { }
            }
            if ("PIPE_SEGMENT".equals(i.getTargetType())
                    && !"验收通过".equals(i.getAcceptanceStatus())) {
                vo.pendingAcceptanceCount++;
            }
            if ("GAS_USER".equals(i.getTargetType()) && !"已确认".equals(i.getSafetyStatus())) {
                vo.pendingSafetyCount++;
            }
            for (MaintenanceImpactVO.DiffItem d : i.getDiffs()) {
                if ("BLOCK".equals(d.getLevel())) {
                    vo.blockCount++;
                } else {
                    vo.warnCount++;
                }
            }
        }
        return vo;
    }
}
