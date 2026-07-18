"""
EMAIL Source Adapter (O365 mailbox poller)
==========================================
Polls an Office 365 mailbox for UNREAD mail via the Microsoft Graph API
(MSAL client-credentials / daemon auth) and turns each mail into one raw
circular event.

v3 contract:
    * fetch()                — unread messages as source-native dicts
    * get_document_blobs()   — attachment bytes (saved to NAS by the pipeline)
    * get_document_urls()    — document links found in the mail body
    * on_published()         — marks the mail READ only after the Kafka
                               publish is acked (at-least-once delivery)

Configuration:
    Env:  O365_TENANT_ID, O365_CLIENT_ID, O365_CLIENT_SECRET  (credentials)
    DB:   source('EMAIL').config_json — {"mailbox": ..., "fetch_limit": 25}
          (env O365_TARGET_EMAIL overrides the mailbox)

Requires an Azure AD app registration with Mail.ReadWrite (Application).
"""
from __future__ import annotations

import base64
import logging
import os
import re
from typing import Any

import requests

from core.adapter import SourceAdapter, DateWindow

logger = logging.getLogger(__name__)

GRAPH_BASE = "https://graph.microsoft.com/v1.0"
SCOPES = ["https://graph.microsoft.com/.default"]

# Document links worth pulling out of mail bodies
_DOC_LINK_RE = re.compile(
    r"https?://[^\s\"'<>]+?\.(?:pdf|docx?|xlsx?|zip)(?=[\s\"'<>]|$)",
    re.IGNORECASE,
)


