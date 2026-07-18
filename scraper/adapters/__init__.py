"""
Adapter registry (v3) — source_id → SourceAdapter class.

The DB `source` table is the registry of record (config_json drives the poll
schedule); this map only binds a source_id to its adapter implementation.
Adding a new scraper = one adapter class + one entry here + one source row.
"""
from __future__ import annotations


def get_adapter(source_id: str):
    """Instantiate the adapter for a source_id. Raises KeyError if unknown."""
    from adapters.nse.nse_adapter import NSEAdapter
    from adapters.email.email_adapter import EmailAdapter

    registry = {
        "NSE": NSEAdapter,
        "EMAIL": EmailAdapter,
    }
    return registry[source_id.upper()]()
