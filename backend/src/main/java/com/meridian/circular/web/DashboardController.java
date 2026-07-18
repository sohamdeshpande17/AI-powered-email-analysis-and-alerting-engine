package com.meridian.circular.web;

import com.meridian.circular.dto.Dtos.DashboardStats;
import com.meridian.circular.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Compliance dashboard aggregates, with year + date filters (BRD FR-DASH). */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** Dashboard aggregates for the acting workspace, with year + date filters. */
    @GetMapping
    public DashboardStats stats(@RequestParam(required = false) Integer year,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        return dashboard.stats(year, DateParams.from(from), DateParams.to(to));
    }
}
