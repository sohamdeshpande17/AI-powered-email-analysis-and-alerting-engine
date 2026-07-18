package com.meridian.circular.repo;

import com.meridian.circular.domain.TeamCc;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link TeamCc} (a team's default Cc recipients). Lookups key on
 * the owning team; the team itself is already tenant-scoped.
 */
public interface TeamCcRepository extends JpaRepository<TeamCc, UUID> {

    /** All default-Cc recipients of a team ({@code team.team_id}). */
    List<TeamCc> findByTeamId(UUID teamId);

    /** Whether the team already has this Cc email (case-insensitive dedupe). */
    boolean existsByTeamIdAndEmailAddressIgnoreCase(UUID teamId, String emailAddress);
}
