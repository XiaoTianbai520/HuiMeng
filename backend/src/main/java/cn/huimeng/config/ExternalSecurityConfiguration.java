package cn.huimeng.config;

import cn.huimeng.security.ExternalBoundaryFilter;
import cn.huimeng.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** Protocol ingress is stateless and cannot borrow an employee session. */
@Configuration
public class ExternalSecurityConfiguration {
  @Bean
  @Order(1)
  SecurityFilterChain external(
      HttpSecurity http, ExternalClientService clients, AuditService audit, ObjectMapper json)
      throws Exception {
    http.securityMatcher("/mcp", "/mcp/**", "/a2a", "/a2a/**");
    http.csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable());
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.addFilterBefore(
        new ExternalBoundaryFilter(clients, audit, json), AnonymousAuthenticationFilter.class);
    // 边界过滤器保证只有已认证的外部客户端到达控制器；这里再用认证状态兜底。
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
  }
}
