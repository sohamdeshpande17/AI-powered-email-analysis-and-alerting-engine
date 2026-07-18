package com.meridian.circular.service;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.AuditEvent;
import com.meridian.circular.dto.Dtos.AuditDto;
import com.meridian.circular.dto.Dtos.PageResponse;
import com.meridian.circular.repo.AppUserRepository;
import com.meridian.circular.repo.AuditEventRepository;
import com.meridian.circular.security.SuperAdminActor;
import com.meridian.circular.security.TenantContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Writes and reads the append-only audit trail (BRD FR-AUDIT). */
@Service
public class AuditService {

    private final AuditEventRepository audit;
    private final AppUserRepository users;

    public AuditService(AuditEventRepository audit, AppUserRepository users) {
        this.audit = audit;
        this.users = users;
    }

    /** Record one audit event. The human-readable summary is stored in {@code reason}. */
    public void record(AppUser actor, String action, String entityType,
                        String entityId, String detail) {
        // God-mode: super-admin actions inside a department are NOT audited
        // (only real department users' actions are kept in the tenant trail).
        if (actor != null && SuperAdminActor.isSentinel(actor.userId)) {
            return;
        }
        AuditEvent e = new AuditEvent();
        e.actorUserId = actor != null ? actor.userId : null;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.reason = detail;
        audit.save(e);
    }

    /**
     * Paged audit listing, newest first, with optional action / search / date
     * filters. Paging is done server-side so the client never loads the whole
     * trail; {@code total} reflects the filtered count for the page header.
     */
    public PageResponse<AuditDto> list(String action, String search, Instant from, Instant to,
                                       int page, int size) {
        List<AuditDto> filtered = filterAndSort(action, search, from, to);

        int total = filtered.size();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int fromIdx = Math.min(safePage * safeSize, total);
        int toIdx = Math.min(fromIdx + safeSize, total);
        return new PageResponse<>(filtered.subList(fromIdx, toIdx), total, safePage, safeSize);
    }

    private List<AuditDto> filterAndSort(String action, String search, Instant from, Instant to) {
        Map<UUID, String> names = userNames();
        String q = search == null ? "" : search.trim().toLowerCase();

        return audit.findAll().stream()
                .filter(e -> action == null || action.isBlank()
                        || action.equals("ALL") || action.equals(e.action))
                .filter(e -> from == null || !e.occurredAt.isBefore(from))
                .filter(e -> to == null || !e.occurredAt.isAfter(to))
                .map(e -> new AuditDto(
                        e.eventId,
                        e.actorUserId,
                        e.actorUserId == null ? "System"
                                : names.getOrDefault(e.actorUserId, "Unknown"),
                        e.action,
                        e.entityType,
                        e.entityId,
                        e.reason,
                        e.occurredAt))
                .filter(d -> q.isEmpty() || matches(d, q))
                .sorted(Comparator.comparing(AuditDto::occurredAt).reversed())
                .toList();
    }

    private boolean matches(AuditDto d, String q) {
        return (d.actorName() + ' ' + d.action() + ' ' + d.entityType() + ' '
                + d.entityId() + ' ' + (d.detail() == null ? "" : d.detail()))
                .toLowerCase().contains(q);
    }

    private Map<UUID, String> userNames() {
        Map<UUID, String> map = new HashMap<>();
        users.findAllByTenantId(TenantContext.get()).forEach(u -> map.put(u.userId, u.displayName));
        // Super-admin god-mode actions are stamped with the reserved sentinel id.
        map.put(SuperAdminActor.SENTINEL_ID, SuperAdminActor.DISPLAY_NAME);
        return map;
    }
}
