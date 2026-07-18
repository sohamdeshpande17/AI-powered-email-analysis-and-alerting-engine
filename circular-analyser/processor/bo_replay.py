"""
BO replay CLI (v3 architecture §3.3).

Re-drives parked messages from the BO topic back onto the main raw topic
after a fix is deployed. Replay is a manual/operational action — there is
no automatic re-drive loop.

Usage:
    python -m processor.bo_replay --limit 10      # replay up to 10 messages
    python -m processor.bo_replay --dry-run       # list what is parked
"""
from __future__ import annotations

import argparse
import sys

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from . import config


def main() -> None:
    parser = argparse.ArgumentParser(description="Replay BO-parked circular events")
    parser.add_argument("--limit", type=int, default=10,
                        help="Max messages to replay (default 10)")
    parser.add_argument("--dry-run", action="store_true",
                        help="List parked messages without republishing")
    args = parser.parse_args()

    from kafka import KafkaConsumer, KafkaProducer

    consumer = KafkaConsumer(
        config.KAFKA_BO_TOPIC,
        bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS,
        group_id=f"{config.KAFKA_CONSUMER_GROUP}.bo-replay",
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        consumer_timeout_ms=10000,
    )
    producer = None if args.dry_run else KafkaProducer(
        bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS, acks="all",
    )

    replayed = 0
    print(f"\n  BO replay — topic {config.KAFKA_BO_TOPIC} → "
          f"{config.KAFKA_RAW_TOPIC} (limit {args.limit}"
          f"{', DRY RUN' if args.dry_run else ''})\n")

    for message in consumer:
        headers = {k: v.decode("utf-8", "replace") for k, v in (message.headers or [])}
        print(f"  offset={message.offset} stage={headers.get('x-error-stage')} "
              f"retries={headers.get('x-retry-count')} "
              f"failed_at={headers.get('x-failed-at')}")
        print(f"    error: {headers.get('x-error-message', '')[:160]}")

        if not args.dry_run:
            # Republish the ORIGINAL value; keep only the source_id header.
            source_hdr = [(k, v) for k, v in (message.headers or [])
                          if k == "source_id"]
            producer.send(config.KAFKA_RAW_TOPIC, key=message.key,
                          value=message.value, headers=source_hdr).get(timeout=10)
            consumer.commit()
            print("    → replayed")

        replayed += 1
        if replayed >= args.limit:
            break

    if producer:
        producer.flush(timeout=10)
        producer.close(timeout=10)
    consumer.close()

    verb = "listed" if args.dry_run else "replayed"
    print(f"\n  ✓ {replayed} message(s) {verb}\n")


if __name__ == "__main__":
    main()
