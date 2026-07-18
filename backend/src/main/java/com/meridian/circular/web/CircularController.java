package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.AttachmentDto;
import com.meridian.circular.dto.Dtos.BulkForwardRequest;
import com.meridian.circular.dto.Dtos.BulkForwardResult;
import com.meridian.circular.dto.Dtos.CircularDetail;
import com.meridian.circular.dto.Dtos.CircularSummary;
import com.meridian.circular.dto.Dtos.PageResponse;
import com.meridian.circular.dto.Dtos.CommentDto;
import com.meridian.circular.dto.Dtos.CommentRequest;
import com.meridian.circular.dto.Dtos.DownloadedFile;
import com.meridian.circular.dto.Dtos.ForwardRequest;
import com.meridian.circular.dto.Dtos.ReferredCircular;
import com.meridian.circular.dto.Dtos.ReminderDto;
import com.meridian.circular.dto.Dtos.ReviewRequest;
import com.meridian.circular.dto.Dtos.WorkflowHistoryEntry;
import com.meridian.circular.security.Actor;
import com.meridian.circular.service.CircularService;
import com.meridian.circular.service.CircularUploadService;
import com.meridian.circular.service.DashboardService;
import com.meridian.circular.service.ReminderService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Circular inbox, detail, and the three-state Compliance lifecycle. */
@RestController
@RequestMapping("/api/circulars")
public class CircularController {

    private final CircularService circulars;
    private final ReminderService reminders;
    private final DashboardService dashboard;
    private final CircularUploadService uploads;

    public CircularController(CircularService circulars, ReminderService reminders,
                              DashboardService dashboard, CircularUploadService uploads) {
        this.circulars = circulars;
        this.reminders = reminders;
        this.dashboard = dashboard;
        this.uploads = uploads;
    }

