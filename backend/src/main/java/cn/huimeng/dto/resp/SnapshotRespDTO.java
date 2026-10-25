package cn.huimeng.dto.resp;

/** 原文件文本快照；revision 为原字节 SHA-256，不改写 BOM 或换行。 */
public record SnapshotRespDTO(String text, String encoding, String revision) {}
