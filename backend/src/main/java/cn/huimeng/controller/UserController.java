package cn.huimeng.controller;

import cn.huimeng.dto.req.*;
import cn.huimeng.dto.resp.*;
import cn.huimeng.security.RequestContext;
import cn.huimeng.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 管理员账号接口；业务权限与最后管理员约束由服务层再次验证。 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserController {
  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  /** 分页读取账号，不包含认证数据。 */
  @GetMapping
  public PageRespDTO<UserRespDTO> list(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
    return users.list(cursor, limit);
  }

  /** 创建内部账号。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserRespDTO create(
      @Valid @RequestBody CreateUserReqDTO input, HttpServletRequest request) {
    return users.create(input, RequestContext.requestId(request));
  }

  /** 更改角色、启停状态或密码。 */
  @PatchMapping("/{userId}")
  public UserRespDTO update(
      @PathVariable String userId,
      @Valid @RequestBody UpdateUserReqDTO input,
      HttpServletRequest request) {
    return users.update(userId, input, RequestContext.requestId(request));
  }
}
