package com.meridian.circular.service;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.Circular;
import com.meridian.circular.domain.CircularAttachment;
import com.meridian.circular.domain.CircularDocument;
import com.meridian.circular.domain.CircularWorkflow;
import com.meridian.circular.domain.Forwarding;
import com.meridian.circular.domain.Reclassification;
import com.meridian.circular.dto.Dtos.AnalysisDto;
import com.meridian.circular.dto.Dtos.AttachmentDto;
import com.meridian.circular.dto.Dtos.BulkForwardRequest;
import com.meridian.circular.dto.Dtos.BulkForwardResult;
import com.meridian.circular.dto.Dtos.BulkItem;
import com.meridian.circular.dto.Dtos.CircularDetail;
import com.meridian.circular.dto.Dtos.CircularSummary;
import com.meridian.circular.dto.Dtos.CommentDto;
import com.meridian.circular.dto.Dtos.DocumentDto;
import com.meridian.circular.dto.Dtos.DownloadedFile;
import com.meridian.circular.dto.Dtos.ForwardRequest;
import com.meridian.circular.dto.Dtos.ForwardingDto;
import com.meridian.circular.dto.Dtos.PageResponse;
import com.meridian.circular.dto.Dtos.ReferredCircular;
import com.meridian.circular.dto.Dtos.ReviewRequest;
import com.meridian.circular.dto.Dtos.WorkflowHistoryEntry;
import com.meridian.circular.repo.AppUserRepository;
import com.meridian.circular.repo.CircularAttachmentRepository;
import com.meridian.circular.repo.CircularDocumentRepository;
import com.meridian.circular.repo.CircularRepository;
import com.meridian.circular.repo.CircularWorkflowRepository;
import com.meridian.circular.repo.ForwardingRepository;
import com.meridian.circular.repo.ReclassificationRepository;
import com.meridian.circular.repo.SourceRepository;
import com.meridian.circular.security.SuperAdminActor;
import com.meridian.circular.security.TenantContext;
import com.meridian.circular.web.ApiException;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The circular lifecycle (v3): RECEIVED → IN_ACTION → CLOSED. The pipeline
 * (circular-processor) writes circulars; the backend works them. Every status
 * transition and comment is an append-only {@link CircularWorkflow} event —
 * the circular's {@code status} column is denormalized in the same
 * transaction. The API identifier is the raw-circular UUID
 * ({@code Circular.rawCircularId}) because {@code circular_no} contains
 * slashes and cannot be a path variable; payloads expose both.
 */
@Service
public class CircularService {

    private static final Logger log = LoggerFactory.getLogger(CircularService.class);

