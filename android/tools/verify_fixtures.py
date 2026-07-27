#!/usr/bin/env python3
"""Validate the workbooks produced by XlsxAppender using an independent parser.

The Kotlin unit tests read their own output back with the same code that wrote it, so a
structural mistake both halves agree on would slip through. openpyxl is a completely
separate OOXML implementation (and the same library the original desktop app used), so if
it can open these files and finds the expected values, the output is genuinely well-formed.

Run after `gradle :core:test`:

    python3 tools/verify_fixtures.py core/build/fixtures
"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

try:
    import openpyxl
except ImportError:
    sys.exit("openpyxl is required: pip install openpyxl")

failures: list[str] = []
checks = 0


def check(label: str, actual, expected) -> None:
    global checks
    checks += 1
    if actual != expected:
        failures.append(f"{label}: expected {expected!r}, got {actual!r}")


def load(path: Path, data_only: bool = False):
    """Opening at all is itself the most important assertion."""
    try:
        return openpyxl.load_workbook(path, data_only=data_only)
    except Exception as exc:  # noqa: BLE001
        failures.append(f"{path.name}: openpyxl could not open the file: {exc}")
        return None


def verify_purchase(fixtures: Path) -> None:
    before = load(fixtures / "purchase_before.xlsx")
    wb = load(fixtures / "purchase_after.xlsx")
    if not wb or not before:
        return

    check("purchase tabs", wb.sheetnames, ["May", "Jun"])

    # Untouched tab is untouched.
    check("May row count unchanged", wb["May"].max_row, before["May"].max_row)

    ws = wb["Jun"]
    check("Jun row count", ws.max_row, 7)  # header + 3 existing + 3 appended

    headers = [c.value for c in ws[1]]
    check(
        "headers appended in order, originals intact",
        headers,
        [
            "SR NO", "DATE", "GST NO", "INVOICE NO", "NAME", "QTY", "RATE %",
            "HSN", "TAXABLE", "CGST", "SGST", "IGST", "TOTAL GRAND",
            "PAID DATE", "PAYMENT METHOD", "CATEGORY",
        ],
    )

    # Existing rows survived verbatim.
    check("existing SR NO 1 intact", ws["A2"].value, 1)
    check("existing invoice no intact", ws["D2"].value, "INV-1000")

    # SR NO continues rather than restarting.
    check("appended SR NO", [ws[f"A{r}"].value for r in (4, 5, 6)], [3, 4, 5])

    # Dates round-trip as real dates, not text.
    check("appended date is a date", isinstance(ws["B5"].value, datetime), True)
    check("appended date value", ws["B5"].value, datetime(2025, 6, 12))
    check("paid date value", ws["N5"].value, datetime(2025, 6, 20))

    check("vendor name with XML-hostile characters", ws["E5"].value, "Acme Traders & Sons <Pvt> Ltd")
    check("payment method", ws["O5"].value, "UPI")
    check("category business", ws["P5"].value, "Business Expense")
    check("category personal", ws["P7"].value, "Personal Expense")

    # Bug #2: SUM only where there is a taxable value.
    check("invoice row uses SUM", ws["M5"].value, "=SUM(I5:L5)")
    check("bank row has no formula", ws["M6"].value, 450.0)
    check("bank row taxable is blank", ws["I6"].value, None)

    # Cached formula results must be right for anything reading values without recalculating.
    cached = load(fixtures / "purchase_after.xlsx", data_only=True)
    if cached:
        cws = cached["Jun"]
        check("cached SUM result", cws["M5"].value, 2360.0)
        check("bank amount straight into TOTAL GRAND", cws["M6"].value, 450.0)
        check("personal expense amount", cws["M7"].value, 612.5)

    # Formatting inherited from the row above.
    check("date column keeps its number format", ws["B5"].number_format, ws["B4"].number_format)
    check("taxable keeps its number format", ws["I5"].number_format, ws["I4"].number_format)

    # Sheet features the appender knows nothing about.
    check("frozen pane preserved", ws.freeze_panes, "A2")
    check("column width preserved", ws.column_dimensions["E"].width, 32)


def verify_new_tab(fixtures: Path) -> None:
    wb = load(fixtures / "purchase_newtab.xlsx")
    if not wb:
        return
    check("new tab registered", wb.sheetnames, ["May", "Jun", "Jul"])

    ws = wb["Jul"]
    check("cloned headers", [c.value for c in ws[1]][:13], [
        "SR NO", "DATE", "GST NO", "INVOICE NO", "NAME", "QTY", "RATE %",
        "HSN", "TAXABLE", "CGST", "SGST", "IGST", "TOTAL GRAND",
    ])
    check("cloned tab has no inherited data", ws.max_row, 2)
    check("fresh SR NO sequence", ws["A2"].value, 1)
    check("new tab row written", ws["E2"].value, "New Month Vendor")
    check("cloned column width", ws.column_dimensions["E"].width, 32)

    # The source tab must be completely unaffected by the clone.
    check("source tab intact", wb["Jun"].max_row, 4)


def verify_sales(fixtures: Path) -> None:
    wb = load(fixtures / "sales_after.xlsx")
    if not wb:
        return
    check("sales tab uses the full month name", wb.sheetnames, ["June"])

    ws = wb["June"]
    headers = [c.value for c in ws[1]]
    check("sales gets GST TYPE, not CATEGORY", headers[13:], ["PAID DATE", "PAYMENT METHOD", "GST TYPE"])
    check("no CATEGORY column in sales", "CATEGORY" in headers, False)

    check("RCM insurer flagged", ws["E4"].value, "Star Health and Allied Insurance")
    check("RCM gst type", ws["P4"].value, "RCM")
    check("direct gst type", ws["P5"].value, "Direct".upper())


def main() -> int:
    fixtures = Path(sys.argv[1] if len(sys.argv) > 1 else "core/build/fixtures")
    if not fixtures.is_dir():
        sys.exit(f"fixtures directory not found: {fixtures}\nRun `gradle :core:test` first.")

    verify_purchase(fixtures)
    verify_new_tab(fixtures)
    verify_sales(fixtures)

    if failures:
        print(f"FAILED — {len(failures)} of {checks} checks did not pass:\n")
        for f in failures:
            print(f"  ✗ {f}")
        return 1

    print(f"OK — all {checks} checks passed against openpyxl.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
