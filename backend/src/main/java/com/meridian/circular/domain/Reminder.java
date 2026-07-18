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
 * A scheduled reminder email sent to a team about a circular. Every reminder row
 * references the {@link ReminderInterval} that fired it. Lives in the acting
 * workspace's schema (schema-per-tenant): no {@code tenant_id}, references the
 * circular by {@code circular_no} within the schema; unique per
 * {@code (circular_no, team_id, interval_id)}.
 *
 * @see ReminderInterval
 * @see Circular
 */
@Entity
@Table(name = "reminder")
public class Reminder extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID reminderId;

    /** Owning circular number ({@code circular.circular_no}, same schema). */
    public String circularNo;

    /** Team the reminder was sent to ({@code public.team.team_id}). */
    public UUID teamId;

    /** Interval that fired this reminder ({@code public.reminder_interval.interval_id}). */
    public UUID intervalId;

    /** Recipient addresses the reminder was sent to. */
    public String sentTo;

    /** Send outcome - SENT | FAILED. */
    public String sendStatus = "SENT";

    /** When the reminder was sent; defaulted to now on insert. */
    public Instant sentAt;

    /** Default {@link #sentAt} to now on first persist. */
    @PrePersist
    void onInsert() {
        if (sentAt == null) sentAt = Instant.now();
    }
}
