package com.meridian.circular.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * Audit base for the tables written unattended by the processor pipeline — the
 * shared raw-layer {@code raw_circular_document} and the summarized
 * {@code circular}. Identical to {@link Audited} except the {@code *_by} columns
 * are free text (VARCHAR) rather than a {@code user_id} UUID: these rows are
 * created by the pipeline, not a human, so they are stamped with the literal
 * actor {@code "System"} (see {@code processor.db_writer.SYSTEM_ACTOR}).
 *
 * <p>Every other business table keeps the UUID {@link Audited} convention
 * (architecture.md §5.1); only the circular/raw tables diverge.
 */
@MappedSuperclass
public abstract class SystemAudited {

    /** Actor that created the row — the literal {@code "System"} for pipeline writes. */
    public String createdBy;

    /** Creation timestamp; set once on first persist if not already populated. */
    public Instant createdOn;

    /** Actor that last updated the row — the literal {@code "System"} for pipeline writes. */
    public String updatedBy;

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
