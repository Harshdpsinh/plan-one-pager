import streamlit as st
from google import genai
from google.genai import types as genai_types
import os
import re
import time
import tempfile
from io import BytesIO

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.units import mm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer,
    Table, TableStyle, HRFlowable, KeepTogether
)
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.pdfbase import pdfmetrics

# ─── PAGE CONFIG ──────────────────────────────────────────────────────────────
st.set_page_config(
    page_title="Policy Fact Sheet Generator",
    page_icon="🏥",
    layout="centered",
)

# ─── CUSTOM CSS ───────────────────────────────────────────────────────────────
st.markdown("""
<style>
    @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@300;400;500;600;700&family=DM+Mono:wght@400;500&display=swap');

    html, body, [class*="css"] {
        font-family: 'DM Sans', sans-serif;
    }

    .stApp {
        background: #f0f4f8;
    }

    .main-header {
        background: linear-gradient(135deg, #0f2027 0%, #203a43 50%, #2c5364 100%);
        border-radius: 16px;
        padding: 36px 40px 32px;
        margin-bottom: 28px;
        color: white;
        position: relative;
        overflow: hidden;
    }
    .main-header::before {
        content: '';
        position: absolute;
        top: -50px; right: -50px;
        width: 200px; height: 200px;
        background: rgba(255,255,255,0.04);
        border-radius: 50%;
    }
    .main-header h1 {
        font-size: 1.8rem;
        font-weight: 700;
        margin: 0 0 6px 0;
        letter-spacing: -0.5px;
    }
    .main-header p {
        font-size: 0.92rem;
        color: rgba(255,255,255,0.7);
        margin: 0;
        font-weight: 300;
    }
    .badge {
        display: inline-block;
        background: rgba(99,202,141,0.2);
        color: #63ca8d;
        border: 1px solid rgba(99,202,141,0.4);
        border-radius: 20px;
        padding: 2px 10px;
        font-size: 0.72rem;
        font-weight: 600;
        letter-spacing: 0.5px;
        text-transform: uppercase;
        margin-bottom: 12px;
    }

    .card {
        background: white;
        border-radius: 12px;
        padding: 24px;
        margin-bottom: 16px;
        border: 1px solid #e2e8f0;
        box-shadow: 0 1px 4px rgba(0,0,0,0.05);
    }
    .card h4 {
        margin: 0 0 4px 0;
        font-size: 0.9rem;
        font-weight: 600;
        color: #1a202c;
    }
    .card p {
        margin: 0;
        font-size: 0.82rem;
        color: #718096;
    }

    .stTextInput > div > div > input {
        border-radius: 8px;
        border: 1.5px solid #e2e8f0;
        font-family: 'DM Mono', monospace;
        font-size: 0.85rem;
    }
    .stTextInput > div > div > input:focus {
        border-color: #2c5364;
        box-shadow: 0 0 0 3px rgba(44,83,100,0.1);
    }

    .stFileUploader {
        border-radius: 10px;
    }

    div[data-testid="stDownloadButton"] button {
        background: linear-gradient(135deg, #203a43, #2c5364);
        color: white;
        border: none;
        border-radius: 8px;
        font-weight: 600;
        font-size: 0.9rem;
        padding: 10px 24px;
        width: 100%;
        transition: all 0.2s;
    }
    div[data-testid="stDownloadButton"] button:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 12px rgba(44,83,100,0.35);
    }

    .stButton > button {
        background: linear-gradient(135deg, #0f2027, #2c5364);
        color: white;
        border: none;
        border-radius: 8px;
        font-weight: 600;
        font-size: 0.95rem;
        padding: 12px 32px;
        width: 100%;
        transition: all 0.2s;
    }
    .stButton > button:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 16px rgba(44,83,100,0.4);
    }

    .status-box {
        background: #ebf8f0;
        border: 1px solid #9ae6b4;
        border-radius: 8px;
        padding: 14px 18px;
        font-size: 0.88rem;
        color: #276749;
        margin: 12px 0;
    }
    .error-box {
        background: #fff5f5;
        border: 1px solid #fc8181;
        border-radius: 8px;
        padding: 14px 18px;
        font-size: 0.88rem;
        color: #c53030;
        margin: 12px 0;
    }
    .preview-box {
        background: #f7fafc;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        padding: 18px 22px;
        font-size: 0.82rem;
        font-family: 'DM Mono', monospace;
        max-height: 380px;
        overflow-y: auto;
        color: #2d3748;
        line-height: 1.6;
    }
    .step-label {
        font-size: 0.78rem;
        font-weight: 600;
        color: #718096;
        text-transform: uppercase;
        letter-spacing: 0.8px;
        margin-bottom: 6px;
    }
</style>
""", unsafe_allow_html=True)

