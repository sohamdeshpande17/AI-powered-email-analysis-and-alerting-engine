package com.meridian.circular.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response payloads for the REST API, grouped as nested records.
 *
 * <p>Convention: a circular's API identifier is its {@code rawCircularId} (a
 * UUID, exposed as {@code id} on response records), which is URL-safe and — under
 * tenant scoping — uniquely identifies the acting workspace's copy.
 * {@code circularNo} is the human-readable business number, unique only within a
 * workspace. Records are immutable; "…Request" records are request bodies and
 * the rest are response views.
 */
public final class Dtos {

    private Dtos() {
    }

    // ---- responses ---------------------------------------------------------

    /** Inbox/list row for a circular. {@code id} is the raw-circular UUID (API identifier); {@code circularNo} is the business number. */
    public record CircularSummary(
            UUID id,
            String circularNo,
            String subject,
            String sourceName,
            String source,
            /** Adapter type — MAILBOX | WEB_SCRAPER | MANUAL_UPLOAD. */
            String sourceType,
            String status,
            String urgency,
            List<String> categories,
            LocalDate dueAt,
            LocalDate effectiveAt,
            LocalDate issuedAt,
            Instant ingestedAt) {
    }

    /** One attachment/document on a circular — metadata only (bytes stay on the NAS). */
    public record DocumentDto(
            UUID id,
            String source,
            String filename,
            String mimeType,
            Long sizeBytes,
            boolean isArchive,
            UUID parentId) {
    }

    /** AI analysis block — v3: folded into the circular row itself. */
    public record AnalysisDto(
            String provider,
            String model,
            Double confidence,
            String sentiment,
            String urgency,
            List<String> categories,
            String summary,
            String requiredAction,
            List<String> keyEntities,
            /** Team ids resolved from the AI's recommended team names (category map). */
            List<UUID> recommendedTeamIds) {
    }

    /** A forwarding of a circular to a team (response view). */
    public record ForwardingDto(
            UUID id,
            UUID teamId,
            String teamName,
            UUID forwardedBy,
            String forwardedByName,
            Instant forwardedAt,
            String emailSubject,
            String sendStatus,
            /** Officer-supplied reason captured at forward time. */
            String reason) {
    }

    /** Full circular detail — header, lifecycle timestamps, body, analysis, documents and forwardings. */
    public record CircularDetail(
            UUID id,
            String circularNo,
            String subject,
            /** Business source code — NSE | BSE | EMAIL. */
            String sourceId,
            String sourceName,
            String source,
            /** Adapter type — MAILBOX | WEB_SCRAPER | MANUAL_UPLOAD. */
            String sourceType,
            LocalDate issuedAt,
            Instant ingestedAt,
            String status,
            String urgency,
            List<String> categories,
            LocalDate dueAt,
            LocalDate effectiveAt,
            Instant inActionAt,
            Instant closedAt,
            String closingComment,
            UUID closedBy,
            String closedByName,
            String body,
            List<String> referredCircularIds,
            AnalysisDto analysis,
            List<DocumentDto> documents,
            List<ForwardingDto> forwardings) {
    }

    /** A resolved reference to another circular (by {@code circular_no}). */
    public record ReferredCircular(
            String ref,
            UUID circularId,
            String subject,
            String status) {
    }

    /** A COMMENT workflow event (v3 — comments live in circular_workflow). */
    public record CommentDto(
            UUID id,
            String circularNo,
            UUID authorUserId,
            String authorName,
            String body,
            Instant createdAt) {
    }

    /** A scheduled reminder that fired against a circular (read view). */
    public record ReminderDto(
            UUID id,
            String circularNo,
            UUID teamId,
            String teamName,
            UUID intervalId,
            Integer daysAfterAction,
            String sentTo,
            Instant sentAt) {
    }

    /** One entry in the audit trail (response view). */
    public record AuditDto(
            Long id,
            UUID actorUserId,
            String actorName,
            String action,
            String entityType,
            String entityId,
            String detail,
            Instant occurredAt) {
    }

    /** Aggregated counters backing the Compliance dashboard. */
    public record DashboardStats(
            long total,
            Map<String, Long> byStatus,
            Map<String, Long> byUrgency,
            Map<String, Long> byCategory,
            Map<String, Long> byTeam,
            long dueBreached,
            double reclassificationRate) {
    }

