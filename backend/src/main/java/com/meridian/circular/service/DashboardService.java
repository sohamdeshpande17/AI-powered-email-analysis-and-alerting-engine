package com.meridian.circular.service;

import com.meridian.circular.domain.Circular;
import com.meridian.circular.dto.Dtos.DashboardStats;
import com.meridian.circular.repo.CircularRepository;
import com.meridian.circular.repo.ForwardingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Aggregates the Compliance dashboard (BRD FR-DASH). */
@Service
public class DashboardService {

    private final CircularRepository circulars;
    private final ForwardingRepository forwardings;
    private final TeamService teamService;

    public DashboardService(CircularRepository circulars, ForwardingRepository forwardings,
                            TeamService teamService) {
        this.circulars = circulars;
        this.forwardings = forwardings;
        this.teamService = teamService;
    }

    /** Distinct calendar years present in the corpus (for the year filter). */
    public List<Integer> years() {
        return circulars.findAll().stream()
                .filter(c -> c.ingestedAt != null)
                .map(c -> c.ingestedAt.atZone(ZoneOffset.UTC).getYear())
                .distinct()
                .sorted((a, b) -> b - a)
                .toList();
    }

    /** Aggregate the acting workspace's circulars into dashboard counters. */
    public DashboardStats stats(Integer year, Instant from, Instant to) {
        List<Circular> scoped = circulars.findAll().stream()
                .filter(c -> inRange(c, year, from, to))
                .toList();

        Map<String, Long> byStatus = new HashMap<>();
        Map<String, Long> byUrgency = new HashMap<>();
        Map<String, Long> byCategory = new HashMap<>();
        Map<String, Long> byTeam = new HashMap<>();
        long dueBreached = 0;
        LocalDate today = LocalDate.now();

        Set<String> scopedIds = scoped.stream()
                .map(c -> c.circularNo).collect(Collectors.toSet());

        for (Circular c : scoped) {
            byStatus.merge(c.status, 1L, Long::sum);
            if (c.urgency != null) byUrgency.merge(c.urgency, 1L, Long::sum);
            for (String cat : c.categories) byCategory.merge(cat, 1L, Long::sum);
            // SLA breach is tracked only while a circular is In Action (FR-SLA-03).
            if ("IN_ACTION".equals(c.status) && c.dueAt != null && c.dueAt.isBefore(today)) {
                dueBreached++;
            }
        }
        forwardings.findAll().stream()
                .filter(f -> scopedIds.contains(f.circularNo))
                .forEach(f -> byTeam.merge(teamService.teamName(f.teamId), 1L, Long::sum));

        return new DashboardStats(scoped.size(), byStatus, byUrgency, byCategory,
                byTeam, dueBreached, 0.18);
    }

    private boolean inRange(Circular c, Integer year, Instant from, Instant to) {
        if (c.ingestedAt == null) {
            return false;
        }
        if (year != null
                && c.ingestedAt.atZone(ZoneOffset.UTC).getYear() != year) {
            return false;
        }
        if (from != null && c.ingestedAt.isBefore(from)) {
            return false;
        }
        return to == null || !c.ingestedAt.isAfter(to);
    }
}
