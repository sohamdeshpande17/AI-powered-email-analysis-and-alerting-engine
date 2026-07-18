package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit record (BRD FR-AUDIT-01). The database blocks UPDATE/DELETE
 * via triggers; this entity is only ever inserted. It lives in the acting
 * workspace's schema (schema-per-tenant), so the audit trail is isolated by
 * schema and carries no {@code tenant_id}. {@code ip_address} is intentionally
 * not mapped and stays NULL in this build.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    /** Surrogate PK — DB identity sequence. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long eventId;

    /** Id of the user who performed the action ({@code "user".user_id}); null = system. */
    public UUID actorUserId;

    /** Action code, e.g. FORWARD | CLOSE | RECLASSIFY | USER_CREATE. */
    public String action;

    /** Type of the affected entity, e.g. {@code circular} | {@code team}. */
    public String entityType;

    /** Business id of the affected entity (string form). */
    public String entityId;

    /** Optional pre-change state snapshot; JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    public String beforeState;

    /** Optional post-change state snapshot; JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    public String afterState;

    /** Human-readable summary / reason for the event. */
    public String reason;

    /** Originating user agent, when captured. */
    public String userAgent;

    /** When the event occurred; defaulted to now on insert. */
    public Instant occurredAt;

    /** Default {@code occurredAt} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
