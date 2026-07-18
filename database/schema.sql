\encoding UTF8
-- ============================================================================
-- Circular Analyser - single database script (schema-per-tenant)
-- ============================================================================
-- The ONE file that creates the entire database: tears down any previous
-- installation, creates the public (shared) schema + a per-tenant schema for
-- each workspace, and loads seed data. Idempotent - safe to re-run.
--
--   psql -U postgres -d circular_analyser -f schema.sql
--
-- Multi-tenancy model (schema-per-tenant):
--   * A tenant is a DEPARTMENT workspace (Compliance, Legal, Regulatory).
--   * public schema holds: masters (tenant, role, "user", team*,
--     circular_category, source, source_tenant, reminder_interval, app_config),
--     the shared raw layer (raw_circular, raw_circular_document, ingest_audit),
--     and the CANONICAL public.circular - the tenant-agnostic AI store (one row
--     per analysed circular, PK circular_no). public.circular is the source of
--     truth used to prepopulate a new tenant; it is not served to end users
--     directly.
--   * Each workspace gets its OWN schema (lower(tenant.code), e.g.
--     compliance) holding 8 working tables: circular (PK circular_no, no
--     tenant_id), circular_history, circular_workflow, forwarding, audit_event,
--     reclassification, reminder, sla_escalation_log. The schema name IS the
--     tenant, so these tables carry no tenant_id.
--   * provision_tenant(code) creates a workspace schema + its 8 tables and
--     copies the source-tagged rows from public.circular. The backend routes to
--     the right schema via search_path, resolved server-side from the verified
--     bearer token.
-- ============================================================================

-- ############################################################################
-- SECTION A - TEARDOWN
-- ############################################################################

BEGIN;

-- Drop every provisioned tenant schema (dynamic - covers tenants added later).
DO $teardown$
DECLARE r record;
BEGIN
    IF to_regclass('public.tenant') IS NOT NULL THEN
        FOR r IN SELECT code FROM public.tenant LOOP
            EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', lower(r.code));
        END LOOP;
    END IF;
END
$teardown$;

-- public tables (dependency order). The per-tenant working tables used to live
-- in public in earlier versions - dropped here too so an upgrade is clean.
DROP TABLE IF EXISTS public.platform_admin        CASCADE;
DROP TABLE IF EXISTS public.source_tenant         CASCADE;
DROP TABLE IF EXISTS public.sla_escalation_log    CASCADE;
DROP TABLE IF EXISTS public.reminder              CASCADE;
DROP TABLE IF EXISTS public.reminder_interval     CASCADE;
DROP TABLE IF EXISTS public.reclassification      CASCADE;
DROP TABLE IF EXISTS public.circular_workflow     CASCADE;
DROP TABLE IF EXISTS public.forwarding            CASCADE;
DROP TABLE IF EXISTS public.circular_history      CASCADE;
DROP TABLE IF EXISTS public.circular              CASCADE;
DROP TABLE IF EXISTS public.audit_event           CASCADE;
DROP TABLE IF EXISTS public.raw_circular_document CASCADE;
DROP TABLE IF EXISTS public.raw_circular          CASCADE;
DROP TABLE IF EXISTS public.ingest_audit          CASCADE;
DROP TABLE IF EXISTS public.app_config            CASCADE;
DROP TABLE IF EXISTS public.source                CASCADE;
DROP TABLE IF EXISTS public.circular_category     CASCADE;
DROP TABLE IF EXISTS public.team_cc               CASCADE;
DROP TABLE IF EXISTS public.team_feature          CASCADE;
DROP TABLE IF EXISTS public.team_member           CASCADE;
DROP TABLE IF EXISTS public.team                  CASCADE;
DROP TABLE IF EXISTS public."user"                CASCADE;
DROP TABLE IF EXISTS public.role                  CASCADE;
DROP TABLE IF EXISTS public.tenant                CASCADE;
DROP TABLE IF EXISTS public.organization          CASCADE;
DROP TABLE IF EXISTS public.business_function     CASCADE;

-- functions
DROP FUNCTION IF EXISTS provision_tenant(text)           CASCADE;
DROP FUNCTION IF EXISTS set_updated_on()                 CASCADE;
DROP FUNCTION IF EXISTS circular_versioning()            CASCADE;
DROP FUNCTION IF EXISTS audit_event_block_mutation()     CASCADE;

COMMIT;

-- ############################################################################
-- SECTION B - SCHEMA
-- ############################################################################

BEGIN;

-- gen_random_uuid() is built-in in PostgreSQL 13+ - no extension needed.

-- ----------------------------------------------------------------------------
-- Shared trigger functions (live in public; referenced by every schema).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_on()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_on = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- System-versioning for a per-tenant circular. Inserts the OLD row into the
-- SAME schema's circular_history (resolved via TG_TABLE_SCHEMA), so one shared
-- function serves every tenant schema.
CREATE OR REPLACE FUNCTION circular_versioning()
RETURNS TRIGGER AS $$
BEGIN
    OLD.sys_period := tstzrange(lower(OLD.sys_period), now());
    EXECUTE format('INSERT INTO %I.circular_history SELECT ($1).*', TG_TABLE_SCHEMA)
        USING OLD;
    IF TG_OP = 'UPDATE' THEN
        NEW.sys_period := tstzrange(now(), NULL);
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

