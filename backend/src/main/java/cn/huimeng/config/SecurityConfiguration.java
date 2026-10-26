package cn.huimeng.config;

import cn.huimeng.common.exception.GlobalExceptionHandler;
import cn.huimeng.security.BoundaryFilter;
import cn.huimeng.service.AuditService;
import cn.huimeng.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/** Explicit platform API allowlist; no creative wildcard and no browser Bearer authentication. */
@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    var repository = new HttpSessionCsrfTokenRepository();
    repository.setHeaderName("X-CSRF-Token");
    return repository;
  }

  @Bean
  SecurityFilterChain browser(
      HttpSecurity http,
      UserService users,
      AuditService audit,
      HttpSessionCsrfTokenRepository csrf,
      @Value("${huimeng.public-origin}") String origin)
      throws Exception {
    http.securityContext(context -> context.requireExplicitSave(true));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
    http.requestCache(cache -> cache.disable());
    http.formLogin(login -> login.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable());
    http.addFilterBefore(new BoundaryFilter(users, audit, origin), CsrfFilter.class);
    http.csrf(
        config ->
            config
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    http.exceptionHandling(
        config ->
            config
                .authenticationEntryPoint(
                    (req, res, ex) ->
                        GlobalExceptionHandler.write(req, res, 401, "UNAUTHENTICATED", "请先登录"))
                .accessDeniedHandler(
                    (req, res, ex) ->
                        GlobalExceptionHandler.write(
                            req,
                            res,
                            403,
                            ex instanceof org.springframework.security.web.csrf.CsrfException
                                ? "CSRF_INVALID"
                                : "CAPABILITY_FORBIDDEN",
                            "请求未获授权")));
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                .permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/auth/me",
                    "/api/v1/projects",
                    "/api/v1/projects/*",
                    "/api/v1/preferences",
                    "/api/v1/models",
                    "/api/v1/projects/*/media",
                    "/api/v1/projects/*/media/*/content",
                    "/api/v1/projects/*/runs/*",
                    "/api/v1/projects/*/creative/workspaces/files/read",
                    "/api/v1/projects/*/creative/workspaces/files/list",
                    "/api/v1/projects/*/creative/agent/get",
                    "/api/v1/projects/*/creative/agent/list",
                    "/api/v1/projects/*/creative/agent/skills",
                    "/api/v1/capabilities/nodes/get",
                    "/api/v1/capabilities/tools/get",
                    "/api/v1/capabilities/tools/renderers",
                    "/api/v1/capabilities/ffmpeg/status")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                .authenticated()
                .requestMatchers(
                    HttpMethod.PUT,
                    "/api/v1/preferences",
                    "/api/v1/projects/*/creative/workspaces/files/write")
                .authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/media")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/projects/*/creative/workspaces/files/mkdir",
                    "/api/v1/projects/*/creative/workspaces/files/copy",
                    "/api/v1/projects/*/creative/workspaces/files/rename")
                .authenticated()
                .requestMatchers(
                    HttpMethod.DELETE,
                    "/api/v1/projects/*/creative/workspaces/files/remove",
                    "/api/v1/projects/*/creative/agent/message")
                .authenticated()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/projects/*/creative/ai/media/generate",
                    "/api/v1/projects/*/creative/agent",
                    "/api/v1/projects/*/creative/agent/create",
                    "/api/v1/projects/*/creative/agent/cancel",
                    "/api/v1/projects/*/creative/agent/answer",
                    "/api/v1/projects/*/creative/agent/canvasResult",
                    "/api/v1/projects/*/creative/ffmpeg/execute",
                    "/api/v1/projects/*/runs/*/cancel")
                .authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/v1/projects/*/creative/agent/rename")
                .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/*/media/*")
                .authenticated()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/admin/users",
                    "/api/v1/admin/providers",
                    "/api/v1/admin/models",
                    "/api/v1/admin/external-clients",
                    "/api/v1/admin/plugin-market/status",
                    "/api/v1/admin/configuration/export",
                    "/api/v1/projects/*/members")
                .hasRole("ADMIN")
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/admin/users",
                    "/api/v1/projects",
                    "/api/v1/projects/*/members",
                    "/api/v1/admin/external-clients",
                    "/api/v1/admin/external-clients/*/revoke")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*", "/api/v1/projects/*")
                .hasRole("ADMIN")
                .requestMatchers(
                    HttpMethod.PUT,
                    "/api/v1/admin/providers/*/secret",
                    "/api/v1/admin/models/approval",
                    "/api/v1/admin/plugin-market/secret")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/*/members/*")
                .hasRole("ADMIN")
                .anyRequest()
                .denyAll());
    return http.build();
  }
}
