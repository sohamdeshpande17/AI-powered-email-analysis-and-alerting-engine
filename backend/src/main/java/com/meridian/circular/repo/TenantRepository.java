package com.meridian.circular.repo;

import com.meridian.circular.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the shared {@link Tenant} master ({@code public.tenant}). Used
 * at sign-in to resolve a user's {@code tenant_id} to its workspace schema name.
 */
public interface TenantRepository extends JpaRepository<Tenant, Integer> {
}
