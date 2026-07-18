package com.meridian.circular.repo;

import com.meridian.circular.domain.TeamFeature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link TeamFeature} (descriptive, non-routing team tags).
 * Lookups key on the owning team; the team itself is already tenant-scoped.
 */
public interface TeamFeatureRepository extends JpaRepository<TeamFeature, UUID> {

    /** All feature tags on a team ({@code team.team_id}). */
    List<TeamFeature> findByTeamId(UUID teamId);

    /** A specific feature tag on a team (dedupe / remove). */
    Optional<TeamFeature> findByTeamIdAndFeatureCode(UUID teamId, String featureCode);
}
