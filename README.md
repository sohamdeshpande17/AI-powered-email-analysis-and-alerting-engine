# Circular Analyser

**AI-powered regulatory circular intake, classification, and compliance workflow engine.**

Regulatory circulars (RBI, SEBI, NSE, BSE, IRDAI, MCA, etc.) arrive constantly by
email and web postings. Circular Analyser automates the pipeline from *"a
document landed somewhere"* to *"the right team acted on it before the
deadline, with an audit trail"*:

1. **Collect** — poll a compliance mailbox and scrape exchange circular
   pages for new documents (email body + PDF/Word/Excel/ZIP attachments).
2. **Understand** — an LLM (Claude or OpenAI) reads each circular and its
   attachments and extracts urgency, category, a plain-English summary, the
   required action, key entities, a due date, and which internal team should
   own it.
3. **Route** — a compliance officer reviews/corrects the AI's classification
   and forwards the circular to one or more internal teams by email; the
   circular moves from `RECEIVED` → `IN_ACTION`.
4. **Track to closure** — the circular stays open until someone closes it
   with a comment and optional evidence attachments; reminders and SLA
   escalation are built into the data model for teams that don't close in
   time.
5. **Audit** — every reclassification, forward, recall, and status change is
   appended to an audit trail, and every workspace (department) is isolated
   from the others.

## Architecture

Four independently-run components, connected by Kafka and a shared
PostgreSQL database:

```
 ┌──────────────┐   ┌───────────────┐   ┌────────────────────────┐
 │  NSE scraper │   │  Email poller │   │   Backend REST API      │
 │  (scraper/)  │   │  (scraper/)   │   │   (manual upload)       │
 └──────┬───────┘   └───────┬───────┘   └────────────┬─────────────┘
        │ raw payload + documents saved to NAS, envelope published to Kafka
        └───────────────────┴────────────────────────┘
                             │
                    circular.raw.v1 (Kafka)
                             ▼
                 ┌───────────────────────────┐
                 │      circular-analyser      │
                 │      (AI processor)         │
                 │  consume → map → raw_circular│
                 │  → LLM analysis (NAS docs)   │
                 │  → circular + RECEIVED event │
                 └──────────────┬───────────────┘
                   poison / exhausted retries
                             │
                             ▼
                  circular.raw.bo.v1 (back-out topic)

                             │ writes
                             ▼
                 ┌───────────────────────────┐
                 │   PostgreSQL (database/)    │
                 │   schema-per-tenant          │
                 └──────────────┬───────────────┘
                             │ reads / writes
                             ▼
                 ┌───────────────────────────┐
                 │  Backend REST API (backend/) │
                 │  Spring Boot — workflow,      │
                 │  forwarding, audit, reminders │
                 └──────────────┬───────────────┘
                             │ HTTP (JSON)
                             ▼
                        Frontend (not yet built —
                        see "Project status" below)
```

| Component | Path | Language / stack | What it does |
|---|---|---|---|
| **Scraper / source services** | [`scraper/`](scraper/) | Python 3.11+ | Polls the NSE circular CSV endpoint and an O365 mailbox (Microsoft Graph), saves documents to a NAS path, publishes raw events to Kafka. No AI, no DB writes to the working tables. |
| **AI processor (circular-analyser)** | [`circular-analyser/`](circular-analyser/) | Python 3.11+ | Single Kafka consumer: consume → map (per-source) → raw DB write → LLM analysis (Claude or OpenAI) → canonical + per-tenant `circular` upsert. Failed messages retry then park on a back-out topic for manual replay. |
| **Database** | [`database/`](database/) | PostgreSQL 14+ | One script (`schema.sql`) creates the shared `public` schema (masters, raw layer, canonical AI store) plus one schema per department workspace (schema-per-tenant multi-tenancy), with seed data. |
| **Backend API** | [`backend/`](backend/) | Java 21, Spring Boot 3.4 | REST API for the compliance workflow: review/reclassify, forward to teams (sends email via Microsoft Graph), bulk-forward, recall, close with evidence, reminders, teams/users/roles admin, audit trail. Multi-tenant via Hibernate schema-per-request routing. |