    /** A Microsoft Graph directory person. */
    public record DirectoryUser(String oid, String name, String email) {
    }

    /** A directory hit that may be a person or a distribution list / group. */
    public record DirectoryRecipient(String oid, String name, String email,
                                     /** USER | GROUP. */ String type) {
    }

    /** A team member (forward To: recipient). */
    public record TeamMemberDto(UUID id, String email, String displayName) {
    }

    /** A default Cc recipient on a team — person or distribution list. */
    public record TeamCcDto(UUID id, String email, String displayName,
                            /** USER | GROUP. */ String type) {
    }

    /** A team with its members, default-Cc recipients and feature tags. */
    public record TeamDto(
            UUID id,
            String name,
            String description,
            boolean isActive,
            List<String> features,
            List<TeamMemberDto> members,
            List<TeamCcDto> ccRecipients) {
    }

    /** A provisioned application user (response view); {@code role} is the single role id. */
    public record UserDto(
            UUID id,
            String name,
            String email,
            String role,
            boolean isActive,
            Instant lastLoginAt) {
    }

    /** Per-circular outcome within a bulk-forward operation. */
    public record BulkItem(UUID id, boolean ok, String subject, String error) {
    }

    /** Result of a bulk-forward — the batch id, the count sent, and per-item outcomes. */
    public record BulkForwardResult(UUID batchId, int sent, List<BulkItem> results) {
    }

    /**
     * Generic paged response envelope. Total reflects the count AFTER status
     * + search filtering, so client-side page counts are always accurate.
     */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {
    }

    /** A registered ingestion source (response view). */
    public record SourceDto(
            String id,
            String type,
            String name,
            String description,
            Map<String, Object> config,
            boolean isActive,
            Instant lastRunAt,
            Instant lastSuccessAt,
            String lastError) {
    }

    /** Toggle a source's scraping on/off (Config → Scraper resources). */
    public record SourceUpdateRequest(Boolean isActive) {
    }

    /** A configured reminder interval (response view). */
    public record ReminderIntervalDto(
            UUID id,
            Integer daysAfterAction,
            String label,
            boolean isActive,
            Integer sortOrder,
            /** PRE_DUE | POST_DUE | POST_ACTION (legacy). */
            String kind,
            Instant updatedAt) {
    }

    /** A per-workspace config entry (response view). */
    public record AppConfigDto(
            String key,
            Object value,
            String description,
            UUID updatedBy,
            Instant updatedAt) {
    }

    // ---- requests ----------------------------------------------------------

    /** Body of the Compliance review save — edits, re-classification, referred ids and due date. */
    public record ReviewRequest(
            String subject,
            String body,
            String summary,
            String requiredAction,
            List<String> keyEntities,
            List<String> categories,
            String urgency,
            List<String> referredCircularIds,
            LocalDate dueAt,
            LocalDate effectiveAt,
            String reason) {
    }

    /** A user-supplied attachment on a forward (base64-encoded content). */
    public record CustomAttachment(String filename, String contentBase64, String mimeType) {}

    /** Body of the forward action — target teams, email overrides, attachments and optional re-classification. */
    public record ForwardRequest(
            List<UUID> teamIds,
            String emailSubject,
            String emailBody,
            List<String> customTo,
            List<String> customCc,
            String finalHtmlBody,
            List<CustomAttachment> customAttachments,
            List<String> categories,
            String urgency,
            LocalDate dueAt,
            LocalDate effectiveAt,
            String reason) {
    }

    /** Body of the bulk-forward action — the circulars and the single destination team. */
    public record BulkForwardRequest(List<UUID> circularIds, UUID teamId) {
    }

    /** Body of the add-comment action. */
    public record CommentRequest(String body) {
    }

    /** Body of the create-user (provision) action. */
    public record CreateUserRequest(String oid, String name, String email, String role) {
    }

    /**
     * Microsoft (Entra ID) SSO sign-in. {@code token} is the MSAL-issued ID
     * token, validated server-side when SSO validation is enabled; the
     * oid/email/name fields are a fallback used only in dev (validation off).
     */
    public record SsoLoginRequest(String token, String oid, String email, String name) {
    }

    /**
     * Microsoft (Entra ID) SSO sign-in via the server-side authorization-code
     * flow. {@code code} is the single-use authorization code Entra returned to
     * the SPA; {@code redirectUri} must match the one used in the authorize
     * request; {@code codeVerifier} is the PKCE verifier the SPA generated.
     * The backend redeems these for tokens (see EntraSsoExchangeService).
     */
    public record SsoExchangeRequest(String code, String redirectUri, String codeVerifier) {
    }

