package cn.huimeng.dto.resp;

/** 登录成功的账号视图；会话凭据仅通过安全 Cookie 传输。 */
public record LoginRespDTO(UserRespDTO user) {}
