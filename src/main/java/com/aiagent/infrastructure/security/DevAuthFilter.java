package com.aiagent.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 学习模式下的固定身份过滤器。
 *
 * <p>当 {@code ai.security.auth-enabled=false} 时生效：任何请求都会被赋予固定学习账号
 * {@link #DEV_USERNAME} 的 USER/ADMIN 权限，便于直接调试 RAG 接口而无需登录。</p>
 *
 * <p>该过滤器只做“缺省身份兜底”，不会覆盖已有认证（如携带合法 JWT 时仍以 JWT 主体为准）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "ai.security", name = "auth-enabled", havingValue = "false")
public class DevAuthFilter extends OncePerRequestFilter {

    public static final String DEV_USERNAME = "demo";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !existing.isAuthenticated()) {
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            DEV_USERNAME,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"),
                                    new SimpleGrantedAuthority("ROLE_ADMIN"))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
