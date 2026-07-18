package com.meridian.circular.repo;

import com.meridian.circular.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the append-only {@link AuditEvent} trail. Maps the
 * per-tenant-schema {@code audit_event} table (resolved via {@code search_path}),
 * so reads are automatically scoped to the acting workspace. The DB blocks
 * UPDATE/DELETE, so only insert + read are used.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
