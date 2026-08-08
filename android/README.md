# Gohil Bookkeeper

Turns a month of financial documents into rows in the two Excel registers. No LLM, no cloud,
no network.

Two ways to run it, sharing one tested engine:

| | What it is | Best for |
| --- | --- | --- |
| **Phone app** | Sideloaded APK, fully offline, OCR built in | Photographing bills as they arrive |
| **Local server** | A web page served from your own computer | A month's worth at once, on a real keyboard |

Both use the same `core` module — the Excel writing, parsing, matching and categorisation
rules are shared verbatim, so there is no second implementation to drift out of step.

---

## What it does

Feed it a month's paperwork:

- bank statement PDF (password protected)
- credit card bill PDF (password protected)
- purchase invoices — PDFs or phone photos
- sale/commission invoices

It extracts the fields, matches payments to invoices, categorises everything, and appends
rows to the Purchase and Sales registers, continuing each sheet's own SR NO sequence.

Anything it cannot read confidently goes to a **review list** with the raw extracted text
shown, and is written only if you tick it. These registers go to a chartered accountant, so a
gap you fill in beats a plausible-looking wrong number.

---

## It has no internet permission

Look in `app/src/main/AndroidManifest.xml`: there is no `android.permission.INTERNET`.

That is not a promise, it is an OS-enforced fact — nothing in this app can open a socket.
Bank statements, their passwords and invoice contents cannot leave the phone even if some
dependency tried. Text recognition works offline because the ML Kit model is bundled into the
APK rather than fetched.

Statement passwords are held in the **Android Keystore** (AES-256-GCM), the equivalent of the
desktop app's use of Windows Credential Manager. Each bank account and card is added once as
a named account with its own password, so several banks and several cards are all handled;
passwords are remembered for the following month. They are excluded from cloud backup and
device transfer, and wrapped in a type whose `toString()` prints `Secret(****)` so they cannot
end up in a log by accident.

---

## How your workbooks are protected

The hard rule is that existing rows and columns are never reordered, deleted or overwritten.

Rather than parsing the workbook and re-saving it — which silently drops whatever the parsing
library does not model — the `.xlsx` is treated as the zip of XML it actually is. Only the
target month's worksheet is rewritten; every other part is copied across byte-for-byte. Your
charts, frozen panes, column widths, print setup and other months' tabs survive untouched.

On top of that:

- New rows inherit the formatting of the row above them.
- Columns this app adds (`PAID DATE`, `PAYMENT METHOD`, `CATEGORY` / `GST TYPE`) are appended
  to the right, once, and reused thereafter.
- The updated workbook is reopened and verified **before** the original is touched.
- A timestamped backup is written first, if you have picked a backup folder.
- Any failure at any point leaves your original file exactly as it was.

---

## Installing

1. Open the repository's **Releases** page on the phone, or the **Actions** tab → latest
   `Android` run → `gohil-bookkeeper-apk` artifact.
2. Download the APK and tap it.
3. Android will ask you to allow installs from your browser or Files app — this is the normal
   sideload prompt. Allow it, then install.

Keep the workbooks in **local phone storage**, not Google Drive or OneDrive. Writing back
through a cloud storage provider is unreliable.

### Signing

CI signs with a release keystore if these repository secrets exist, and falls back to debug
signing otherwise:

| Secret | Meaning |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | keystore file, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

```bash
keytool -genkey -v -keystore release.keystore -alias bookkeeper \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore    # paste into ANDROID_KEYSTORE_B64
```

Worth doing early: Android refuses to upgrade an installed app whose signing key changed, so
switching later means uninstalling first — which clears the saved passwords.

---

## The local server (computer, and any device on your network)

Download `gohil-bookkeeper-*.zip` from the Releases page, unzip, and run:

```bash
bin/gohil-bookkeeper              # macOS / Linux
bin\gohil-bookkeeper.bat          # Windows
```

It prints a link — open it in any browser. Needs **Java 17 or newer** and nothing else.

```
--port 8080     listen on a different port
--network       also serve other devices on your network
--help
```

By default it listens on **localhost only**, so nothing outside that computer can reach it.
`--network` lets you use it from your phone or another laptop, and because that puts an
upload form for bank statements on a shared network, it prints a URL containing a
single-use access token. Share that link only with your own devices; restarting invalidates
it.

**Your files are never modified.** You upload copies, and download updated workbooks at the
end — the originals on disk cannot be touched by construction. Nothing is logged, and the
server makes no outbound connections at all.

On the GST screen, statement passwords go in one box, one per line, and every protected PDF
is tried against all of them. The Spend Analysis screen does it properly — see below.

