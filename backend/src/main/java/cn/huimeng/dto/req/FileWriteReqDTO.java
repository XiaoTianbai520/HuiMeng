package cn.huimeng.dto.req;

/** 原格式保存参数；请求体单独限量读取，项目和操作者来自可信会话。 */
public record FileWriteReqDTO(
    String path, String expectedRevision, boolean exclusive, String encoding, long byteLength) {}
