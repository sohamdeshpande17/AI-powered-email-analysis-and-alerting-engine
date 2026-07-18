package com.meridian.circular.web;

import com.meridian.circular.dto.Dtos.AuditDto;
import com.meridian.circular.dto.Dtos.PageResponse;
import com.meridian.circular.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The append-only audit log, with search + date filters (BRD FR-AUDIT). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /** Paged audit trail for the acting workspace, with action/search/date filters. */
    @GetMapping
    public PageResponse<AuditDto> list(@RequestParam(required = false) String action,
                                       @RequestParam(required = false) String search,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false, defaultValue = "0") Integer page,
                                       @RequestParam(required = false, defaultValue = "10") Integer size) {
        return audit.list(action, search, DateParams.from(from), DateParams.to(to), page, size);
    }
}
