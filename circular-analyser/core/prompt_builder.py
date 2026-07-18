"""
Prompt construction — builds the system prompt and email text block
for LLM analysis.

The allowed category taxonomy is fetched dynamically from the database
(public.circular_category) at prompt-build time, so adding/removing a
category in the UI changes classification without a code change. A short
TTL cache avoids a DB round-trip on every analysis, and a hardcoded
fallback keeps analysis working if the DB is briefly unreachable.
"""

import os
import time
from datetime import datetime
from typing import Dict, Any, List

try:
    import psycopg2
except Exception:  # pragma: no cover - psycopg2 always present in the processor
    psycopg2 = None

# Used only when the DB can't be reached, so analysis never hard-fails.
_FALLBACK_CATEGORIES = [
    "regulatory", "compliance", "escalation", "change_notification", "general_inquiry",
]

_CACHE_TTL_SECONDS = 300
_cat_cache = {"at": 0.0, "value": None}


def _db_categories() -> List[str]:
    """Active category ids from public.circular_category, or [] on any failure."""
    if psycopg2 is None:
        return []
    try:
        conn = psycopg2.connect(
            host=os.getenv("CIRCULAR_DB_HOST", "localhost"),
            port=int(os.getenv("CIRCULAR_DB_PORT", "5432")),
            dbname=os.getenv("CIRCULAR_DB_NAME", "circular_analyser"),
            user=os.getenv("CIRCULAR_DB_USER", "postgres"),
            password=os.getenv("CIRCULAR_DB_PASSWORD", "postgres"),
            connect_timeout=5,
        )
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id FROM public.circular_category "
                    "WHERE is_active = TRUE ORDER BY id"
                )
                return [row[0] for row in cur.fetchall()]
        finally:
            conn.close()
    except Exception:
        return []


def allowed_categories() -> List[str]:
    """Allowed category ids — DB-driven (cached ~5 min) with a static fallback."""
    now = time.time()
    if _cat_cache["value"] and now - _cat_cache["at"] < _CACHE_TTL_SECONDS:
        return _cat_cache["value"]
    cats = _db_categories() or _FALLBACK_CATEGORIES
    _cat_cache["at"] = now
    _cat_cache["value"] = cats
    return cats


