package com.meridian.circular.repo;

import com.meridian.circular.domain.TeamMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link TeamMember} (a team's To: recipients). Lookups key on
 * the owning team; the team itself is already tenant-scoped.
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    /** All members of a team ({@code team.team_id}). */
    List<TeamMember> findByTeamId(UUID teamId);

    /** Whether the team already has this member email (case-insensitive dedupe). */
    boolean existsByTeamIdAndEmailAddressIgnoreCase(UUID teamId, String emailAddress);
}
