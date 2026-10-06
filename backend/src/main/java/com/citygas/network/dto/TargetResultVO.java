package com.citygas.network.dto;

import java.time.LocalDateTime;

import com.citygas.network.entity.InspectionTarget;

import lombok.Getter;
import lombok.Setter;

/** 关联对象及其最新巡检结果（详情页「各对象最新结果」） */
@Getter
@Setter
public class TargetResultVO {

    private Long linkId;
    private String targetType;
    private Long targetId;
    private String targetCode;
    private String targetName;
    private Integer roundNo;
    private String latestResult;
    private LocalDateTime checkedAt;

    public static TargetResultVO of(InspectionTarget t) {
        TargetResultVO v = new TargetResultVO();
        v.linkId = t.getId();
        v.targetType = t.getTargetType();
        v.targetId = t.getTargetId();
        v.targetCode = t.getTargetCode();
        v.targetName = t.getTargetName();
        v.roundNo = t.getRoundNo();
        v.latestResult = t.getLatestResult();
        v.checkedAt = t.getCheckedAt();
        return v;
    }
}