---

## Spend Analysis (`/spend`)

A second screen on the same server: drop in statements, card bills and receipts, and get
categorised charts plus a personal loan book. It shares the GST screen's parsers, so a
statement cannot read one way here and another way there.

**Per-file passwords.** Every uploaded file is checked individually *before* anything is
parsed. A file that is not encrypted is never asked about. A file that is encrypted gets its
own prompt, naming that file. Once a password works it is remembered against the file's
SHA-256 content hash, so the same document is never asked about again — including after the
server is restarted, and including when the bank names every month's export `statement.pdf`.

**What "remembered" costs, plainly.** The passwords are written to
`~/.gohil-bookkeeper/passwords.enc`, encrypted with AES-256-GCM; the key is a separate file,
`passwords.key`. Both are owner-only (0600 on macOS/Linux; on Windows they rely on your user
profile's ACL, which is weaker). This protects against a backup agent or a search indexer
scooping up a plaintext file of bank passwords, and against someone who gets one file but not
the other. It does **not** protect against anyone who can already log in as you — they can
read both and decrypt. That is a real reduction in safety versus retyping each time, and it
is the direct price of "never ask me twice". Untick **Remember it** on any file to keep that
one out, and **Forget every remembered password** wipes both files.

The phone app does not use this: Android has a real keyring, and it keeps using it.

**Loans.** Money lent out or borrowed, with principal, rate, term, an EMI figure and the
planned amortisation. The balance shown is computed from the repayments you actually record —
interest accrues daily on the reducing balance and each repayment clears interest before
principal — not from the schedule. Loans stay out of the spending charts on purpose: lending
₹50,000 is not an expense, the money is still yours. It appears in **Net cash flow** instead,
alongside spending. The loan book is plain JSON at `~/.gohil-bookkeeper/loans.json`, readable
in Notepad long after this app is gone.

**Categories** are keyword rules, not a model. Every row shows which keyword decided it, so a
wrong guess is fixed by editing a list rather than retraining anything, and it needs no network.

The lists live in **`~/.gohil-bookkeeper/spend-categories.json`**, written out from the shipped
defaults the first time you run the app. It is plain text:

```json
{
  "categories": {
    "PROFESSIONAL": { "keywords": ["chartered accountant", "audit fee"], "priority": 2 }
  },
  "investments": { "MUTUAL_FUND": ["sip instalment", "groww"] }
}
```

Add the merchants this month left in **Uncategorised**, save, and press **Analyse spending**
again — no restart, no rebuild. Higher `priority` wins when two categories match the same line.
A misspelled category name costs that one rule, not the file; a file that will not parse falls
back to the built-in lists and says so on screen rather than failing the run. **Restore the
built-in lists** on the Spend page is the way back from an edit that went wrong.

**Investments are separated from expenses**, on the same argument as loans: a ₹10,000 SIP is
not money consumed, it is money that changed shape. SIPs, deposits, LIC/insurance premiums,
PPF/NPS and broking transfers are detected by `core/.../spend/Investments.kt` and get their own
table, their own column in cash flow, and their own sheet in the CA pack — never the spending
chart. Only debits are ever classified, which matters here: commission *received* from an AMC
or an insurer is income and belongs in the sales register, not in this list.

---

## The Excel pack for your CA

**Download Excel for CA** on `/spend` produces one workbook, eight sheets:

| Sheet | What is in it |
| --- | --- |
| Summary | The period, and each kind of money separately, with a note on each line |
| Expenses | Every outflow off the statements, with category, the keyword that chose it, and the source file. Rows needing a decision are sorted to the bottom and flagged |
| Expense by category | The same totals as the pie chart |
| Investments | SIPs, deposits, premiums, PPF/NPS — explicitly *not* expenses |
| Sales (GST) | Commission/service invoices with taxable, CGST, SGST, IGST, total, and RCM vs Direct |
| Purchases (GST) | Purchase invoices with the same GST breakdown |
| Loans | Given and received, with what is actually outstanding today |
| Cash flow | Money out and in per month, investing and lending included as outflows |

The two GST sheets fill in from whatever was processed on the **GST bookkeeping screen** in the
same browser session, so do that first if you want them. Every sheet is a flat table — no merged
cells, no formulas — so the CA can sort, filter and paste it into their own software.

The file is written by `core/.../xlsx/XlsxWriter.kt`, validated before it is handed over, and
checked in CI by `verify_fixtures.py` reopening it with openpyxl. **A blank GST cell stays
blank** rather than becoming a zero: a zero in a GST column is a wrong number in a filing.

### How to use it

1. On `/spend`, drop in the month's bank statements and card bills, unlock any that ask, and
   press **Analyse spending**.
2. If you want the GST sheets, open the **GST bookkeeping screen** in the same browser and
   process that month's purchase and sale invoices there first.
3. Record any personal loans given or received, and their repayments.
4. Press **Download Excel for CA**. The file is named for the period it covers —
   `CA-Pack-Jun-2026.xlsx` — so it explains itself in an inbox.
5. Open it and read the **Expenses** sheet from the bottom: the flagged rows are the ones the
   app would not guess at. Fix a wrong category by editing the keyword list, not the sheet, so
   next month is right too.
6. Email it to the CA. It is a *report*, not a filing, and not a substitute for the registers —
   those are still updated by the GST screen's download.

**OCR is optional here.** Text-based PDFs — nearly all bank statements and vendor invoices —
work with no extra setup. Scans and photos need [Tesseract](https://github.com/tesseract-ocr/tesseract)
installed and on your PATH; without it those files go to the review list saying so, and the
rest of the run is unaffected. The phone app has recognition built in, so photographed bills
are easiest there.

---

## Project layout

```
android/
  core/    pure Kotlin/JVM — xlsx writing, parsing, matching, categorisation, routing
  app/     Android — Compose UI, file pickers, PDF/OCR, keystore vault
  web/     local server — Javalin + browser UI (GST + spend analysis), reuses core
  tools/   verify_fixtures.py, independent workbook validation
```

`core` deliberately has no Android dependencies. Everything that decides what lands in the
accountant's workbook is testable on a plain JDK, and is covered by 58 tests.

`settings.gradle.kts` only includes `:app` when an Android SDK is present, so `:core` builds
and tests anywhere:

```bash
cd android
./gradlew :core:test :web:test                    # no Android SDK needed
python3 tools/verify_fixtures.py core/build/fixtures
./gradlew :web:run                                # start the server locally
```

The second command matters. The Kotlin tests read their output back with the same code that
wrote it, which cannot catch a structural mistake both halves agree on; `verify_fixtures.py`
reopens the generated workbooks with **openpyxl**, a completely independent OOXML
implementation. CI runs both on every push.

To build the app itself you need the Android SDK (Android Studio, or `ANDROID_HOME` set):

```bash
./gradlew :app:assembleRelease
```

---

## Accounts and passwords

Add each bank account and credit card once — a name you recognise ("HDFC Savings", "Axis
Flipkart Card") and its statement password. Then each month, tap **Add statement** on that
account and pick the PDF. The right password is used automatically.

If a statement is filed under the wrong account, it still opens: the app tries that account's
password first, then the other saved ones. They are all your own passwords on your own
device, and an encrypted PDF has no lockout, so the extra attempts cost nothing. Only if none
of them work does the file go to review saying so.

Deleting an account also deletes its password from the Keystore.

## Categorisation rules

`app/src/main/assets/categories.json` holds the keyword lists — personal vs business, the
confirmed RCM insurers, payment method hints. It is generated from `CategoryRules.defaults()`
and uses the same shape as the desktop app's `config/categories.json`, so the two are
interchangeable. Editing it needs no rebuild.

**`rcm_insurers` deserves care.** Only insurers confirmed to pay GST directly under the
Reverse Charge Mechanism belong there — currently just Star Health. An unknown insurer is
treated as Direct rather than guessed at, because marking a Direct insurer as RCM misstates
a GST return.

---

## Known limits

- **The parsers are uncalibrated.** Bank PDF layouts and vendor invoice formats vary
  enormously, and these are general patterns. The first live month will put a fair number of
  items in the review list — that is the design working, not failing. Tune the regexes in
  `core/src/main/kotlin/.../parse/` once you have seen real documents.
- **AES-256 encrypted PDFs** may not unlock. PDFBox handles the RC4/AES-128 encryption Indian
  banks use in practice.
- **Processing runs while the app is open.** Long OCR batches should not be backgrounded
  mid-run; there is no foreground service yet.
- **The web server is the verified path.** Its tests drive the real HTTP server with a real
  password-protected PDF and check the workbook that comes back out, so that route is
  exercised end to end. The phone app is not — see below.
- **The `app` module compiles but has never been run.** It was written in an environment
  with no Android SDK; CI compiles it and produces an APK, so it is known to build, but no
  screen has ever been displayed and no real PDF has ever been through it. `core` is the part
  backed by executed tests. Treat the first install as the first real test of the UI, the
  file pickers, the PDF unlocking and the OCR path.
