package cn.huimeng.security;

/** Password-free identity reloaded from persistence on every request. */
public record Actor(String id, String username, String role, String status, long authVersion) {}