def build_system_prompt() -> str:
    """Build the system prompt with today's date for deadline assessment."""
    today = datetime.now().strftime("%Y-%m-%d")
    category_list = ", ".join(allowed_categories())
    return f"""You are an expert email analyst for a financial institution.
Today's date is {today}. Use this to assess deadline proximity.

Analyze the given email (including any attachment summaries) and return a JSON object with EXACTLY these fields:

{{
  "circular_id": "<the official circular/notice reference number printed in THIS circular, exactly as written, e.g. 'NSE/COMP/2026/123', 'SEBI/HO/MIRSD/2026/45', 'RBI/2025-26/78'. Use null if no such reference is present>",
  "referred_circulars": ["<official reference numbers of OTHER circulars/notifications/notices that THIS circular explicitly cites, supersedes, amends, or replaces. Empty list if none>"],
  "due_date": "<the explicit deadline by which the required compliance action must be COMPLETED, stated in this circular as YYYY-MM-DD, or null if no such deadline is stated>",
  "effective_date": "<the date this circular's provisions come into force / apply from, stated in this circular as YYYY-MM-DD, or null if no effective date is stated>",
  "categories": ["<one or more from: {category_list}>"],
  "confidence": <float 0.0–1.0>,
  "sentiment": "<one of: urgent, frustrated, neutral, positive, concerned>",
  "urgency": "<one of: critical, high, medium, low>",
  "summary": "<comprehensive DETAILED summary of the email and attachments. MUST be a highly detailed, multi-point bulleted list covering all key facts and context>",
  "required_action": "<specific recommended actions. MUST be a detailed, step-by-step bulleted list of exactly what the team needs to do taking the attachments into account>",
  "key_entities": ["<important entities: regulation names, deadlines, amounts, people, system names>"],
  "recommended_teams": ["<the internal team(s) this circular should be forwarded to, chosen ONLY from the Team list below using the Category→Team mapping. Use the EXACT team name(s) as written. Empty list if none clearly applies>"]
}}

Team list (use these exact names in recommended_teams):
- Legal
- Internal Audit
- Risk Management
- Information Technology
- Operations
- Treasury & ALM

Category → Team mapping (use to populate recommended_teams from the categories you assign):
- regulatory          -> Legal
- compliance          -> Internal Audit
- escalation          -> Risk Management
- change_notification -> Information Technology
- general_inquiry     -> Operations
- Add "Treasury & ALM" ONLY when the circular clearly concerns treasury, liquidity, ALM or funding (regardless of category).

Urgency guidelines:
- critical: deadline within 3 days, or immediate action required, or security/fraud incident
- high: deadline within 30 days, or regulatory/compliance changes requiring system updates
- medium: deadline within 90 days, or routine policy updates
- low: informational, no specific deadline

Rules:
- circular_id: extract the document's own official reference number precisely as printed (preserve slashes, year, and case). Never fabricate one — return null if the circular carries no explicit reference number.
- due_date: only an explicitly stated deadline for COMPLETING the action (YYYY-MM-DD). Never infer or fabricate one — return null when the circular states no completion deadline. This is distinct from effective_date.
- effective_date: only an explicitly stated date on which the circular's provisions take effect / apply from (YYYY-MM-DD). Never infer or fabricate one — return null when the circular states no effective date. A circular's effective_date may be before or after its due_date; do not copy one into the other.
- referred_circulars: list ONLY explicit references to OTHER circulars/notifications (the ones this circular cites, supersedes, amends or replaces). Use the official reference numbers as printed; return an empty list if none are referenced. Do not include this circular's own circular_id.
- An email CAN belong to multiple categories (e.g. both regulatory AND compliance)
- If ATTACHMENTS are present, base your categorization and analysis PRIMARILY on the attachment content — attachments contain the key documents (circulars, regulations, reports). The email body is often just a cover note.
- Be highly specific and exhaustive in the `summary` and `required_action` fields. Do not use generic one-liners. Break complex information down into bullet points using `- `.
- Extract dates, monetary amounts, regulation IDs, and system names as key_entities
- recommended_teams: derive STRICTLY from the Category→Team mapping above using the categories you assigned; use the exact team names. Multiple categories may yield multiple teams. Return an empty list only when no category clearly applies.
- Return ONLY valid JSON, no markdown fences, no extra text
- Read the email and attachments carefully to understand the context and sentiment; do not rely solely on keywords."""


def build_email_text(email_data: Dict[str, Any]) -> str:
    """Format an email dict into a text block for the LLM prompt.
    When attachments are present, they are prioritized over the email body."""
    attachments = email_data.get("attachments", [])
    has_attachments = bool(attachments)

    parts = [
        f"FROM: {email_data['sender']}",
        f"DATE: {email_data['date']}",
        f"SUBJECT: {email_data['subject']}",
        "",
        "BODY:",
        email_data["body"],
    ]

    if has_attachments:
        parts.append("")
        parts.append("=" * 60)
        parts.append("ATTACHMENTS (PRIMARY — use these for classification if present):")
        parts.append("=" * 60)
        for att in attachments:
            if isinstance(att, dict):
                parts.append(f"\n📎 FILE: {att.get('filename', 'unknown')}")
                parts.append(f"   TYPE: {att.get('type', 'unknown')}")
                parts.append(f"   SIZE: {att.get('size', 0)} bytes")
                parts.append(f"   CONTENT:")
                parts.append(att.get("content", "[no content]"))
            else:
                # Backward compatibility with old string format
                parts.append(f"  - {att}")

    return "\n".join(parts)
