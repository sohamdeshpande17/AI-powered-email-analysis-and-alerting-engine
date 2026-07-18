"""
Circular Analyser â€” Scraper Scheduler (v3)
==========================================
Single-threaded scheduler for the scraper source services.

v3 changes:
    * The poll schedule comes from the DB source registry â€”
      source.config_json.poll_interval_minutes per WEB_SCRAPER source.
      (sources.yml and the CDC relay are gone.)
    * Each poll publishes raw events to Kafka (circular.raw.v1) and saves
      documents to NAS â€” no circular DB writes from here.

Scheduled jobs:
    - Poll each active WEB_SCRAPER source at its configured interval
    - Health check every 15 minutes (alerts if last success > 2h)

Usage:
    python scheduler.py
    python scheduler.py --once       # Run all sources once, then exit
"""
from __future__ import annotations

import argparse
import logging
import signal
import sys
import time
from datetime import datetime, timedelta

import os
from pathlib import Path
from dotenv import load_dotenv

# .env holds only APP_ENV=dev|uat|prod — the single switch.
# The matching .env.{APP_ENV} holds all environment-specific config.
_root = Path(__file__).resolve().parent
load_dotenv(_root / ".env")
load_dotenv(_root / f".env.{os.getenv('APP_ENV', 'dev')}", override=True)

# Fix Windows console encoding
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

logger = logging.getLogger("scheduler")


# â”€â”€ Job Functions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

def poll_source(source: dict) -> None:
    """Poll a single source and publish its circulars."""
    source_id = source["source_id"]
    cfg = source.get("config_json") or {}
    window_days = cfg.get("window_days", 1)

    logger.info("=" * 50)
    logger.info("POLL: %s (%s)", source.get("name", source_id), source_id)
    logger.info("=" * 50)

    try:
        from adapters import get_adapter
        from core.adapter import DateWindow
        from core.pipeline import PublishPipeline

        adapter = get_adapter(source_id)
        window = DateWindow.last_n_days(window_days)

        logger.info("[%s] Window: %s â†’ %s", source_id, window.from_str(), window.to_str())

        raw_data = adapter.fetch(window)
        if not raw_data:
            logger.info("[%s] No circulars found", source_id)
            return

        logger.info("[%s] Found %d circulars", source_id, len(raw_data))

        with PublishPipeline() as pipeline:
            result = pipeline.run(source_id, adapter, raw_data)

        logger.info(
            "[%s] Results: published=%d, failed=%d, total=%d",
            source_id, result["published"], result["failed"], result["total"],
        )

    except KeyError:
        logger.error("[%s] No adapter registered (adapters/__init__.py)", source_id)
    except Exception as e:
        logger.error("[%s] Poll failed: %s", source_id, e, exc_info=True)


def health_check() -> None:
    """Check if all sources have been polled recently."""
    try:
        from core.db import SourceRepository

        with SourceRepository() as repo:
            sources = repo.get_active_sources(source_type="WEB_SCRAPER")
            conn = repo._get_conn()
            try:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT name, last_success_at, last_error FROM source "
                        "WHERE is_active AND source_type = 'WEB_SCRAPER' ORDER BY name"
                    )
                    rows = cur.fetchall()
            finally:
                repo._put_conn(conn)

        threshold = datetime.now() - timedelta(hours=2)

        for name, last_success, last_error in rows:
            if last_success is None:
                logger.warning("[Health] %s: NEVER polled successfully", name)
            elif last_success.replace(tzinfo=None) < threshold:
                logger.warning(
                    "[Health] %s: Last success %s (>2h ago). Error: %s",
                    name, last_success, last_error or "none",
                )
            else:
                logger.info("[Health] %s: OK (last success: %s)", name, last_success)

    except Exception as e:
        logger.error("[Health] Check failed: %s", e)


# â”€â”€ Scheduler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

_shutdown = False


def _signal_handler(signum, frame):
    global _shutdown
    _shutdown = True
    logger.info("Shutdown signal received")


def print_banner(sources: list[dict]) -> None:
    print()
    print("â•”â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•—")
    print("â•‘   Circular Analyser â€” Scraper Scheduler (v3)          â•‘")
    print("â•šâ•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
    print()
    print("  Scheduled Jobs (from DB source.config_json):")
    for src in sources:
        cfg = src.get("config_json") or {}
        print(f"    âœ“ {src['source_id']:6s}  every {cfg.get('poll_interval_minutes', 30)} min")
    print(f"    âœ“ Health  every 15 min")
    print()
    print(f"  Started at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Press Ctrl+C to stop")
    print()


def run_scheduler(run_once: bool = False, only_source: str | None = None) -> None:
    """
    Main scheduler loop. Poll intervals come from the DB source registry.

    Args:
        run_once:    Poll all selected sources once, then exit.
        only_source: Restrict to one source_id â€” this is how the nse-scraper
                     deployable runs from the shared codebase.
    """
    try:
        import schedule as sched
    except ImportError:
        print("  âŒ 'schedule' library not installed. Run: pip install schedule")
        sys.exit(1)

    from core.db import SourceRepository

    with SourceRepository() as repo:
        sources = repo.get_active_sources(source_type="WEB_SCRAPER")

    if only_source:
        sources = [s for s in sources if s["source_id"].upper() == only_source.upper()]

    if not sources:
        print("  âŒ No matching active WEB_SCRAPER sources in the DB. "
              "Run database/schema.sql.")
        sys.exit(1)

    if run_once:
        print("\n  Running all sources once ...\n")
        for src in sources:
            poll_source(src)
        print("\n  âœ“ Done\n")
        return

    for src in sources:
        cfg = src.get("config_json") or {}
        interval = cfg.get("poll_interval_minutes", 30)
        sched.every(interval).minutes.do(poll_source, source=src)

    sched.every(15).minutes.do(health_check)

    print_banner(sources)

    # Run initial poll for all sources
    logger.info("Running initial poll for all sources ...")
    for src in sources:
        poll_source(src)

    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    while not _shutdown:
        sched.run_pending()
        time.sleep(1)

    logger.info("Scheduler stopped")


# â”€â”€ CLI â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

if __name__ == "__main__":
    from core.logging_setup import configure_logging
    configure_logging()

    parser = argparse.ArgumentParser(description="Circular Analyser Scraper Scheduler (v3)")
    parser.add_argument(
        "--once", action="store_true",
        help="Run all sources once and exit (no continuous scheduling)",
    )
    parser.add_argument(
        "--source", "-s",
        help="Run as a single-source service (e.g. --source NSE for the "
             "nse-scraper deployable)",
    )
    args = parser.parse_args()

    run_scheduler(run_once=args.once, only_source=args.source)
