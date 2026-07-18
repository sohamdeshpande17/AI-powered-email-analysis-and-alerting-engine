"""
AI stage (v3 architecture §2, Stage 4).

Fetches the circular's documents FROM NAS (saved there by the source
service — bytes never travel through Kafka), verifies the sha256, extracts
text, builds the prompt, and calls the configured LLM provider.

Reuses the engine's existing modules:
    emails.attachment_extractor.extract_attachment_text — PDF/DOCX/XLSX/CSV
    core.prompt_builder                                 — system prompt + text block
    providers.get_provider                              — Claude / OpenAI
"""
from __future__ import annotations

import hashlib
import logging
from pathlib import Path
from typing import Any

from core.prompt_builder import build_email_text
from emails.attachment_extractor import extract_attachment_text
from providers import get_provider

from . import config

logger = logging.getLogger(__name__)


class AIStage:
    def __init__(self, nas_base_path: str | None = None):
        self.nas_base = Path(nas_base_path or config.NAS_BASE_PATH)
        self.provider = get_provider()
        logger.info("[AIStage] Provider=%s model=%s, NAS=%s",
                    self.provider.name, self.provider.model, self.nas_base)

    def analyse(self, raw_row: dict[str, Any],
                documents: list[dict]) -> tuple[dict[str, Any], dict[str, str]]:
        """
        Run the LLM on one circular.

        Returns (analysis_dict, extracted_texts) where extracted_texts maps
        nas_relative_path → text (persisted for document search).
        """
        attachments, extracted_texts = [], {}

        for doc in documents:
            rel_path = doc.get("nas_relative_path", "")
            filename = doc.get("original_filename", rel_path.rsplit("/", 1)[-1])
            content = self._read_nas(rel_path, doc.get("sha256"))
            if content is None:
                continue
            text = extract_attachment_text(
                filename, doc.get("mime_type", ""), content
            )
            extracted_texts[rel_path] = text
            attachments.append({
                "filename": filename,
                "type": doc.get("mime_type", ""),
                "size": doc.get("size_bytes") or len(content),
                "content": text,
            })

        email_shaped = {
            "sender": f"{raw_row.get('source_name') or ''} "
                      f"<{raw_row.get('source') or raw_row['source_id']}>",
            "date": str(raw_row.get("issued_at") or ""),
            "subject": raw_row["subject"],
            "body": raw_row.get("email_body") or "",
            "attachments": attachments,
        }

        email_text = build_email_text(email_shaped)
        analysis, elapsed = self.provider.process_realtime(email_text)
        logger.info("[AIStage] Analysed circular_id=%s in %.1fs (urgency=%s, "
                    "circular_no=%s)", raw_row["circular_id"], elapsed,
                    analysis.get("urgency"), analysis.get("circular_id"))

        if analysis.get("_parse_error"):
            raise RuntimeError(
                f"LLM response unparseable: {analysis.get('summary', '')[:200]}"
            )
        return analysis, extracted_texts

    def _read_nas(self, rel_path: str, sha256: str | None) -> bytes | None:
        """Read one document from the NAS mount, verifying its hash."""
        if not rel_path:
            return None
        full = self.nas_base / rel_path
        try:
            content = full.read_bytes()
        except OSError as e:
            logger.warning("[AIStage] NAS read failed (%s): %s", full, e)
            return None
        if sha256 and hashlib.sha256(content).hexdigest() != sha256.strip():
            logger.warning("[AIStage] sha256 mismatch — skipping %s", rel_path)
            return None
        return content