class EmailAdapter(SourceAdapter):
    """O365 mailbox adapter — one unread mail = one raw circular."""

    code = "EMAIL"
    name = "Compliance O365 Inbox"
    base_url = ""

    def __init__(self, mailbox: str | None = None, fetch_limit: int = 25,
                 allowed_senders: list[str] | None = None):
        self.mailbox = mailbox or os.getenv("O365_TARGET_EMAIL", "")
        self.fetch_limit = fetch_limit
        # Allow-list of sender addresses (lower-cased). Only mail FROM one of
        # these is published to Kafka. Empty/None = no restriction (all unread
        # mail is published, the legacy behaviour).
        self.allowed_senders = {
            str(s).strip().lower()
            for s in (allowed_senders or [])
            if str(s).strip()
        }
        self._token: str | None = None

    # ── auth ──────────────────────────────────────────────────────────

    def _get_token(self) -> str:
        if self._token:
            return self._token

        try:
            import msal
        except ImportError:
            raise ImportError("msal is required. Install with: pip install msal")

        tenant = os.getenv("O365_TENANT_ID", "")
        client_id = os.getenv("O365_CLIENT_ID", "")
        client_secret = os.getenv("O365_CLIENT_SECRET", "")
        if not (tenant and client_id and client_secret):
            raise ValueError(
                "O365 credentials not set — configure O365_TENANT_ID, "
                "O365_CLIENT_ID and O365_CLIENT_SECRET."
            )

        app = msal.ConfidentialClientApplication(
            client_id=client_id,
            client_credential=client_secret,
            authority=f"https://login.microsoftonline.com/{tenant}",
        )
        result = app.acquire_token_for_client(scopes=SCOPES)
        if "access_token" not in result:
            error = result.get("error_description", result.get("error", "unknown"))
            raise ValueError(f"Failed to acquire O365 access token: {error}")

        self._token = result["access_token"]
        return self._token

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self._get_token()}",
            "Content-Type": "application/json",
        }

    # ── fetch ─────────────────────────────────────────────────────────

    def fetch(self, window: DateWindow) -> list[dict]:
        """Fetch UNREAD messages (window is ignored — unread IS the filter)."""
        if not self.mailbox:
            raise ValueError("Mailbox not configured (source.config_json.mailbox "
                             "or O365_TARGET_EMAIL).")

        logger.info("[EMAIL] Fetching unread mail from %s ...", self.mailbox)

        url = f"{GRAPH_BASE}/users/{self.mailbox}/messages"
        params = {
            "$filter": "isRead eq false",
            "$top": str(self.fetch_limit),
            "$orderby": "receivedDateTime DESC",
            "$select": "id,subject,sender,receivedDateTime,body,categories,hasAttachments",
        }
        resp = requests.get(url, headers=self._headers(), params=params, timeout=30)
        resp.raise_for_status()
        messages = resp.json().get("value", [])

        logger.info("[EMAIL] Found %d unread message(s)", len(messages))

        raws = []
        skipped = 0
        for msg in messages:
            sender_obj = msg.get("sender", {}).get("emailAddress", {})
            sender_email = sender_obj.get("address", "")

            # Only publish mail from approved senders. Non-approved unread mail
            # is left untouched (still UNREAD) for human review — we never push
            # it to Kafka.
            if self.allowed_senders and sender_email.strip().lower() not in self.allowed_senders:
                skipped += 1
                logger.info("[EMAIL] Skipping mail from non-approved sender: %s",
                            sender_email or "(unknown)")
                continue

            body_obj = msg.get("body", {})
            raw_body = body_obj.get("content", "")
            body_text = raw_body
            if body_obj.get("contentType", "").lower() == "html":
                body_text = re.sub(r"<[^>]+>", " ", raw_body)
                body_text = re.sub(r"\s+", " ", body_text).strip()

            raws.append({
                "message_id": msg.get("id", ""),
                "subject": msg.get("subject", "(no subject)"),
                "sender_name": sender_obj.get("name", ""),
                "sender_email": sender_obj.get("address", ""),
                "received_at": msg.get("receivedDateTime", ""),
                "body": body_text,
                "body_html": raw_body,
                # mail category — the processor maps this to department
                "categories": msg.get("categories", []),
                "has_attachments": bool(msg.get("hasAttachments", False)),
            })

        if self.allowed_senders:
            logger.info("[EMAIL] Approved %d / %d unread message(s) "
                        "(%d skipped — sender not on allow-list)",
                        len(raws), len(messages), skipped)
        return raws

    # ── documents ─────────────────────────────────────────────────────

    def get_document_blobs(self, raw: dict[str, Any]) -> list[dict]:
        """Fetch attachment bytes for one message."""
        if not raw.get("has_attachments"):
            return []

        url = (f"{GRAPH_BASE}/users/{self.mailbox}/messages/"
               f"{raw['message_id']}/attachments")
        # No $select — Graph 400s on some attachment types when it is used.
        resp = requests.get(url, headers=self._headers(), timeout=30)
        resp.raise_for_status()

        blobs = []
        for att in resp.json().get("value", []):
            if att.get("@odata.type", "") == "#microsoft.graph.itemAttachment":
                continue  # inline/nested items — not file documents
            content_b64 = att.get("contentBytes", "")
            if not content_b64:
                continue
            try:
                content = base64.b64decode(content_b64)
            except Exception:
                continue
            blobs.append({
                "filename": att.get("name", "attachment"),
                "content": content,
                "mime_type": att.get("contentType", "application/octet-stream"),
                "document_source": "attachment",
            })
        return blobs

    def get_document_urls(self, raw: dict[str, Any]) -> list[dict]:
        """Document links (pdf/doc/xls/zip) found in the mail body."""
        seen, urls = set(), []
        for url in _DOC_LINK_RE.findall(raw.get("body_html", "")):
            if url not in seen:
                seen.add(url)
                urls.append({"url": url, "filename": ""})
        return urls

    # ── post-publish ──────────────────────────────────────────────────

    def on_published(self, raw: dict[str, Any]) -> None:
        """Mark the mail READ — called only after the Kafka publish acked."""
        url = f"{GRAPH_BASE}/users/{self.mailbox}/messages/{raw['message_id']}"
        resp = requests.patch(url, headers=self._headers(),
                              json={"isRead": True}, timeout=15)
        if resp.status_code == 403:
            raise RuntimeError(
                "403 Forbidden marking mail read — ensure Mail.ReadWrite "
                "(Application) permission with admin consent."
            )
        resp.raise_for_status()
        logger.info("[EMAIL] Marked read: %s", raw.get("subject", ""))
