package com.meridian.circular.service;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.Team;
import com.meridian.circular.domain.TeamFeature;
import com.meridian.circular.domain.TeamMember;
import com.meridian.circular.domain.TeamCc;
import com.meridian.circular.dto.Dtos.CreateTeamRequest;
import com.meridian.circular.dto.Dtos.TeamCcDto;
import com.meridian.circular.dto.Dtos.TeamCcRequest;
import com.meridian.circular.dto.Dtos.TeamDto;
import com.meridian.circular.dto.Dtos.TeamFeatureRequest;
import com.meridian.circular.dto.Dtos.TeamMemberDto;
import com.meridian.circular.dto.Dtos.TeamMemberRequest;
import com.meridian.circular.dto.Dtos.UpdateTeamRequest;
import com.meridian.circular.repo.TeamCcRepository;
import com.meridian.circular.repo.TeamFeatureRepository;
import com.meridian.circular.repo.TeamMemberRepository;
import com.meridian.circular.repo.TeamRepository;
import com.meridian.circular.security.TenantContext;
import com.meridian.circular.web.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teams — name + feature tags + member emails (no distribution lists in v2.0.0). */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TeamFeatureRepository features;
    private final TeamCcRepository ccRecipients;
    private final AuditService audit;

    public TeamService(TeamRepository teams, TeamMemberRepository members,
                       TeamFeatureRepository features, TeamCcRepository ccRecipients,
                       AuditService audit) {
        this.teams = teams;
        this.members = members;
        this.features = features;
        this.ccRecipients = ccRecipients;
        this.audit = audit;
    }

    /** All teams in the acting workspace, name-sorted, as DTOs. */
    public List<TeamDto> list() {
        return teams.findAllByTenantId(TenantContext.get()).stream()
                .sorted(Comparator.comparing(t -> t.name))
                .map(this::toDto)
                .toList();
    }

    /** Map a team to its DTO, eagerly loading members, Cc recipients and features. */
    public TeamDto toDto(Team team) {
        List<TeamMemberDto> memberDtos = members.findByTeamId(team.teamId).stream()
                .map(m -> new TeamMemberDto(m.memberId, m.emailAddress, m.displayName))
                .toList();
        List<String> featureCodes = features.findByTeamId(team.teamId).stream()
                .map(f -> f.featureCode)
                .sorted()
                .toList();
        List<TeamCcDto> ccDtos = ccRecipients.findByTeamId(team.teamId).stream()
                .map(c -> new TeamCcDto(c.ccId, c.emailAddress, c.displayName, c.ccType))
                .toList();
        return new TeamDto(team.teamId, team.name, team.description,
                team.isActive, featureCodes, memberDtos, ccDtos);
    }

    /** Load a team by id or throw 404. */
    public Team require(UUID teamId) {
        return teams.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("Team"));
    }

    /** Flat recipient address list for a team — used as the To: list on forward. */
    public List<String> recipientEmails(UUID teamId) {
        return members.findByTeamId(teamId).stream()
                .map(m -> m.emailAddress)
                .toList();
    }

    /** Default Cc addresses for a team — added to the Cc: line on forward. */
    public List<String> ccEmails(UUID teamId) {
        return ccRecipients.findByTeamId(teamId).stream()
                .map(c -> c.emailAddress)
                .toList();
    }

    /** Display name for a team id; "Unknown team" if it no longer exists. */
    public String teamName(UUID teamId) {
        return teams.findById(teamId).map(t -> t.name).orElse("Unknown team");
    }

    /** Resolve a team name (as the AI emits it) to its id; null when unknown. */
    public UUID teamIdByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        return teams.findAllByTenantId(TenantContext.get()).stream()
                .filter(t -> n.equalsIgnoreCase(t.name))
                .map(t -> t.teamId)
                .findFirst()
                .orElse(null);
    }

    /** Create a team in the acting workspace (name required). */
    @Transactional
    public TeamDto create(CreateTeamRequest req, AppUser actor) {
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("Team name is required.");
        }
        Team team = new Team();
        team.name = req.name().trim();
        team.description = req.description();
        team.createdBy = actor.userId;
        teams.save(team);
        audit.record(actor, "TEAM_CREATE", "team", team.teamId.toString(),
                "Created team " + team.name);
        return toDto(team);
    }

    /** Update a team's name / description / active flag. */
    @Transactional
    public TeamDto update(UUID teamId, UpdateTeamRequest req, AppUser actor) {
        Team team = require(teamId);
        if (req.name() != null) team.name = req.name();
        if (req.description() != null) team.description = req.description();
        if (req.isActive() != null) team.isActive = req.isActive();
        teams.save(team);
        audit.record(actor, "TEAM_UPDATE", "team", teamId.toString(),
                "Updated team " + team.name);
        return toDto(team);
    }

    /** Add a member email to a team (idempotent on email, case-insensitive). */
    @Transactional
    public TeamDto addMember(UUID teamId, TeamMemberRequest req, AppUser actor) {
        Team team = require(teamId);
        if (req.email() == null || req.email().isBlank()) {
            throw ApiException.badRequest("Member email is required.");
        }
        String email = req.email().trim();
        if (!members.existsByTeamIdAndEmailAddressIgnoreCase(teamId, email)) {
            TeamMember m = new TeamMember();
            m.teamId = teamId;
            m.emailAddress = email;
            m.displayName = req.displayName() == null || req.displayName().isBlank()
                    ? null : req.displayName().trim();
            members.save(m);
            audit.record(actor, "TEAM_MEMBER_ADD", "team", teamId.toString(),
                    "+ member " + email);
        }
        return toDto(team);
    }

    /** Remove a member from a team (no-op if already absent). */
    @Transactional
    public TeamDto removeMember(UUID teamId, UUID memberId, AppUser actor) {
        Team team = require(teamId);
        members.findById(memberId).ifPresent(m -> {
            members.delete(m);
            audit.record(actor, "TEAM_MEMBER_REMOVE", "team", teamId.toString(),
                    "- member " + m.emailAddress);
        });
        return toDto(team);
    }

    /** Add a default Cc recipient (USER or GROUP) to a team (idempotent on email). */
    @Transactional
    public TeamDto addCc(UUID teamId, TeamCcRequest req, AppUser actor) {
        Team team = require(teamId);
        if (req.email() == null || req.email().isBlank()) {
            throw ApiException.badRequest("Cc email is required.");
        }
        String email = req.email().trim();
        if (!ccRecipients.existsByTeamIdAndEmailAddressIgnoreCase(teamId, email)) {
            TeamCc cc = new TeamCc();
            cc.teamId = teamId;
            cc.emailAddress = email;
            cc.displayName = req.displayName() == null || req.displayName().isBlank()
                    ? null : req.displayName().trim();
            cc.ccType = "GROUP".equalsIgnoreCase(req.type()) ? "GROUP" : "USER";
            ccRecipients.save(cc);
            audit.record(actor, "TEAM_CC_ADD", "team", teamId.toString(),
                    "+ default Cc " + email);
        }
        return toDto(team);
    }

    /** Remove a default Cc recipient from a team (no-op if already absent). */
    @Transactional
    public TeamDto removeCc(UUID teamId, UUID ccId, AppUser actor) {
        Team team = require(teamId);
        ccRecipients.findById(ccId).ifPresent(c -> {
            ccRecipients.delete(c);
            audit.record(actor, "TEAM_CC_REMOVE", "team", teamId.toString(),
                    "- default Cc " + c.emailAddress);
        });
        return toDto(team);
    }

    /** Add a feature tag to a team (upper-cased, idempotent). */
    @Transactional
    public TeamDto addFeature(UUID teamId, TeamFeatureRequest req, AppUser actor) {
        Team team = require(teamId);
        if (req.featureCode() == null || req.featureCode().isBlank()) {
            throw ApiException.badRequest("Feature code is required.");
        }
        String code = req.featureCode().trim().toUpperCase();
        if (features.findByTeamIdAndFeatureCode(teamId, code).isEmpty()) {
            TeamFeature f = new TeamFeature();
            f.teamId = teamId;
            f.featureCode = code;
            features.save(f);
            audit.record(actor, "TEAM_FEATURE_ADD", "team", teamId.toString(),
                    "+ feature " + code);
        }
        return toDto(team);
    }

    /** Remove a feature tag from a team (no-op if absent). */
    @Transactional
    public TeamDto removeFeature(UUID teamId, String featureCode, AppUser actor) {
        Team team = require(teamId);
        String code = featureCode == null ? "" : featureCode.trim().toUpperCase();
        features.findByTeamIdAndFeatureCode(teamId, code).ifPresent(f -> {
            features.delete(f);
            audit.record(actor, "TEAM_FEATURE_REMOVE", "team", teamId.toString(),
                    "- feature " + code);
        });
        return toDto(team);
    }
}
