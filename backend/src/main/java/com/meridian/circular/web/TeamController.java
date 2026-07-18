package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.CreateTeamRequest;
import com.meridian.circular.dto.Dtos.TeamCcRequest;
import com.meridian.circular.dto.Dtos.TeamDto;
import com.meridian.circular.dto.Dtos.TeamFeatureRequest;
import com.meridian.circular.dto.Dtos.TeamMemberRequest;
import com.meridian.circular.dto.Dtos.UpdateTeamRequest;
import com.meridian.circular.security.Actor;
import com.meridian.circular.service.TeamService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teams — name + feature tags + member emails. Compliance picks the team on
 * the spot at forward time; there are no standing routing rules.
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    /** List the acting workspace's teams (with members, Cc and features). */
    @GetMapping
    public List<TeamDto> list() {
        return teams.list();
    }

    /** Create a team in the acting workspace. */
    @PostMapping
    public TeamDto create(@RequestBody CreateTeamRequest req, @Actor AppUser actor) {
        return teams.create(req, actor);
    }

    /** Update a team's name / description / active flag. */
    @PatchMapping("/{id}")
    public TeamDto update(@PathVariable UUID id,
                          @RequestBody UpdateTeamRequest req,
                          @Actor AppUser actor) {
        return teams.update(id, req, actor);
    }

    /** Add a member (To: recipient) to a team. */
    @PostMapping("/{id}/members")
    public TeamDto addMember(@PathVariable UUID id,
                             @RequestBody TeamMemberRequest req,
                             @Actor AppUser actor) {
        return teams.addMember(id, req, actor);
    }

    /** Remove a member from a team. */
    @DeleteMapping("/{id}/members/{memberId}")
    public TeamDto removeMember(@PathVariable UUID id,
                                @PathVariable UUID memberId,
                                @Actor AppUser actor) {
        return teams.removeMember(id, memberId, actor);
    }

    /** Add a default Cc recipient (person or distribution list) to a team. */
    @PostMapping("/{id}/cc")
    public TeamDto addCc(@PathVariable UUID id,
                         @RequestBody TeamCcRequest req,
                         @Actor AppUser actor) {
        return teams.addCc(id, req, actor);
    }

    /** Remove a default Cc recipient from a team. */
    @DeleteMapping("/{id}/cc/{ccId}")
    public TeamDto removeCc(@PathVariable UUID id,
                            @PathVariable UUID ccId,
                            @Actor AppUser actor) {
        return teams.removeCc(id, ccId, actor);
    }

    /** Add a (non-routing) feature tag to a team. */
    @PostMapping("/{id}/features")
    public TeamDto addFeature(@PathVariable UUID id,
                              @RequestBody TeamFeatureRequest req,
                              @Actor AppUser actor) {
        return teams.addFeature(id, req, actor);
    }

    /** Remove a feature tag from a team. */
    @DeleteMapping("/{id}/features/{featureCode}")
    public TeamDto removeFeature(@PathVariable UUID id,
                                 @PathVariable String featureCode,
                                 @Actor AppUser actor) {
        return teams.removeFeature(id, featureCode, actor);
    }
}
