"""
Source Adapter Contract
=======================
Abstract base class for all source adapters (NSE, BSE, SEBI, RBI, etc.).
Each adapter knows how to fetch circulars from one specific regulator.

v3: adapters only fetch raw data and point at downloadable documents. Mapping
to the canonical schema happens on the consumer side (circular-processor).
Adding a new source = one subclass of SourceAdapter + a registry entry in
adapters/__init__.py + a source row in the DB.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, date
from typing import Any


@dataclass
class DateWindow:
    """Date range for fetching circulars."""
    from_date: date
    to_date: date

    def from_str(self, fmt: str = "%d-%m-%Y") -> str:
        return self.from_date.strftime(fmt)

    def to_str(self, fmt: str = "%d-%m-%Y") -> str:
        return self.to_date.strftime(fmt)

    @classmethod
    def today(cls) -> DateWindow:
        """Create a window for today only."""
        t = date.today()
        return cls(from_date=t, to_date=t)

    @classmethod
    def last_n_days(cls, n: int) -> DateWindow:
        """Create a window from N days ago to today."""
        from datetime import timedelta
        t = date.today()
        return cls(from_date=t - timedelta(days=n), to_date=t)

    @classmethod
    def from_strings(cls, from_str: str, to_str: str, fmt: str = "%d-%m-%Y") -> DateWindow:
        """Parse from dd-mm-yyyy strings."""
        return cls(
            from_date=datetime.strptime(from_str, fmt).date(),
            to_date=datetime.strptime(to_str, fmt).date(),
        )


class SourceAdapter(ABC):
    """
    Abstract base class for source adapters.

    Every adapter must implement:
        - fetch(window) → list[dict]               (raw API/scraped data)
        - get_document_urls(raw) → list[dict]      (downloadable documents)

    The publish pipeline handles everything else (NAS save, Kafka publish,
    ingest audit).
    """

    code: str = ""
    name: str = ""
    base_url: str = ""

    @abstractmethod
    def fetch(self, window: DateWindow) -> list[dict]:
        """
        Fetch raw circular data from the source for the given date window.

        Args:
            window: Date range to fetch.

        Returns:
            List of raw circular dicts from the source API/scrape.
        """

    def get_document_urls(self, raw: dict[str, Any]) -> list[dict]:
        """
        Return downloadable documents for one raw circular.

        Each entry: {"url": "<absolute url>", "filename": "<name or ''>"}.
        Default: no documents.
        """
        return []

    def get_document_blobs(self, raw: dict[str, Any]) -> list[dict]:
        """
        Return documents already held as bytes (e.g. email attachments).

        Each entry: {"filename": str, "content": bytes,
                     "mime_type": str, "document_source": str}.
        Default: none.
        """
        return []

    def on_published(self, raw: dict[str, Any]) -> None:
        """
        Hook called after the raw circular's Kafka publish is acked.
        Used by the email poller to mark the mail as read only once the
        event is safely on the topic. Default: no-op.
        """
