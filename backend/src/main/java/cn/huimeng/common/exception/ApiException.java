package cn.huimeng.common.exception;

/** Only safe, fixed messages cross the HTTP boundary. */
public class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException invalid() {
    return new ApiException(400, "INVALID_ARGUMENT", "请求参数无效");
  }

  public static ApiException forbidden() {
    return new ApiException(403, "CAPABILITY_FORBIDDEN", "无权执行此操作");
  }
}
