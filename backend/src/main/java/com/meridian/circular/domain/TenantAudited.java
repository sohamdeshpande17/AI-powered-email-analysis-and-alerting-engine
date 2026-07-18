package com.meridian.circular.domain;

import com.meridian.circular.security.TenantContext;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * Base class for every tenant-scoped (per-workspace) table: the {@link Audited}
 * columns plus the {@code tenant_id} scoping key. A {@code tenant_id} value is
 * the id of a {@code tenant} (= organisation × business function workspace).
 *
 * <p>On insert, {@link #tenantOnInsert()} stamps {@code tenantId} from the
 * request-scoped {@link TenantContext} when it has not been set explicitly.
 * There is deliberately <strong>no default</strong>: if no tenant has been
 * resolved (i.e. {@code TenantContext.get()} is {@code null}), the column stays
 * {@code null} and the write fails the {@code NOT NULL} constraint — failing
 * loudly instead of silently leaking the row into another workspace.
 *
 * <p>Reads are <em>not</em> filtered here; tenant scoping on reads is applied
 * explicitly by the repositories/services (e.g. {@code findAllByTenantId}). This
 * is a {@link MappedSuperclass}: it contributes the column but is not an entity.
 *
 * @see Audited
 * @see TenantContext
 */
@MappedSuperclass
public abstract class TenantAudited extends Audited {

    /**
     * Owning workspace id ({@code tenant.tenant_id}); the single scoping key on
     * every per-workspace row. Stamped from {@link TenantContext} on insert when
     * not set explicitly; never defaulted.
     */
    public Integer tenantId;

    /** Stamp {@code tenantId} from the current request's tenant when unset. */
    @PrePersist
    void tenantOnInsert() {
        if (tenantId == null) tenantId = TenantContext.get();
    }
}
