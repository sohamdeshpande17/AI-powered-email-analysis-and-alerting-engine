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
 * Dedupes IN_ACTION SLA escalations so the worker never double-sends. Lives in
 * the acting workspace's schema (schema-per-tenant): no {@code tenant_id},
 * references the circular by {@code circular_no} within the schema; unique per
 * {@code (circular_no, escalation_type)}.
 *
 * @see Circular
 */
@Entity
@Table(name = "sla_escalation_log")
public class SlaEscalationLog extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID escalationId;

    /** Owning circular number ({@code circular.circular_no}, same schema). */
    public String circularNo;

    /** Escalation type - BREACH_24 | BREACH_48. */
    public String escalationType;

    /** When the escalation was sent; defaulted to now on insert. */
    public Instant sentAt;

    /** Default {@link #sentAt} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (sentAt == null) sentAt = Instant.now();
    }
}
