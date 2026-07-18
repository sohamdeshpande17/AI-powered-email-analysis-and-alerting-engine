package com.meridian.circular.config;

import com.meridian.circular.security.TenantContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Supplies Hibernate the current request's workspace schema (from
 * {@link TenantContext}) as the tenant identifier. Falls back to {@code public}
 * when no tenant is resolved (e.g. sign-in endpoints), so shared masters and the
 * canonical {@code public.circular} are always reachable.
 */
public class SchemaTenantResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String PUBLIC = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String schema = TenantContext.getSchema();
        return (schema == null || schema.isBlank()) ? PUBLIC : schema;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
