"""
circular-processor consumer (v3 architecture §2).

One consume loop, five stages per message:

    Stage 1  CONSUME     validate the circular.raw envelope
    Stage 2  MAP         source-based massaging (processor.mappers)
    Stage 3  RAW_DB      raw_circular + raw_circular_document (own txn)
    Stage 4  AI          NAS docs → prompt → LLM
    Stage 5  SUMMARY_DB  circular + RECEIVED workflow row (one txn)

Failure contract (§3.3):
    * transient errors retry in-process (3 attempts, exponential backoff)
    * poison messages (bad envelope, unknown source) and exhausted retries
      are parked on the BO topic; the offset commits and the loop continues
    * a failed AI stage does NOT roll back the raw write — Stage 3 is
      idempotent, so a BO replay only redoes the missing stages
"""
from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any

from core.logging_setup import new_trace_id

from . import config
from .ai_stage import AIStage
from .bo import BOPublisher
from .db_writer import CircularWriter
from .mappers import MappingError, get_mapper

logger = logging.getLogger(__name__)

_REQUIRED_FIELDS = ("circular_id", "source_id", "payload")


class PoisonMessage(Exception):
    """Unrecoverable message — straight to BO, no retries."""

    def __init__(self, stage: str, msg: str):
        super().__init__(msg)
        self.stage = stage


