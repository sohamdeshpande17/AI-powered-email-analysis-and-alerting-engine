"""
Tests for the NSE CSV parser. Run with plain Python (no pytest needed):

    python -m adapters.nse.test_nse_adapter
"""
from __future__ import annotations

from adapters.nse.nse_adapter import parse_circulars_csv

# Trimmed real sample: includes the duplicate refs 74717 and 74688.
_SAMPLE = (
    '"DATE","DEPARTMENT","DOWNLOAD REFERENCE NO.","SUBJECT","LINK","FILE SIZE"\n'
    '"June 15, 2026","Inspection & Compliance","NSE/INSP/74727","Reminder for Audit Report","https://nsearchives.nseindia.com/content/circulars/INSP74727.pdf","137 KB"\n'
    '"June 15, 2026","Capital Market (Equities) Trade","NSE/CMTR/74724","Proposed OFS","https://nsearchives.nseindia.com/content/circulars/CMTR74724.zip","6.61 MB"\n'
    '"June 15, 2026","Debt Segment","NSE/CML/74717","Listing of privately placed securities","https://nsearchives.nseindia.com/content/circulars/CML74717.pdf","353 KB"\n'
    '"June 15, 2026","Listing","NSE/CML/74717","Listing of privately placed securities","https://nsearchives.nseindia.com/content/circulars/CML74717.pdf","353 KB"\n'
    '"June 15, 2026","Debt Segment","NSE/CML/74688","Suspension of trading","https://nsearchives.nseindia.com/content/circulars/CML74688.pdf","271 KB"\n'
    '"June 15, 2026","Listing","NSE/CML/74688","Suspension of trading","https://nsearchives.nseindia.com/content/circulars/CML74688.pdf","271 KB"\n'
)


def test_dedup_collapses_duplicate_refs():
    rows = parse_circulars_csv(_SAMPLE)
    # 6 data rows -> 4 unique (74717 and 74688 each appear twice)
    assert len(rows) == 4, [r["circNumber"] for r in rows]


def test_field_mapping_and_filename():
    rows = parse_circulars_csv(_SAMPLE)
    first = rows[0]
    assert first["circNumber"] == "NSE/INSP/74727"
    assert first["sub"] == "Reminder for Audit Report"
    assert first["fileDept"] == "Inspection & Compliance"
    assert first["circFilelink"].endswith("INSP74727.pdf")
    assert first["circFilename"] == "INSP74727.pdf"


def test_date_normalised_to_mapper_format():
    rows = parse_circulars_csv(_SAMPLE)
    assert rows[0]["cirDate"] == "15-Jun-2026"


def test_first_department_wins_on_dedup():
    rows = parse_circulars_csv(_SAMPLE)
    cml717 = next(r for r in rows if r["circNumber"] == "NSE/CML/74717")
    assert cml717["fileDept"] == "Debt Segment"


def test_rows_without_subject_or_link_skipped():
    csv_text = (
        '"DATE","DEPARTMENT","DOWNLOAD REFERENCE NO.","SUBJECT","LINK","FILE SIZE"\n'
        '"June 15, 2026","Listing","NSE/CML/1","","https://x/a.pdf","1 KB"\n'
        '"June 15, 2026","Listing","NSE/CML/2","Has subject","","1 KB"\n'
    )
    assert parse_circulars_csv(csv_text) == []


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print(f"  ok  {name}")
    print("All tests passed.")
