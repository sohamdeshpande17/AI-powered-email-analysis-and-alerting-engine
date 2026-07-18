package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A routing destination — a named set of email recipients within a workspace
 * (BRD FR-TEAM-01). Tenant-scoped (extends {@link TenantAudited}); team names
 * are unique per workspace ({@code UNIQUE (tenant_id, name)}). Members,
 * default-Cc recipients and feature tags hang off the team via
 * {@link TeamMember}, {@link TeamCc} and {@link TeamFeature}.
 */
@Entity
@Table(name = "team")
public class Team extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID teamId;

    /** Team name (unique within the workspace). */
    public String name;

    /** Description of the team. */
    public String description;

    /** Whether the team is active/selectable for forwarding. */
    public boolean isActive = true;
}
