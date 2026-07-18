package com.meridian.circular.repo;

import com.meridian.circular.domain.PlatformAdmin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the {@link PlatformAdmin} (super admin) master in
 * {@code public.platform_admin}. Used by the password login flow and the
 * super-admin self-management endpoints.
 */
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

    Optional<PlatformAdmin> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    long countByIsActiveTrue();
}
