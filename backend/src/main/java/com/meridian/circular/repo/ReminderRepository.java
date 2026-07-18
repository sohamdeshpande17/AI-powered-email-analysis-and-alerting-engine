package com.meridian.circular.repo;

import com.meridian.circular.domain.Reminder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Reminder} (the read view of scheduled reminders that
 * have fired). Finders key on the owning circular's surrogate id
 * ({@code circular.id}), scoping results to one workspace's copy.
 */
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    /** All reminders fired for a circular, newest first. */
    List<Reminder> findByCircularNoOrderBySentAtDesc(String circularNo);
}
