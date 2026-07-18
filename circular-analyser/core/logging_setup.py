"""
Centralised logging for the AI engine.

Provides a per-circular **trace id** carried on every log line via a context
variable, so all log output for one circular (whether sourced from email or the
scraper/Kafka) can be followed end-to-end and correlated with the backend (the
same id is sent to the ingest API as the X-Trace-Id header — see persistence).

Usage:
    from core.logging_setup import configure_logging, new_trace_id, get_trace_id
    configure_logging()              # once, at startup
    log = logging.getLogger("pipeline")
    new_trace_id()                   # at the start of processing one circular
    log.info("Processing %s", subject)   # line is tagged with the trace id
"""

import logging
import os
import sys
from contextvars import ContextVar
from logging.handlers import RotatingFileHandler
from uuid import uuid4

# Current circular's trace id; "-" when no circular is being processed.
_trace_id: ContextVar[str] = ContextVar("trace_id", default="-")

_configured = False


def new_trace_id() -> str:
    """Generate, set, and return a fresh short trace id for a new circular."""
    tid = uuid4().hex[:8]
    _trace_id.set(tid)
    return tid


def set_trace_id(tid: str) -> None:
    """Adopt an existing trace id (e.g. one received from the scraper)."""
    _trace_id.set(tid or "-")


def get_trace_id() -> str:
    """The trace id active on the current context."""
    return _trace_id.get()


class _TraceFilter(logging.Filter):
    """Inject the active trace id onto every record so the format can show it."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = _trace_id.get()
        return True


def configure_logging(level: int = logging.INFO,
                      log_file: str = "logs/analyser.log") -> None:
    """Configure root logging once: trace-id-tagged console + rotating file."""
    global _configured
    if _configured:
        return

    fmt = "%(asctime)s %(levelname)-5s [%(trace_id)s] %(name)s — %(message)s"
    formatter = logging.Formatter(fmt, datefmt="%Y-%m-%d %H:%M:%S")
    trace_filter = _TraceFilter()

    root = logging.getLogger()
    root.setLevel(level)

    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(formatter)
    console.addFilter(trace_filter)
    root.addHandler(console)

    try:
        os.makedirs(os.path.dirname(log_file), exist_ok=True)
        file_handler = RotatingFileHandler(
            log_file, maxBytes=5_000_000, backupCount=5, encoding="utf-8")
        file_handler.setFormatter(formatter)
        file_handler.addFilter(trace_filter)
        root.addHandler(file_handler)
    except Exception as exc:  # console-only if the file can't be opened
        root.warning("File logging disabled (%s); logging to console only", exc)

    # Quieten noisy third-party loggers.
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("kafka").setLevel(logging.WARNING)
    # kafka-python 3.0.0's internal event loop warns when a task step exceeds
    # 100ms. Heavy synchronous work on the main thread (LLM calls, DB writes,
    # PDF extraction) under the GIL deschedules the IO thread, so steps measure
    # over the threshold without any real livelock. Suppress the false positives.
    logging.getLogger("kafka.net.selector").setLevel(logging.ERROR)

    _configured = True
