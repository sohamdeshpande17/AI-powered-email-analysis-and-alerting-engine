package com.meridian.circular.repo;

import com.meridian.circular.domain.Role;
import com.meridian.circular.domain.RoleId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the per-department {@link Role} catalog (composite key
 * {@code (tenantId, id)}). Roles are scoped to a department, so every lookup
 * carries the {@code tenantId}.
 */
public interface RoleRepository extends JpaRepository<Role, RoleId> {

    List<Role> findByTenantId(Integer tenantId);

    Optional<Role> findByTenantIdAndId(Integer tenantId, String id);

    boolean existsByTenantIdAndId(Integer tenantId, String id);
}
