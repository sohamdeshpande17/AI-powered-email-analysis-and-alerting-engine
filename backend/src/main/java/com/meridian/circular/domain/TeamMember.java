package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A member email address belonging to a {@link Team} — the To: list when a
 * circular is forwarded to that team. Unique per {@code (team_id,
 * email_address)}. Tenant-scoped (extends {@link TenantAudited}).
 *
 * @see Team
 */
@Entity
@Table(name = "team_member")
public class TeamMember extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID memberId;

    /** Owning team id ({@code team.team_id}). */
    public UUID teamId;

    /** Member email address (the forward To: recipient). */
    public String emailAddress;

    /** Optional display name for the member. */
    public String displayName;
}
