"""
Circular Analyser — Email Poller (v3 source service)
====================================================
Polls the compliance O365 mailbox for UNREAD mail, saves attachments and
linked documents to NAS, and publishes one raw event per mail to Kafka
(circular.raw.v1). Mail is marked READ only after its publish is acked.

The poll interval and mailbox come from the DB source registry:
    source('EMAIL').config_json — {"poll_interval_minutes": 5,
                                   "mailbox": "...", "fetch_limit": 25}
Credentials come from env: O365_TENANT_ID / O365_CLIENT_ID / O365_CLIENT_SECRET.

Usage:
    python email_poller.py            # continuous poll loop
    python email_poller.py --once     # single poll, then exit
"""
from __future__ import annotations

import argparse
import logging
import signal
import sys
import time
from datetime import datetime

import os
from pathlib import Path
from dotenv import load_dotenv

# .env holds only APP_ENV=dev|uat|prod — the single switch.
# The matching .env.{APP_ENV} holds all environment-specific config.
_root = Path(__file__).resolve().parent
load_dotenv(_root / ".env")
load_dotenv(_root / f".env.{os.getenv('APP_ENV', 'dev')}", override=True)

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

logger = logging.getLogger("email-poller")

SOURCE_ID = "EMAIL"

_shutdown = False


def _signal_handler(signum, frame):
    global _shutdown
    _shutdown = True
    logger.info("Shutdown signal received")


def poll_once() -> None:
    """One poll cycle: fetch unread → NAS → Kafka → mark read."""
    from adapters.email.email_adapter import EmailAdapter
    from core.adapter import DateWindow
    from core.db import SourceRepository
    from core.pipeline import PublishPipeline

    with SourceRepository() as repo:
        source = repo.get_source(SOURCE_ID)

    cfg = (source or {}).get("config_json") or {}
    adapter = EmailAdapter(
        mailbox=cfg.get("mailbox"),
        fetch_limit=int(cfg.get("fetch_limit", 25)),
        # Only mail from these senders is published to Kafka. Maintained in
        # source('EMAIL').config_json — no code change to update the list.
        allowed_senders=cfg.get("allowed_senders"),
    )

    raw_data = adapter.fetch(DateWindow.today())
    if not raw_data:
        logger.info("[EMAIL] No unread mail")
        return

    with PublishPipeline() as pipeline:
        result = pipeline.run(SOURCE_ID, adapter, raw_data)

    logger.info(
        "[EMAIL] Results: published=%d, failed=%d, total=%d",
        result["published"], result["failed"], result["total"],
    )


def main() -> None:
    from core.logging_setup import configure_logging
    configure_logging()

    parser = argparse.ArgumentParser(description="Circular Analyser Email Poller (v3)")
    parser.add_argument("--once", action="store_true",
                        help="Run one poll cycle and exit")
    args = parser.parse_args()

    if args.once:
        poll_once()
        return

    from core.db import SourceRepository
    with SourceRepository() as repo:
        source = repo.get_source(SOURCE_ID)
    cfg = (source or {}).get("config_json") or {}
    interval_min = int(cfg.get("poll_interval_minutes", 5))

    print()
    print("╔════════════════════════════════════════════════════════╗")
    print("║   Circular Analyser — Email Poller (v3)               ║")
    print("╚════════════════════════════════════════════════════════╝")
    print(f"\n  Mailbox poll: every {interval_min} min")
    print(f"  Started at:   {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Press Ctrl+C to stop\n")

    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    while not _shutdown:
        try:
            poll_once()
        except Exception as e:
            logger.error("[EMAIL] Poll failed: %s", e, exc_info=True)

        # sleep in 1s slices so Ctrl+C stays responsive
        for _ in range(interval_min * 60):
            if _shutdown:
                break
            time.sleep(1)

    logger.info("Email poller stopped")


if __name__ == "__main__":
    main()
