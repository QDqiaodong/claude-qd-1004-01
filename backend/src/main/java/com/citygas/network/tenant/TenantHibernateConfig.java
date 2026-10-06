package com.citygas.network.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式把 {@link CurrentTenantIdentifierResolver} 装配进 Hibernate，
 * 并声明 DISCRIMINATOR 多租户策略。
 *
 * <p>仅靠 {@code hibernate.multiTenancy} 属性时 Spring Boot 不会自动装配 resolver，
 * 导致 SessionFactory 构建期报 “no tenant identifier specified”。这里用
 * {@link HibernatePropertiesCustomizer} 把 resolver 实例直接塞进 Hibernate 配置，
 * 是最稳妥的接法。</p>
 */
@Configuration
public class TenantHibernateConfig {

    @Bean
    public HibernatePropertiesCustomizer tenantPropertiesCustomizer(CurrentTenantIdentifierResolver<?> resolver) {
        return properties -> {
            properties.put("hibernate.multiTenancy", "DISCRIMINATOR");
            properties.put("hibernate.tenant_identifier_resolver", resolver);
        };
    }
}
