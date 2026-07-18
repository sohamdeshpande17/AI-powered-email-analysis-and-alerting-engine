package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Captures a Compliance override of the LLM output - feedback for model tuning
 * (FR-ENH-01). One row per changed field. Lives in the acting workspace's schema
 * (schema-per-tenant): no {@code tenant_id}, references the circular by
 * {@code circular_no} within the schema.
 *
 * @see Circular
 */
@Entity
@Table(name = "reclassification")
public class Reclassification extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID reclassificationId;

    /** Owning circular number ({@code circular.circular_no}, same schema). */
    public String circularNo;

    /** Id of the user who made the change ({@code "user".user_id}). */
    public UUID changedBy;

    /** When the change was made; defaulted to now on insert. */
    public Instant changedAt;

    /** Field changed - category | urgency | referred_id | due_date. */
    public String field;

    /** Kind of change - add | remove | change. */
    public String action;

    /** Value before the change (string form); null for an add. */
    public String beforeValue;

    /** Value after the change (string form); null for a remove. */
    public String afterValue;

    /** Optional reason supplied by the officer. */
    public String reason;

    /** Default {@link #changedAt} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (changedAt == null) changedAt = Instant.now();
    }
}
