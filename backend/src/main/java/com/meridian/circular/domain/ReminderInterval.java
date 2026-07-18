package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A fixed reminder interval owned by the admin Config module, per workspace.
 * Each active row fires once per circular at {@link #daysAfterAction} days
 * relative to the anchor given by {@link #kind}. v2.0.0 supports scheduled
 * reminders only — no manual reminders. Tenant-scoped (extends
 * {@link TenantAudited}); unique per {@code (tenant_id, kind, days_after_action)}.
 *
 * @see Reminder
 */
@Entity
@Table(name = "reminder_interval")
public class ReminderInterval extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID intervalId;

    /** Offset in days from the anchor (see {@link #kind}); non-negative. */
    public Integer daysAfterAction;

    /** Human-readable label shown in the Config UI. */
    public String label;

    /** Whether this interval is active and will fire. */
    public boolean isActive = true;

    /** Display ordering in the Config UI. */
    public Integer sortOrder = 0;

    /**
     * Anchor for {@link #daysAfterAction}: {@code PRE_DUE} fires N days BEFORE
     * due_at, {@code POST_DUE} N days AFTER due_at, {@code POST_ACTION} (legacy)
     * N days after the circular went IN_ACTION.
     */
    public String kind = "POST_ACTION";
}
