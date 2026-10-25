package cn.huimeng.security;

/** 已通过哈希凭证与有效项目验证的外部客户端身份。 */
public record ExternalActor(String clientId, String projectId) {}
