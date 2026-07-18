"""
Publish Pipeline
================
Source-agnostic publish pipeline (v3 architecture).

Any source service calls: pipeline.run("NSE", adapter, raw_data)

Per raw circular:
    1. Mint the system circular_id (UUID) — the join key between NAS,
       the raw tables and the Kafka message.
    2. Download the circular's documents and save them to NAS ONLY
       ({SOURCE}/{YYYY}/{MM}/{circular_id}/{filename}). Bytes never
       travel through Kafka.
    3. Publish the raw envelope (source-native payload + document
       metadata) to circular.raw.v1.

Per run:
    * one ingest_audit row (RUNNING → SUCCESS/PARTIAL/FAILED)
    * source health update (last_run_at / last_success_at / last_error)

Massaging to the canonical schema is the circular-processor's job —
this pipeline never interprets source-native fields.
"""
from __future__ import annotations

import hashlib
import io
import logging
import mimetypes
import zipfile
from datetime import datetime
from pathlib import Path
from uuid import uuid4

import requests

from .db import SourceRepository
from .logging_setup import new_trace_id
from .raw_publisher import RawCircularPublisher
from .storage import get_storage_backend

logger = logging.getLogger(__name__)

_DOWNLOAD_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "*/*",
}


class PublishPipeline:
    """Mint id → NAS documents → Kafka publish → ingest audit."""

    def __init__(
        self,
        db_config: dict | None = None,
        storage_config: dict | None = None,
        kafka_config: dict | None = None,
    ):
        self.repo = SourceRepository(db_config)
        self.storage = get_storage_backend(storage_config)
        self.publisher = RawCircularPublisher(kafka_config)

    def run(self, source_id: str, adapter, raw_data: list[dict]) -> dict:
        """
        Publish a batch of raw circulars fetched by `adapter`.

        Args:
            source_id: Source code ("NSE", "BSE", "EMAIL").
            adapter:   SourceAdapter that produced raw_data (supplies
                       get_document_urls + base_url for relative links).
            raw_data:  Source-native raw circular dicts.

        Returns:
            Counts dict: {"published": N, "failed": F, "total": T}
        """
        logger.info(
            "[Pipeline] Publishing: source=%s records=%d",
            source_id, len(raw_data),
        )

        run_id = self.repo.start_ingest_audit(source_id)
        counts = {"published": 0, "skipped": 0, "failed": 0, "total": len(raw_data)}
        errors: list[str] = []
        now = datetime.now()

        for i, raw in enumerate(raw_data):
            # New trace id per circular — tags this circular's log lines.
            new_trace_id()
            circular_id = str(uuid4())
            try:
                documents = self._save_documents(
                    source_id, circular_id, adapter, raw, now
                )
                envelope = RawCircularPublisher.build_envelope(
                    source_id=source_id,
                    circular_id=circular_id,
                    payload=raw,
                    documents=documents,
                )
                self.publisher.publish(envelope)
                counts["published"] += 1
                logger.info(
                    "[Pipeline] Published [%d/%d] source=%s circular_id=%s docs=%d",
                    i + 1, len(raw_data), source_id, circular_id, len(documents),
                )
                try:
                    adapter.on_published(raw)
                except Exception as e:
                    logger.warning(
                        "[Pipeline] on_published hook failed (circular_id=%s): %s",
                        circular_id, e,
                    )
            except Exception as e:
                counts["failed"] += 1
                errors.append(str(e))
                logger.error(
                    "[Pipeline] Failed [%d/%d] source=%s: %s",
                    i + 1, len(raw_data), source_id, e,
                )

        self.publisher.flush()

        if counts["failed"] == 0:
            status = "SUCCESS"
        elif counts["published"] > 0:
            status = "PARTIAL"
        else:
            status = "FAILED"

        error_summary = "; ".join(errors[:5]) if errors else None
        self.repo.finish_ingest_audit(run_id, status, counts, error_summary)
        self.repo.update_source_health(
            source_id, success=(status == "SUCCESS"), error=error_summary
        )

        logger.info(
            "[Pipeline] Done: source=%s status=%s published=%d failed=%d total=%d",
            source_id, status, counts["published"], counts["failed"], counts["total"],
        )
        return counts

    # ── documents ─────────────────────────────────────────────────────

    def _save_documents(self, source_id: str, circular_id: str, adapter,
                        raw: dict, now: datetime) -> list[dict]:
        """Save each adapter-declared document to NAS.

        Two shapes: blobs already in hand (email attachments) and URLs to
        download (scraped links). A failed document is logged and skipped —
        the circular still publishes, so the processor/UI can fall back to
        the original link.

        ZIP files are expanded: the archive itself is saved first, then every
        member file is extracted and saved at the same circular_id level. All
        entries (ZIP + members) share the same circular_id so the AI processor
        receives all documents for one circular together.
        """
        documents = []

        for blob in adapter.get_document_blobs(raw):
            filename = blob.get("filename") or "attachment"
            content = blob.get("content") or b""
            if not content:
                continue
            try:
                docs = self._save_one(
                    source_id, circular_id, now,
                    content, filename,
                    document_source=blob.get("document_source", "attachment"),
                    hint_mime=blob.get("mime_type"),
                )
                documents.extend(docs)
            except Exception as e:
                logger.warning(
                    "[Pipeline] Attachment save failed (circular_id=%s file=%s): %s",
                    circular_id, filename, e,
                )

        for doc in adapter.get_document_urls(raw):
            url = (doc.get("url") or "").strip()
            if not url:
                continue
            if url.startswith("/") and adapter.base_url:
                url = adapter.base_url.rstrip("/") + url
            try:
                content = self._download(url)
                filename = doc.get("filename") or url.rstrip("/").rsplit("/", 1)[-1]
                docs = self._save_one(
                    source_id, circular_id, now,
                    content, filename,
                    document_source="linked_download",
                )
                documents.extend(docs)
            except Exception as e:
                logger.warning(
                    "[Pipeline] Document download failed (circular_id=%s url=%s): %s",
                    circular_id, url, e,
                )
        return documents

    def _save_one(self, source_id: str, circular_id: str, now: datetime,
                  content: bytes, filename: str,
                  document_source: str, hint_mime: str | None = None) -> list[dict]:
        """
        Save a single document blob. If it is a ZIP, also extract and save
        every member. Returns a list of document dicts (one entry per file
        saved — the archive itself plus all extracted members).
        """
        is_zip = (
            filename.lower().endswith(".zip")
            or (hint_mime or "").lower() == "application/zip"
            or (hint_mime or "").lower() == "application/x-zip-compressed"
        )

        entries = []

        # Always save the original file first
        rel_path = self.storage.save_document(
            source_code=source_id,
            circular_id=circular_id,
            content=content,
            date=now,
            filename=filename,
        )
        entries.append({
            "document_source": document_source,
            "original_filename": filename,
            "nas_relative_path": rel_path,
            "mime_type": hint_mime
                         or mimetypes.guess_type(filename)[0]
                         or "application/octet-stream",
            "size_bytes": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        })

        if not is_zip:
            return entries

        # Extract ZIP members and save each at the same circular_id level
        members = self._extract_zip(content)
        if not members:
            logger.warning("[Pipeline] ZIP %s contained no extractable files", filename)
            return entries

        for member_name, member_bytes in members:
            try:
                member_rel = self.storage.save_document(
                    source_code=source_id,
                    circular_id=circular_id,
                    content=member_bytes,
                    date=now,
                    filename=member_name,
                )
                entries.append({
                    "document_source": "archive_child",
                    "original_filename": member_name,
                    "nas_relative_path": member_rel,
                    "mime_type": mimetypes.guess_type(member_name)[0]
                                 or "application/octet-stream",
                    "size_bytes": len(member_bytes),
                    "sha256": hashlib.sha256(member_bytes).hexdigest(),
                })
                logger.info(
                    "[Pipeline] ZIP extracted: %s → %s (%.1f KB)",
                    filename, member_name, len(member_bytes) / 1024,
                )
            except Exception as e:
                logger.warning(
                    "[Pipeline] ZIP member save failed (circular_id=%s member=%s): %s",
                    circular_id, member_name, e,
                )

        return entries

    @staticmethod
    def _download(url: str, timeout: int = 60) -> bytes:
        response = requests.get(url, headers=_DOWNLOAD_HEADERS, timeout=timeout)
        response.raise_for_status()
        return response.content

    @staticmethod
    def _extract_zip(content: bytes) -> list[tuple[str, bytes]]:
        """Return (member_filename, member_bytes) for every file inside a ZIP."""
        members = []
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as zf:
                for info in zf.infolist():
                    if info.is_dir():
                        continue
                    # Use only the basename — flatten nested zip subdirs to same level
                    name = Path(info.filename).name
                    if not name:
                        continue
                    members.append((name, zf.read(info.filename)))
        except zipfile.BadZipFile as e:
            logger.warning("[Pipeline] ZIP extraction failed: %s", e)
        return members

    # ── lifecycle ─────────────────────────────────────────────────────

    def close(self) -> None:
        self.publisher.close()
        self.repo.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False
