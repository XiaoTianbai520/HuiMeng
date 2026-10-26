package cn.huimeng.dto.resp;

/** 原 Agent 接口的 {code,data,message} 响应包装。 */
public record ApiRespDTO<T>(int code, T data, String message) {
  public static <T> ApiRespDTO<T> ok(T data) {
    return new ApiRespDTO<>(200, data, null);
  }
}
