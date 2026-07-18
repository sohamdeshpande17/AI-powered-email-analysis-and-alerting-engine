package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A default Cc recipient for a {@link Team} — added to the Cc: line on every
 * forward to that team. Sourced from the Microsoft Graph directory, so it can be
 * an individual user OR a distribution list / group address (see {@link #ccType}).
 * Unique per {@code (team_id, email_address)}. Tenant-scoped (extends
 * {@link TenantAudited}).
 *
 * @see Team
 */
@Entity
@Table(name = "team_cc")
public class TeamCc extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID ccId;

    /** Owning team id ({@code team.team_id}). */
    public UUID teamId;

    /** Cc email address (person or distribution-list/group address). */
    public String emailAddress;

    /** Optional display name for the Cc recipient. */
    public String displayName;

    /** Whether this Cc address is a person or a distribution list — USER | GROUP. */
    public String ccType = "USER";
}
