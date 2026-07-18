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
 * A circular forwarded to one team by email (BRD FR-FWD-04) - the action that
 * moves a circular RECEIVED to IN_ACTION. Lives in the acting workspace's schema
 * (schema-per-tenant): no {@code tenant_id}, references the circular by
 * {@code circular_no} within the schema; unique per {@code (circular_no, team_id)}.
 *
 * @see Circular
 */
@Entity
@Table(name = "forwarding")
public class Forwarding extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID forwardingId;

    /** Owning circular number ({@code circular.circular_no}, same schema). */
    public String circularNo;

    /** Destination team id ({@code public.team.team_id}). */
    public UUID teamId;

    /** Id of the user who forwarded ({@code "user".user_id}). */
    public UUID forwardedBy;

    /** When the forward was made; defaulted to now on insert. */
    public Instant forwardedAt;

    /** Subject line used on the forward email. */
    public String emailSubject;

    /** Snapshot of the email body sent (for audit/recall). */
    public String emailBodySnapshot;

    /** Send outcome - PENDING | SENT | FAILED | SKIPPED | RECALLED. */
    public String sendStatus = "SENT";

    /** Groups one bulk-assign operation (BRD FR-FWD-05); null for single forwards. */
    public UUID bulkBatchId;

    /**
     * The mandatory "why" supplied by the officer when forwarding. Surfaced on
     * the workflow history timeline.
     */
    public String reason;

    /** Default {@link #forwardedAt} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (forwardedAt == null) forwardedAt = Instant.now();
    }
}
