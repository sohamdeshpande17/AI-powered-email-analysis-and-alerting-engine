package com.meridian.circular.security;

import com.meridian.circular.domain.AppUser;
import java.util.UUID;

/**
 * The reserved identity a super admin uses when acting inside a department —
 * for cross-tenant user management and god-mode workflow actions. A fixed
 * sentinel UUID is stamped as the actor on audit / workflow rows (those columns
 * carry no FK), and audit/history name resolution renders it as
 * {@value #DISPLAY_NAME} rather than "Unknown".
 *
 * <p>The {@link #synthetic(Integer)} actor is an in-memory {@link AppUser} — never
 * persisted — so the existing tenant-scoped services accept it unchanged.
 */
public final class SuperAdminActor {

    /** Reserved actor id for super-admin actions inside a department. */
    public static final UUID SENTINEL_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** Display name shown wherever the sentinel actor appears. */
    public static final String DISPLAY_NAME = "Super Admin";

    private SuperAdminActor() {
    }

    /** Whether an actor id is the reserved super-admin sentinel. */
    public static boolean isSentinel(UUID userId) {
        return SENTINEL_ID.equals(userId);
    }

    /**
     * An in-memory {@link AppUser} representing the super admin acting in the
     * given department. Carries the sentinel id and the {@code admin} role so
     * tenant-scoped services and audit writes work without a real user row.
     */
    public static AppUser synthetic(Integer tenantId) {
        AppUser u = new AppUser();
        u.userId = SENTINEL_ID;
        u.displayName = DISPLAY_NAME;
        u.email = "superadmin";
        u.roleId = "admin";
        u.isActive = true;
        u.tenantId = tenantId;
        return u;
    }
}
