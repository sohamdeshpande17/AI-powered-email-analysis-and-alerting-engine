"""
Raw Circular Event Publisher
============================
Publishes raw circular events to Kafka (v3 architecture §3).

Every source service (nse-scraper, bse-scraper, email-poller) publishes the
same envelope to ONE topic — circular.raw.v1 — keyed by the system-generated
circular_id with a source_id header. Source-based massaging happens on the
consumer side (circular-processor), NOT here.

Document bytes never travel through Kafka: sources save files to NAS and the
envelope carries metadata + NAS paths only.

Envelope:
    {
      "event_type": "circular.raw", "event_version": "1",
      "event_id": "<uuid>", "occurred_at": "<iso>",
      "source_id": "NSE", "circular_id": "<uuid>",
      "payload": { ...source-native raw fields... },
      "documents": [ {original_filename, nas_relative_path, mime_type,
                      size_bytes, sha256, document_source} ]
    }
"""
from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone
from uuid import uuid4

from .kafka_config import KAFKA_CONFIG

logger = logging.getLogger(__name__)

RAW_TOPIC = os.getenv("KAFKA_RAW_TOPIC", "circular.raw.v1")


class RawCircularPublisher:
    """Kafka producer for circular.raw.v1 events."""

    def __init__(self, kafka_config: dict | None = None, topic: str | None = None):
        self._config = kafka_config or KAFKA_CONFIG
        self.topic = topic or RAW_TOPIC
        self._producer = None

    @property
    def producer(self):
        if self._producer is None:
            self._producer = self._create_producer()
        return self._producer

    def _create_producer(self):
        try:
            from kafka import KafkaProducer
        except ImportError:
            raise ImportError(
                "kafka-python is required. Install with: pip install kafka-python"
            )

        producer_kwargs = {
            "bootstrap_servers": self._config["bootstrap_servers"],
            "value_serializer": lambda v: json.dumps(v, default=str).encode("utf-8"),
            "key_serializer": lambda k: k.encode("utf-8") if k else None,
            "acks": "all",
            "retries": 3,
            "max_in_flight_requests_per_connection": 1,  # ordering guarantee
        }

        security_protocol = self._config.get("security_protocol", "PLAINTEXT")
        producer_kwargs["security_protocol"] = security_protocol
        if security_protocol in ("SASL_PLAINTEXT", "SASL_SSL"):
            sasl_mechanism = self._config.get("sasl_mechanism")
            if sasl_mechanism:
                producer_kwargs["sasl_mechanism"] = sasl_mechanism
                producer_kwargs["sasl_plain_username"] = self._config.get("sasl_username")
                producer_kwargs["sasl_plain_password"] = self._config.get("sasl_password")

        logger.info(
            "[RawPublisher] Connecting to %s (topic=%s, protocol=%s)",
            self._config["bootstrap_servers"], self.topic, security_protocol,
        )
        return KafkaProducer(**producer_kwargs)

    @staticmethod
    def build_envelope(source_id: str, circular_id: str, payload: dict,
                       documents: list[dict]) -> dict:
        # No tenant_id: raw_circular is the shared common pool. The processor
        # fans out per-workspace summarized copies via source_tenant.
        return {
            "event_type": "circular.raw",
            "event_version": "1",
            "event_id": str(uuid4()),
            "occurred_at": datetime.now(timezone.utc).isoformat(),
            "source_id": source_id,
            "circular_id": circular_id,
            "payload": payload,
            "documents": documents,
        }

    def publish(self, envelope: dict) -> None:
        """Publish one envelope, keyed by circular_id with a source_id header."""
        future = self.producer.send(
            self.topic,
            key=envelope["circular_id"],
            value=envelope,
            headers=[("source_id", envelope["source_id"].encode("utf-8"))],
        )
        meta = future.get(timeout=10)
        logger.debug(
            "[RawPublisher] Published %s partition=%d offset=%d circular_id=%s",
            meta.topic, meta.partition, meta.offset, envelope["circular_id"],
        )

    def flush(self, timeout: float = 30.0) -> None:
        if self._producer is not None:
            self._producer.flush(timeout=timeout)

    def close(self) -> None:
        if self._producer is not None:
            try:
                self._producer.flush(timeout=10)
                self._producer.close(timeout=10)
            except Exception as e:
                logger.warning("[RawPublisher] Error closing producer: %s", e)
            finally:
                self._producer = None
            logger.info("[RawPublisher] Closed.")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False
