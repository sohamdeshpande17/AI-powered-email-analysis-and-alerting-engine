package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform-level operator (super admin) in {@code public.platform_admin}.
 * Sits ABOVE all departments: signs in with a username + bcrypt password (not
 * Entra SSO) and administers the whole application — departments, the role
 * catalog, users across every department, other super admins — and may act
 * inside any department's workflow (god-mode). Belongs to no tenant.
 *
 * @see TenantAudited the per-department user counterpart, {@link AppUser}
 */
@Entity
@Table(name = "platform_admin")
public class PlatformAdmin extends Audited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID adminId;

    /** Sign-in username (unique). */
    public String username;

    /** BCrypt password hash ({@code $2a}/{@code $2b}). */
    public String passwordHash;

    /** Display name shown in the admin console. */
    public String displayName;

    /** Whether the account is active and allowed to sign in. */
    public boolean isActive = true;

    /** When true, the next successful login forces a password reset. */
    public boolean mustChangePassword = false;

    /** Timestamp of the last successful sign-in. */
    public Instant lastLoginAt;
}
