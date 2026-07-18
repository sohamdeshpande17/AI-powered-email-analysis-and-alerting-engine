package com.meridian.circular.repo;

import com.meridian.circular.domain.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Team}. The listing finder is tenant-scoped
 * ({@link #findAllByTenantId}) so admin team lists never span workspaces;
 * by-id lookups (CRUD defaults) operate within the acting workspace's UI flow.
 */
public interface TeamRepository extends JpaRepository<Team, UUID> {

    /** All teams in the given workspace. */
    List<Team> findAllByTenantId(Integer tenantId);
}
