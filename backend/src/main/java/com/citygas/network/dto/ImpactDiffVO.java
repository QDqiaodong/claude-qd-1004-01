package com.citygas.network.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 影响差异提示：检修单发布后，冻结快照与当前拓扑 / 档案的偏差。
 * 只提示、绝不改写已发布范围。
 */
@Getter
@Setter
public class ImpactDiffVO {

    /** ADDED 当前拓扑新增未纳入 / REMOVED 快照对象已偏离当前拓扑 / UNAVAILABLE 快照对象已停用或删除 */
    private String diffType;

    /** GATE_STATION / REGULATOR_STATION / GAS_USER / PIPE_SEGMENT */
    private String objectType;

    private String objectCode;

    private String objectName;

    private String message;

    public ImpactDiffVO() {
    }

    public ImpactDiffVO(String diffType, String objectType, String objectCode, String objectName, String message) {
        this.diffType = diffType;
        this.objectType = objectType;
        this.objectCode = objectCode;
        this.objectName = objectName;
        this.message = message;
    }
}
