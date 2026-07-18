package com.meridian.circular.repo;

import com.meridian.circular.domain.ReminderInterval;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link ReminderInterval} (the admin-configured reminder
 * schedule, per workspace). The tenant-scoped finder backs the Config UI; the
 * unscoped variants support internal lookups.
 */
public interface ReminderIntervalRepository extends JpaRepository<ReminderInterval, UUID> {

    /** All intervals across tenants, ordered by display sort order. */
    List<ReminderInterval> findAllByOrderBySortOrderAsc();

    /** A workspace's intervals, ordered by display sort order (Config UI). */
    List<ReminderInterval> findAllByTenantIdOrderBySortOrderAsc(Integer tenantId);

    /** Lookup by day offset (legacy helper). */
    Optional<ReminderInterval> findByDaysAfterAction(Integer daysAfterAction);
}
