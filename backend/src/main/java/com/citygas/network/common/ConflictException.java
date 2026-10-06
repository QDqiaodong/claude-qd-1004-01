package com.citygas.network.common;

/**
 * 冲突异常（HTTP 409）：典型场景是两名操作员并发关闭同一批次，
 * 只有一人成功，后来者收到明确冲突提示，且不能覆盖先到者的关闭结果。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
