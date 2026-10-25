package cn.huimeng.dao.entity;

/** 项目持久化记录；工作区映射不得进入公开响应。 */
public record ProjectDO(String id, String name, String status, String workspaceKey) {
  @Override
  public String toString() {
    return "ProjectDO[redacted]";
  }
}
