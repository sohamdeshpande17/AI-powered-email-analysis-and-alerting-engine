package com.meridian.circular.repo;

import com.meridian.circular.domain.Reclassification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Reclassification} — the append-only log of Compliance
 * overrides of the LLM output (FR-ENH-01). Rows are written by the service layer;
 * no custom finders are needed beyond the CRUD defaults.
 */
public interface ReclassificationRepository extends JpaRepository<Reclassification, UUID> {
}