# ─── ANALYSIS PROMPT ──────────────────────────────────────────────────────────
ANALYSIS_PROMPT = """
Role: You are an expert health insurance actuary and policy analyst.

Task: I will provide you with the full legal text of a health insurance policy. You must extract the key terms and generate a highly structured, dense, one-page summary Fact Sheet.

Constraints & Formatting:
* Do not use conversational filler. Output only the final Fact Sheet.
* Format the output using Markdown tables and bullet points so it is scannable and easy to convert into a PDF.
* Keep explanations extremely brief. Prioritize numbers, percentages, and timeframes over full sentences.
* If a specific value is not found in the document, write "Not Specified".

Required Sections (use EXACTLY these headers in this order):

## 1. POLICY BASICS & ELIGIBILITY
| Field | Detail |
Create a table. Extract: Policy Name, Insurer Name, Cover Type (Individual/Floater/Both), Entry Age (Adult), Entry Age (Child), Max Family Size (if floater), Policy Tenure Options, Co-payment clauses, Zone-based pricing (if any), and Renewability.

## 2. SUM INSURED & FINANCIALS
| Field | Detail |
Create a table. Extract: Available Sum Insured Options (list all), Premium Payment Mode, No-Claim Bonus % per year, NCB Cap (max %), NCB reset conditions, available Discounts (list type and %), Wellness Rewards.

## 3. CORE HOSPITALIZATION BENEFITS
| Benefit | Limit/Detail |
Create a table with two columns. Extract limits for: Room Rent, ICU Charges, Pre-Hospitalization (days), Post-Hospitalization (days), Road Ambulance, Day Care Procedures, Domiciliary Treatment, AYUSH Treatment, Organ Donor Expenses, Mental Illness Cover, Modern/Advanced Treatments. For each, state the exact limit or % of SI.

## 4. WAITING PERIODS & KEY EXCLUSIONS

**Waiting Periods:**
| Type | Duration |
Table with: Initial Waiting Period, Specific Disease Waiting Period (list diseases), Pre-Existing Disease (PED) Waiting Period, Maternity Waiting Period (if any), Moratorium Period.

**Key Exclusions (summarized bullets):**
* List the 8–10 most important exclusions as brief bullets.

## 5. UNIQUE FEATURES & OPTIONAL COVERS

**Special Features:**
* Bullet-point any restoration/reload/refill benefits (state trigger and % restored).
* Bullet-point maternity & newborn cover details if present.
* Bullet-point annual health check-up entitlement.
* Any other standout features (e.g., international cover, loyalty bonus, premium waiver).

**Optional Riders / Add-ons Available:**
| Rider Name | Key Benefit |
Table listing purchasable add-ons and their primary benefit.
"""

