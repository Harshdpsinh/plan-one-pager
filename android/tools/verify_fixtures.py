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


def verify_ca_pack(fixtures: Path) -> None:
    """The workbook that goes to the accountant, read by something other than what wrote it.

    This one is written from scratch rather than spliced, so the failure mode is different:
    not "a preserved part was damaged" but "the package we assembled is not a valid workbook".
    openpyxl opening it, finding every sheet, and reading dates back as dates is the check.
    """
    wb = load(fixtures / "ca_pack.xlsx")
    if not wb:
        return

    check(
        "ca pack sheets",
        wb.sheetnames,
        [
            "Summary", "Expenses", "Expense by category", "Investments",
            "Sales (GST)", "Purchases (GST)", "Loans", "Cash flow",
        ],
    )

    ws = wb["Expenses"]
    # Two notes, a blank line, then the header — the layout the sheet builder promises.
    header_row = 4
    check(
        "ca pack: expense headers",
        [c.value for c in ws[header_row]],
        ["Date", "Merchant / narration", "Amount", "Category",
         "Matched on", "Needs check", "Source file"],
    )
    check("ca pack: expense rows + total", ws.max_row, header_row + 4)
    # Settled rows come first, newest first; the flagged one is pushed to the end.
    check("ca pack: dates are real dates", ws.cell(header_row + 1, 1).value, datetime(2026, 6, 7))
    check("ca pack: amount is a number", ws.cell(header_row + 2, 3).value, 1240.0)
    check("ca pack: flagged row is last", ws.cell(ws.max_row - 1, 6).value, "Yes - no rule matched")
    check("ca pack: expense total", ws.cell(ws.max_row, 3).value, 5550.5)

    # The SIP must be in Investments and nowhere near the expense total.
    merchants = [ws.cell(r, 2).value for r in range(header_row + 1, ws.max_row)]
    check("ca pack: no investment in expenses", any("PARAG" in (m or "") for m in merchants), False)

    inv = wb["Investments"]
    inv_header = 5  # three notes, blank, header
    check("ca pack: investment headers start correctly", inv.cell(inv_header, 1).value, "Date")
    check("ca pack: investment amount", inv.cell(inv_header + 1, 3).value, 10000.0)

    sales = wb["Sales (GST)"]
    sales_header = 5
    check("ca pack: sales headers", [c.value for c in sales[sales_header]][:9],
          ["Date", "GST NO", "Invoice no", "Name", "Taxable", "CGST", "SGST", "IGST", "Total"])
    check("ca pack: taxable value", sales.cell(sales_header + 1, 5).value, 40000.0)
    check("ca pack: cgst", sales.cell(sales_header + 1, 6).value, 3600.0)
    # IGST was never read. It must be empty, not zero — a zero here is a wrong GST figure.
    check("ca pack: unread IGST stays blank", sales.cell(sales_header + 1, 8).value, None)
    check("ca pack: gst type", sales.cell(sales_header + 1, 12).value, "RCM")

    flow = wb["Cash flow"]
    flow_header = 4
    check("ca pack: cash flow has an Invested column",
          [c.value for c in flow[flow_header]][:3], ["Month", "Expenses", "Invested"])

    check("ca pack: money format applied", ws.cell(header_row + 1, 3).number_format, "#,##0.00")


def verify_web_output(root: Path) -> None:
    """The workbook the local web server hands back must be as sound as the phone app's."""
    path = root / "web/build/web-fixtures/web_purchase_after.xlsx"
    if not path.exists():
        print(f"note: {path} not found — skipping web checks (run `gradle :web:test` first)")
        return

    wb = load(path)
    if not wb:
        return
    check("web: tabs preserved", wb.sheetnames, ["May", "Jun"])

    ws = wb["Jun"]
    check("web: row appended", ws.max_row, 4)  # header + 2 existing + 1 new
    check("web: vendor from the invoice letterhead", ws["E4"].value, "SHARMA OFFICE SUPPLIES PVT LTD")
    check("web: invoice number", ws["D4"].value, "INV-2025-0412")
    check("web: taxable value", ws["I4"].value, 4500.0)
    check("web: CGST not swallowed by the rate", ws["J4"].value, 405.0)
    check("web: SGST not swallowed by the rate", ws["K4"].value, 405.0)
    check("web: date parsed day-first", ws["B4"].value, datetime(2025, 6, 12))
    check("web: added columns", [c.value for c in ws[1]][13:], ["PAID DATE", "PAYMENT METHOD", "CATEGORY"])

    cached = load(path, data_only=True)
    if cached:
        check("web: TOTAL GRAND", cached["Jun"]["M4"].value, 5310.0)

    # The CA pack as the server actually hands it over, not as the unit test builds it.
    pack_path = root / "web/build/web-fixtures/web_ca_pack.xlsx"
    if pack_path.exists():
        pack = load(pack_path)
        if pack:
            check("web ca pack: sheets", len(pack.sheetnames), 8)
            expenses = pack["Expenses"]
            # Four debits on the fixture statement; the SIP is not one of them.
            check("web ca pack: expense rows + total", expenses.max_row, 9)
            check("web ca pack: total is the four expenses", expenses.cell(9, 3).value, 14749.5)
            check("web ca pack: SIP is in Investments", pack["Investments"].cell(6, 3).value, 10000.0)


def main() -> int:
    fixtures = Path(sys.argv[1] if len(sys.argv) > 1 else "core/build/fixtures")
    if not fixtures.is_dir():
        sys.exit(f"fixtures directory not found: {fixtures}\nRun `gradle :core:test` first.")

    verify_purchase(fixtures)
    verify_new_tab(fixtures)
    verify_sales(fixtures)
    verify_ca_pack(fixtures)
    # fixtures is <root>/core/build/fixtures; the web output lives under the same root.
    verify_web_output(fixtures.parent.parent.parent)

    if failures:
        print(f"FAILED — {len(failures)} of {checks} checks did not pass:\n")
        for f in failures:
            print(f"  ✗ {f}")
        return 1

    print(f"OK — all {checks} checks passed against openpyxl.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
