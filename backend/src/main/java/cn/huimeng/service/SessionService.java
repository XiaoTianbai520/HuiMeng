package cn.huimeng.service;

import cn.huimeng.dto.req.LoginReqDTO;
import cn.huimeng.dto.resp.*;
import jakarta.servlet.http.*;

/** 会话业务编排；控制器只绑定请求，不处理限速、会话轮换或审计。 */
public interface SessionService {
  /** 登录时执行限速、密码验证、会话及 CSRF 轮换。 */
  LoginRespDTO login(LoginReqDTO input, HttpServletRequest request, HttpServletResponse response);

  /** 读取当前可信账号。 */
  UserRespDTO currentUser();

  /** 失效当前会话、清除 Cookie 并记录退出。 */
  void logout(HttpServletRequest request, HttpServletResponse response);
}
