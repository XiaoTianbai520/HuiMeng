package cn.huimeng.dao.entity;

/** 账号持久化记录；与对外账号 DTO 分离。 */
public record UserDO(
    String id,
    String username,
    String passwordHash,
    String status,
    String platformRole,
    long authVersion) {
  @Override
  public String toString() {
    return "UserDO[redacted]";
  }
}
