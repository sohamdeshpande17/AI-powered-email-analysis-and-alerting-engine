package com.meridian.circular.repo;

import com.meridian.circular.domain.CircularDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link CircularDocument} (the shared raw-document layer).
 * Documents belong to a raw circular, not a workspace, so lookups key on the raw
 * circular id; the caller scopes access through the owning {@link com.meridian.circular.domain.Circular}.
 */
public interface CircularDocumentRepository extends JpaRepository<CircularDocument, UUID> {

    /** All documents of a raw circular ({@code raw_circular.circular_id}). */
    List<CircularDocument> findByCircularId(UUID circularId);
}
