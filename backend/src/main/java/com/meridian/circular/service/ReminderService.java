package com.meridian.circular.service;

import com.meridian.circular.domain.Circular;
import com.meridian.circular.domain.Reminder;
import com.meridian.circular.domain.ReminderInterval;
import com.meridian.circular.dto.Dtos.ReminderDto;
import com.meridian.circular.repo.CircularRepository;
import com.meridian.circular.repo.ReminderIntervalRepository;
import com.meridian.circular.repo.ReminderRepository;
import com.meridian.circular.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reminders in v2.0.0 are scheduled-only. There is no manual reminder API —
 * the scheduler fires once per active {@link ReminderInterval} per circular
 * while the circular is IN_ACTION. This service exists to expose the audit
 * view of what fired against a given circular.
 */
@Service
public class ReminderService {

    private final ReminderRepository reminders;
    private final ReminderIntervalRepository intervals;
    private final CircularRepository circulars;
    private final TeamService teamService;

    public ReminderService(ReminderRepository reminders,
                           ReminderIntervalRepository intervals,
                           CircularRepository circulars,
                           TeamService teamService) {
        this.reminders = reminders;
        this.intervals = intervals;
        this.circulars = circulars;
        this.teamService = teamService;
    }

    /** {@code id} is the raw-circular UUID used as the API identifier. */
    public List<ReminderDto> listForCircular(UUID id) {
        Circular c = circulars.findByRawCircularId(id)
                .orElseThrow(() -> ApiException.notFound("Circular"));
        Map<UUID, Integer> intervalDays = new HashMap<>();
        for (ReminderInterval i : intervals.findAll()) {
            intervalDays.put(i.intervalId, i.daysAfterAction);
        }
        return reminders.findByCircularNoOrderBySentAtDesc(c.circularNo).stream()
                .map(r -> toDto(r, c.circularNo, intervalDays))
                .toList();
    }

    private ReminderDto toDto(Reminder r, String circularNo, Map<UUID, Integer> intervalDays) {
        return new ReminderDto(r.reminderId, circularNo, r.teamId,
                r.teamId == null ? null : teamService.teamName(r.teamId),
                r.intervalId,
                r.intervalId == null ? null : intervalDays.get(r.intervalId),
                r.sentTo, r.sentAt);
    }
}
