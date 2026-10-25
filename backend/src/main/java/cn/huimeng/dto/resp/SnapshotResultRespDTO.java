package cn.huimeng.dto.resp;

/** 沿用冻结的文件读取响应 envelope。 */
public record SnapshotResultRespDTO(int code, SnapshotRespDTO data, String message) {}
