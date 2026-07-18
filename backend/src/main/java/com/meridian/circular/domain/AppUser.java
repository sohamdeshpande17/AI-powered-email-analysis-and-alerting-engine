package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A provisioned application user, belonging to exactly one workspace
 * ({@code tenant_id}) with exactly one role (BRD FR-USER-01). The same person
 * may exist as separate rows in different workspaces; uniqueness is per tenant
 * ({@code UNIQUE (tenant_id, email)} and {@code UNIQUE (tenant_id, azure_oid)}).
 *
 * <p>Authentication lookups (by {@link #azureOid} / {@link #email}) are
 * deliberately <em>cross-tenant</em>: at SSO sign-in the tenant is not yet known,
 * so the user is resolved first and {@link #tenantId} then establishes the acting
 * workspace for the rest of the session.
 *
 * <p>v3: table renamed {@code app_user → "user"} (a reserved word — hence the
 * quoting) and {@code role_code → role_id} (lowercase role ids).
 *
 * @see TenantAudited
 * @see Role
 */
@Entity
@Table(name = "\"user\"")
public class AppUser extends TenantAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID userId;

    /** Microsoft Entra ID object id (stable); backfilled on first SSO sign-in. */
    public String azureOid;

    /** Sign-in email address (unique within the workspace). */
    public String email;

    /** Display name shown in the UI. */
    public String displayName;

    /** Role id ({@code role.id}) — admin | compliance_head | compliance_officer | auditor_readonly. */
    public String roleId;

    /** Whether the account is active and allowed to sign in. */
    public boolean isActive = true;

    /** Timestamp of the user's last successful sign-in. */
    public Instant lastLoginAt;
}
