package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One physical file on the NAS belonging to a raw circular (v3: table
 * {@code raw_circular_document}, in the raw layer). Part of the
 * <strong>shared</strong> raw layer (multi-tenancy: not tenant-scoped — extends
 * {@link SystemAudited} with text {@code *_by} columns stamped "System" by the
 * pipeline, no {@code tenant_id}).
 *
 * <p>{@link #circularId} references {@code raw_circular.circular_id}; join from a
 * {@link Circular} via its {@code rawCircularId}. Document bytes never travel
 * through the system — only NAS-relative paths and metadata are stored.
 */
@Entity
@Table(name = "raw_circular_document")
public class CircularDocument extends SystemAudited {

    /** Surrogate PK (UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID documentId;

    /** Owning raw circular id ({@code raw_circular.circular_id}). */
    public UUID circularId;

    /** Parent document id when this file was extracted from an archive; else null. */
    public UUID parentDocumentId;

    /** Origin — attachment | linked_download | archive_child. */
    public String documentSource;

    /** Original filename as received from the source. */
    public String originalFilename;

    /** MIME type, when known. */
    public String mimeType;

    /** File size in bytes, when known. */
    public Long sizeBytes;

    /** Whether this file is itself an archive (e.g. a ZIP). */
    public boolean isArchive = false;

    /** Path to the file on the NAS, relative to the storage root. */
    public String nasRelativePath;

    /** SHA-256 of the file content, when computed. */
    public String sha256;

    /** Text extracted from the document by the AI stage (search support). */
    public String extractedText;
}
