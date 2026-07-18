package com.meridian.circular.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for the standard audit columns carried by every business table
 * (architecture.md §5.1): {@code created_by} / {@code created_on} /
 * {@code updated_by} / {@code updated_on}.
 *
 * <p>This {@link MappedSuperclass} contributes its columns to every concrete
 * entity that extends it; it is not itself an entity and has no table. Two
 * lifecycle callbacks keep the timestamps current:
 * <ul>
 *   <li>{@link #auditedOnInsert()} stamps {@code created_on}/{@code updated_on}
 *       on first persist (only when not already set);</li>
 *   <li>{@link #auditedOnUpdate()} refreshes {@code updated_on} on every update.</li>
 * </ul>
 *
 * <p>The {@code *_by} columns are the acting user's id ({@code "user".user_id})
 * and are set by the service layer; they intentionally carry no FK constraint
 * (system/pipeline writes, and to avoid a tenant↔user FK cycle — architecture.md
 * §5.1).
 *
 * <p>Tenant-scoped tables additionally carry {@code tenant_id} via
 * {@link TenantAudited}. Global masters ({@code role}, {@code source},
 * {@code circular_category}, {@code raw_circular_document}, {@code organization})
 * extend this class directly and carry no tenant column.
 */
@MappedSuperclass
public abstract class Audited {

    /** Id of the user who created the row ({@code "user".user_id}); no FK. */
    public UUID createdBy;

    /** Creation timestamp; set once on first persist if not already populated. */
    public Instant createdOn;

    /** Id of the user who last updated the row ({@code "user".user_id}); no FK. */
    public UUID updatedBy;

    /** Last-modification timestamp; refreshed on every update. */
    public Instant updatedOn;

    /** Stamp creation/modification timestamps on first persist (idempotent). */
    @PrePersist
    void auditedOnInsert() {
        Instant now = Instant.now();
        if (createdOn == null) createdOn = now;
        if (updatedOn == null) updatedOn = now;
    }

    /** Refresh {@code updated_on} on every update. */
    @PreUpdate
    void auditedOnUpdate() {
        updatedOn = Instant.now();
    }
}
