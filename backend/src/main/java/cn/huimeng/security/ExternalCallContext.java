package cn.huimeng.security;

/**
 * 外部客户端在本次请求内的项目绑定。
 *
 * <p>MCP/A2A 没有员工会话，业务服务仍要经过统一的成员校验；这里让 {@code ProjectService} 在“凭证已绑定同一项目”时放行，
 * 而不是给外部客户端伪造成员关系或直接绕过校验。异步执行线程必须由调用方显式绑定，请求结束即清除。
 */
public final class ExternalCallContext {
  private static final ThreadLocal<ExternalActor> CURRENT = new ThreadLocal<>();

  private ExternalCallContext() {}

  public static void bind(ExternalActor actor) {
    CURRENT.set(actor);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static ExternalActor current() {
    return CURRENT.get();
  }

  /** 只对凭证自身绑定的项目放行，其他项目一律仍按无权处理。 */
  public static boolean allows(String projectId) {
    ExternalActor actor = CURRENT.get();
    return actor != null && actor.projectId().equals(projectId);
  }
}
