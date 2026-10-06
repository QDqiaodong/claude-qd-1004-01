package com.citygas.network.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 从请求参数 / 头里取租户：{@code ?tenant=B片区} 或 {@code X-Tenant}。
 * 没有就落到默认租户 A片区。请求结束后必须清掉 ThreadLocal。
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getParameter("tenant");
        if (tenant == null || tenant.isBlank()) {
            tenant = request.getHeader("X-Tenant");
        }
        TenantContext.set(tenant);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