    /**
     * Paged inbox listing. Defaults page=0, size=50 (large enough for the
     * dashboard "Recent circulars" + drilldown sources). Returns a
     * {@code PageResponse} envelope so the client knows the total filtered
     * count without a second round-trip.
     */
    @GetMapping
    public PageResponse<CircularSummary> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) String urgency,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String source,
                                              @RequestParam(required = false) String due,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false, defaultValue = "0") Integer page,
                                              @RequestParam(required = false, defaultValue = "50") Integer size) {
        return circulars.list(status, search, urgency, category, source, due,
                DateParams.from(from), DateParams.to(to), page, size);
    }

    /** Distinct calendar years present in the corpus (for the year filter). */
    @GetMapping("/years")
    public List<Integer> years() {
        return dashboard.years();
    }

    /**
     * Excel (.xlsx) export of the filtered Repository view. Not a full dump —
     * the service rejects the request unless at least one structured filter is
     * applied (a bare search term is not enough).
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) String urgency,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String due,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @Actor AppUser actor) {
        byte[] xlsx = circulars.export(status, search, urgency, category, source, due,
                DateParams.from(from), DateParams.to(to), actor);
        String filename = "circulars-export-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + ".xlsx";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(xlsx);
    }

    /**
     * Manual circular upload — open to EVERY signed-in role. Saves the files
     * to the shared NAS and publishes the standard circular.raw.v1 envelope;
     * the circular-processor then runs AI analysis and the circular appears
     * in the Inbox like any scraped/emailed one (source MANUAL).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam String subject,
                                      @RequestParam(required = false) String circularNo,
                                      @RequestParam(required = false) String department,
                                      @RequestParam(required = false) String body,
                                      @RequestParam(required = false) String issuedAt,
                                      @RequestParam(required = false) List<MultipartFile> files,
                                      @Actor AppUser actor) {
        UUID circularId = uploads.upload(subject, circularNo, department,
                body, issuedAt, files, actor);
        return Map.of("circularId", circularId.toString());
    }

    @GetMapping("/referred")
    public List<ReferredCircular> referred(@RequestParam(required = false) List<String> refs) {
        return circulars.referred(refs == null ? List.of() : refs);
    }

    /** Full detail for one circular (the acting workspace's copy). */
    @GetMapping("/{id}")
    public CircularDetail get(@PathVariable UUID id) {
        return circulars.detail(id);
    }

    /** Save a Compliance review — edits, re-classification, referred ids, due date. */
    @PostMapping("/{id}/review")
    public CircularDetail review(@PathVariable UUID id,
                                 @RequestBody ReviewRequest req,
                                 @Actor AppUser actor) {
        return circulars.saveReview(id, req, actor);
    }

    /** Forward to one or more teams — moves the circular RECEIVED → IN_ACTION. */
    @PostMapping("/{id}/forward")
    public CircularDetail forward(@PathVariable UUID id,
                                  @RequestBody ForwardRequest req,
                                  @Actor AppUser actor) {
        return circulars.forward(id, req, actor);
    }

    /** Render the forward email HTML for preview (no email sent). */
    @PostMapping("/{id}/preview-html")
    public String previewHtml(@PathVariable UUID id,
                              @RequestBody ForwardRequest req,
                              @Actor AppUser actor) {
        return circulars.previewForwardHtml(id, req, actor);
    }

    /** Bulk-forward many circulars to a single team — one email each. */
    @PostMapping("/bulk-forward")
    public BulkForwardResult bulkForward(@RequestBody BulkForwardRequest req,
                                         @Actor AppUser actor) {
        return circulars.bulkForward(req, actor);
    }

    /** Recall a forwarding; if none remain the circular returns to RECEIVED. */
    @DeleteMapping("/{id}/forwardings/{forwardingId}")
    public CircularDetail recall(@PathVariable UUID id,
                                 @PathVariable UUID forwardingId,
                                 @Actor AppUser actor) {
        return circulars.recall(id, forwardingId, actor);
    }

    /**
     * Close a circular (a closing comment is mandatory) with optional evidence
     * files. Multipart so evidence can be attached in the same request; the
     * files are stored on the separate attachments NAS root.
     */
    @PostMapping(value = "/{id}/close", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CircularDetail close(@PathVariable UUID id,
                                @RequestParam String closingComment,
                                @RequestParam(required = false) List<MultipartFile> files,
                                @Actor AppUser actor) {
        return circulars.close(id, closingComment, files, actor);
    }

    /** List a circular's workflow attachments (closure evidence). */
    @GetMapping("/{id}/attachments")
    public List<AttachmentDto> attachments(@PathVariable UUID id) {
        return circulars.listAttachments(id);
    }

    /** Download a workflow attachment, streamed from the attachments NAS root. */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id,
                                                     @PathVariable UUID attachmentId,
                                                     @Actor(required = false) AppUser actor) {
        return fileResponse(circulars.downloadAttachment(id, attachmentId, actor));
    }

    /** Stage-by-stage workflow timeline for the detail page. Read-only. */
    @GetMapping("/{id}/workflow-history")
    public List<WorkflowHistoryEntry> workflowHistory(@PathVariable UUID id) {
        return circulars.workflowHistory(id);
    }

    /** Download an attachment, streamed from the NAS (BRD FR-DATA-05). */
    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable UUID id,
                                                   @PathVariable UUID documentId,
                                                   @Actor(required = false) AppUser actor) {
        return fileResponse(circulars.downloadDocument(id, documentId, actor));
    }

    /**
     * Build a streaming file response. Regulator filenames routinely contain
     * spaces, commas, parentheses and non-ASCII characters. A raw quoted
     * filename in Content-Disposition makes Tomcat reject the header (download
     * fails with a 500), so it is built through ContentDisposition (RFC 5987 /
     * UTF-8 encoded, with an ASCII fallback).
     */
    private ResponseEntity<byte[]> fileResponse(DownloadedFile file) {
        MediaType type;
        try {
            type = file.mimeType() != null && !file.mimeType().isBlank()
                    ? MediaType.parseMediaType(file.mimeType())
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename() != null ? file.filename() : "attachment",
                        StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }

    /** Comments on a circular (COMMENT workflow events). */
    @GetMapping("/{id}/comments")
    public List<CommentDto> comments(@PathVariable UUID id) {
        return circulars.comments(id);
    }

    /** Add a comment to a circular. */
    @PostMapping("/{id}/comments")
    public CommentDto addComment(@PathVariable UUID id,
                                 @RequestBody CommentRequest req,
                                 @Actor AppUser actor) {
        return circulars.addComment(id, req.body(), actor);
    }

    /** Scheduled reminders that have fired against this circular. Read-only. */
    @GetMapping("/{id}/reminders")
    public List<ReminderDto> reminders(@PathVariable UUID id) {
        return reminders.listForCircular(id);
    }
}
