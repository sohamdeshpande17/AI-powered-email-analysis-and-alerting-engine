package com.meridian.circular.repo;

import com.meridian.circular.domain.CircularAttachment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link CircularAttachment} (closure evidence and future
 * workflow attachments), scoped to the acting workspace schema via search_path.
 */
public interface CircularAttachmentRepository extends JpaRepository<CircularAttachment, UUID> {

    /** All attachments for a circular, oldest first. */
    List<CircularAttachment> findByCircularNoOrderByCreatedOnAsc(String circularNo);
}