-- audit_event is append-only (UPDATE/DELETE blocked) in every schema.
CREATE OR REPLACE FUNCTION audit_event_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only - % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SECTION 1 - Tenant (= department workspace)
-- ============================================================================
-- One row per department workspace (Compliance, Legal, Regulatory).
-- lower(code) is the workspace's schema name.

CREATE TABLE tenant (
    tenant_id     INTEGER      PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL UNIQUE,        -- 'COMPLIANCE' -> schema compliance
    name          VARCHAR(120) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    UUID,
    created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID,
    updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================================
-- SECTION 1b - Platform admin (super admin)
-- ============================================================================
-- Platform-level operators that sit ABOVE all departments. They sign in with a
-- username + password (bcrypt) - NOT Entra SSO - and administer the whole
-- application (departments, roles, users across tenants, other super admins)
-- plus act inside any department's workflow (god-mode). Belongs to no tenant.

CREATE TABLE platform_admin (
    admin_id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username             VARCHAR(60)  NOT NULL UNIQUE,
    password_hash        TEXT         NOT NULL,        -- bcrypt ($2a/$2b)
    display_name         VARCHAR(120) NOT NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at        TIMESTAMPTZ,
    created_by           UUID,
    created_on           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           UUID,
    updated_on           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================================
-- SECTION 2 - Roles (per-tenant master) & users (cross-tenant for sign-in)
-- ============================================================================
-- role is a per-department catalog (public + tenant_id, like team/app_config):
-- every department owns its own set of roles, keyed by (tenant_id, id). The
-- standard ids (admin, compliance_head, compliance_officer, auditor_readonly)
-- are seeded into every department so the code's RBAC keeps working everywhere;
-- a department may add its own extra roles on top.

CREATE TABLE role (
    tenant_id   INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    id          VARCHAR(40)  NOT NULL,
    name        VARCHAR(80)  NOT NULL,
    description TEXT,
    created_by  UUID,
    created_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  UUID,
    updated_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE "user" (
    user_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    azure_oid     VARCHAR(100),
    email         VARCHAR(255) NOT NULL,
    display_name  VARCHAR(150) NOT NULL,
    role_id       VARCHAR(40)  NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_by    UUID,
    created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID,
    updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id     INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (tenant_id, email),
    UNIQUE (tenant_id, azure_oid),
    -- a user's role must belong to that user's own department
    FOREIGN KEY (tenant_id, role_id) REFERENCES role(tenant_id, id)
);

-- ============================================================================
-- SECTION 3 - Teams (shared routing master; tenant-scoped by tenant_id)
-- ============================================================================

CREATE TABLE team (
    team_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  UUID,
    created_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  UUID,
    updated_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id   INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE team_member (
    member_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id       UUID         NOT NULL REFERENCES team(team_id) ON DELETE CASCADE,
    email_address VARCHAR(320) NOT NULL,
    display_name  VARCHAR(150),
    created_by    UUID,
    created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID,
    updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id     INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (team_id, email_address)
);

CREATE TABLE team_feature (
    feature_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id      UUID         NOT NULL REFERENCES team(team_id) ON DELETE CASCADE,
    feature_code VARCHAR(60)  NOT NULL,
    created_by   UUID,
    created_on   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   UUID,
    updated_on   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id    INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (team_id, feature_code)
);

CREATE TABLE team_cc (
    cc_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id       UUID         NOT NULL REFERENCES team(team_id) ON DELETE CASCADE,
    email_address VARCHAR(320) NOT NULL,
    display_name  VARCHAR(150),
    cc_type       VARCHAR(10)  NOT NULL DEFAULT 'USER'
        CHECK (cc_type IN ('USER','GROUP')),
    created_by    UUID,
    created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID,
    updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id     INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (team_id, email_address)
);

-- ============================================================================
-- SECTION 4 - Circular category master (shared taxonomy)
-- ============================================================================

CREATE TABLE circular_category (
    id          VARCHAR(50)  PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  UUID,
    created_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  UUID,
    updated_on  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================================
-- SECTION 5 - Source registry (shared) + source -> tenant fan-out map
-- ============================================================================

CREATE TABLE source (
    source_id       VARCHAR(20)  PRIMARY KEY,
    source_type     VARCHAR(30)  NOT NULL
        CHECK (source_type IN ('MAILBOX','WEB_SCRAPER','MANUAL_UPLOAD')),
    name            VARCHAR(120) NOT NULL,
    base_url        TEXT,
    description     TEXT,
    config_json     JSONB        NOT NULL DEFAULT '{}',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error      TEXT,
    created_by      UUID,
    created_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      UUID,
    updated_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (name)
);

-- Which workspaces each shared source feeds; drives the per-tenant fan-out and
-- the prepopulation of a new tenant from public.circular.
CREATE TABLE source_tenant (
    source_id  VARCHAR(20) NOT NULL REFERENCES source(source_id) ON DELETE CASCADE,
    tenant_id  INTEGER     NOT NULL REFERENCES tenant(tenant_id)  ON DELETE CASCADE,
    created_by UUID,
    created_on TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, tenant_id)
);

-- ============================================================================
-- SECTION 6 - Raw layer (shared common pool)
-- ============================================================================

CREATE TABLE raw_circular (
    circular_id  UUID         PRIMARY KEY,
    source_id    VARCHAR(20)  NOT NULL REFERENCES source(source_id),
    circular_no  VARCHAR(255),
    subject      TEXT         NOT NULL,
    issued_at    DATE,
    department   VARCHAR(255),
    email_body   TEXT,
    source_name  VARCHAR(255),
    source       VARCHAR(320),
    source_url   TEXT,
    storage_path TEXT,
    -- Free text (not a user_id UUID): pipeline writes stamp the literal "System".
    created_by   VARCHAR(64),
    created_on   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(64),
    updated_on   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_raw_circular_source_url
    ON raw_circular (source_id, source_url)
    WHERE source_url IS NOT NULL;

CREATE TABLE raw_circular_document (
    document_id        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    circular_id        UUID          NOT NULL
                                     REFERENCES raw_circular(circular_id) ON DELETE CASCADE,
    parent_document_id UUID          REFERENCES raw_circular_document(document_id) ON DELETE CASCADE,
    document_source    VARCHAR(20)   NOT NULL
        CHECK (document_source IN ('attachment','linked_download','archive_child')),
    original_filename  VARCHAR(512)  NOT NULL,
    mime_type          VARCHAR(150),
    size_bytes         BIGINT,
    is_archive         BOOLEAN       NOT NULL DEFAULT FALSE,
    nas_relative_path  VARCHAR(1024) NOT NULL,
    sha256             CHAR(64),
    extracted_text     TEXT,
    -- Free text (not a user_id UUID): pipeline writes stamp the literal "System".
    created_by         VARCHAR(64),
    created_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         VARCHAR(64),
    updated_on         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE ingest_audit (
    run_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id     VARCHAR(20)  NOT NULL REFERENCES source(source_id),
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(12)  NOT NULL
        CHECK (status IN ('RUNNING','SUCCESS','FAILED','PARTIAL')),
    fetched_count INTEGER      DEFAULT 0,
    new_count     INTEGER      DEFAULT 0,
    skipped_count INTEGER      DEFAULT 0,
    failed_count  INTEGER      DEFAULT 0,
    error_summary TEXT,
    created_by    UUID,
    created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID,
    updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================================
-- SECTION 7 - reminder_interval + app_config (shared; tenant-scoped)
-- ============================================================================

CREATE TABLE reminder_interval (
    interval_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    days_after_action INTEGER      NOT NULL CHECK (days_after_action >= 0),
    label             VARCHAR(80)  NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    kind              VARCHAR(20)  NOT NULL DEFAULT 'POST_ACTION'
        CHECK (kind IN ('PRE_DUE','POST_DUE','POST_ACTION')),
    created_by        UUID,
    created_on        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID,
    updated_on        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id         INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    UNIQUE (tenant_id, kind, days_after_action)
);

CREATE TABLE app_config (
    tenant_id   INTEGER      NOT NULL REFERENCES tenant(tenant_id),
    config_key  VARCHAR(80)  NOT NULL,
    value_json  JSONB        NOT NULL,
    description TEXT,
    created_by  UUID,
    created_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  UUID,
    updated_on  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, config_key)
);

-- ============================================================================
-- SECTION 8 - Canonical public.circular (AI truth store; not user-served)
-- ============================================================================
-- One row per analysed circular, tenant-agnostic, PK circular_no. The processor
-- writes here once when AI analysis completes; provision_tenant copies the
-- source-tagged rows into each workspace schema. No workflow/history here.

CREATE TABLE circular (
    circular_no     VARCHAR(255) PRIMARY KEY,
    raw_circular_id UUID         NOT NULL UNIQUE REFERENCES raw_circular(circular_id),
    source_id       VARCHAR(20)  NOT NULL REFERENCES source(source_id),
    subject         TEXT         NOT NULL,
    source_name     VARCHAR(255),
    source          VARCHAR(320),
    body_content    TEXT,
    referred_circular_ids JSONB   NOT NULL DEFAULT '[]',
    status          VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED','IN_ACTION','CLOSED')),
    urgency         VARCHAR(10)
        CHECK (urgency IN ('critical','high','medium','low')),
    categories      JSONB        NOT NULL DEFAULT '[]',
    summary         TEXT,
    required_action TEXT,
    key_entities    JSONB        NOT NULL DEFAULT '[]',
    recommended_teams JSONB      NOT NULL DEFAULT '[]',
    confidence      NUMERIC(4,3),
    sentiment       VARCHAR(20),
    provider        VARCHAR(30),
    model           VARCHAR(80),
    issued_at       DATE,
    due_at          DATE,
    effective_at    DATE,
    ingested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Free text (not a user_id UUID): pipeline writes stamp the literal "System".
    created_by      VARCHAR(64),
    created_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(64),
    updated_on      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pub_circular_source ON circular (source_id);

-- ============================================================================
-- SECTION 9 - provision_tenant(code): create a workspace schema + 8 tables
--             and prepopulate from public.circular (source-tagged rows).
-- ============================================================================
CREATE OR REPLACE FUNCTION provision_tenant(p_code text)
RETURNS void AS $provision$
DECLARE
    s text := lower(p_code);
BEGIN
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', s);

    -- circular (per-workspace working copy; PK circular_no; no tenant_id)
    EXECUTE format($ddl$
        CREATE TABLE IF NOT EXISTS %1$I.circular (
            circular_no     VARCHAR(255) PRIMARY KEY,
            raw_circular_id UUID         NOT NULL UNIQUE REFERENCES public.raw_circular(circular_id),
            source_id       VARCHAR(20)  NOT NULL REFERENCES public.source(source_id),
            subject         TEXT         NOT NULL,
            source_name     VARCHAR(255),
            source          VARCHAR(320),
            body_content    TEXT,
            referred_circular_ids JSONB  NOT NULL DEFAULT '[]',
            status          VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED'
                CHECK (status IN ('RECEIVED','IN_ACTION','CLOSED')),
            urgency         VARCHAR(10)
                CHECK (urgency IN ('critical','high','medium','low')),
            categories      JSONB        NOT NULL DEFAULT '[]',
            summary         TEXT,
            required_action TEXT,
            key_entities    JSONB        NOT NULL DEFAULT '[]',
            recommended_teams JSONB      NOT NULL DEFAULT '[]',
            confidence      NUMERIC(4,3),
            sentiment       VARCHAR(20),
            provider        VARCHAR(30),
            model           VARCHAR(80),
            issued_at       DATE,
            due_at          DATE,
            effective_at    DATE,
            ingested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
            sys_period      TSTZRANGE    NOT NULL DEFAULT tstzrange(now(), NULL),
            -- Free text (not a user_id UUID): pipeline writes stamp the literal "System".
            created_by      VARCHAR(64),
            created_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by      VARCHAR(64),
            updated_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            search_tsv      tsvector GENERATED ALWAYS AS (
                to_tsvector('english',
                    coalesce(circular_no,'')  || ' ' || coalesce(subject,'')      || ' ' ||
                    coalesce(source_name,'')  || ' ' || coalesce(source,'')        || ' ' ||
                    coalesce(body_content,'') || ' ' || coalesce(summary,'')       || ' ' ||
                    coalesce(required_action,''))) STORED
        );
        CREATE TABLE IF NOT EXISTS %1$I.circular_history (LIKE %1$I.circular);

        CREATE TABLE IF NOT EXISTS %1$I.circular_workflow (
            workflow_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no   VARCHAR(255) NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            action        VARCHAR(20)  NOT NULL
                CHECK (action IN ('RECEIVED','IN_ACTION','CLOSED','COMMENT')),
            acted_by      UUID,
            acted_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            comment       TEXT,
            forwarding_id UUID,
            created_by    UUID,
            created_on    TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by    UUID,
            updated_on    TIMESTAMPTZ  NOT NULL DEFAULT now()
        );

        CREATE TABLE IF NOT EXISTS %1$I.forwarding (
            forwarding_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no         VARCHAR(255) NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            team_id             UUID         NOT NULL REFERENCES public.team(team_id),
            forwarded_by        UUID,
            forwarded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            email_subject       TEXT,
            email_body_snapshot TEXT,
            send_status         VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                CHECK (send_status IN ('PENDING','SENT','FAILED','SKIPPED','RECALLED')),
            send_error_message  TEXT,
            bulk_batch_id       UUID,
            reason              TEXT,
            created_by          UUID,
            created_on          TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by          UUID,
            updated_on          TIMESTAMPTZ  NOT NULL DEFAULT now(),
            UNIQUE (circular_no, team_id)
        );
        ALTER TABLE %1$I.circular_workflow
            ADD CONSTRAINT fk_workflow_forwarding
            FOREIGN KEY (forwarding_id) REFERENCES %1$I.forwarding(forwarding_id);

        CREATE TABLE IF NOT EXISTS %1$I.reclassification (
            reclassification_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no         VARCHAR(255) NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            changed_by          UUID         NOT NULL,
            changed_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
            field               VARCHAR(20)  NOT NULL
                CHECK (field IN ('category','urgency','referred_id','due_date','effective_date')),
            action              VARCHAR(10)  NOT NULL
                CHECK (action IN ('add','remove','change')),
            before_value        TEXT,
            after_value         TEXT,
            reason              TEXT,
            created_by          UUID,
            created_on          TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by          UUID,
            updated_on          TIMESTAMPTZ  NOT NULL DEFAULT now()
        );

        CREATE TABLE IF NOT EXISTS %1$I.reminder (
            reminder_id  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no  VARCHAR(255) NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            team_id      UUID         REFERENCES public.team(team_id),
            interval_id  UUID         REFERENCES public.reminder_interval(interval_id),
            sent_to      TEXT,
            send_status  VARCHAR(12)  NOT NULL DEFAULT 'SENT'
                CHECK (send_status IN ('SENT','FAILED')),
            sent_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            created_by   UUID,
            created_on   TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by   UUID,
            updated_on   TIMESTAMPTZ  NOT NULL DEFAULT now(),
            UNIQUE (circular_no, team_id, interval_id)
        );

        CREATE TABLE IF NOT EXISTS %1$I.sla_escalation_log (
            escalation_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no     VARCHAR(255) NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            escalation_type VARCHAR(20)  NOT NULL
                CHECK (escalation_type IN ('BREACH_24','BREACH_48')),
            sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
            created_by      UUID,
            created_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by      UUID,
            updated_on      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            UNIQUE (circular_no, escalation_type)
        );

        -- Workflow attachments (closure evidence). Files live on a SEPARATE NAS
        -- root (app.nas.attachments-root); only metadata + the relative path are
        -- stored here. created_by/updated_by are the uploader's user_id (UUID) —
        -- a human action, unlike the pipeline-written circular tables.
        CREATE TABLE IF NOT EXISTS %1$I.circular_attachment (
            attachment_id     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
            circular_no       VARCHAR(255)  NOT NULL REFERENCES %1$I.circular(circular_no) ON DELETE CASCADE,
            kind              VARCHAR(30)   NOT NULL DEFAULT 'CLOSURE_EVIDENCE'
                CHECK (kind IN ('CLOSURE_EVIDENCE')),
            original_filename VARCHAR(512)  NOT NULL,
            mime_type         VARCHAR(150),
            size_bytes        BIGINT,
            nas_relative_path VARCHAR(1024) NOT NULL,
            sha256            CHAR(64),
            created_by        UUID,
            created_on        TIMESTAMPTZ   NOT NULL DEFAULT now(),
            updated_by        UUID,
            updated_on        TIMESTAMPTZ   NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_circular_attachment_no
            ON %1$I.circular_attachment (circular_no);

        CREATE TABLE IF NOT EXISTS %1$I.audit_event (
            event_id      BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            actor_user_id UUID,
            action        VARCHAR(60) NOT NULL,
            entity_type   VARCHAR(40) NOT NULL,
            entity_id     VARCHAR(255) NOT NULL,
            before_state  JSONB,
            after_state   JSONB,
            reason        TEXT,
            ip_address    INET,
            user_agent    TEXT,
            occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
        );
    $ddl$, s);

    -- triggers (reference the shared public functions)
    EXECUTE format($trg$
        CREATE TRIGGER trg_circular_updated_on
            BEFORE UPDATE ON %1$I.circular
            FOR EACH ROW EXECUTE FUNCTION public.set_updated_on();
        CREATE TRIGGER trg_circular_versioning
            BEFORE UPDATE OR DELETE ON %1$I.circular
            FOR EACH ROW EXECUTE FUNCTION public.circular_versioning();
        CREATE TRIGGER trg_audit_event_no_update
            BEFORE UPDATE ON %1$I.audit_event
            FOR EACH ROW EXECUTE FUNCTION public.audit_event_block_mutation();
        CREATE TRIGGER trg_audit_event_no_delete
            BEFORE DELETE ON %1$I.audit_event
            FOR EACH ROW EXECUTE FUNCTION public.audit_event_block_mutation();
    $trg$, s);

    -- indexes (auto-named per schema; no cross-schema collisions)
    EXECUTE format($idx$
        CREATE INDEX ON %1$I.circular (status);
        CREATE INDEX ON %1$I.circular (ingested_at DESC);
        CREATE INDEX ON %1$I.circular (due_at);
        CREATE INDEX ON %1$I.circular (source_id);
        CREATE INDEX ON %1$I.circular (urgency);
        CREATE INDEX ON %1$I.circular (raw_circular_id);
        CREATE INDEX ON %1$I.circular USING GIN (search_tsv);
        CREATE INDEX ON %1$I.circular USING GIN (categories);
        CREATE INDEX ON %1$I.circular_history (circular_no, sys_period);
        CREATE INDEX ON %1$I.circular_workflow (circular_no, acted_on);
        CREATE INDEX ON %1$I.forwarding (circular_no);
        CREATE INDEX ON %1$I.forwarding (team_id);
        CREATE INDEX ON %1$I.forwarding (bulk_batch_id);
        CREATE INDEX ON %1$I.reclassification (circular_no);
        CREATE INDEX ON %1$I.reminder (circular_no);
        CREATE INDEX ON %1$I.sla_escalation_log (circular_no);
        CREATE INDEX ON %1$I.audit_event (entity_type, entity_id);
        CREATE INDEX ON %1$I.audit_event (occurred_at DESC);
    $idx$, s);

    -- prepopulate: copy the source-tagged canonical circulars + a RECEIVED row
    EXECUTE format($copy$
        INSERT INTO %1$I.circular (circular_no, raw_circular_id, source_id, subject,
            source_name, source, body_content, referred_circular_ids, status, urgency,
            categories, summary, required_action, key_entities, recommended_teams,
            confidence, sentiment, provider, model, issued_at, due_at, ingested_at)
        SELECT c.circular_no, c.raw_circular_id, c.source_id, c.subject,
            c.source_name, c.source, c.body_content, c.referred_circular_ids, c.status, c.urgency,
            c.categories, c.summary, c.required_action, c.key_entities, c.recommended_teams,
            c.confidence, c.sentiment, c.provider, c.model, c.issued_at, c.due_at, c.ingested_at
        FROM public.circular c
        WHERE c.source_id IN (
            SELECT st.source_id FROM public.source_tenant st
            JOIN public.tenant t ON t.tenant_id = st.tenant_id
            WHERE t.code = %2$L)
        ON CONFLICT (circular_no) DO NOTHING;

        INSERT INTO %1$I.circular_workflow (circular_no, action)
        SELECT c.circular_no, 'RECEIVED'
        FROM %1$I.circular c
        WHERE NOT EXISTS (
            SELECT 1 FROM %1$I.circular_workflow w
            WHERE w.circular_no = c.circular_no AND w.action = 'RECEIVED');
    $copy$, s, upper(p_code));
END;
$provision$ LANGUAGE plpgsql;

-- ============================================================================
-- SECTION 10 - public indexes + updated_on triggers
-- ============================================================================

CREATE INDEX idx_raw_circular_src    ON raw_circular (source_id, created_on DESC);
CREATE INDEX idx_raw_circular_no     ON raw_circular (circular_no);
CREATE INDEX idx_raw_doc_circular    ON raw_circular_document (circular_id);
CREATE INDEX idx_raw_doc_parent      ON raw_circular_document (parent_document_id);
CREATE INDEX idx_raw_doc_text_search ON raw_circular_document
    USING GIN (to_tsvector('english', coalesce(extracted_text, '')));
CREATE INDEX idx_ingest_audit_source ON ingest_audit (source_id, started_at DESC);
CREATE INDEX idx_team_member_team    ON team_member (team_id);
CREATE INDEX idx_team_feature_team   ON team_feature (team_id);
CREATE INDEX idx_team_feature_code   ON team_feature (feature_code);

CREATE TRIGGER trg_tenant_updated_on
    BEFORE UPDATE ON tenant FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_platform_admin_updated_on
    BEFORE UPDATE ON platform_admin FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_user_updated_on
    BEFORE UPDATE ON "user" FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_team_updated_on
    BEFORE UPDATE ON team FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_source_updated_on
    BEFORE UPDATE ON source FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_raw_circular_updated_on
    BEFORE UPDATE ON raw_circular FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_circular_updated_on
    BEFORE UPDATE ON circular FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_reminder_interval_updated_on
    BEFORE UPDATE ON reminder_interval FOR EACH ROW EXECUTE FUNCTION set_updated_on();
CREATE TRIGGER trg_app_config_updated_on
    BEFORE UPDATE ON app_config FOR EACH ROW EXECUTE FUNCTION set_updated_on();

COMMIT;

-- ############################################################################
-- SECTION C - SEED DATA
-- ############################################################################

BEGIN;

INSERT INTO tenant (tenant_id, code, name) VALUES
    (1, 'COMPLIANCE', 'Compliance'),
    (2, 'LEGAL',      'Legal'),
    (3, 'REGULATORY', 'Regulatory')
ON CONFLICT (tenant_id) DO NOTHING;

-- Bootstrap super admin. Default password is 'ChangeMe@123' (bcrypt below);
-- must_change_password forces a reset on first login. Change/rotate in any
-- shared environment.
INSERT INTO platform_admin (username, password_hash, display_name, must_change_password)
VALUES ('superadmin',
        '$2b$10$C/Hm9VaVcV6lEPD2F6XY0OFVUt2FqawucFqoby19OyXRb7DLKL4Fa',
        'Super Admin', TRUE)
ON CONFLICT (username) DO NOTHING;

-- The standard roles, seeded into EVERY department (cross join) so RBAC works
-- in each workspace. Super admins can add department-specific roles on top.
INSERT INTO role (tenant_id, id, name, description)
SELECT t.tenant_id, r.id, r.name, r.description
FROM tenant t
CROSS JOIN (VALUES
    ('admin',              'Administrator',
        'Manages application users, roles, the teams master, Sources and Config.'),
    ('compliance_head',    'Compliance Head',
        'Owns the compliance function; oversight, reports and masters.'),
    ('compliance_officer', 'Compliance Officer',
        'Reviews, re-classifies and forwards circulars; tracks to closure.'),
    ('auditor_readonly',   'Auditor (Read-only)',
        'Read-only access to all circulars and the audit trail.')
) AS r(id, name, description)
ON CONFLICT (tenant_id, id) DO NOTHING;

INSERT INTO "user" (email, display_name, role_id, tenant_id)
VALUES ('admin@meridiancapital.com', 'Admin User', 'admin', 1)
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO circular_category (id, name, description) VALUES
    ('regulatory',          'Regulatory',
        'Regulator-issued circulars (RBI, SEBI, NSE, BSE, IRDAI, MCA, etc.).'),
    ('compliance',          'Compliance',
        'Internal/external compliance obligations and gap notices.'),
    ('escalation',          'Escalation',
        'Incidents or matters requiring urgent escalation.'),
    ('change_notification', 'Change Notification',
        'Operational or system change notifications.'),
    ('general_inquiry',     'General Inquiry',
        'General correspondence not fitting other categories.')
ON CONFLICT (id) DO NOTHING;

INSERT INTO source (source_id, source_type, name, base_url, description, config_json)
VALUES
    ('NSE',   'WEB_SCRAPER', 'National Stock Exchange of India',
     'https://www.nseindia.com',
     'NSE circulars source (CSV endpoint).',
     '{"poll_interval_minutes": 30, "window_days": 1}'),
    ('EMAIL', 'MAILBOX',     'Compliance O365 Inbox',
     NULL,
     'Primary regulatory mailbox - polled for UNREAD mail. Only mail from '
     'allowed_senders is published to Kafka; an empty list publishes all.',
     '{"poll_interval_minutes": 5, "mailbox": "analyser@meridiancapital.com", "protocol": "graph", "folder": "Inbox", "allowed_senders": []}'),
    ('MANUAL', 'MANUAL_UPLOAD', 'Manual Upload',
     NULL,
     'Circulars uploaded by users from the web UI (any role).',
     '{}')
ON CONFLICT (source_id) DO NOTHING;

-- NSE + EMAIL feed every workspace; MANUAL routes to the uploader's workspace.
INSERT INTO source_tenant (source_id, tenant_id)
SELECT s.source_id, t.tenant_id
FROM (VALUES ('NSE'), ('EMAIL')) AS s(source_id)
CROSS JOIN tenant t
ON CONFLICT (source_id, tenant_id) DO NOTHING;

INSERT INTO app_config (tenant_id, config_key, value_json, description)
VALUES
    (1, 'reminder.pause_on_closed', 'true',
     'Whether reminders pause once a circular is CLOSED.'),
    (1, 'reminder.send_window',
     '{"start_hour":9,"end_hour":19,"timezone":"Asia/Kolkata"}',
     'Time-of-day window in which scheduled reminders may be sent.')
ON CONFLICT (tenant_id, config_key) DO NOTHING;

-- INSERT INTO team (name, description, created_by, tenant_id)
-- SELECT v.name, v.description, u.user_id, 1
-- FROM (
--     VALUES
--         ('Treasury & ALM',         'Liquidity, ALM and treasury operations.'),
--         ('Information Technology', 'IT and system change management.'),
--         ('Risk Management',        'Enterprise and operational risk.'),
--         ('Legal',                  'Legal and regulatory liaison.'),
--         ('Operations',             'Branch and back-office operations.'),
--         ('Internal Audit',         'Internal audit and assurance.')
-- ) AS v(name, description)
-- LEFT JOIN "user" u ON u.email = 'admin@meridiancapital.com'
-- ON CONFLICT (tenant_id, name) DO NOTHING;

-- INSERT INTO team_member (team_id, email_address, display_name, tenant_id)
-- SELECT t.team_id, m.email_address, m.display_name, 1
-- FROM (
--     VALUES
--         ('Treasury & ALM',         'alm.head@meridiancapital.com',        'ALM Head'),
--         ('Treasury & ALM',         'treasury.ops@meridiancapital.com',    'Treasury Ops'),
--         ('Information Technology', 'infosec.lead@meridiancapital.com',    'InfoSec Lead'),
--         ('Information Technology', 'it.change.owner@meridiancapital.com', 'IT Change Owner'),
--         ('Risk Management',        'orm.head@meridiancapital.com',        'Operational Risk Head'),
--         ('Legal',                  'legal.head@meridiancapital.com',      'Legal Head'),
--         ('Operations',             'ops.head@meridiancapital.com',        'Operations Head'),
--         ('Internal Audit',         'ia.head@meridiancapital.com',         'Internal Audit Head')
-- ) AS m(team_name, email_address, display_name)
-- JOIN team t ON t.name = m.team_name
-- ON CONFLICT (team_id, email_address) DO NOTHING;

-- INSERT INTO team_feature (team_id, feature_code, tenant_id)
-- SELECT t.team_id, f.feature_code, 1
-- FROM (
--     VALUES
--         ('Treasury & ALM',         'LIQUIDITY'),
--         ('Treasury & ALM',         'ALM'),
--         ('Information Technology', 'INFOSEC'),
--         ('Information Technology', 'CHANGE_MGMT'),
--         ('Risk Management',        'OP_RISK'),
--         ('Risk Management',        'ENTERPRISE_RISK'),
--         ('Legal',                  'LEGAL'),
--         ('Legal',                  'CONTRACTS'),
--         ('Operations',             'BRANCH_OPS'),
--         ('Operations',             'BACK_OFFICE'),
--         ('Internal Audit',         'AUDIT'),
--         ('Internal Audit',         'ASSURANCE')
-- ) AS f(team_name, feature_code)
-- JOIN team t ON t.name = f.team_name
-- ON CONFLICT (team_id, feature_code) DO NOTHING;

INSERT INTO reminder_interval (days_after_action, label, kind, is_active, sort_order, updated_by, tenant_id)
SELECT v.days_after_action, v.label, v.kind, v.is_active, v.sort_order, u.user_id, 1
FROM (
    VALUES
        ( 3,  'T+3 days  - first nudge',                'POST_ACTION', TRUE,   10),
        ( 7,  'T+7 days  - second nudge',               'POST_ACTION', TRUE,   20),
        (14,  'T+14 days - escalation',                 'POST_ACTION', TRUE,   30),
        (30,  'T+30 days - final notice',               'POST_ACTION', TRUE,   40),
        (30,  'T-30 days before due (first warning)',   'PRE_DUE',     FALSE, -30),
        (15,  'T-15 days before due (second warning)',  'PRE_DUE',     FALSE, -15),
        ( 7,  'T-7 days before due (final warning)',    'PRE_DUE',     FALSE,  -7),
        ( 1,  'T+1 day after due - daily cadence',      'POST_DUE',    FALSE,   1),
        ( 3,  'T+3 days after due',                     'POST_DUE',    FALSE,   3),
        ( 7,  'T+7 days after due',                     'POST_DUE',    FALSE,   7),
        (14,  'T+14 days after due',                    'POST_DUE',    FALSE,  14)
) AS v(days_after_action, label, kind, is_active, sort_order)
LEFT JOIN "user" u ON u.email = 'admin@meridiancapital.com'
ON CONFLICT (tenant_id, kind, days_after_action) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Sample raw + canonical circular (the AI truth store). Per-workspace copies
-- are created by provision_tenant below.
-- ----------------------------------------------------------------------------
-- INSERT INTO raw_circular (circular_id, source_id, circular_no, subject, issued_at,
--                           department, email_body, source_name, source, source_url, storage_path)
-- VALUES
--     ('11111111-1111-1111-1111-111111111111', 'EMAIL', 'RBI/2026-27/45',
--      'URGENT: Revised Liquidity Coverage Ratio (LCR) computation - RBI/2026-27/45',
--      current_date - 1, 'Risk Regulatory',
--      'The Reserve Bank of India has issued a circular mandating a revised LCR computation methodology effective April 1, 2026. This circular supersedes RBI/2025-26/12.',
--      'Reserve Bank of India', 'risk.regulatory@centralbank.gov.in',
--      NULL, 'EMAIL/2026/06/11111111-1111-1111-1111-111111111111'),
--     ('22222222-2222-2222-2222-222222222222', 'NSE', 'NSE/CMPT/2026/89',
--      'NSE circular on revised member compliance reporting formats',
--      current_date - 2, 'Compliance',
--      NULL, 'National Stock Exchange of India', 'https://www.nseindia.com',
--      'https://nsearchives.nseindia.com/content/circulars/CMPT2026_89.pdf',
--      'NSE/2026/06/22222222-2222-2222-2222-222222222222')
-- ON CONFLICT (circular_id) DO NOTHING;

-- INSERT INTO raw_circular_document (circular_id, document_source, original_filename,
--                                    mime_type, size_bytes, nas_relative_path)
-- SELECT v.circular_id::uuid, v.doc_source, v.filename, v.mime_type, v.size_bytes,
--        rc.storage_path || '/' || v.filename
-- FROM (
--     VALUES
--         ('11111111-1111-1111-1111-111111111111', 'attachment',      'RBI_Circular_2026_27_45.pdf', 'application/pdf', 245000),
--         ('22222222-2222-2222-2222-222222222222', 'linked_download', 'CMPT2026_89.pdf',             'application/pdf', 156000)
-- ) AS v(circular_id, doc_source, filename, mime_type, size_bytes)
-- JOIN raw_circular rc ON rc.circular_id = v.circular_id::uuid
-- WHERE NOT EXISTS (
--     SELECT 1 FROM raw_circular_document d WHERE d.circular_id = v.circular_id::uuid
-- );

-- Canonical public.circular (tenant-agnostic; both RECEIVED).
-- INSERT INTO circular (circular_no, raw_circular_id, source_id, subject, source_name,
--                       source, body_content, referred_circular_ids, status, urgency,
--                       categories, summary, required_action, key_entities, confidence,
--                       sentiment, provider, model, issued_at, due_at, ingested_at)
-- VALUES
--     ('RBI/2026-27/45', '11111111-1111-1111-1111-111111111111', 'EMAIL',
--      'URGENT: Revised Liquidity Coverage Ratio (LCR) computation - RBI/2026-27/45',
--      'Reserve Bank of India', 'risk.regulatory@centralbank.gov.in',
--      'The Reserve Bank of India has issued a circular mandating a revised LCR computation methodology effective April 1, 2026. This circular supersedes RBI/2025-26/12.',
--      '["RBI/2025-26/12","DBR.LCR/2024-15"]', 'RECEIVED', 'critical',
--      '["regulatory","compliance"]',
--      'RBI mandates a revised LCR computation methodology effective Apr 1, 2026.',
--      'Circulate to Treasury and ALM; update the LCR engine; complete an impact assessment within 48 hours.',
--      '["RBI/2026-27/45","LCR","HQLA"]', 0.95, 'urgent', 'claude', 'claude-sonnet-4-6',
--      current_date - 1, current_date + 1, now() - interval '1 day'),
--     ('NSE/CMPT/2026/89', '22222222-2222-2222-2222-222222222222', 'NSE',
--      'NSE circular on revised member compliance reporting formats',
--      'National Stock Exchange of India', 'https://www.nseindia.com',
--      NULL, '[]', 'RECEIVED', 'high',
--      '["regulatory"]',
--      'NSE revised the member compliance reporting formats; submissions must use the new format from the next cycle.',
--      'Update the compliance reporting workflow; brief Operations.',
--      '["NSE","compliance reporting"]', 0.89, 'neutral', 'claude', 'claude-sonnet-4-6',
--      current_date - 2, current_date + 3, now() - interval '2 days')
-- ON CONFLICT (circular_no) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Provision a schema per workspace (creates the 8 tables + copies the
-- source-tagged canonical circulars + a RECEIVED workflow row).
-- ----------------------------------------------------------------------------
SELECT provision_tenant(code) FROM tenant ORDER BY tenant_id;

COMMIT;

-- ============================================================================
-- End of schema.sql
-- ============================================================================
