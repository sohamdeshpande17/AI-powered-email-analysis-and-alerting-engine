"""
Centralised logging for the scraper service.

Adds a per-circular **trace id** to every log line via a context variable, so
all log output for one circular (raw → mapped → upserted) can be followed, and
the format is shared across the CLI and scheduler entry points.

Usage:
    from core.logging_setup import configure_logging, new_trace_id
    configure_logging()                  # once, at startup
    logger = logging.getLogger(__name__)
    new_trace_id()                       # at the start of one circular
    logger.info("Mapped %s", ref)        # line is tagged with the trace id
"""
from __future__ import annotations

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
    """Adopt an existing trace id."""
    _trace_id.set(tid or "-")


def get_trace_id() -> str:
    """The trace id active on the current context."""
    return _trace_id.get()


class _TraceFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = _trace_id.get()
        return True


def configure_logging(level: int = logging.INFO,
                      log_file: str = "logs/scraper.log") -> None:
    """Configure root logging once: trace-id-tagged console + rotating file."""
    global _configured
    if _configured:
        return

    fmt = "%(asctime)s [%(levelname)s] [%(trace_id)s] %(name)s — %(message)s"
    formatter = logging.Formatter(fmt, datefmt="%Y-%m-%d %H:%M:%S")
    trace_filter = _TraceFilter()

    root = logging.getLogger()
    root.setLevel(level)
    # Replace any handlers a prior basicConfig installed so the trace-id format
    # and filter apply uniformly.
    for handler in list(root.handlers):
        root.removeHandler(handler)

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
    except Exception as exc:
        root.warning("File logging disabled (%s); logging to console only", exc)

    logging.getLogger("urllib3").setLevel(logging.WARNING)

    _configured = True
