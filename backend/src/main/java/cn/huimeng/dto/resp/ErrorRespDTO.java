package cn.huimeng.dto.resp;

import java.util.Map;

/** 统一错误响应；不反射输入、SQL、路径或异常详情。 */
public record ErrorRespDTO(
    String code, String message, String requestId, Map<String, Object> details) {}