# ─── PDF GENERATOR ────────────────────────────────────────────────────────────
def build_pdf_styles():
    """Return a dict of ReportLab ParagraphStyles."""
    base = getSampleStyleSheet()

    def ps(name, **kw):
        return ParagraphStyle(name, parent=base['Normal'], **kw)

    DARK   = colors.HexColor('#0f2027')
    MID    = colors.HexColor('#203a43')
    ACCENT = colors.HexColor('#2c5364')
    LIGHT_BG = colors.HexColor('#eaf2f8')
    MUTED  = colors.HexColor('#718096')
    WHITE  = colors.white

    return {
        'title':       ps('title',    fontName='Helvetica-Bold', fontSize=16,
                          textColor=WHITE,   spaceAfter=2,  leading=20),
        'subtitle':    ps('subtitle', fontName='Helvetica',      fontSize=9,
                          textColor=colors.HexColor('#a0aec0'),
                          spaceAfter=0, leading=12),
        'h1':          ps('h1',       fontName='Helvetica-Bold', fontSize=11,
                          textColor=WHITE,   spaceBefore=4, spaceAfter=3,
                          leading=14, leftIndent=0),
        'body':        ps('body',     fontName='Helvetica',      fontSize=8.5,
                          textColor=DARK,    spaceAfter=2,  leading=12),
        'bullet':      ps('bullet',   fontName='Helvetica',      fontSize=8.5,
                          textColor=DARK,    spaceAfter=1,  leading=12,
                          leftIndent=10, firstLineIndent=0),
        'cell':        ps('cell',     fontName='Helvetica',      fontSize=8,
                          textColor=DARK,    leading=11),
        'cell_hdr':    ps('cell_hdr', fontName='Helvetica-Bold', fontSize=8,
                          textColor=WHITE,   leading=11),
        'cell_bold':   ps('cell_bold',fontName='Helvetica-Bold', fontSize=8,
                          textColor=DARK,    leading=11),
        'section_hdr': ps('section_hdr', fontName='Helvetica-Bold', fontSize=9.5,
                          textColor=DARK, spaceBefore=6, spaceAfter=2, leading=13),
        '_dark':   DARK,
        '_mid':    MID,
        '_accent': ACCENT,
        '_light':  LIGHT_BG,
        '_white':  WHITE,
        '_muted':  MUTED,
    }


def apply_inline(text):
    """Convert **bold** and *italic* markdown to ReportLab XML tags."""
    text = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', text)
    text = re.sub(r'\*(.+?)\*',     r'<i>\1</i>', text)
    # Escape bare & that aren't already entities
    text = re.sub(r'&(?!amp;|lt;|gt;|quot;|apos;)', '&amp;', text)
    return text