class RawCircularProcessor:
    """The single v3 application: consumer + massaging + DB writes + AI + BO."""

    def __init__(self):
        self._consumer = None
        self._shutdown = threading.Event()
        self.bo = BOPublisher()
        self.writer = CircularWriter()
        self.ai = AIStage()

    # ── consumer setup ────────────────────────────────────────────────

    def _create_consumer(self):
        from kafka import KafkaConsumer

        # max_poll_records=1: one event per poll — LLM calls take 10-20s and
        # a buffered batch would blow max.poll.interval. 30 min headroom.
        self._consumer = KafkaConsumer(
            config.KAFKA_RAW_TOPIC,
            bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS,
            group_id=config.KAFKA_CONSUMER_GROUP,
            auto_offset_reset=config.KAFKA_AUTO_OFFSET_RESET,
            enable_auto_commit=False,
            consumer_timeout_ms=5000,
            max_poll_records=1,
            max_poll_interval_ms=1800000,
        )
        logger.info(
            "[Processor] Subscribed — topic=%s group=%s servers=%s",
            config.KAFKA_RAW_TOPIC, config.KAFKA_CONSUMER_GROUP,
            config.KAFKA_BOOTSTRAP_SERVERS,
        )

    def start(self) -> None:
        """Consume until stop(). Blocks."""
        self._create_consumer()
        logger.info("[Processor] Consume loop started")
        try:
            while not self._shutdown.is_set():
                for message in self._consumer:
                    if self._shutdown.is_set():
                        break
                    self._handle_message(message)
                    self._safe_commit()
        finally:
            self._cleanup()

    def stop(self) -> None:
        logger.info("[Processor] Stop requested")
        self._shutdown.set()

    def _safe_commit(self) -> None:
        from kafka.errors import CommitFailedError
        try:
            self._consumer.commit()
        except CommitFailedError as e:
            logger.warning("[Processor] Offset commit deferred (group rejoin "
                           "on next poll): %s", e)

    def _cleanup(self) -> None:
        for closer in (lambda: self._consumer and self._consumer.close(),
                       self.bo.close, self.writer.close):
            try:
                closer()
            except Exception as e:
                logger.warning("[Processor] Cleanup error: %s", e)
        logger.info("[Processor] Stopped")

    # ── message handling ──────────────────────────────────────────────

    def _handle_message(self, message) -> None:
        """Retry wrapper + BO parking around _process. Never raises."""
        new_trace_id()
        attempt, stage = 0, "CONSUME"
        while True:
            attempt += 1
            try:
                self._process(message)
                return
            except PoisonMessage as e:
                self.bo.park(message, e.stage, str(e), retry_count=0)
                return
            except Exception as e:
                stage = getattr(e, "_stage", stage)
                if attempt >= config.RETRY_MAX_ATTEMPTS:
                    try:
                        self.bo.park(message, stage, str(e), retry_count=attempt)
                    except Exception as bo_err:
                        logger.critical(
                            "[Processor] BO publish ALSO failed — message "
                            "offset=%s lost from flow until replayed manually: %s",
                            message.offset, bo_err,
                        )
                    return
                delay = config.RETRY_BASE_DELAY_SECONDS * (4 ** (attempt - 1))
                logger.warning(
                    "[Processor] Attempt %d/%d failed at %s (%s) — retrying "
                    "in %.0fs", attempt, config.RETRY_MAX_ATTEMPTS, stage, e, delay,
                )
                self.writer.reconnect_if_needed()
                time.sleep(delay)

    def _process(self, message) -> None:
        """Run the five stages for one message."""
        # Stage 1 — CONSUME: parse + validate envelope
        stage = "CONSUME"
        try:
            event = message.value if isinstance(message.value, dict) \
                else json.loads(message.value.decode("utf-8"))
        except Exception as e:
            raise PoisonMessage(stage, f"Envelope is not valid JSON: {e}")
        missing = [f for f in _REQUIRED_FIELDS if not event.get(f)]
        if missing:
            raise PoisonMessage(stage, f"Envelope missing fields: {missing}")

        # tenant_id on the envelope is only meaningful for MANUAL uploads
        # (the uploader's workspace); scraped sources fan out via source_tenant.
        manual_tenant_id = event.get("tenant_id")
        documents = event.get("documents") or []

        logger.info("[Processor] Event source=%s circular_id=%s docs=%d",
                    event["source_id"], event["circular_id"], len(documents))

        # Stage 2 — MAP: source-based massaging
        stage = "MAP"
        try:
            raw_row = get_mapper(event["source_id"]).map(event)
        except MappingError as e:
            raise PoisonMessage(stage, str(e))
        except Exception as e:
            raise PoisonMessage(stage, f"Mapper crashed: {e}")

        # Stage 3 — RAW_DB (own transaction; idempotent; shared, no tenant)
        stage = "RAW_DB"
        try:
            outcome = self.writer.write_raw(raw_row, documents)
        except Exception as e:
            e._stage = stage
            raise
        if outcome == "duplicate":
            return  # re-scrape of an already-ingested circular

        # Stage 4 — AI
        stage = "AI"
        try:
            analysis, extracted = self.ai.analyse(raw_row, documents)
            self.writer.update_extracted_text(raw_row["circular_id"], extracted)
        except Exception as e:
            e._stage = stage
            raise

        # Stage 5 — SUMMARY_DB: write the canonical public.circular once, then
        # copy it into each workspace schema the source feeds.
        stage = "SUMMARY_DB"
        try:
            circular_no = self.writer.write_canonical(
                raw_row, analysis, self.ai.provider.name, self.ai.provider.model,
            )
            schemas = self.writer.resolve_target_schemas(
                raw_row["source_id"], manual_tenant_id=manual_tenant_id,
            )
            if not schemas:
                logger.warning(
                    "[Processor] No workspaces mapped to source=%s — canonical "
                    "stored, no tenant copy (raw=%s)",
                    raw_row["source_id"], raw_row["circular_id"],
                )
                return
            for schema in schemas:
                self.writer.write_tenant_circular(
                    schema, raw_row, analysis, self.ai.provider.name,
                    self.ai.provider.model, circular_no,
                )
                logger.info("[Processor] ✓ circular_no=%s schema=%s (raw=%s, source=%s)",
                            circular_no, schema, raw_row["circular_id"], raw_row["source_id"])
        except Exception as e:
            e._stage = stage
            raise
