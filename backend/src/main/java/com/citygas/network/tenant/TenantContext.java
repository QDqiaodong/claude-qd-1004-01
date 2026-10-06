package com.citygas.network.tenant;

/**
 * 当前租户（片区运营所）上下文。
 *
 * <p>本仓的后端路线是 Hibernate 6 的 {@code @TenantId} 鉴别器多租户：
 * 查询会自动带上租户条件、写入会自动填租户，租户值就从这个 ThreadLocal 取。</p>
 */
public final class TenantContext {

    /** 默认租户：城东运营所 */
    public static final String DEFAULT_TENANT = "A片区";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenant) {
        CURRENT.set(tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant.trim());
    }

    public static String get() {
        String v = CURRENT.get();
        return v == null ? DEFAULT_TENANT : v;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
