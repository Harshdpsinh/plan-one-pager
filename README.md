# 🏥 Health Insurance Policy Fact Sheet Generator

Upload any health insurance policy PDF → get a structured one-page summary PDF powered by Google Gemini.

---

## What It Does

Extracts and formats 5 key sections from any policy document:

1. **Policy Basics & Eligibility** — entry ages, tenure, co-payments, zone pricing
2. **Sum Insured & Financials** — SI options, NCB rules, discounts, wellness rewards
3. **Core Hospitalization Benefits** — room rent, ICU, pre/post-hosp, ambulance, AYUSH
4. **Waiting Periods & Key Exclusions** — PED, specific disease, moratorium, top exclusions
5. **Unique Features & Optional Covers** — restoration, maternity, riders/add-ons

---

## Tech Stack (All Free)

| Component | Tool | Cost |
|---|---|---|
| UI | Streamlit | Free |
| AI Brain | Google Gemini 1.5 Flash | Free tier (generous) |
| PDF Generator | ReportLab | Free / Open Source |
| Hosting | Streamlit Community Cloud | Free |

---

## Local Setup (5 Minutes)

```bash
# 1. Clone / download this folder
cd insurance_analyzer

# 2. Create virtual environment (optional but recommended)
python -m venv venv
source venv/bin/activate        # macOS/Linux
venv\Scripts\activate.bat       # Windows

# 3. Install dependencies
pip install -r requirements.txt

# 4. Run the app
streamlit run app.py
```

Then open http://localhost:8501 in your browser.

---

## Get a Free Gemini API Key

1. Go to https://aistudio.google.com/app/apikey
2. Sign in with your Google account
3. Click **Create API Key**
4. Copy it — paste it into the app when prompted

The free tier allows ~1,500 requests/day with Gemini 1.5 Flash. More than enough for personal use.

---

## Deploy Free to the Internet (Streamlit Community Cloud)

1. Push this folder to a **public GitHub repo**
2. Go to https://share.streamlit.io
3. Sign in with GitHub → click **New App**
4. Select your repo, set `app.py` as the main file
5. Click **Deploy** — your app is live in ~2 minutes

**No API key hardcoding needed** — users enter their own key in the UI, so it's safe to deploy publicly.

---

## File Structure

```
insurance_analyzer/
├── app.py              # Main Streamlit application
├── requirements.txt    # Python dependencies
└── README.md           # This file
```

---

## Notes

- Works best with text-based PDFs (policy wordings). Scanned/image PDFs may yield incomplete results.
- The Gemini free tier has a file size limit of ~20MB. Most policy PDFs are well under this.
- Output is AI-generated. Always verify against the original policy document before purchase decisions.
