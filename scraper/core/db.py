"""
Source Repository
=================
Slim PostgreSQL repository for source services (v3 architecture).

Source services no longer write circular data — that is the
circular-processor's job. The only DB touchpoints left here are:

    * source registry reads (config_json drives the poll schedule)
    * ingest_audit rows (one per run)
    * source health updates (last_run_at / last_success_at / last_error)

Usage:
    repo = SourceRepository()
    cfg = repo.get_source("NSE")
    run_id = repo.start_ingest_audit("NSE")
    repo.finish_ingest_audit(run_id, "SUCCESS", counts)
"""
from __future__ import annotations

import logging

import psycopg2
from psycopg2.extras import RealDictCursor
from psycopg2.pool import ThreadedConnectionPool

from .db_config import DB_CONFIG

logger = logging.getLogger(__name__)


class SourceRepository:
    """DB access for source services (scrapers / email-poller)."""

    def __init__(self, db_config: dict | None = None):
        cfg = db_config or DB_CONFIG
        try:
            self.pool = ThreadedConnectionPool(
                minconn=1,
                maxconn=4,
                host=cfg["host"],
                port=cfg["port"],
                database=cfg["database"],
                user=cfg["user"],
                password=cfg["password"],
            )
            logger.info(
                "[DB] Connection pool created: %s@%s:%s/%s",
                cfg["user"], cfg["host"], cfg["port"], cfg["database"],
            )
        except psycopg2.Error as e:
            logger.error("[DB] Failed to create connection pool: %s", e)
            raise

    # ── connection helpers ────────────────────────────────────────────

    def _get_conn(self):
        return self.pool.getconn()

    def _put_conn(self, conn):
        self.pool.putconn(conn)

    def close(self) -> None:
        if self.pool and not self.pool.closed:
            self.pool.closeall()
            logger.info("[DB] Connection pool closed.")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False

    # ── source registry ───────────────────────────────────────────────

    def get_source(self, source_id: str) -> dict | None:
        """Fetch one source row (source_id, name, base_url, config_json, is_active)."""
        conn = self._get_conn()
        try:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(
                    "SELECT source_id, source_type, name, base_url, config_json, "
                    "       is_active "
                    "FROM source WHERE source_id = %s",
                    (source_id,),
                )
                return cur.fetchone()
        finally:
            self._put_conn(conn)

    def get_active_sources(self, source_type: str | None = None) -> list[dict]:
        """List active sources, optionally filtered by type (e.g. WEB_SCRAPER)."""
        conn = self._get_conn()
        try:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                if source_type:
                    cur.execute(
                        "SELECT source_id, source_type, name, base_url, config_json "
                        "FROM source WHERE is_active AND source_type = %s "
                        "ORDER BY source_id",
                        (source_type,),
                    )
                else:
                    cur.execute(
                        "SELECT source_id, source_type, name, base_url, config_json "
                        "FROM source WHERE is_active ORDER BY source_id"
                    )
                return list(cur.fetchall())
        finally:
            self._put_conn(conn)

    def update_source_health(self, source_id: str, success: bool,
                             error: str | None = None) -> None:
        conn = self._get_conn()
        try:
            with conn.cursor() as cur:
                if success:
                    cur.execute(
                        "UPDATE source SET last_run_at = now(), "
                        "last_success_at = now(), last_error = NULL "
                        "WHERE source_id = %s",
                        (source_id,),
                    )
                else:
                    cur.execute(
                        "UPDATE source SET last_run_at = now(), last_error = %s "
                        "WHERE source_id = %s",
                        (error, source_id),
                    )
            conn.commit()
        except psycopg2.Error as e:
            conn.rollback()
            logger.error("[DB] update_source_health failed: %s", e)
        finally:
            self._put_conn(conn)

    # ── ingest audit ──────────────────────────────────────────────────

    def start_ingest_audit(self, source_id: str) -> str | None:
        """Insert a RUNNING ingest_audit row; returns run_id."""
        conn = self._get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO ingest_audit (source_id, status) "
                    "VALUES (%s, 'RUNNING') RETURNING run_id",
                    (source_id,),
                )
                run_id = cur.fetchone()[0]
            conn.commit()
            return str(run_id)
        except psycopg2.Error as e:
            conn.rollback()
            logger.error("[DB] start_ingest_audit failed: %s", e)
            return None
        finally:
            self._put_conn(conn)

    def finish_ingest_audit(self, run_id: str | None, status: str,
                            counts: dict, error_summary: str | None = None) -> None:
        if run_id is None:
            return
        conn = self._get_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE ingest_audit SET finished_at = now(), status = %s, "
                    "fetched_count = %s, new_count = %s, skipped_count = %s, "
                    "failed_count = %s, error_summary = %s "
                    "WHERE run_id = %s",
                    (
                        status,
                        counts.get("total", 0),
                        counts.get("published", 0),
                        counts.get("skipped", 0),
                        counts.get("failed", 0),
                        error_summary,
                        run_id,
                    ),
                )
            conn.commit()
        except psycopg2.Error as e:
            conn.rollback()
            logger.error("[DB] finish_ingest_audit failed: %s", e)
        finally:
            self._put_conn(conn)