Each subfolder has its own README with full setup, configuration, and API
details — this file is the map.

## Key design points

- **Schema-per-tenant multi-tenancy.** Each department (e.g. Compliance,
  Legal, Regulatory) gets its own PostgreSQL schema with its own working
  copy of `circular`, `circular_workflow`, `forwarding`, `audit_event`, etc.
  The AI runs once per circular against a shared canonical store
  (`public.circular`); the result is fanned out to every workspace the
  source feeds.
- **NAS-first documents.** Attachment/document bytes never travel through
  Kafka — they're written straight to a NAS path by the source service, and
  read back by the AI stage and the backend. Kafka only carries metadata.
- **At-least-once + idempotent writes.** Every DB write in the pipeline is
  an upsert keyed on a stable id, so replaying a Kafka message (or a BO
  replay after a fix) never double-processes a circular.
- **Failure isolation.** Poison messages and exhausted retries are parked on
  a Kafka back-out topic with structured error headers, and replayed
  manually once the underlying issue is fixed — the main consume loop never
  blocks on one bad message.
- **Append-only audit trail.** `audit_event` blocks UPDATE/DELETE at the
  database level (a trigger raises on any attempt), and every circular
  change is versioned via `circular_history`.

## Getting started

Bring the components up in this order — each layer depends on the one
before it.

### 1. Database

```bash
psql -h localhost -U postgres -d circular_analyser -f database/schema.sql
```

Creates and seeds everything: tenants, roles, a bootstrap admin user
(`admin@meridiancapital.com`), circular categories, sources (`NSE`, `EMAIL`,
`MANUAL`), and one schema per tenant. See [`database/README.md`](database/README.md).

### 2. Backend API

```bash
cd backend
mvn spring-boot:run
```

Requires JDK 21+, Maven 3.9+, and the database above running on
`localhost:5432`. Serves on `http://localhost:8080`; Swagger UI at
`/swagger-ui.html`. See [`backend/README.md`](backend/README.md) for the
full REST surface and the dev auth model (`X-User-Id` header until Azure AD
SSO is wired in).

> Environment-specific config (`application.yml`, DB credentials, the
> `app.graph.*` / `app.sso.*` Microsoft Graph & Entra ID settings) is not
> committed — create it locally per your environment.

### 3. AI processor (circular-analyser)

```bash
cd circular-analyser
pip install -r requirements.txt
python -m processor.main
```

Requires a running Kafka broker and the database above. Set
`LLM_PROVIDER` (`claude` or `openai`) and the matching API key env var. See
[`circular-analyser/README.md`](circular-analyser/README.md) for the full
environment variable reference and the back-out/replay workflow.

### 4. Scrapers / source services

```bash
cd scraper
pip install -r requirements.txt
python scheduler.py              # NSE (and any other WEB_SCRAPER sources)
python email_poller.py           # O365 mailbox poller
```

Poll intervals and mailbox/allow-list config come from the `source` table
(`config_json`), not files — edit them via SQL or (once built) the admin UI.
O365 credentials come from `O365_TENANT_ID` / `O365_CLIENT_ID` /
`O365_CLIENT_SECRET` env vars.

## Project status

**Implemented:** ingestion (NSE scraper + O365 email poller + manual
upload), AI classification pipeline with retry/back-out handling, the full
circular workflow (review, forward, bulk-forward, recall, close with
evidence), teams/users/roles administration, audit trail, schema-per-tenant
multi-tenancy, Excel export.

**Not yet implemented:**
- Frontend — the backend is ready to be pointed at one (see
  [`backend/README.md`](backend/README.md) → "Frontend wiring").
- Production Azure AD SSO (dev auth uses an `X-User-Id` header stand-in).
- Background workers for scheduled reminders and SLA escalation — the
  tables and manual endpoints exist; the scheduler loop is future work.

## License

Not yet set — add a `LICENSE` file and update this section before making
the repository public.
