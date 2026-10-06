package com.citygas.network.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.citygas.network.entity.HazardHistory;
import com.citygas.network.entity.InspectionHazard;

import lombok.Getter;
import lombok.Setter;

/** 隐患视图：隐患本体 + 完整处理历史（驳回/再整改留痕） */
@Getter
@Setter
public class HazardVO {

    private Long id;
    private Long batchId;
    private String targetType;
    private Long targetId;
    private Integer roundNo;
    private String problem;
    private String riskLevel;
    private String rectifyRequire;
    private String ownerName;
    private LocalDateTime dueAt;
    private String status;
    private String rectifyNote;
    private LocalDateTime rectifiedAt;
    private LocalDateTime reviewedAt;
    private String reviewedBy;
    private LocalDateTime createdAt;
    private List<HistoryVO> history;

    @Getter
    @Setter
    public static class HistoryVO {
        private Long id;
        private String action;
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String note;
        private LocalDateTime createdAt;

        public static HistoryVO of(HazardHistory h) {
            HistoryVO v = new HistoryVO();
            v.id = h.getId();
            v.action = h.getAction();
            v.fromStatus = h.getFromStatus();
            v.toStatus = h.getToStatus();
            v.operatorName = h.getOperatorName();
            v.note = h.getNote();
            v.createdAt = h.getCreatedAt();
            return v;
        }
    }

    public static HazardVO of(InspectionHazard h, List<HazardHistory> history) {
        HazardVO v = new HazardVO();
        v.id = h.getId();
        v.batchId = h.getBatchId();
        v.targetType = h.getTargetType();
        v.targetId = h.getTargetId();
        v.roundNo = h.getRoundNo();
        v.problem = h.getProblem();
        v.riskLevel = h.getRiskLevel();
        v.rectifyRequire = h.getRectifyRequire();
        v.ownerName = h.getOwnerName();
        v.dueAt = h.getDueAt();
        v.status = h.getStatus();
        v.rectifyNote = h.getRectifyNote();
        v.rectifiedAt = h.getRectifiedAt();
        v.reviewedAt = h.getReviewedAt();
        v.reviewedBy = h.getReviewedBy();
        v.createdAt = h.getCreatedAt();
        v.history = history.stream().map(HistoryVO::of).toList();
        return v;
    }
}
