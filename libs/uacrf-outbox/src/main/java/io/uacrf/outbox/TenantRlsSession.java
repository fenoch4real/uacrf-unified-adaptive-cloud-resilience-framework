package io.uacrf.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Sets transaction-local PostgreSQL Row-Level Security (RLS) session parameter `app.tenant_id`
 * so PostgreSQL enforces tenant isolation natively in addition to application query filters.
 */
public final class TenantRlsSession {

    private TenantRlsSession() {
    }

    public static void bindTenant(JdbcTemplate jdbc, UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null when binding RLS context");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    public static void clear(JdbcTemplate jdbc) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', '', true)", String.class);
    }
}
