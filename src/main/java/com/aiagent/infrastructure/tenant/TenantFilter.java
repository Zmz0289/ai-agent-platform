package com.aiagent.infrastructure.tenant;

import com.aiagent.infrastructure.config.AiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从请求头解析租户并绑定到 {@link TenantContext}。
 *
 * <p>请求结束必定清理线程上下文，避免 Web 线程复用导致租户串号。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private final AiProperties aiProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        AiProperties.Tenant config = aiProperties.getTenant();
        if (config == null) {
            config = new AiProperties.Tenant();
        }
        try {
            String tenant;
            try {
                tenant = config.isEnabled()
                        ? TenantContext.resolve(request.getHeader(config.getHeaderName()), config.getDefaultTenant())
                        : TenantContext.resolve(null, config.getDefaultTenant());
            } catch (IllegalArgumentException invalidTenant) {
                // 过滤器在 MVC 异常处理之外，直接返回 400，避免非法租户落到默认租户造成越权。
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, invalidTenant.getMessage());
                return;
            }
            TenantContext.set(tenant);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
