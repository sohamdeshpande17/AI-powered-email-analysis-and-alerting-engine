package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A user-uploaded attachment tied to a circular's workflow — currently closure
 * evidence ({@code kind = CLOSURE_EVIDENCE}). Lives in the workspace schema (the
 * schema is the tenant, so no {@code tenant_id}) and is stored on a SEPARATE NAS
 * root from the source documents (see
 * {@link com.meridian.circular.service.EvidenceStorageService}).
 *
 * <p>Attaching is a human action, so the audit {@code *_by} columns are the
 * acting user's {@code user_id} (UUID) via {@link Audited} — unlike the
 * pipeline-written circular tables ({@link SystemAudited}).
 */
@Entity
@Table(name = "circular_attachment")
public class CircularAttachment extends Audited {

    /** Surrogate PK. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID attachmentId;

    /** Owning circular ({@code circular.circular_no}). */
    public String circularNo;

    /** Attachment category — {@code CLOSURE_EVIDENCE} for now. */
    public String kind;

    /** Original filename as uploaded. */
    public String originalFilename;

    /** MIME type, when known. */
    public String mimeType;

    /** File size in bytes. */
    public Long sizeBytes;

    /** Path to the file on the attachments NAS root, relative to that root. */
    public String nasRelativePath;

    /** SHA-256 of the file content. */
    public String sha256;
}
