# Circular Processor (AI Engine) — v3

The single v3 processing application for the Circular Analyser platform
(see `../architecture.md`). It consumes raw circulars published to Kafka
by the source services (nse-scraper, bse-scraper, email-poller), massages
each source-native payload into the common shape, writes the raw row,
runs LLM analysis over the email body and the documents stored on the NAS,
and upserts the summarized row into `public.circular` — all in one process,
so only one application needs to be started.

```
Kafka circular.raw.v1
        │
        ▼
┌─────────────────────────────────────────────┐
│            circular-processor               │
│                                             │
│  consume → map (per source) → raw_circular  │
│     → AI analysis (docs read from NAS)      │
│     → public.circular + RECEIVED workflow   │
└──────────────┬──────────────────────────────┘
               │ on poison / exhausted retries
               ▼
     Kafka circular.raw.bo.v1  (back-out topic)
```

Supports **Claude (Anthropic)** and **OpenAI** as LLM providers.

---

## Running

```bash
pip install -r requirements.txt
python -m processor.main
```

Replay parked BO messages after fixing the underlying fault:

```bash
python -m processor.bo_replay --dry-run     # inspect what's parked
python -m processor.bo_replay --limit 10    # republish to circular.raw.v1
```

---

## Project Structure

```
circular-analyser/
│
├── processor/                       # v3 service (entry: python -m processor.main)
│   ├── main.py                      # Entry point — wiring + logging
│   ├── config.py                    # Kafka / DB / NAS / retry settings (env-based)
│   ├── consumer.py                  # Kafka consumer loop + retry/BO handling
│   ├── mappers.py                   # Per-source payload massaging (NSE/BSE/EMAIL)
│   ├── ai_stage.py                  # NAS document reads + LLM analysis
│   ├── db_writer.py                 # raw_circular + circular + workflow upserts
│   ├── bo.py                        # Back-out topic publisher (error headers)
│   └── bo_replay.py                 # Manual BO replay CLI
│
├── core/                            # Shared utilities
│   ├── rate_limiter.py              # Thread-safe token-bucket rate limiter
│   ├── prompt_builder.py            # System prompt + email text formatting
│   ├── response_parser.py           # LLM JSON response parsing
│   └── logging_setup.py             # Trace-id aware logging
│
├── providers/                       # LLM provider abstraction
│   ├── __init__.py                  # Factory — get_provider()
│   ├── base.py                      # Abstract base class (interface)
│   ├── claude.py                    # Anthropic Claude implementation
│   └── openai.py                    # OpenAI implementation
│
├── emails/
│   └── attachment_extractor.py      # PDF/DOCX/XLSX/CSV/ZIP text extraction
│
├── config.py                        # LLM provider settings (env-based)
└── requirements.txt
```

The v2 polling pipeline (`main.py`, `pipeline/`, `consumers/`, `batch/`,
`alerts/`, `store/`, mailbox readers, the alert sender, and the providers'
async-batch methods) was removed in v3 — mailbox reading lives in the
email-poller source service and outbound mail in the backend's
GraphMailService. Analysis is real-time only.

---

## Configuration

All settings come from environment variables. LLM provider settings
(keys, models, rate limits) are read by the root `config.py`; the
processor adds its own in `processor/config.py`:

### Kafka

| Variable | Default | Description |
|----------|---------|-------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_RAW_TOPIC` | `circular.raw.v1` | Raw circular events (in) |
| `KAFKA_BO_TOPIC` | `circular.raw.bo.v1` | Back-out topic for failures (out) |
| `KAFKA_CONSUMER_GROUP` | `circular-processor` | Consumer group id |
| `RETRY_MAX_ATTEMPTS` | `3` | In-process retries before BO (backoff 1/4/16s) |

### Database / NAS

| Variable | Default | Description |
|----------|---------|-------------|
| `CIRCULAR_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASSWORD` | `localhost` / `5432` / `circular_analyser` / `postgres` / `postgres` | Postgres connection |
| `STORAGE_BASE_PATH` | `./circular_documents` | NAS mount — same path the source services write to |
| `DEFAULT_TENANT_ID` | `1` | Fallback tenant (1 = Meridian Capital) |

### LLM provider

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_PROVIDER` | `claude` | `claude` or `openai` |
| `CLAUDE_API_KEY` / `OPENAI_API_KEY` | *(required)* | Provider API key |
| `CLAUDE_MODEL` / `OPENAI_MODEL` | see `config.py` | Model identifier |
| `CLAUDE_RPM_LIMIT` / `OPENAI_RPM_LIMIT` | `5` / `10` | Requests per minute |

---

## Failure handling (BO topic)

- **Poison messages** (unparseable JSON, missing required fields, unknown
  source) go straight to `circular.raw.bo.v1`.
- **Transient failures** (DB down, LLM 5xx/429, NAS unreachable) retry
  in-process 3 times with exponential backoff, then park on BO.
- Each BO message carries the original bytes plus headers:
  `x-error-stage` (CONSUME | MAP | RAW_DB | AI | SUMMARY_DB),
  `x-error-message`, `x-retry-count`, `x-original-partition/offset`,
  `x-failed-at`.
- Replay is manual via `python -m processor.bo_replay` once the fault is
  fixed; all writes are idempotent, so replaying a half-processed message
  is safe.

---

## License

Proprietary — internal use only.
