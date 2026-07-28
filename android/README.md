# Gohil Bookkeeper — Android

Turns a month of financial documents into rows in the two Excel registers, entirely on the
phone. No LLM, no server, no network.

Sideloaded APK — not on the Play Store.

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

## Project layout

```
android/
  core/    pure Kotlin/JVM — xlsx writing, parsing, matching, categorisation, routing
  app/     Android — Compose UI, file pickers, PDF/OCR, keystore vault
  tools/   verify_fixtures.py, independent workbook validation
```

`core` deliberately has no Android dependencies. Everything that decides what lands in the
accountant's workbook is testable on a plain JDK, and is covered by 58 tests.

`settings.gradle.kts` only includes `:app` when an Android SDK is present, so `:core` builds
and tests anywhere:

```bash
cd android
./gradlew :core:test                              # no Android SDK needed
python3 tools/verify_fixtures.py core/build/fixtures
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
- **The `app` module compiles but has never been run.** It was written in an environment
  with no Android SDK; CI compiles it and produces an APK, so it is known to build, but no
  screen has ever been displayed and no real PDF has ever been through it. `core` is the part
  backed by executed tests. Treat the first install as the first real test of the UI, the
  file pickers, the PDF unlocking and the OCR path.