    /** Evidence file rules — mirror the manual-upload dialog. */
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;
    private static final Set<String> ALLOWED_ATTACHMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "csv", "txt", "zip", "eml", "msg");
    private static final String KIND_CLOSURE_EVIDENCE = "CLOSURE_EVIDENCE";

    private final CircularRepository circulars;
    private final CircularWorkflowRepository workflow;
    private final CircularDocumentRepository documents;
    private final CircularAttachmentRepository attachments;
    private final ForwardingRepository forwardings;
    private final ReclassificationRepository reclassifications;
    private final AppUserRepository users;
    private final SourceRepository sources;
    private final TeamService teamService;
    private final NasStorageService nas;
    private final EvidenceStorageService evidence;
    private final AuditService audit;
    private final GraphMailService graphMail;
    private final CircularExportService exportService;

    public CircularService(CircularRepository circulars,
                           CircularWorkflowRepository workflow,
                           CircularDocumentRepository documents,
                           CircularAttachmentRepository attachments,
                           ForwardingRepository forwardings,
                           ReclassificationRepository reclassifications,
                           AppUserRepository users,
                           SourceRepository sources,
                           TeamService teamService,
                           NasStorageService nas,
                           EvidenceStorageService evidence,
                           AuditService audit,
                           GraphMailService graphMail,
                           CircularExportService exportService) {
        this.circulars = circulars;
        this.workflow = workflow;
        this.documents = documents;
        this.attachments = attachments;
        this.forwardings = forwardings;
        this.reclassifications = reclassifications;
        this.users = users;
        this.sources = sources;
        this.teamService = teamService;
        this.nas = nas;
        this.evidence = evidence;
        this.audit = audit;
        this.graphMail = graphMail;
        this.exportService = exportService;
    }

    // ---- reads -------------------------------------------------------------

    public PageResponse<CircularSummary> list(String status, String search, int page, int size) {
        return list(status, search, null, null, null, null, null, null, page, size);
    }

    /**
     * Paged listing with the full filter set: free-text search, plus optional
     * urgency / category / due / ingested-date-range facets. {@code OVERDUE}
     * is a virtual status — IN_ACTION rows whose due_at is in the past.
     */
    public PageResponse<CircularSummary> list(String status, String search,
                                              String urgency, String category, String source,
                                              String due, Instant fromDate, Instant toDate,
                                              int page, int size) {
        List<Circular> filtered = filterCirculars(status, search, urgency, category,
                source, due, fromDate, toDate);

        int total = filtered.size();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<CircularSummary> pageItems = filtered.subList(from, to).stream()
                .map(this::toSummary)
                .toList();
        return new PageResponse<>(pageItems, total, safePage, safeSize);
    }

    /** Shared filter pipeline behind both the paged list and the Excel export. */
    private List<Circular> filterCirculars(String status, String search,
                                           String urgency, String category, String source,
                                           String due, Instant fromDate, Instant toDate) {
        String q = search == null ? "" : search.trim().toLowerCase();
        String urg = urgency == null ? "" : urgency.trim();
        String cat = category == null ? "" : category.trim();
        String src = source == null ? "" : source.trim();
        String dueFilter = due == null ? "" : due.trim().toUpperCase();
        LocalDate today = LocalDate.now();
        boolean overdue = "OVERDUE".equals(status);

        return circulars.findAll().stream()
                .filter(c -> {
                    if (overdue) {
                        return "IN_ACTION".equals(c.status)
                                && c.dueAt != null && c.dueAt.isBefore(today);
                    }
                    return status == null || status.isBlank()
                            || status.equals("ALL") || status.equals(c.status);
                })
                .filter(c -> q.isEmpty() || matchesSearch(c, q))
                .filter(c -> urg.isEmpty() || urg.equalsIgnoreCase(c.urgency))
                .filter(c -> cat.isEmpty() || c.categories.contains(cat))
                .filter(c -> src.isEmpty() || src.equalsIgnoreCase(c.sourceId))
                .filter(c -> matchesDue(c, dueFilter, today))
                .filter(c -> fromDate == null || (c.ingestedAt != null && !c.ingestedAt.isBefore(fromDate)))
                .filter(c -> toDate == null || (c.ingestedAt != null && !c.ingestedAt.isAfter(toDate)))
                .sorted(Comparator.comparing(
                        (Circular c) -> c.ingestedAt == null ? Instant.MIN : c.ingestedAt)
                        .reversed())
                .toList();
    }

    /**
     * Excel export of the Repository view. Deliberately NOT a full dump: at
     * least one STRUCTURED filter (status / urgency / category / source / due /
     * date range) must be applied — a bare free-text search, or nothing at all,
     * is rejected. The search term is still honoured when combined with a
     * structured filter.
     */
    public byte[] export(String status, String search, String urgency, String category,
                         String source, String due, Instant fromDate, Instant toDate,
                         AppUser actor) {
        boolean hasStructuredFilter =
                notBlank(status) && !"ALL".equalsIgnoreCase(status.trim())
                || notBlank(urgency)
                || notBlank(category)
                || notBlank(source)
                || notBlank(due)
                || fromDate != null
                || toDate != null;
        if (!hasStructuredFilter) {
            throw ApiException.badRequest(
                    "Apply at least one filter (status, urgency, category, source, "
                            + "due or date range) before exporting. A search term alone "
                            + "is not enough.");
        }

        List<CircularSummary> rows = filterCirculars(status, search, urgency, category,
                source, due, fromDate, toDate).stream()
                .map(this::toSummary)
                .toList();

        byte[] xlsx = exportService.toXlsx(rows);
        audit.record(actor, "EXPORT", "circular", "repository",
                "Exported " + rows.size() + " filtered circular(s) to Excel");
        return xlsx;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public CircularDetail detail(UUID id) {
        return toDetail(require(id));
    }

    public List<ReferredCircular> referred(List<String> refs) {
        List<ReferredCircular> out = new ArrayList<>();
        for (String ref : refs) {
            Circular match = circulars.findById(ref).orElse(null);
            out.add(new ReferredCircular(ref,
                    match == null ? null : match.rawCircularId,
                    match == null ? null : match.subject,
                    match == null ? null : match.status));
        }
        return out;
    }

    /** Comments are COMMENT workflow events (v3 — circular_comment is gone). */
    public List<CommentDto> comments(UUID id) {
        Circular c = require(id);
        Map<UUID, String> names = userNames();
        return workflow.findByCircularNoAndActionOrderByActedOnAsc(c.circularNo, "COMMENT")
                .stream()
                .map(w -> new CommentDto(w.workflowId, c.circularNo, w.actedBy,
                        w.actedBy == null ? "System" : names.getOrDefault(w.actedBy, "Unknown"),
                        w.comment, w.actedOn))
                .toList();
    }

    /** Stream an attachment back from the NAS; the access is audited (FR-DATA-05). */
    public DownloadedFile downloadDocument(UUID id, UUID documentId, AppUser actor) {
        Circular c = require(id);
        CircularDocument d = documents.findById(documentId)
                .filter(x -> c.rawCircularId.equals(x.circularId))
                .orElseThrow(() -> ApiException.notFound("Document"));
        byte[] content = nas.read(d.nasRelativePath);
        audit.record(actor, "DOCUMENT_VIEW", "circular", c.circularNo,
                "Downloaded attachment " + d.originalFilename);
        return new DownloadedFile(d.originalFilename, d.mimeType, content);
    }

    // ---- writes ------------------------------------------------------------

    @Transactional
    public CommentDto addComment(UUID id, String body, AppUser actor) {
        Circular c = require(id);
        rejectIfClosed(c, "post a comment on");
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("Comment body is required.");
        }
        CircularWorkflow w = workflowEvent(c, "COMMENT", actor, body.trim(), null);
        audit.record(actor, "COMMENT", "circular", c.circularNo, "Added a comment");
        return new CommentDto(w.workflowId, c.circularNo, actor.userId,
                actor.displayName, w.comment, w.actedOn);
    }

    /**
     * Save a Compliance review — re-classification, referred IDs, due date.
     * Due-date rule (v3 point 2): a due date may be preponed but never moved
     * later than the date already on record.
     */
    @Transactional
    public CircularDetail saveReview(UUID id, ReviewRequest req, AppUser actor) {
        Circular c = require(id);
        rejectIfClosed(c, "re-classify");

        if (req.subject() != null && !req.subject().isBlank()) {
            c.subject = req.subject().trim();
        }
        if (req.body() != null) {
            c.bodyContent = req.body();
        }
        if (req.summary() != null) {
            c.summary = req.summary();
        }
        if (req.requiredAction() != null) {
            c.requiredAction = req.requiredAction();
        }
        if (req.keyEntities() != null) {
            c.keyEntities = new ArrayList<>(req.keyEntities());
        }
        if (req.categories() != null) {
            for (String added : req.categories()) {
                if (!c.categories.contains(added)) {
                    saveReclass(c, actor, "category", "add", null, added, req.reason());
                }
            }
            for (String removed : c.categories) {
                if (!req.categories().contains(removed)) {
                    saveReclass(c, actor, "category", "remove", removed, null, req.reason());
                }
            }
            c.categories = new ArrayList<>(req.categories());
        }
        if (req.urgency() != null && !req.urgency().equals(c.urgency)) {
            saveReclass(c, actor, "urgency", "change", c.urgency, req.urgency(), req.reason());
            c.urgency = req.urgency();
        }
        if (req.referredCircularIds() != null) {
            c.referredCircularIds = new ArrayList<>(req.referredCircularIds());
        }
        if (req.dueAt() != null && !req.dueAt().equals(c.dueAt)) {
            applyDueDate(c, req.dueAt(), actor, req.reason());
        }
        if (req.effectiveAt() != null && !req.effectiveAt().equals(c.effectiveAt)) {
            applyEffectiveDate(c, req.effectiveAt(), actor, req.reason());
        }
        circulars.save(c);
        audit.record(actor, "RECLASSIFY", "circular", c.circularNo,
                "Saved review" + (req.reason() == null ? "" : " — " + req.reason()));
        return toDetail(c);
    }

    /**
     * Forward a circular to one or more teams — the action that moves it
     * RECEIVED → IN_ACTION. Sends an email per team via O365 Graph and
     * records an IN_ACTION workflow event per forwarding.
     */
    public String previewForwardHtml(UUID id, ForwardRequest req, AppUser actor) {
        Circular c = require(id);

        Forwarding f = new Forwarding();
        f.circularNo = c.circularNo;
        f.forwardedBy = actor.userId;
        f.emailSubject = req.emailSubject() != null ? req.emailSubject() : c.subject;
        f.emailBodySnapshot = req.emailBody() != null ? req.emailBody() : c.bodyContent;
        f.forwardedAt = Instant.now();

        Circular temp = new Circular();
        temp.circularNo = c.circularNo;
        temp.subject = c.subject;
        temp.sourceName = c.sourceName;
        temp.issuedAt = c.issuedAt;
        temp.summary = c.summary;
        temp.requiredAction = c.requiredAction;
        temp.keyEntities = c.keyEntities;
        temp.confidence = c.confidence;
        temp.categories = req.categories() != null ? new ArrayList<>(req.categories()) : c.categories;
        temp.urgency = req.urgency() != null ? req.urgency() : c.urgency;

        String teamName = "Selected Teams";
        if (req.teamIds() != null && req.teamIds().size() == 1) {
            teamName = teamService.teamName(req.teamIds().get(0));
        } else if (req.teamIds() != null && req.teamIds().size() > 1) {
            teamName = req.teamIds().size() + " Teams";
        }
        return graphMail.buildForwardHtml(temp, f, teamName);
    }

    @Transactional
    public CircularDetail forward(UUID id, ForwardRequest req, AppUser actor) {
        Circular c = require(id);
        rejectIfClosed(c, "forward");
        if (req.teamIds() == null || req.teamIds().isEmpty()) {
            throw ApiException.badRequest("Select at least one team.");
        }
        if (req.categories() != null) c.categories = new ArrayList<>(req.categories());
        if (req.urgency() != null) c.urgency = req.urgency();
        if (req.dueAt() != null && !req.dueAt().equals(c.dueAt)) {
            applyDueDate(c, req.dueAt(), actor, req.reason());
        }
        if (req.effectiveAt() != null && !req.effectiveAt().equals(c.effectiveAt)) {
            applyEffectiveDate(c, req.effectiveAt(), actor, req.reason());
        }

        var docs = documents.findByCircularId(c.rawCircularId);

        // Record one forwarding + one IN_ACTION workflow event per team, and
        // accumulate the merged (de-duplicated) To/Cc recipients across every
        // selected team. The email is sent ONCE below — sending inside this
        // loop would deliver a full copy per team to the whole merged list,
        // so N teams meant everyone received N identical emails.
        List<String> teamNames = new ArrayList<>();
        List<Forwarding> created = new ArrayList<>();
        LinkedHashSet<String> mergedRecipients = new LinkedHashSet<>();
        LinkedHashSet<String> mergedCc = new LinkedHashSet<>();
        for (UUID teamId : req.teamIds()) {
            teamService.require(teamId);
            Forwarding f = new Forwarding();
            f.circularNo = c.circularNo;
            f.teamId = teamId;
            f.forwardedBy = actor.userId;
            f.emailSubject = req.emailSubject() != null ? req.emailSubject() : c.subject;
            f.emailBodySnapshot = req.emailBody() != null ? req.emailBody() : c.bodyContent;
            f.reason = req.reason();
            forwardings.save(f);
            created.add(f);

            teamNames.add(teamService.teamName(teamId));
            mergedRecipients.addAll(teamService.recipientEmails(teamId));
            mergedCc.addAll(teamService.ccEmails(teamId));
            workflowEvent(c, "IN_ACTION", actor, req.reason(), f.forwardingId);
        }

        // One combined email to all selected teams (matches the single
        // multi-team preview). Every forwarding record shares its send status.
        boolean hasCustomTo = req.customTo() != null && !req.customTo().isEmpty();
        if (!mergedRecipients.isEmpty() || !mergedCc.isEmpty() || hasCustomTo) {
            String headerName = teamNames.size() == 1
                    ? teamNames.get(0) : teamNames.size() + " Teams";
            var result = graphMail.sendForwardEmail(c, created.get(0), headerName,
                    new ArrayList<>(mergedRecipients), new ArrayList<>(mergedCc), docs, req);
            String status = result instanceof GraphMailService.SendResult.Sent ? "SENT" : "FAILED";
            for (Forwarding f : created) {
                f.sendStatus = status;
                forwardings.save(f);
            }
        }
        c.status = "IN_ACTION";
        circulars.save(c);
        audit.record(actor, "FORWARD", "circular", c.circularNo,
                "Forwarded to " + String.join(", ", teamNames) + " — moved to IN_ACTION"
                        + (req.reason() == null ? "" : " — " + req.reason()));
        return toDetail(c);
    }

    /** Bulk-forward many circulars to a single team — each as its own email. */
    @Transactional
    public BulkForwardResult bulkForward(BulkForwardRequest req, AppUser actor) {
        if (req.teamId() == null || req.circularIds() == null || req.circularIds().isEmpty()) {
            throw ApiException.badRequest("Select circulars and a destination team.");
        }
        teamService.require(req.teamId());
        UUID batchId = UUID.randomUUID();
        List<BulkItem> results = new ArrayList<>();
        String tName = teamService.teamName(req.teamId());
        List<String> recipients = teamService.recipientEmails(req.teamId());
        List<String> ccEmails = teamService.ccEmails(req.teamId());

        for (UUID id : req.circularIds()) {
            Circular c = circulars.findByRawCircularId(id).orElse(null);
            if (c == null) {
                results.add(new BulkItem(id, false, null, "not found"));
                continue;
            }
            var docs = documents.findByCircularId(c.rawCircularId);

            Forwarding f = new Forwarding();
            f.circularNo = c.circularNo;
            f.teamId = req.teamId();
            f.forwardedBy = actor.userId;
            f.emailSubject = c.subject;
            f.emailBodySnapshot = c.bodyContent;
            f.bulkBatchId = batchId;
            forwardings.save(f);

            if (!recipients.isEmpty() || !ccEmails.isEmpty()) {
                var result = graphMail.sendForwardEmail(c, f, tName, recipients, ccEmails, docs, null);
                f.sendStatus = result instanceof GraphMailService.SendResult.Sent ? "SENT" : "FAILED";
                forwardings.save(f);
            }
            workflowEvent(c, "IN_ACTION", actor, null, f.forwardingId);
            c.status = "IN_ACTION";
            circulars.save(c);
            results.add(new BulkItem(id, true, c.subject, null));
        }
        int sent = (int) results.stream().filter(BulkItem::ok).count();
        audit.record(actor, "BULK_FORWARD", "team", req.teamId().toString(),
                "Bulk-forwarded " + sent + " circular(s) to " + tName
                        + " — one email each, moved to IN_ACTION");
        return new BulkForwardResult(batchId, sent, results);
    }

    /** Recall a forwarding. If none remain the circular returns to RECEIVED. */
    @Transactional
    public CircularDetail recall(UUID id, UUID forwardingId, AppUser actor) {
        Circular c = require(id);
        rejectIfClosed(c, "recall a forwarding on");
        Forwarding f = forwardings.findById(forwardingId)
                .orElseThrow(() -> ApiException.notFound("Forwarding"));

        String tName = teamService.teamName(f.teamId);
        List<String> recipients = teamService.recipientEmails(f.teamId);
        if (!recipients.isEmpty()) {
            graphMail.sendRecallEmail(c, f, tName, actor.displayName, recipients);
        }

        // Detach workflow events that reference this forwarding before deleting it.
        workflow.findByCircularNoOrderByActedOnAsc(c.circularNo).stream()
                .filter(w -> forwardingId.equals(w.forwardingId))
                .forEach(w -> {
                    w.forwardingId = null;
                    workflow.save(w);
                });
        forwardings.delete(f);
        if (forwardings.findByCircularNoOrderByForwardedAtAsc(c.circularNo).isEmpty()) {
            c.status = "RECEIVED";
            circulars.save(c);
            workflowEvent(c, "RECEIVED", actor,
                    "Returned to RECEIVED — forwarding to " + tName + " recalled", null);
        }
        audit.record(actor, "RECALL", "circular", c.circularNo,
                "Recalled forwarding to " + tName);
        return toDetail(c);
    }

    @Transactional
    public CircularDetail close(UUID id, String closingComment,
                                List<MultipartFile> files, AppUser actor) {
        Circular c = require(id);
        if ("CLOSED".equals(c.status)) {
            throw ApiException.badRequest("Circular is already closed.");
        }
        if (closingComment == null || closingComment.isBlank()) {
            throw ApiException.badRequest("A closing comment is required to close a circular.");
        }
        String comment = closingComment.trim();
        String prev = c.status;
        c.status = "CLOSED";
        circulars.save(c);
        workflowEvent(c, "CLOSED", actor, comment, null);
        // Optional closure evidence — stored on the SEPARATE attachments NAS root
        // and recorded per file in circular_attachment (same transaction).
        int stored = storeEvidence(c, KIND_CLOSURE_EVIDENCE, files, actor);
        audit.record(actor, "STATUS_CHANGE", "circular", c.circularNo,
                prev + " → CLOSED — " + comment
                        + (stored > 0 ? " (" + stored + " evidence file(s))" : ""));
        return toDetail(c);
    }

    /** Attachments (closure evidence) for the circular detail view. */
    public List<AttachmentDto> listAttachments(UUID id) {
        Circular c = require(id);
        Map<UUID, String> names = userNames();
        return attachments.findByCircularNoOrderByCreatedOnAsc(c.circularNo).stream()
                .map(a -> new AttachmentDto(a.attachmentId, a.originalFilename,
                        a.mimeType, a.sizeBytes, a.kind, a.createdBy,
                        a.createdBy == null ? "System"
                                : names.getOrDefault(a.createdBy, "Unknown"),
                        a.createdOn))
                .toList();
    }

    /** Stream an attachment from the attachments NAS root; the access is audited. */
    public DownloadedFile downloadAttachment(UUID id, UUID attachmentId, AppUser actor) {
        Circular c = require(id);
        CircularAttachment a = attachments.findById(attachmentId)
                .filter(x -> c.circularNo.equals(x.circularNo))
                .orElseThrow(() -> ApiException.notFound("Attachment"));
        byte[] content = evidence.read(a.nasRelativePath);
        audit.record(actor, "DOCUMENT_VIEW", "circular", c.circularNo,
                "Downloaded evidence attachment " + a.originalFilename);
        return new DownloadedFile(a.originalFilename, a.mimeType, content);
    }

    /**
     * Persist each uploaded file to the attachments NAS root and a
     * circular_attachment row. Empty/blank files are skipped. Returns the number
     * actually stored.
     */
    private int storeEvidence(Circular c, String kind, List<MultipartFile> files,
                              AppUser actor) {
        if (files == null || files.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String filename = validateAttachment(file);
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw ApiException.badRequest("Could not read uploaded file: " + filename);
            }
            NasStorageService.StoredFile s = evidence.store(c.circularNo, filename, content);
            CircularAttachment a = new CircularAttachment();
            a.circularNo = c.circularNo;
            a.kind = kind;
            a.originalFilename = filename;
            a.mimeType = file.getContentType();
            a.sizeBytes = s.sizeBytes();
            a.nasRelativePath = s.relativePath();
            a.sha256 = s.sha256();
            a.createdBy = actor == null ? null : actor.userId;
            a.updatedBy = actor == null ? null : actor.userId;
            attachments.save(a);
            count++;
        }
        return count;
    }

    /** Enforce size + extension rules; returns the base filename. */
    private String validateAttachment(MultipartFile file) {
        String raw = file.getOriginalFilename();
        String filename = (raw == null || raw.isBlank())
                ? "attachment"
                : java.nio.file.Paths.get(raw).getFileName().toString();
        if (file.getSize() > MAX_ATTACHMENT_BYTES) {
            throw ApiException.badRequest(
                    "File " + filename + " exceeds the 25 MB limit.");
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_ATTACHMENT_EXTENSIONS.contains(ext)) {
            throw ApiException.badRequest(
                    "File type not allowed: " + filename
                            + " (PDF, Word, Excel, CSV, text or ZIP only).");
        }
        return filename;
    }

    /**
     * Workflow timeline backing {@code GET /circulars/{id}/workflow-history} —
     * read straight off the append-only circular_workflow table.
     */
    public List<WorkflowHistoryEntry> workflowHistory(UUID id) {
        Circular c = require(id);
        Map<UUID, String> names = userNames();
        Map<UUID, Forwarding> fwds = new HashMap<>();
        forwardings.findByCircularNoOrderByForwardedAtAsc(c.circularNo)
                .forEach(f -> fwds.put(f.forwardingId, f));

        return workflow.findByCircularNoOrderByActedOnAsc(c.circularNo).stream()
                .filter(w -> !"COMMENT".equals(w.action))
                .map(w -> {
                    Forwarding f = w.forwardingId == null ? null : fwds.get(w.forwardingId);
                    return new WorkflowHistoryEntry(
                            w.action, w.actedOn, w.actedBy,
                            w.actedBy == null
                                    ? (c.sourceName == null ? "Source" : c.sourceName)
                                    : names.getOrDefault(w.actedBy, "Unknown"),
                            f == null ? null : f.teamId,
                            f == null ? null : teamService.teamName(f.teamId),
                            w.comment);
                })
                .toList();
    }

    // ---- helpers -----------------------------------------------------------

    private Circular require(UUID id) {
        return circulars.findByRawCircularId(id)
                .orElseThrow(() -> ApiException.notFound("Circular"));
    }

    private void rejectIfClosed(Circular c, String verb) {
        if ("CLOSED".equals(c.status)) {
            throw ApiException.badRequest(
                    "Cannot " + verb + " a closed circular. Reopen first if needed.");
        }
    }

    /**
     * Due-date rule (v3): prepone allowed, postpone rejected. Setting a date
     * where none exists is allowed; the change is recorded as a
     * reclassification event.
     */
    private void applyDueDate(Circular c, LocalDate newDue, AppUser actor, String reason) {
        if (c.dueAt != null && newDue.isAfter(c.dueAt)) {
            throw ApiException.badRequest(
                    "Due date cannot be moved later than the current due date ("
                            + c.dueAt + ").");
        }
        saveReclass(c, actor, "due_date", "change",
                c.dueAt == null ? null : c.dueAt.toString(), newDue.toString(), reason);
        c.dueAt = newDue;
    }

    /**
     * Effective-date rule: prepone allowed, postpone rejected (same rule as the
     * due date). Setting a date where none exists is allowed; the change is
     * recorded as a reclassification event.
     */
    private void applyEffectiveDate(Circular c, LocalDate newEffective, AppUser actor, String reason) {
        if (c.effectiveAt != null && newEffective.isAfter(c.effectiveAt)) {
            throw ApiException.badRequest(
                    "Effective date cannot be moved later than the current effective date ("
                            + c.effectiveAt + ").");
        }
        saveReclass(c, actor, "effective_date", "change",
                c.effectiveAt == null ? null : c.effectiveAt.toString(),
                newEffective.toString(), reason);
        c.effectiveAt = newEffective;
    }

    private CircularWorkflow workflowEvent(Circular c, String action, AppUser actor,
                                           String comment, UUID forwardingId) {
        CircularWorkflow w = new CircularWorkflow();
        w.circularNo = c.circularNo;
        w.action = action;
        w.actedBy = actor == null ? null : actor.userId;
        w.comment = comment;
        w.forwardingId = forwardingId;
        return workflow.save(w);
    }

    private String sourceType(String sourceId) {
        if (sourceId == null) return null;
        return sources.findById(sourceId).map(s -> s.sourceType).orElse(null);
    }

    private void saveReclass(Circular c, AppUser actor, String field, String action,
                             String before, String after, String reason) {
        Reclassification r = new Reclassification();
        r.circularNo = c.circularNo;
        r.changedBy = actor.userId;
        r.field = field;
        r.action = action;
        r.beforeValue = before;
        r.afterValue = after;
        r.reason = reason;
        reclassifications.save(r);
    }

    private boolean matchesSearch(Circular c, String q) {
        String hay = nz(c.subject) + ' '
                + nz(c.circularNo) + ' '
                + nz(c.sourceName) + ' '
                + nz(c.source) + ' '
                + nz(c.bodyContent) + ' '
                + String.join(" ", c.categories) + ' '
                + String.join(" ", c.referredCircularIds) + ' '
                + nz(c.summary) + ' '
                + String.join(" ", c.keyEntities);
        return hay.toLowerCase().contains(q);
    }

    /** Repository "due" facet — OVERDUE / HAS_DUE / NO_DUE; blank matches all. */
    private boolean matchesDue(Circular c, String dueFilter, LocalDate today) {
        return switch (dueFilter) {
            case "OVERDUE" -> c.dueAt != null && c.dueAt.isBefore(today);
            case "HAS_DUE" -> c.dueAt != null;
            case "NO_DUE" -> c.dueAt == null;
            default -> true;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Map<UUID, String> userNames() {
        Map<UUID, String> map = new HashMap<>();
        users.findAllByTenantId(TenantContext.get()).forEach(u -> map.put(u.userId, u.displayName));
        // The super admin acting in god-mode is stamped with the reserved
        // sentinel id (no real user row) — render it as "Super Admin".
        map.put(SuperAdminActor.SENTINEL_ID, SuperAdminActor.DISPLAY_NAME);
        return map;
    }

    private CircularSummary toSummary(Circular c) {
        return new CircularSummary(c.rawCircularId, c.circularNo, c.subject,
                c.sourceName, c.source, sourceType(c.sourceId),
                c.status, c.urgency, c.categories, c.dueAt, c.effectiveAt, c.issuedAt, c.ingestedAt);
    }

    private AnalysisDto toAnalysis(Circular c) {
        if (c.summary == null && c.requiredAction == null && c.provider == null) {
            return null;
        }
        // Resolve the AI's recommended team NAMES to team ids the UI can
        // pre-select. Names that don't match a live team are silently dropped.
        List<UUID> recommendedTeamIds = new ArrayList<>();
        if (c.recommendedTeams != null) {
            for (String name : c.recommendedTeams) {
                UUID tid = teamService.teamIdByName(name);
                if (tid != null && !recommendedTeamIds.contains(tid)) {
                    recommendedTeamIds.add(tid);
                }
            }
        }
        return new AnalysisDto(c.provider, c.model, c.confidence, c.sentiment,
                c.urgency, c.categories, c.summary, c.requiredAction,
                c.keyEntities == null ? new ArrayList<>() : c.keyEntities,
                recommendedTeamIds);
    }

    private CircularDetail toDetail(Circular c) {
        List<DocumentDto> docs = documents.findByCircularId(c.rawCircularId).stream()
                .map(d -> new DocumentDto(d.documentId, d.documentSource,
                        d.originalFilename, d.mimeType, d.sizeBytes,
                        d.isArchive, d.parentDocumentId))
                .toList();

        Map<UUID, String> names = userNames();
        List<ForwardingDto> fwds = forwardings
                .findByCircularNoOrderByForwardedAtAsc(c.circularNo).stream()
                .map(f -> new ForwardingDto(f.forwardingId, f.teamId,
                        teamService.teamName(f.teamId), f.forwardedBy,
                        f.forwardedBy == null ? null
                                : names.getOrDefault(f.forwardedBy, "Unknown"),
                        f.forwardedAt, f.emailSubject, f.sendStatus, f.reason))
                .toList();

        // Transition timestamps come off the workflow history (v3).
        Instant inActionAt = null;
        Instant closedAt = null;
        UUID closedBy = null;
        String closingComment = null;
        for (CircularWorkflow w : workflow.findByCircularNoOrderByActedOnAsc(c.circularNo)) {
            if ("IN_ACTION".equals(w.action) && inActionAt == null) {
                inActionAt = w.actedOn;
            } else if ("CLOSED".equals(w.action)) {
                closedAt = w.actedOn;
                closedBy = w.actedBy;
                closingComment = w.comment;
            }
        }
        String closedByName = closedBy == null
                ? null : names.getOrDefault(closedBy, "Unknown");

        return new CircularDetail(c.rawCircularId, c.circularNo, c.subject,
                c.sourceId, c.sourceName, c.source, sourceType(c.sourceId),
                c.issuedAt, c.ingestedAt, c.status, c.urgency, c.categories,
                c.dueAt, c.effectiveAt, inActionAt, closedAt, closingComment, closedBy,
                closedByName, c.bodyContent, c.referredCircularIds,
                toAnalysis(c), docs, fwds);
    }
}
