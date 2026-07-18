"""
circular-processor — entry point (v3).

THE single application to start for the consume side:
    Kafka consumer + source massaging + raw DB write + AI engine +
    summarized DB write + BO failure topic.

Usage:
    python -m processor.main
"""
from __future__ import annotations

import sys

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from core.logging_setup import configure_logging

configure_logging()

import config as engine_config  # noqa: E402 — LLM provider settings
from . import config            # noqa: E402
from .consumer import RawCircularProcessor  # noqa: E402


def main() -> None:
    print()
    print("╔════════════════════════════════════════════════════════╗")
    print("║   Circular Analyser — circular-processor (v3)         ║")
    print("╚════════════════════════════════════════════════════════╝")
    print()
    print(f"  Kafka:   {config.KAFKA_BOOTSTRAP_SERVERS}")
    print(f"  Topic:   {config.KAFKA_RAW_TOPIC}  (BO: {config.KAFKA_BO_TOPIC})")
    print(f"  Group:   {config.KAFKA_CONSUMER_GROUP}")
    print(f"  DB:      {config.DB_CONFIG['host']}:{config.DB_CONFIG['port']}"
          f"/{config.DB_CONFIG['database']}")
    print(f"  NAS:     {config.NAS_BASE_PATH}")
    print(f"  LLM:     {engine_config.LLM_PROVIDER}")
    print()

    processor = RawCircularProcessor()
    try:
        processor.start()  # blocks
    except KeyboardInterrupt:
        processor.stop()


if __name__ == "__main__":
    main()
