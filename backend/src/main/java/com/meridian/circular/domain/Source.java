package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A registered ingestion source — NSE / BSE web scrapers, the EMAIL mailbox, or
 * MANUAL upload. <strong>Global</strong> shared infrastructure (multi-tenancy):
 * sources are not owned by a workspace (extends {@link Audited}, no
 * {@code tenant_id}); which workspaces a source feeds is defined by the
 * {@code source_tenant} mapping, which drives the summarized fan-out.
 *
 * <p>v3: PK is the business code (was {@code source_code}; the old UUID id and
 * {@code schema_name} are gone). Scraper poll schedules live in
 * {@link #configJson} (e.g. {@code {"poll_interval_minutes": 30}}).
 */
@Entity
@Table(name = "source")
public class Source extends Audited {

    /** PK — business source code (e.g. {@code NSE}, {@code EMAIL}, {@code MANUAL}). */
    @Id
    public String sourceId;

    /** Adapter type — MAILBOX | WEB_SCRAPER | MANUAL_UPLOAD. */
    public String sourceType;

    /** Human-readable source name. */
    public String name;

    /** Base URL for scrapers / linked downloads (null for mailbox/manual). */
    public String baseUrl;

    /** Description of the source. */
    public String description;

    /** Source-specific config (poll interval, mailbox, allowed senders, …); JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> configJson = new HashMap<>();

    /** Whether ingestion from this source is enabled. */
    public boolean isActive = true;

    /** Timestamp of the last ingestion run (success or failure). */
    public Instant lastRunAt;

    /** Timestamp of the last successful ingestion run. */
    public Instant lastSuccessAt;

    /** Last error message from a failed run, if any. */
    public String lastError;
}
