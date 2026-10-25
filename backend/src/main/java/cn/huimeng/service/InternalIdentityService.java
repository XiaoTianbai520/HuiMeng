package cn.huimeng.service;

/** 私有执行器内部身份；不能由浏览器自报身份或目录替代。 */
public interface InternalIdentityService {
  /** 重新检查成员后签发 20 秒的就绪探针身份，绑定请求方法、路径和空正文摘要。 */
  String issueReadyProbe(String projectId, String requestId);
}
