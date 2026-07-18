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
 * One workflow EVENT on a circular - append-only history (v3 §5.5). Actions:
 * RECEIVED | IN_ACTION | CLOSED | COMMENT. Lives in the acting workspace's
 * schema (schema-per-tenant), so it carries no {@code tenant_id} and references
 * the circular within the same schema by {@code circular_no}. The owning
 * circular's denormalized {@code status} is maintained in the same transaction
 * as each status event.
 *
 * @see Circular
 */
@Entity
@Table(name = "circular_workflow")
public class CircularWorkflow extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID workflowId;

    /** Owning circular number ({@code circular.circular_no}, same schema). */
    public String circularNo;

    /** Event action - RECEIVED | IN_ACTION | CLOSED | COMMENT. */
    public String action;

    /** Id of the acting user ({@code "user".user_id}); null for system/source events. */
    public UUID actedBy;

    /** When the event occurred; defaulted to now on insert. */
    public Instant actedOn;

    /** Free-text comment (closing comment for CLOSED, body for COMMENT). */
    public String comment;

    /** Set when an IN_ACTION event came from a forward ({@code forwarding.forwarding_id}). */
    public UUID forwardingId;

    /** Default {@link #actedOn} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (actedOn == null) actedOn = Instant.now();
    }
}
