package com.meridian.circular.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The summarized circular - one row in the acting workspace's schema
 * (schema-per-tenant). The entity maps the unqualified table name {@code circular},
 * which Hibernate resolves to the current tenant's schema via the connection
 * {@code search_path} (set from the verified bearer token). The canonical
 * {@code public.circular} (the AI truth store) is written only by the processor
 * and is not mapped here.
 *
 * <p>PK is {@code circular_no} (the AI-extracted number, unique within the
 * workspace schema). The table carries no {@code tenant_id} - the schema is the
 * tenant - so this entity extends {@link SystemAudited} (text {@code *_by}
 * columns stamped "System" by the pipeline), not {@link TenantAudited}.
 *
 * <p>{@code sys_period} and {@code search_tsv} are DB-managed (temporal trigger /
 * generated column) and intentionally not mapped.
 */
@Entity
@Table(name = "circular")
public class Circular extends SystemAudited {

    /** AI-extracted number; PK within the workspace schema. */
    @Id
    public String circularNo;

    /** Link to the shared raw layer ({@code public.raw_circular}); the API identifier. */
    public UUID rawCircularId;

    /** Business source code - NSE | BSE | EMAIL (FK public.source.source_id). */
    public String sourceId;

    /** Circular subject line. */
    public String subject;

    /** Display name of the originating source. */
    public String sourceName;

    /** Source-specific origin value (sender address or source URL). */
    public String source;

    /** Editable body content; seeded from the raw circular / AI output. */
    public String bodyContent;

    /** Circular numbers this one refers to (AI-extracted); JSONB list. */
    @JdbcTypeCode(SqlTypes.JSON)
    public List<String> referredCircularIds = new ArrayList<>();

    /** Current lifecycle status - RECEIVED | IN_ACTION | CLOSED (denormalized). */
    public String status = "RECEIVED";

    /** Urgency - critical | high | medium | low. */
    public String urgency;

    /** Category ids ({@code circular_category.id}); Compliance override or AI. JSONB list. */
    @JdbcTypeCode(SqlTypes.JSON)
    public List<String> categories = new ArrayList<>();

    // ---- AI analysis (was circular_analysis / ai.circular) ----

    /** AI-generated summary of the circular. */
    public String summary;

    /** AI-generated required-action text. */
    public String requiredAction;

    /** Key entities the AI extracted; JSONB list. */
    @JdbcTypeCode(SqlTypes.JSON)
    public List<String> keyEntities = new ArrayList<>();

    /** Team names the AI recommends this circular be forwarded to (category map). */
    @JdbcTypeCode(SqlTypes.JSON)
    public List<String> recommendedTeams = new ArrayList<>();

    /** AI confidence score for the analysis (0-1). */
    public Double confidence;

    /** AI-assessed sentiment/tone. */
    public String sentiment;

    /** LLM provider that produced the analysis (e.g. "claude"). */
    public String provider;

    /** LLM model id that produced the analysis. */
    public String model;

    /** Date the circular was issued by the source. */
    public LocalDate issuedAt;

    /** Compliance due date; may be preponed but never postponed (v3 rule). */
    public LocalDate dueAt;

    /** Effective date — when the circular's provisions come into force; may be preponed but never postponed. */
    public LocalDate effectiveAt;

    /** Ingestion timestamp (combined ingested/analysed). Set on first persist. */
    public Instant ingestedAt;

    /** Default {@link #ingestedAt} to now on first persist if unset. */
    @PrePersist
    void onInsert() {
        if (ingestedAt == null) ingestedAt = Instant.now();
    }
}
