package com.meridian.circular.repo;

import com.meridian.circular.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link AppUser}. The lookup finders ({@link #findByEmailIgnoreCase},
 * {@link #findByAzureOid}, {@link #existsByEmailIgnoreCase}) are deliberately
 * <strong>cross-tenant</strong> — they back SSO sign-in, where the workspace is
 * not yet known. Listing finders are tenant-scoped ({@link #findAllByTenantId})
 * so admin user lists never span workspaces.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Resolve a user by email, case-insensitive (cross-tenant; SSO/login). */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /** Resolve a user by stable Entra object id (cross-tenant; SSO). */
    Optional<AppUser> findByAzureOid(String azureOid);

    /** All users in the given workspace (tenant-scoped admin list). */
    List<AppUser> findAllByTenantId(Integer tenantId);

    /** Whether a user with this email exists, case-insensitive (cross-tenant). */
    boolean existsByEmailIgnoreCase(String email);

    /** Users holding a given role id ({@code role.id}). */
    List<AppUser> findByRoleId(String roleId);
}
