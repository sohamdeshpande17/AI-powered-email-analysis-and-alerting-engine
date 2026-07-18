package com.meridian.circular.service;

import com.meridian.circular.domain.AppConfig;
import com.meridian.circular.domain.AppUser;
import com.meridian.circular.domain.ReminderInterval;
import com.meridian.circular.domain.Source;
import com.meridian.circular.dto.Dtos.AppConfigDto;
import com.meridian.circular.dto.Dtos.AppConfigRequest;
import com.meridian.circular.dto.Dtos.ReminderIntervalDto;
import com.meridian.circular.dto.Dtos.ReminderIntervalRequest;
import com.meridian.circular.dto.Dtos.SourceDto;
import com.meridian.circular.dto.Dtos.SourceUpdateRequest;
import com.meridian.circular.repo.AppConfigRepository;
import com.meridian.circular.repo.ReminderIntervalRepository;
import com.meridian.circular.repo.SourceRepository;
import com.meridian.circular.security.TenantContext;
import com.meridian.circular.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin-only Config module. Owns the fixed reminder interval list and a
 * generic key/value config store reserved for future settings. Also exposes
 * the Source registry for monitoring.
 */
@Service
public class ConfigService {

    private final ReminderIntervalRepository intervals;
    private final AppConfigRepository config;
    private final SourceRepository sources;
    private final AuditService audit;

    public ConfigService(ReminderIntervalRepository intervals,
                         AppConfigRepository config,
                         SourceRepository sources,
                         AuditService audit) {
        this.intervals = intervals;
        this.config = config;
        this.sources = sources;
        this.audit = audit;
    }

    // ---- reminder intervals -------------------------------------------------

    public List<ReminderIntervalDto> listIntervals() {
        return intervals.findAllByTenantIdOrderBySortOrderAsc(TenantContext.get()).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public ReminderIntervalDto createInterval(ReminderIntervalRequest req, AppUser actor) {
        if (req.daysAfterAction() == null || req.daysAfterAction() < 0) {
            throw ApiException.badRequest("Days must be a non-negative integer.");
        }
        String kind = normaliseKind(req.kind());
        // Uniqueness is composite (kind, daysAfterAction) from V2.2.0 onward —
        // -30 PRE_DUE and +30 POST_DUE are distinct rows.
        boolean dup = intervals.findAllByTenantIdOrderBySortOrderAsc(TenantContext.get()).stream()
                .anyMatch(x -> kind.equals(x.kind)
                        && req.daysAfterAction().equals(x.daysAfterAction));
        if (dup) {
            throw ApiException.badRequest("An interval already exists for that (kind, days) pair.");
        }
        ReminderInterval r = new ReminderInterval();
        r.daysAfterAction = req.daysAfterAction();
        r.kind = kind;
        r.label = (req.label() == null || req.label().isBlank())
                ? defaultLabel(kind, req.daysAfterAction())
                : req.label().trim();
        r.isActive = req.isActive() == null || req.isActive();
        r.sortOrder = req.sortOrder() != null ? req.sortOrder()
                : intervals.findAllByTenantIdOrderBySortOrderAsc(TenantContext.get()).stream()
                        .mapToInt(x -> x.sortOrder == null ? 0 : x.sortOrder)
                        .max().orElse(0) + 10;
        r.updatedBy = actor.userId;
        intervals.save(r);
        audit.record(actor, "CONFIG_UPDATE", "reminder_interval", r.intervalId.toString(),
                "Added reminder interval " + r.kind + " " + r.daysAfterAction + "d");
        return toDto(r);
    }

    @Transactional
    public ReminderIntervalDto updateInterval(UUID id, ReminderIntervalRequest req, AppUser actor) {
        ReminderInterval r = intervals.findById(id)
                .orElseThrow(() -> ApiException.notFound("Reminder interval"));
        if (req.daysAfterAction() != null) r.daysAfterAction = req.daysAfterAction();
        if (req.label() != null) r.label = req.label();
        if (req.isActive() != null) r.isActive = req.isActive();
        if (req.sortOrder() != null) r.sortOrder = req.sortOrder();
        if (req.kind() != null) r.kind = normaliseKind(req.kind());
        r.updatedBy = actor.userId;
        intervals.save(r);
        audit.record(actor, "CONFIG_UPDATE", "reminder_interval", id.toString(),
                "Updated reminder interval " + r.kind + " " + r.daysAfterAction + "d");
        return toDto(r);
    }

    @Transactional
    public void deleteInterval(UUID id, AppUser actor) {
        ReminderInterval r = intervals.findById(id)
                .orElseThrow(() -> ApiException.notFound("Reminder interval"));
        intervals.delete(r);
        audit.record(actor, "CONFIG_UPDATE", "reminder_interval", id.toString(),
                "Removed reminder interval " + r.kind + " " + r.daysAfterAction + "d");
    }

    private ReminderIntervalDto toDto(ReminderInterval r) {
        return new ReminderIntervalDto(r.intervalId, r.daysAfterAction, r.label,
                r.isActive, r.sortOrder, r.kind, r.updatedOn);
    }

    private static String normaliseKind(String raw) {
        if (raw == null || raw.isBlank()) return "POST_ACTION";
        String up = raw.trim().toUpperCase();
        if (!up.equals("PRE_DUE") && !up.equals("POST_DUE") && !up.equals("POST_ACTION")) {
            throw ApiException.badRequest(
                    "kind must be one of PRE_DUE, POST_DUE, POST_ACTION");
        }
        return up;
    }

    private static String defaultLabel(String kind, int days) {
        return switch (kind) {
            case "PRE_DUE" -> "T-" + days + " days before due";
            case "POST_DUE" -> "T+" + days + " days after due";
            default -> "T+" + days + " days after action";
        };
    }

    // ---- generic key/value config ------------------------------------------

    public List<AppConfigDto> listConfig() {
        return config.findAllByTenantId(TenantContext.get()).stream().map(this::toDto).toList();
    }

    @Transactional
    public AppConfigDto updateConfig(String key, AppConfigRequest req, AppUser actor) {
        AppConfig c = config.findById(new AppConfig.Key(TenantContext.get(), key))
                .orElseThrow(() -> ApiException.notFound("Config key"));
        c.valueJson = req.value();
        c.updatedBy = actor.userId;
        config.save(c);
        audit.record(actor, "CONFIG_UPDATE", "app_config", key,
                "Updated config " + key);
        return toDto(c);
    }

    private AppConfigDto toDto(AppConfig c) {
        return new AppConfigDto(c.configKey, c.valueJson, c.description,
                c.updatedBy, c.updatedOn);
    }

    // ---- source registry ---------------------------------------------------

    public List<SourceDto> listSources() {
        return sources.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    /** Turn scraping on/off for a single source (Config → Scraper resources). */
    @Transactional
    public SourceDto updateSource(String id, SourceUpdateRequest req, AppUser actor) {
        Source s = sources.findById(id)
                .orElseThrow(() -> ApiException.notFound("Source"));
        if (req.isActive() != null) {
            s.isActive = req.isActive();
        }
        sources.save(s);
        audit.record(actor, "CONFIG_UPDATE", "source", id,
                "Scraping " + (s.isActive ? "enabled" : "disabled") + " for " + s.name);
        return toDto(s);
    }

    @SuppressWarnings("unchecked")
    private SourceDto toDto(Source s) {
        return new SourceDto(s.sourceId, s.sourceType, s.name, s.description,
                (java.util.Map<String, Object>) (s.configJson == null
                        ? java.util.Map.of() : s.configJson),
                s.isActive, s.lastRunAt, s.lastSuccessAt, s.lastError);
    }
}
