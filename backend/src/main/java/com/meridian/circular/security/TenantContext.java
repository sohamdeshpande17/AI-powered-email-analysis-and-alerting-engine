package com.meridian.circular.security;

/**
 * Holds the acting tenant for the current request thread, set by
 * {@link BearerAuthInterceptor} from the verified bearer token:
 * <ul>
 *   <li>{@link #get()} - the workspace id ({@code tenant.tenant_id}), used to
 *       filter the shared, tenant-scoped public tables ("user", team*,
 *       reminder_interval, app_config);</li>
 *   <li>{@link #getSchema()} - the workspace SCHEMA name (lower(tenant.code)),
 *       which the Hibernate multitenancy connection provider applies as the
 *       connection {@code search_path} so the per-tenant tables (circular,
 *       circular_workflow, forwarding, reclassification, reminder,
 *       sla_escalation_log, audit_event, circular_history) resolve to the right
 *       schema automatically.</li>
 * </ul>
 * There is intentionally NO default - a missing schema resolves to {@code public}
 * (the connection provider's fallback), never another workspace.
 */
public final class TenantContext {

    private static final ThreadLocal<Integer> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SCHEMA = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Set the acting tenant id + schema for this request thread. */
    public static void set(Integer tenantId, String schema) {
        TENANT_ID.set(tenantId);
        SCHEMA.set(schema);
    }

    /** The current tenant id, or {@code null} if none has been resolved. */
    public static Integer get() {
        return TENANT_ID.get();
    }

    /** The current workspace schema name, or {@code null} if none has been resolved. */
    public static String getSchema() {
        return SCHEMA.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        SCHEMA.remove();
    }
}