def parse_table(lines, styles):
    """Parse markdown table lines into a ReportLab Table."""
    rows_data = []
    for line in lines:
        line = line.strip()
        if not line.startswith('|'):
            continue
        # Skip separator rows like |---|---|
        if re.match(r'^\|[-:\s|]+\|$', line):
            continue
        cells = [c.strip() for c in line.split('|')[1:-1]]
        rows_data.append(cells)

    if not rows_data:
        return None

    col_count = max(len(r) for r in rows_data)
    # Pad short rows
    for r in rows_data:
        while len(r) < col_count:
            r.append('')

    DARK   = styles['_dark']
    MID    = styles['_mid']
    LIGHT  = styles['_light']
    WHITE  = styles['_white']

    para_rows = []
    for ri, row in enumerate(rows_data):
        para_row = []
        for cell in row:
            txt = apply_inline(cell)
            style = styles['cell_hdr'] if ri == 0 else styles['cell']
            para_row.append(Paragraph(txt, style))
        para_rows.append(para_row)

    avail = A4[0] - 30*mm
    if col_count == 2:
        col_widths = [avail * 0.38, avail * 0.62]
    else:
        col_widths = [avail / col_count] * col_count

    tbl = Table(para_rows, colWidths=col_widths, repeatRows=1)
    tbl.setStyle(TableStyle([
        ('BACKGROUND',    (0, 0), (-1,  0), MID),
        ('TEXTCOLOR',     (0, 0), (-1,  0), WHITE),
        ('BACKGROUND',    (0, 1), (-1, -1), WHITE),
        ('ROWBACKGROUNDS',(0, 1), (-1, -1), [WHITE, LIGHT]),
        ('GRID',          (0, 0), (-1, -1), 0.4, colors.HexColor('#cbd5e0')),
        ('VALIGN',        (0, 0), (-1, -1), 'TOP'),
        ('TOPPADDING',    (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
        ('LEFTPADDING',   (0, 0), (-1, -1), 6),
        ('RIGHTPADDING',  (0, 0), (-1, -1), 6),
    ]))
    return tbl


def markdown_to_pdf(markdown_text: str, policy_name: str = "Health Insurance Policy") -> BytesIO:
    """Convert Gemini's markdown output into a formatted PDF."""
    buffer = BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        topMargin=0,
        bottomMargin=12*mm,
        leftMargin=14*mm,
        rightMargin=14*mm,
    )

    styles = build_pdf_styles()
    DARK   = styles['_dark']
    MID    = styles['_mid']
    ACCENT = styles['_accent']
    WHITE  = styles['_white']
    MUTED  = styles['_muted']

    story = []

    # ── Header Banner ──
    header_data = [[
        Paragraph(f'<b>{policy_name}</b>', styles['title']),
    ]]
    subtitle_para = Paragraph('AI-Generated Fact Sheet  •  For Reference Only  •  Always verify against original policy document', styles['subtitle'])
    
    header_tbl = Table(
        [[Paragraph(f'<b>{policy_name}</b>', styles['title'])],
         [subtitle_para]],
        colWidths=[A4[0] - 28*mm]
    )
    header_tbl.setStyle(TableStyle([
        ('BACKGROUND',   (0, 0), (-1, -1), DARK),
        ('LEFTPADDING',  (0, 0), (-1, -1), 16),
        ('RIGHTPADDING', (0, 0), (-1, -1), 16),
        ('TOPPADDING',   (0, 0), (0,  0),  14),
        ('BOTTOMPADDING',(0, 1), (-1, -1), 12),
        ('TOPPADDING',   (0, 1), (-1, -1), 0),
    ]))
    story.append(header_tbl)
    story.append(Spacer(1, 5*mm))

    # ── Parse markdown ──
    lines = markdown_text.split('\n')
    i = 0
    current_section_items = []

    def flush_section():
        pass  # We add directly to story

    while i < len(lines):
        raw = lines[i]
        line = raw.strip()

        # H1 / H2 section headers  (## 1. POLICY BASICS...)
        if re.match(r'^#{1,2}\s+\d+\.\s+', line) or re.match(r'^#{1,2}\s+[A-Z]', line):
            text = re.sub(r'^#+\s*', '', line)
            # Wrap in coloured banner row
            hdr_para = Paragraph(apply_inline(text.upper()), styles['h1'])
            hdr_tbl = Table([[hdr_para]], colWidths=[A4[0] - 28*mm])
            hdr_tbl.setStyle(TableStyle([
                ('BACKGROUND',   (0, 0), (-1, -1), MID),
                ('LEFTPADDING',  (0, 0), (-1, -1), 10),
                ('RIGHTPADDING', (0, 0), (-1, -1), 10),
                ('TOPPADDING',   (0, 0), (-1, -1), 5),
                ('BOTTOMPADDING',(0, 0), (-1, -1), 5),
                ('ROUNDEDCORNERS', [4]),
            ]))
            story.append(Spacer(1, 3*mm))
            story.append(hdr_tbl)
            story.append(Spacer(1, 2*mm))
            i += 1
            continue

        # Sub-headers (### or **Heading:**)
        if line.startswith('### ') or re.match(r'^\*\*[A-Z][^*]+:\*\*\s*$', line):
            text = re.sub(r'^#+\s*', '', line)
            text = re.sub(r'\*\*(.*?)\*\*', r'\1', text)
            story.append(Paragraph(f'<b>{apply_inline(text)}</b>', styles['section_hdr']))
            i += 1
            continue

        # Table block — collect all lines belonging to this table
        if line.startswith('|'):
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                table_lines.append(lines[i])
                i += 1
            tbl = parse_table(table_lines, styles)
            if tbl:
                story.append(tbl)
                story.append(Spacer(1, 2*mm))
            continue

        # Bullet point
        if re.match(r'^[*\-]\s+', line):
            text = re.sub(r'^[*\-]\s+', '', line)
            story.append(Paragraph(f'• {apply_inline(text)}', styles['bullet']))
            i += 1
            continue

        # Numbered list
        if re.match(r'^\d+\.\s+', line):
            text = re.sub(r'^\d+\.\s+', '', line)
            story.append(Paragraph(f'• {apply_inline(text)}', styles['bullet']))
            i += 1
            continue

        # Horizontal rule
        if re.match(r'^[-*_]{3,}$', line):
            story.append(HRFlowable(width="100%", thickness=0.5,
                                    color=colors.HexColor('#e2e8f0'),
                                    spaceAfter=3, spaceBefore=3))
            i += 1
            continue

        # Empty line
        if not line:
            story.append(Spacer(1, 1.5*mm))
            i += 1
            continue

        # Plain text / bold-only line
        story.append(Paragraph(apply_inline(line), styles['body']))
        i += 1

    # ── Footer ──
    story.append(Spacer(1, 4*mm))
    story.append(HRFlowable(width="100%", thickness=0.5,
                             color=colors.HexColor('#cbd5e0')))
    story.append(Spacer(1, 2*mm))
    footer_style = ParagraphStyle('footer', fontName='Helvetica', fontSize=7,
                                   textColor=MUTED, alignment=TA_CENTER)
    story.append(Paragraph(
        'Generated by Policy Fact Sheet Generator  •  Powered by Google Gemini  •  '
        'This is an AI-generated summary. Always read the original policy document before purchase.',
        footer_style
    ))

    doc.build(story)
    buffer.seek(0)
    return buffer


# ─── GEMINI ANALYSIS ──────────────────────────────────────────────────────────
def analyze_policy(pdf_bytes: bytes, api_key: str) -> str:
    """Send PDF to Gemini and return the markdown Fact Sheet."""
    client = genai.Client(api_key=api_key)

    # Save to temp file for the File API
    with tempfile.NamedTemporaryFile(delete=False, suffix='.pdf') as tmp:
        tmp.write(pdf_bytes)
        tmp_path = tmp.name

    try:
        # Upload PDF via File API
        with open(tmp_path, 'rb') as f:
            uploaded = client.files.upload(
                file=f,
                config=genai_types.UploadFileConfig(mime_type='application/pdf')
            )

        # Wait for processing
        max_wait, waited = 30, 0
        while uploaded.state.name == 'PROCESSING' and waited < max_wait:
            time.sleep(2)
            waited += 2
            uploaded = client.files.get(name=uploaded.name)

        # Generate analysis
        response = client.models.generate_content(
            model='gemini-1.5-flash',
            contents=[uploaded, ANALYSIS_PROMPT],
            config=genai_types.GenerateContentConfig(
                temperature=0.1,
                max_output_tokens=4096,
            )
        )

        # Cleanup remote file
        try:
            client.files.delete(name=uploaded.name)
        except Exception:
            pass

        return response.text

    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass


# ─── UI ───────────────────────────────────────────────────────────────────────
st.markdown("""
<div class="main-header">
    <div class="badge">✦ Free & Open Source</div>
    <h1>Policy Fact Sheet Generator</h1>
    <p>Upload any health insurance PDF → Get a structured one-page summary powered by Google Gemini</p>
</div>
""", unsafe_allow_html=True)

# Info cards
col1, col2, col3 = st.columns(3)
with col1:
    st.markdown("""<div class="card">
        <h4>📄 Any Policy PDF</h4>
        <p>Upload any health insurance policy document</p>
    </div>""", unsafe_allow_html=True)
with col2:
    st.markdown("""<div class="card">
        <h4>🤖 Gemini 1.5 Flash</h4>
        <p>Extracts key terms, limits & exclusions instantly</p>
    </div>""", unsafe_allow_html=True)
with col3:
    st.markdown("""<div class="card">
        <h4>📊 Structured PDF</h4>
        <p>5-section fact sheet — download and share</p>
    </div>""", unsafe_allow_html=True)

st.markdown("---")

# ── Step 1: API Key ──
st.markdown('<div class="step-label">Step 1 — Gemini API Key</div>', unsafe_allow_html=True)
api_key = st.text_input(
    label="API Key",
    type="password",
    placeholder="AIza...",
    help="Get your free key at https://aistudio.google.com/app/apikey",
    label_visibility="collapsed",
)
st.caption("🔒 Your key is never stored. Get a free key at [aistudio.google.com](https://aistudio.google.com/app/apikey)")

st.markdown("<br>", unsafe_allow_html=True)

# ── Step 2: Upload ──
st.markdown('<div class="step-label">Step 2 — Upload Policy PDF</div>', unsafe_allow_html=True)
uploaded_file = st.file_uploader(
    label="Policy PDF",
    type=["pdf"],
    help="Upload the complete health insurance policy document (wordings PDF)",
    label_visibility="collapsed",
)

if uploaded_file:
    file_size_kb = len(uploaded_file.getvalue()) / 1024
    st.markdown(f"""<div class="status-box">
        ✅ <b>{uploaded_file.name}</b> uploaded — {file_size_kb:.0f} KB
    </div>""", unsafe_allow_html=True)

st.markdown("<br>", unsafe_allow_html=True)

# ── Step 3: Generate ──
st.markdown('<div class="step-label">Step 3 — Generate Fact Sheet</div>', unsafe_allow_html=True)

if st.button("⚡ Generate Fact Sheet", disabled=(not api_key or not uploaded_file)):
    if not api_key:
        st.markdown('<div class="error-box">❌ Please enter your Gemini API key.</div>', unsafe_allow_html=True)
    elif not uploaded_file:
        st.markdown('<div class="error-box">❌ Please upload a PDF policy document.</div>', unsafe_allow_html=True)
    else:
        pdf_bytes = uploaded_file.getvalue()

        with st.spinner("📡 Sending policy to Gemini for analysis…"):
            try:
                markdown_result = analyze_policy(pdf_bytes, api_key)
                st.session_state['markdown_result'] = markdown_result
                st.session_state['source_name'] = uploaded_file.name.replace('.pdf', '')
                st.markdown('<div class="status-box">✅ Analysis complete! Generating PDF…</div>', unsafe_allow_html=True)
            except Exception as e:
                err_msg = str(e)
                if 'API_KEY_INVALID' in err_msg or 'API key' in err_msg.lower():
                    st.markdown('<div class="error-box">❌ Invalid API key. Check it at aistudio.google.com</div>', unsafe_allow_html=True)
                elif 'quota' in err_msg.lower() or 'rate' in err_msg.lower():
                    st.markdown('<div class="error-box">❌ Gemini rate limit reached. Wait a minute and try again.</div>', unsafe_allow_html=True)
                else:
                    st.markdown(f'<div class="error-box">❌ Error: {err_msg[:300]}</div>', unsafe_allow_html=True)
                st.session_state.pop('markdown_result', None)

# ── Results ──
if 'markdown_result' in st.session_state:
    md = st.session_state['markdown_result']
    src = st.session_state.get('source_name', 'Policy')

    # Build PDF
    try:
        pdf_buffer = markdown_to_pdf(md, policy_name=src)
        pdf_bytes_out = pdf_buffer.read()

        st.markdown("---")
        col_a, col_b = st.columns([2, 1])
        with col_a:
            st.markdown("### 📋 Markdown Preview")
            st.markdown('<div class="preview-box">' +
                        md.replace('\n', '<br>').replace(' ', '&nbsp;') +
                        '</div>', unsafe_allow_html=True)
        with col_b:
            st.markdown("### ⬇️ Download")
            st.download_button(
                label="📄 Download Fact Sheet PDF",
                data=pdf_bytes_out,
                file_name=f"{src}_FactSheet.pdf",
                mime="application/pdf",
            )
            st.caption("One-page structured summary with tables and colour formatting.")

            st.markdown("<br>", unsafe_allow_html=True)
            st.markdown("**Also copy raw markdown:**")
            st.text_area("Markdown", value=md, height=200, label_visibility="collapsed")

    except Exception as e:
        st.error(f"PDF generation error: {e}")
        st.markdown("**Raw Markdown output:**")
        st.markdown(md)

st.markdown("---")
st.caption("Made with Streamlit + Google Gemini 1.5 Flash + ReportLab • Runs free on Streamlit Community Cloud")
