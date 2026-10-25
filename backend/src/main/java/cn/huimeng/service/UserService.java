package cn.huimeng.service;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.*;

/** 账号生命周期与可信身份查询；事务内维护最后一名有效管理员约束。 */
public interface UserService {

  /** 是否已完成管理员初始化。 */
  boolean initialized();

  /** 读取安全账号视图。 */
  UserRespDTO get(String id);

  /** 校验密码和账号状态，返回可信内部身份。 */
  Actor authenticate(String username, String password);

  /** 每次会话请求重新读取身份及认证版本；不存在返回空。 */
  Actor findActor(String id);

  /** 通过专用锁行完成首次管理员初始化。 */
  void bootstrap(String username, String password);

  /** 管理员创建账号并追加审计。 */
  UserRespDTO create(CreateUserReqDTO input, String requestId);

  /** 管理员更新账号并使旧会话失效。 */
  UserRespDTO update(String id, UpdateUserReqDTO input, String requestId);

  /** 按游标读取安全账号列表。 */
  PageRespDTO<UserRespDTO> list(String cursor, int limit);
}
