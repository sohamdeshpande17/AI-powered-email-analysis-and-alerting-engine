"""
BO (back-out) topic publisher (v3 architecture §3.3).

When the processor exhausts its retries — or hits a poison message — the
ORIGINAL message bytes are republished verbatim to circular.raw.bo.v1 with
error metadata in headers, the main-topic offset is committed, and
consumption continues. One bad message never blocks the partition.

Replay after a fix: python -m processor.bo_replay --limit N
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from . import config

logger = logging.getLogger(__name__)


class BOPublisher:
    def __init__(self, bootstrap_servers: str | None = None,
                 topic: str | None = None):
        self._servers = bootstrap_servers or config.KAFKA_BOOTSTRAP_SERVERS
        self.topic = topic or config.KAFKA_BO_TOPIC
        self._producer = None

    @property
    def producer(self):
        if self._producer is None:
            from kafka import KafkaProducer
            self._producer = KafkaProducer(
                bootstrap_servers=self._servers,
                acks="all",
                retries=3,
            )
            logger.info("[BO] Producer connected (topic=%s)", self.topic)
        return self._producer

    def park(self, message, stage: str, error: str, retry_count: int) -> None:
        """Republish the original message to the BO topic with error headers."""
        headers = [
            ("x-error-stage", stage.encode()),
            ("x-error-message", str(error)[:1024].encode("utf-8", "replace")),
            ("x-retry-count", str(retry_count).encode()),
            ("x-original-partition", str(message.partition).encode()),
            ("x-original-offset", str(message.offset).encode()),
            ("x-failed-at", datetime.now(timezone.utc).isoformat().encode()),
        ]
        # carry the original headers too (source_id)
        headers.extend(message.headers or [])

        raw_value = message.value if isinstance(message.value, bytes) \
            else json.dumps(message.value, default=str).encode("utf-8")

        future = self.producer.send(
            self.topic, key=message.key, value=raw_value, headers=headers
        )
        future.get(timeout=10)
        logger.warning(
            "[BO] Parked message offset=%s stage=%s error=%s",
            message.offset, stage, str(error)[:200],
        )

    def close(self) -> None:
        if self._producer is not None:
            self._producer.flush(timeout=10)
            self._producer.close(timeout=10)
            self._producer = None
