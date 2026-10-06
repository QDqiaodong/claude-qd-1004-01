package com.citygas.network.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * 把 {@link TenantContext} 里的租户交给 Hibernate。
 * Spring Boot 会自动发现 {@code CurrentTenantIdentifierResolver} 类型的 bean 并装配。
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.get();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(String tenantId) {
        return false;
    }
}
