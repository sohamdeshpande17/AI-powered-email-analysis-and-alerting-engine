package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.dto.Dtos.AppConfigDto;
import com.meridian.circular.dto.Dtos.AppConfigRequest;
import com.meridian.circular.dto.Dtos.ReminderIntervalDto;
import com.meridian.circular.dto.Dtos.ReminderIntervalRequest;
import com.meridian.circular.dto.Dtos.SourceDto;
import com.meridian.circular.dto.Dtos.SourceUpdateRequest;
import com.meridian.circular.security.Actor;
import com.meridian.circular.service.ConfigService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * Admin-only Config module — reminder intervals, generic config keys, and a
 * read view of the Source registry. Authorisation is enforced in the service /
 * actor resolution layer; routes here are unguarded API surface.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService config;

    public ConfigController(ConfigService config) {
        this.config = config;
    }

    // ---- reminder intervals -------------------------------------------------

    /** List the acting workspace's reminder intervals (by sort order). */
    @GetMapping("/reminder-intervals")
    public List<ReminderIntervalDto> listIntervals() {
        return config.listIntervals();
    }

    /** Create a reminder interval. */
    @PostMapping("/reminder-intervals")
    public ReminderIntervalDto createInterval(@RequestBody ReminderIntervalRequest req,
                                              @Actor AppUser actor) {
        return config.createInterval(req, actor);
    }

    /** Update a reminder interval. */
    @PutMapping("/reminder-intervals/{id}")
    public ReminderIntervalDto updateInterval(@PathVariable UUID id,
                                              @RequestBody ReminderIntervalRequest req,
                                              @Actor AppUser actor) {
        return config.updateInterval(id, req, actor);
    }

    /** Delete a reminder interval. */
    @DeleteMapping("/reminder-intervals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInterval(@PathVariable UUID id, @Actor AppUser actor) {
        config.deleteInterval(id, actor);
    }

    // ---- generic key/value config -------------------------------------------

    /** List the acting workspace's generic config key/value entries. */
    @GetMapping("/keys")
    public List<AppConfigDto> listConfig() {
        return config.listConfig();
    }

    /** Update the value of a config key in the acting workspace. */
    @PutMapping("/keys/{key}")
    public AppConfigDto updateConfig(@PathVariable String key,
                                     @RequestBody AppConfigRequest req,
                                     @Actor AppUser actor) {
        return config.updateConfig(key, req, actor);
    }

    // ---- source registry ----------------------------------------------------

    /** Read view of the global Source registry (with health/run status). */
    @GetMapping("/sources")
    public List<SourceDto> listSources() {
        return config.listSources();
    }

    /** Toggle scraping on/off for a source (Config → Scraper resources). */
    @PutMapping("/sources/{id}")
    public SourceDto updateSource(@PathVariable String id,
                                  @RequestBody SourceUpdateRequest req,
                                  @Actor AppUser actor) {
        return config.updateSource(id, req, actor);
    }
}