    /** Temporary direct login — the chosen provisioned user's id. */
    public record LoginRequest(UUID userId) {
    }

    /** Login response — the session bearer token plus the signed-in user. */
    public record AuthResponse(String token, UserDto user) {
    }

    /**
     * SSO login response — the backend session token (for application APIs) plus
     * the Microsoft Graph access token (for the Graph-backed search feature) and
     * its lifetime in seconds, alongside the signed-in user.
     */
    public record SsoAuthResponse(String token, String msAccessToken,
                                  long msExpiresInSeconds, UserDto user) {
    }

    /** Body of the update-user action — role and/or active flag. */
    public record UpdateUserRequest(String role, Boolean isActive) {
    }

    /** Body of the create-team action. */
    public record CreateTeamRequest(String name, String description) {
    }

    /** Body of the update-team action. */
    public record UpdateTeamRequest(String name, String description, Boolean isActive) {
    }

    /** Body of the add-team-member action. */
    public record TeamMemberRequest(String email, String displayName) {
    }

    /** Body of the add-team-Cc action — person or distribution list. */
    public record TeamCcRequest(String email, String displayName,
                                /** USER | GROUP. */ String type) {
    }

    /** Body of the add-team-feature action. */
    public record TeamFeatureRequest(String featureCode) {
    }

    /** Body of the create/update reminder-interval action. */
    public record ReminderIntervalRequest(Integer daysAfterAction, String label,
                                          Boolean isActive, Integer sortOrder,
                                          /** PRE_DUE | POST_DUE | POST_ACTION; null = POST_ACTION. */
                                          String kind) {
    }

    /** One entry on the workflow-history timeline returned by the new endpoint. */
    public record WorkflowHistoryEntry(
            /** RECEIVED | IN_ACTION | CLOSED. */
            String stage,
            Instant at,
            UUID actorUserId,
            String actorName,
            /** Set on IN_ACTION entries — the team the circular was forwarded to. */
            UUID teamId,
            String teamName,
            String comment) {
    }

    /** Body of the update-config action — the new value for the key. */
    public record AppConfigRequest(Object value) {
    }

    // ---- super admin (platform) -------------------------------------------

    /** A super-admin account (response view; never carries the password hash). */
    public record PlatformAdminDto(
            UUID id,
            String username,
            String displayName,
            boolean isActive,
            boolean mustChangePassword,
            Instant lastLoginAt) {
    }

    /** Super-admin username/password login. */
    public record AdminLoginRequest(String username, String password) {
    }

    /** Super-admin login response — the platform bearer token plus the admin. */
    public record AdminAuthResponse(String token, PlatformAdminDto admin, boolean mustChangePassword) {
    }

    /** Body of the super-admin change-password action. */
    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** Body of the create-super-admin action. */
    public record CreateAdminRequest(String username, String displayName, String password) {
    }

    /** Body of the update-super-admin action — display name, active flag and/or password reset. */
    public record UpdateAdminRequest(String displayName, Boolean isActive, String resetPassword) {
    }

    /** A department/tenant (response view). */
    public record DepartmentDto(Integer id, String code, String name, boolean isActive) {
    }

    /** Body of the create-department action. */
    public record CreateDepartmentRequest(String code, String name) {
    }

    /** Body of the update-department action — rename and/or (de)activate. */
    public record UpdateDepartmentRequest(String name, Boolean isActive) {
    }

    /** A role in the global catalog (response view). */
    public record RoleDto(String id, String name, String description) {
    }

    /** Body of the create-role action. */
    public record CreateRoleRequest(String id, String name, String description) {
    }

    /** Body of the update-role action. */
    public record UpdateRoleRequest(String name, String description) {
    }

    /** A file streamed back from the NAS for download. */
    public record DownloadedFile(String filename, String mimeType, byte[] content) {
    }

    /** A circular workflow attachment (e.g. closure evidence) for the detail view. */
    public record AttachmentDto(
            UUID id,
            String filename,
            String mimeType,
            Long sizeBytes,
            /** CLOSURE_EVIDENCE for now. */
            String kind,
            UUID uploadedByUserId,
            String uploadedByName,
            Instant uploadedOn) {
    }
}
