package com.meridian.circular.repo;

import com.meridian.circular.domain.CircularWorkflow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link CircularWorkflow} events. Maps the per-tenant-schema
 * {@code circular_workflow} table (resolved via {@code search_path}); finders
 * key on {@code circular_no} within the workspace schema.
 */
public interface CircularWorkflowRepository extends JpaRepository<CircularWorkflow, UUID> {

    List<CircularWorkflow> findByCircularNoOrderByActedOnAsc(String circularNo);

    List<CircularWorkflow> findByCircularNoAndActionOrderByActedOnAsc(String circularNo, String action);
}
