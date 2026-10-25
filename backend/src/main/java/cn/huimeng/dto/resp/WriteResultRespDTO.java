package cn.huimeng.dto.resp;

/** 保留原写文件成功协议，只返回新修订号。 */
public record WriteResultRespDTO(int code, Revision data, String message) {
  public record Revision(String revision) {}
}
