"""
NSE Source Adapter
==================
Fetches circulars from the National Stock Exchange of India (NSE).

Uses NSE's CSV endpoint directly (no browser automation):

    https://www.nseindia.com/api/circulars?fromDate=DD-MM-YYYY&toDate=DD-MM-YYYY&csv=true

The CSV columns are normalised back into the source-native keys the publish
pipeline and the consumer's NseMapper already expect (circNumber, sub, cirDate,
fileDept, circFilelink) — so nothing downstream changes.

CSV columns:
    DATE, DEPARTMENT, DOWNLOAD REFERENCE NO., SUBJECT, LINK, FILE SIZE
"""
from __future__ import annotations

import csv
import io
import logging
from datetime import datetime

import requests

from core.adapter import SourceAdapter, DateWindow

logger = logging.getLogger(__name__)

_REQUEST_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/csv,*/*",
    "Referer": "https://www.nseindia.com/resources/exchange-communication-circulars",
}


def _normalize_date(value: str) -> str:
    """'June 15, 2026' -> '15-Jun-2026' (a format NseMapper already parses).

    Falls back to the stripped original if the format is unexpected — the
    mapper logs a warning rather than crashing in that case.
    """
    cleaned = (value or "").strip()
    try:
        return datetime.strptime(cleaned, "%B %d, %Y").strftime("%d-%b-%Y")
    except ValueError:
        return cleaned


def parse_circulars_csv(text: str) -> list[dict]:
    """Parse the NSE circulars CSV into normalised raw circular dicts.

    Pure function (no network) so it is unit-testable. Rows are deduped by
    (reference no., link) — NSE lists the same circular under several
    departments — keeping the first department seen.
    """
    rows: list[dict] = []
    seen: set[tuple[str, str]] = set()

    # Guard against a leading BOM surviving into the text (keeps the first
    # column header intact even if the caller didn't decode as utf-8-sig).
    text = text.lstrip(chr(0xFEFF))

    for row in csv.DictReader(io.StringIO(text)):
        ref = (row.get("DOWNLOAD REFERENCE NO.") or "").strip()
        link = (row.get("LINK") or "").strip()
        subject = (row.get("SUBJECT") or "").strip()

        if not subject or not link:
            logger.warning("[NSE] Skipping row with no subject/link: ref=%s", ref)
            continue

        key = (ref, link)
        if key in seen:
            continue
        seen.add(key)

        filename = link.rstrip("/").rsplit("/", 1)[-1]
        rows.append({
            "circNumber": ref,
            "sub": subject,
            "cirDate": _normalize_date(row.get("DATE") or ""),
            "fileDept": (row.get("DEPARTMENT") or "").strip(),
            "circFilelink": link,
            "circFilename": filename,
        })

    return rows


class NSEAdapter(SourceAdapter):
    """NSE circular adapter — reads the CSV endpoint with plain requests."""

    code = "NSE"
    name = "National Stock Exchange of India"
    base_url = "https://www.nseindia.com"

    def get_document_urls(self, raw: dict) -> list[dict]:
        """NSE's circFilelink IS the document (pdf/zip)."""
        url = str(raw.get("circFilelink") or "").strip()
        if not url:
            return []
        return [{"url": url, "filename": str(raw.get("circFilename") or "").strip()}]

    def fetch(self, window: DateWindow) -> list[dict]:
        """Fetch circulars from NSE's CSV endpoint for the given date window."""
        from_str = window.from_str("%d-%m-%Y")
        to_str = window.to_str("%d-%m-%Y")

        logger.info("[NSE] Fetching circulars from %s to %s ...", from_str, to_str)

        url = (
            "https://www.nseindia.com/api/circulars"
            f"?fromDate={from_str}&toDate={to_str}&csv=true"
        )
        try:
            response = requests.get(url, headers=_REQUEST_HEADERS, timeout=60)
            response.raise_for_status()
        except requests.RequestException as e:
            logger.error("[NSE] Fetch error: %s", e)
            return []

        # NSE returns UTF-8 WITH a BOM but no charset in the Content-Type
        # header, so requests defaults to ISO-8859-1 and mangles the first
        # column header ("DATE" -> "ï»¿\"DATE\""), silently dropping the
        # circular date. Decode as utf-8-sig so the BOM is stripped.
        response.encoding = "utf-8-sig"
        circulars = parse_circulars_csv(response.text)
        logger.info("[NSE] Found %d circulars", len(circulars))
        return circulars
