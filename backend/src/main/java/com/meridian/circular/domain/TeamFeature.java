package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A descriptive feature tag attached to a {@link Team}. Informational only — it
 * does NOT drive auto-routing. Unique per {@code (team_id, feature_code)}.
 * Tenant-scoped (extends {@link TenantAudited}).
 *
 * @see Team
 */
@Entity
@Table(name = "team_feature")
public class TeamFeature extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID featureId;

    /** Owning team id ({@code team.team_id}). */
    public UUID teamId;

    /** Feature tag code (upper-case, e.g. {@code LIQUIDITY}). */
    public String featureCode;
}
