package cn.huimeng.dto.resp;

/** 账号安全视图；不包含密码哈希和会话版本。 */
public record UserRespDTO(String id, String username, String role, String status) {}
