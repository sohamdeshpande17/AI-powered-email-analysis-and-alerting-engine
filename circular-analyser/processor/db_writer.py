"""
DB writers (schema-per-tenant).

Stage 3 - raw layer: idempotent insert into the SHARED public.raw_circular +
public.raw_circular_document (no tenant_id). Own transaction so the raw row
survives an AI-stage failure (replays only redo the missing stages).

Stage 5 - summarized fan-out (schema-per-tenant): the LLM runs once on the
shared raw circular; its output is written to the CANONICAL public.circular (the
tenant-agnostic AI truth store, PK circular_no), then copied into the per-tenant
schema's circular (<schema>.circular, PK circular_no, no tenant_id) for each
workspace the source feeds (public.source_tenant), with a RECEIVED row in
<schema>.circular_workflow. MANUAL upload -> the uploader's own workspace.
"""
from __future__ import annotations

import logging
from typing import Any

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json

from . import config

logger = logging.getLogger(__name__)

# The processor writes circulars unattended (no human user), so every row it
# creates is stamped with this literal "System" actor in created_by/updated_by.
# The audit columns on the raw/circular tables are free text (see SystemAudited
# on the backend) precisely so this reads as "System" rather than a user UUID.
SYSTEM_ACTOR = "System"


class CircularWriter:
    """Single-connection writer - the consumer processes one event at a time."""

    def __init__(self, db_config: dict | None = None):
        cfg = db_config or config.DB_CONFIG
        self._conn = psycopg2.connect(
            host=cfg["host"], port=cfg["port"], database=cfg["database"],
            user=cfg["user"], password=cfg["password"],
        )
        self._conn.autocommit = False
        logger.info("[DBWriter] Connected: %s@%s:%s/%s",
                    cfg["user"], cfg["host"], cfg["port"], cfg["database"])

    def close(self) -> None:
        if self._conn and not self._conn.closed:
            self._conn.close()
            logger.info("[DBWriter] Closed.")

    def reconnect_if_needed(self) -> None:
        if self._conn.closed:
            self.__init__()

    # ── Stage 3: raw layer (shared, public) ───────────────────────────

    def write_raw(self, row: dict[str, Any], documents: list[dict]) -> str:
        """
        Insert the raw circular + its document rows.

        raw_circular(+documents) is the SHARED common pool - no tenant_id;
        fan-out to per-schema circular copies happens in Stage 5.

        Returns: "new", "replay" (circular_id already present) or
        "duplicate" (same source_url already ingested under another id -
        re-scrape; the event should be skipped without an AI run).
        """
        try:
            with self._conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO public.raw_circular (circular_id, source_id, circular_no,
                        subject, issued_at, department, email_body, source_name,
                        source, source_url, storage_path, created_by, updated_by)
                    VALUES (%(circular_id)s, %(source_id)s, %(circular_no)s,
                        %(subject)s, %(issued_at)s, %(department)s, %(email_body)s,
                        %(source_name)s, %(source)s, %(source_url)s,
                        %(storage_path)s, %(system_actor)s, %(system_actor)s)
                    ON CONFLICT (circular_id) DO NOTHING
                    """,
                    {**row, "system_actor": SYSTEM_ACTOR},
                )
                inserted = cur.rowcount == 1

                for doc in documents:
                    cur.execute(
                        """
                        INSERT INTO public.raw_circular_document (circular_id,
                            document_source, original_filename, mime_type,
                            size_bytes, nas_relative_path, sha256,
                            created_by, updated_by)
                        SELECT %s, %s, %s, %s, %s, %s, %s, %s, %s
                        WHERE NOT EXISTS (
                            SELECT 1 FROM public.raw_circular_document
                            WHERE circular_id = %s AND nas_relative_path = %s
                        )
                        """,
                        (
                            row["circular_id"],
                            doc.get("document_source", "linked_download"),
                            doc.get("original_filename", "document"),
                            doc.get("mime_type"),
                            doc.get("size_bytes"),
                            doc.get("nas_relative_path"),
                            doc.get("sha256"),
                            SYSTEM_ACTOR,
                            SYSTEM_ACTOR,
                            row["circular_id"],
                            doc.get("nas_relative_path"),
                        ),
                    )
            self._conn.commit()
            return "new" if inserted else "replay"

        except psycopg2.errors.UniqueViolation as e:
            # uq_raw_circular_source_url - same source URL ingested before
            # under a different circular_id: a re-scrape, not an error.
            self._conn.rollback()
            logger.info("[DBWriter] Duplicate source_url - skipping "
                        "(circular_id=%s): %s", row["circular_id"], e.diag.constraint_name)
            return "duplicate"
        except Exception:
            self._conn.rollback()
            raise

    def update_extracted_text(self, circular_id: str,
                              texts: dict[str, str]) -> None:
        """Store the document text the AI stage extracted (search support)."""
        if not texts:
            return
        try:
            with self._conn.cursor() as cur:
                for nas_path, text in texts.items():
                    cur.execute(
                        "UPDATE public.raw_circular_document SET extracted_text = %s "
                        "WHERE circular_id = %s AND nas_relative_path = %s",
                        (text, circular_id, nas_path),
                    )
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise

    # ── Stage 5: summarized fan-out (schema-per-tenant) ───────────────

    def resolve_target_schemas(self, source_id: str,
                               manual_tenant_id: int | None = None) -> list[str]:
        """
        Workspace SCHEMA names that get a per-tenant copy of this circular.

        MANUAL upload -> the uploader's own workspace (manual_tenant_id).
        All other sources -> every workspace mapped to the source in
        public.source_tenant. An unmapped source yields [] (canonical only).
        Schema name = lower(tenant.code), e.g. 'compliance'.
        """
        try:
            with self._conn.cursor() as cur:
                if source_id == "MANUAL":
                    if not manual_tenant_id:
                        return []
                    cur.execute(
                        "SELECT lower(code) FROM public.tenant WHERE tenant_id = %s",
                        (manual_tenant_id,),
                    )
                else:
                    cur.execute(
                        "SELECT lower(t.code) FROM public.source_tenant st "
                        "JOIN public.tenant t ON t.tenant_id = st.tenant_id "
                        "WHERE st.source_id = %s ORDER BY t.tenant_id",
                        (source_id,),
                    )
                return [r[0] for r in cur.fetchall()]
        finally:
            self._conn.rollback()  # read-only - release the implicit txn

    def write_canonical(self, raw_row: dict[str, Any], analysis: dict[str, Any],
                        provider: str, model: str) -> str:
        """
        Upsert the tenant-agnostic public.circular (the AI truth store) and
        return the final circular_no.

        Keyed by raw_circular_id (UNIQUE) so a replay updates the same canonical
        row regardless of any change to the AI-extracted circular_no. On a
        circular_no (PK) clash with a DIFFERENT raw circular, falls back to the
        system circular_id string so nothing is lost.
        """
        params = self._build_params(raw_row, analysis, provider, model)
        try:
            with self._conn.cursor() as cur:
                cur.execute("SAVEPOINT sp_canon")
                try:
                    cur.execute(self._CANONICAL_UPSERT, params)
                    circular_no = cur.fetchone()[0]
                    cur.execute("RELEASE SAVEPOINT sp_canon")
                except psycopg2.errors.UniqueViolation:
                    cur.execute("ROLLBACK TO SAVEPOINT sp_canon")
                    logger.warning(
                        "[DBWriter] canonical circular_no %r collided; falling "
                        "back to system id %s", params["circular_no"],
                        params["raw_circular_id"],
                    )
                    params["circular_no"] = params["raw_circular_id"]
                    cur.execute(self._CANONICAL_UPSERT, params)
                    circular_no = cur.fetchone()[0]
            self._conn.commit()
            return circular_no
        except Exception:
            self._conn.rollback()
            raise

    def write_tenant_circular(self, schema: str, raw_row: dict[str, Any],
                              analysis: dict[str, Any], provider: str, model: str,
                              circular_no: str) -> None:
        """
        Upsert one workspace's copy into <schema>.circular (PK circular_no, no
        tenant_id) + a RECEIVED row in <schema>.circular_workflow. The
        circular_no is the canonical one (globally unique per raw circular), so a
        replay upserts on raw_circular_id without a circular_no clash.
        """
        params = self._build_params(raw_row, analysis, provider, model)
        params["circular_no"] = circular_no
        ident = sql.Identifier(schema)
        upsert = sql.SQL(self._TENANT_UPSERT_TMPL).format(schema=ident)
        workflow = sql.SQL(self._TENANT_WORKFLOW_TMPL).format(schema=ident)
        try:
            with self._conn.cursor() as cur:
                cur.execute(upsert, params)
                cur.execute(workflow, {"circular_no": circular_no})
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise

    @staticmethod
    def _build_params(raw_row: dict[str, Any], analysis: dict[str, Any],
                      provider: str, model: str) -> dict[str, Any]:
        """Shared parameter dict for the canonical + per-tenant circular upserts."""
        ai_no = analysis.get("circular_id")
        circular_no = (str(ai_no).strip() if ai_no else "") or raw_row["circular_id"]
        referred = analysis.get("referred_circulars") or []
        if not isinstance(referred, list):
            referred = [str(referred)]
        return {
            "circular_no": circular_no,
            "raw_circular_id": raw_row["circular_id"],
            "source_id": raw_row["source_id"],
            "subject": raw_row["subject"],
            "source_name": raw_row.get("source_name"),
            "source": raw_row.get("source"),
            "body_content": raw_row.get("email_body"),
            "referred": Json([str(r) for r in referred]),
            "urgency": analysis.get("urgency"),
            "categories": Json(analysis.get("categories") or []),
            "summary": _to_text(analysis.get("summary")),
            "required_action": _to_text(analysis.get("required_action")),
            "key_entities": Json(analysis.get("key_entities") or []),
            "recommended_teams": Json(analysis.get("recommended_teams") or []),
            "confidence": analysis.get("confidence"),
            "sentiment": analysis.get("sentiment"),
            "provider": provider,
            "model": model,
            "issued_at": raw_row.get("issued_at"),
            "due_at": analysis.get("due_date") or None,
            "effective_at": analysis.get("effective_date") or None,
            "system_actor": SYSTEM_ACTOR,
        }

    # Column list shared by canonical + per-tenant upserts (no tenant_id; the
    # schema IS the tenant for the per-tenant copy, and the canonical is
    # tenant-agnostic).
    _COLS = """circular_no, raw_circular_id, source_id, subject, source_name,
        source, body_content, referred_circular_ids, status, urgency, categories,
        summary, required_action, key_entities, recommended_teams, confidence,
        sentiment, provider, model, issued_at, due_at, effective_at,
        created_by, updated_by"""
    _VALS = """%(circular_no)s, %(raw_circular_id)s, %(source_id)s, %(subject)s,
        %(source_name)s, %(source)s, %(body_content)s, %(referred)s, 'RECEIVED',
        %(urgency)s, %(categories)s, %(summary)s, %(required_action)s,
        %(key_entities)s, %(recommended_teams)s, %(confidence)s, %(sentiment)s,
        %(provider)s, %(model)s, %(issued_at)s, %(due_at)s, %(effective_at)s,
        %(system_actor)s, %(system_actor)s"""
    _SET = """urgency = EXCLUDED.urgency, categories = EXCLUDED.categories,
        summary = EXCLUDED.summary, required_action = EXCLUDED.required_action,
        key_entities = EXCLUDED.key_entities,
        recommended_teams = EXCLUDED.recommended_teams,
        confidence = EXCLUDED.confidence, sentiment = EXCLUDED.sentiment,
        provider = EXCLUDED.provider, model = EXCLUDED.model,
        referred_circular_ids = EXCLUDED.referred_circular_ids,
        due_at = coalesce(EXCLUDED.due_at, circular.due_at),
        effective_at = coalesce(EXCLUDED.effective_at, circular.effective_at),
        updated_by = EXCLUDED.updated_by, updated_on = now(),
        ingested_at = now()"""

    # Canonical: keyed by raw_circular_id (UNIQUE); circular_no is the PK.
    _CANONICAL_UPSERT = (
        "INSERT INTO public.circular (" + _COLS + ") VALUES (" + _VALS + ") "
        "ON CONFLICT (raw_circular_id) DO UPDATE SET " + _SET + " "
        "RETURNING circular_no"
    )

    # Per-tenant copy: same column set into <schema>.circular, upsert on
    # raw_circular_id. {schema} is substituted via psycopg2.sql.Identifier.
    _TENANT_UPSERT_TMPL = (
        "INSERT INTO {schema}.circular (" + _COLS + ") VALUES (" + _VALS + ") "
        "ON CONFLICT (raw_circular_id) DO UPDATE SET " + _SET
    )

    _TENANT_WORKFLOW_TMPL = (
        "INSERT INTO {schema}.circular_workflow (circular_no, action) "
        "SELECT %(circular_no)s, 'RECEIVED' "
        "WHERE NOT EXISTS (SELECT 1 FROM {schema}.circular_workflow "
        "WHERE circular_no = %(circular_no)s AND action = 'RECEIVED')"
    )


def _to_text(value: Any) -> str | None:
    """LLMs sometimes return bullet lists as arrays - join to one string."""
    if value is None:
        return None
    if isinstance(value, list):
        return "\n".join(str(item) for item in value)
    return str(value)
