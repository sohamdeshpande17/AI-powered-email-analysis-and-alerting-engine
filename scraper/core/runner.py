"""
CLI Runner
==========
Unified command-line entry point for the source scrapers (v3).

The scraper no longer writes circular data to the DB â€” it saves documents to
NAS and publishes raw events to Kafka (circular.raw.v1). The DB `source`
table is the source registry (config_json drives the scheduler).

Usage:
    python -m core.runner poll --source NSE
    python -m core.runner poll --source NSE --from 25-05-2026 --to 26-05-2026
    python -m core.runner backfill --source NSE --from 01-01-2026 --to 26-05-2026 --chunk-days 30
    python -m core.runner list-sources
"""
from __future__ import annotations

import argparse
import logging
import sys
from datetime import timedelta

from dotenv import load_dotenv
load_dotenv()

from adapters import get_adapter
from .adapter import DateWindow
from .db import SourceRepository
from .pipeline import PublishPipeline

logger = logging.getLogger(__name__)


def get_adapter_for_source(source_id: str):
    """Instantiate the adapter for a source code, or exit with a hint."""
    try:
        return get_adapter(source_id)
    except KeyError:
        print(f"  âŒ No adapter registered for source '{source_id}'")
        print("     Registered adapters: NSE, EMAIL (see adapters/__init__.py)")
        sys.exit(1)


# â”€â”€ Commands â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

def cmd_poll(args):
    """Poll a source and publish raw events to Kafka."""
    adapter = get_adapter_for_source(args.source)

    if args.date_from and args.date_to:
        window = DateWindow.from_strings(args.date_from, args.date_to)
    elif args.days:
        window = DateWindow.last_n_days(args.days)
    else:
        window = DateWindow.last_n_days(1)  # Default: yesterday to today

    print(f"\n{'=' * 60}")
    print(f"  POLL: {adapter.name} ({adapter.code})")
    print(f"  Window: {window.from_str()} â†’ {window.to_str()}")
    print(f"{'=' * 60}\n")

    raw_data = adapter.fetch(window)
    if not raw_data:
        print(f"\n  [{adapter.code}] No circulars found for this window\n")
        return

    print(f"  [{adapter.code}] Found {len(raw_data)} circulars")

    try:
        with PublishPipeline() as pipeline:
            result = pipeline.run(adapter.code, adapter, raw_data)

        print(f"\n  [{adapter.code}] Publish Results:")
        print(f"    Published: {result['published']}")
        print(f"    Failed:    {result['failed']}")
        print(f"    Total:     {result['total']}\n")

    except Exception as e:
        logger.error("[%s] Pipeline failed: %s", adapter.code, e)
        print(f"\n  âŒ Pipeline error: {e}")
        print(f"     Raw data was fetched successfully â€” can retry later\n")


def cmd_backfill(args):
    """Backfill a source over a large date range, chunked into smaller windows."""
    adapter = get_adapter_for_source(args.source)
    chunk_days = args.chunk_days or 30

    if not args.date_from or not args.date_to:
        print("  âŒ --from and --to are required for backfill")
        sys.exit(1)

    full_window = DateWindow.from_strings(args.date_from, args.date_to)

    print(f"\n{'=' * 60}")
    print(f"  BACKFILL: {adapter.name} ({adapter.code})")
    print(f"  Full range: {full_window.from_str()} â†’ {full_window.to_str()}")
    print(f"  Chunk size: {chunk_days} days")
    print(f"{'=' * 60}\n")

    chunks = []
    current = full_window.from_date
    while current <= full_window.to_date:
        chunk_end = min(current + timedelta(days=chunk_days - 1), full_window.to_date)
        chunks.append(DateWindow(from_date=current, to_date=chunk_end))
        current = chunk_end + timedelta(days=1)

    print(f"  [{adapter.code}] {len(chunks)} chunk(s) to process\n")

    totals = {"published": 0, "failed": 0, "total": 0}

    with PublishPipeline() as pipeline:
        for i, chunk in enumerate(chunks, 1):
            print(f"  â”€â”€ Chunk {i}/{len(chunks)}: {chunk.from_str()} â†’ {chunk.to_str()} â”€â”€")

            raw_data = adapter.fetch(chunk)
            if not raw_data:
                print(f"    No circulars found, skipping\n")
                continue

            print(f"    Found {len(raw_data)} circulars")

            try:
                result = pipeline.run(adapter.code, adapter, raw_data)
                for k in totals:
                    totals[k] += result.get(k, 0)
                print(f"    Published: {result['published']}, Failed: {result['failed']}\n")
            except Exception as e:
                logger.error("[%s] Chunk %d failed: %s", adapter.code, i, e)
                print(f"    âŒ Error: {e}\n")

    print(f"\n{'=' * 60}")
    print(f"  BACKFILL COMPLETE: {adapter.code}")
    print(f"    Total published: {totals['published']}")
    print(f"    Total failed:    {totals['failed']}")
    print(f"    Total records:   {totals['total']}")
    print(f"{'=' * 60}\n")


def cmd_list_sources(args):
    """List sources from the DB registry (source table)."""
    with SourceRepository() as repo:
        sources = repo.get_active_sources()

    if not sources:
        print("\n  No active sources in the DB. Run database/schema.sql.\n")
        return

    print(f"\n{'=' * 60}")
    print(f"  ACTIVE SOURCES (DB registry)")
    print(f"{'=' * 60}")
    for src in sources:
        cfg = src.get("config_json") or {}
        print(f"  {src['source_id']:6s}  {src['source_type']:12s}  {src.get('name', '')}")
        print(f"         poll: every {cfg.get('poll_interval_minutes', '?')} min")
        print()


# â”€â”€ CLI Entry Point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

def main():
    # Trace-id-tagged logging (console + rotating file). Replaces basicConfig so
    # every line carries the per-circular trace id set during ingestion.
    from core.logging_setup import configure_logging
    configure_logging()

    parser = argparse.ArgumentParser(
        prog="core.runner",
        description="Circular Analyser â€” Scraper CLI (v3: NAS + Kafka publish)",
    )
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # poll
    poll_parser = subparsers.add_parser("poll", help="Poll a source and publish to Kafka")
    poll_parser.add_argument("--source", "-s", required=True, help="Source code (NSE)")
    poll_parser.add_argument("--from", dest="date_from", help="From date (dd-mm-yyyy)")
    poll_parser.add_argument("--to", dest="date_to", help="To date (dd-mm-yyyy)")
    poll_parser.add_argument("--days", type=int, help="Fetch last N days (default: 1)")

    # backfill
    bf_parser = subparsers.add_parser("backfill", help="Backfill a source over a date range")
    bf_parser.add_argument("--source", "-s", required=True, help="Source code (NSE)")
    bf_parser.add_argument("--from", dest="date_from", required=True, help="From date (dd-mm-yyyy)")
    bf_parser.add_argument("--to", dest="date_to", required=True, help="To date (dd-mm-yyyy)")
    bf_parser.add_argument("--chunk-days", type=int, default=30, help="Days per chunk (default: 30)")

    # list-sources
    subparsers.add_parser("list-sources", help="List active sources from the DB")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    if args.command == "poll":
        cmd_poll(args)
    elif args.command == "backfill":
        cmd_backfill(args)
    elif args.command == "list-sources":
        cmd_list_sources(args)


if __name__ == "__main__":
    main()
