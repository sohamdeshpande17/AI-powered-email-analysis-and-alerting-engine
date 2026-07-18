"""
Source-based massaging (v3 architecture §2, Stage 2).

The sources publish their native payloads untouched; these mappers own the
translation to the canonical raw_circular row. Registry is keyed by the
event's source_id. Adding a source = one mapper class + one registry entry.

Mapper output — column dict for public.raw_circular:
    circular_id, source_id, circular_no, subject, issued_at (date|None),
    department, email_body, source_name, source, source_url, storage_path
"""
from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from datetime import date, datetime
from typing import Any

logger = logging.getLogger(__name__)


class MappingError(ValueError):
    """Poison-message error — the payload cannot be massaged (no retry)."""


def _parse_date(value: str | None, formats: tuple[str, ...]) -> date | None:
    if not value:
        return None
    cleaned = str(value).strip()
    for fmt in formats:
        try:
            return datetime.strptime(cleaned, fmt).date()
        except ValueError:
            continue
    logger.warning("Could not parse date '%s'", value)
    return None


def _storage_path(event: dict[str, Any]) -> str | None:
    """Derive the circular's NAS folder from its first document path."""
    docs = event.get("documents") or []
    if not docs:
        return None
    rel = docs[0].get("nas_relative_path", "")
    return rel.rsplit("/", 1)[0] if "/" in rel else None


class RawCircularMapper(ABC):
    """Massages one circular.raw event payload into raw_circular columns."""

    @abstractmethod
    def map(self, event: dict[str, Any]) -> dict[str, Any]:
        ...

    def _base(self, event: dict[str, Any]) -> dict[str, Any]:
        return {
            "circular_id": event["circular_id"],
            "source_id": event["source_id"],
            "storage_path": _storage_path(event),
        }


class NseMapper(RawCircularMapper):
    """NSE API payload: circNumber, sub/desc, cirDate, fileDept, circFilelink."""

    def map(self, event: dict[str, Any]) -> dict[str, Any]:
        raw = event.get("payload") or {}
        subject = str(raw.get("sub") or raw.get("desc") or "").strip()
        if not subject:
            raise MappingError("NSE payload has no subject (sub/desc)")
        row = self._base(event)
        row.update({
            "circular_no": str(raw.get("circNumber") or "").strip() or None,
            "subject": subject,
            "issued_at": _parse_date(raw.get("cirDate") or raw.get("circDt"),
                                     ("%Y%m%d", "%d-%b-%Y")),
            "department": str(raw.get("circDepartment") or raw.get("fileDept")
                              or "").strip() or None,
            "email_body": None,
            "source_name": "National Stock Exchange of India",
            "source": "https://www.nseindia.com",
            "source_url": str(raw.get("circFilelink") or raw.get("circUrl")
                              or "").strip() or None,
        })
        return row


class EmailMapper(RawCircularMapper):
    """Email-poller payloads. The mail category maps to department."""

    def map(self, event: dict[str, Any]) -> dict[str, Any]:
        raw = event.get("payload") or {}
        subject = str(raw.get("subject") or "").strip()
        if not subject:
            raise MappingError("Email payload has no subject")
        categories = raw.get("categories") or []
        row = self._base(event)
        row.update({
            "circular_no": None,  # the AI extracts the official number
            "subject": subject,
            "issued_at": _parse_date(str(raw.get("received_at", ""))[:10],
                                     ("%Y-%m-%d",)),
            "department": str(categories[0]).strip() if categories else None,
            "email_body": raw.get("body") or None,
            "source_name": str(raw.get("sender_name") or "").strip() or None,
            "source": str(raw.get("sender_email") or "").strip() or None,
            "source_url": None,
        })
        return row


class ManualMapper(RawCircularMapper):
    """UI manual-upload payloads (backend POST /api/circulars/upload)."""

    def map(self, event: dict[str, Any]) -> dict[str, Any]:
        raw = event.get("payload") or {}
        subject = str(raw.get("subject") or "").strip()
        if not subject:
            raise MappingError("Manual-upload payload has no subject")
        row = self._base(event)
        row.update({
            "circular_no": str(raw.get("circular_no") or "").strip() or None,
            "subject": subject,
            "issued_at": _parse_date(raw.get("issued_at"), ("%Y-%m-%d",)),
            "department": str(raw.get("department") or "").strip() or None,
            "email_body": raw.get("body") or None,
            "source_name": str(raw.get("uploaded_by_name") or "").strip()
                           or "Manual Upload",
            "source": str(raw.get("uploaded_by_email") or "").strip() or None,
            "source_url": None,
        })
        return row


_REGISTRY: dict[str, RawCircularMapper] = {
    "NSE": NseMapper(),
    "EMAIL": EmailMapper(),
    "MANUAL": ManualMapper(),
}


def get_mapper(source_id: str) -> RawCircularMapper:
    """Resolve the mapper for a source_id. Unknown source = poison message."""
    try:
        return _REGISTRY[source_id.upper()]
    except KeyError:
        raise MappingError(f"No mapper registered for source_id={source_id!r}")
