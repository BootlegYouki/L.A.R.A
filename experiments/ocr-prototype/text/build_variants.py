"""Re-renders the sample lesson text in messier born-digital layouts (PDF via ReportLab, DOCX via python-docx).
usage: python build_variants.py <lesson.pdf> <outdir>.   Same words in every file, so the answer key (matched by quote text) stays valid."""
import os, re, sys
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, Table, TableStyle, KeepTogether, CondPageBreak, FrameBreak
from lesson_model import blocks_of

HEADER = "Weather, Climate and Typhoons in the Philippines"
W, H = A4
LIG = [("ffi", "ﬃ"), ("ffl", "ﬄ"), ("ff", "ﬀ"), ("fi", "ﬁ"), ("fl", "ﬂ")]
DJ = "/usr/share/fonts/truetype/dejavu/"

def lig(t):
    for a, b in LIG: t = t.replace(a, b)
    return t

def build_pdf(blocks, path, cols=1, hyphen=False, ligature=False, ruled=False):
    if ligature:
        pdfmetrics.registerFont(TTFont("Body", DJ + "DejaVuSerif.ttf")); pdfmetrics.registerFont(TTFont("BodyB", DJ + "DejaVuSerif-Bold.ttf"))
        f, fb = "Body", "BodyB"
    else: f, fb = "Helvetica", "Helvetica-Bold"
    size = 9.5 if cols == 2 else 10.5
    base = ParagraphStyle("b", fontName=f, fontSize=size, leading=size * 1.4, alignment=TA_JUSTIFY if hyphen else TA_LEFT, spaceAfter=5,
                          hyphenationLang="en_US" if hyphen else None, hyphenationMinWordLength=5, uriWasteReduce=0.0 if hyphen else None)
    st = dict(para=base, bullet=ParagraphStyle("bu", parent=base, leftIndent=12, bulletIndent=2, spaceAfter=2),
              h1=ParagraphStyle("h1", parent=base, fontName=fb, fontSize=15, leading=19, spaceBefore=10, spaceAfter=6, alignment=TA_LEFT, keepWithNext=1),
              h2=ParagraphStyle("h2", parent=base, fontName=fb, fontSize=12, leading=15, spaceBefore=8, spaceAfter=3, alignment=TA_LEFT, keepWithNext=1),
              h3=ParagraphStyle("h3", parent=base, fontName=fb, fontSize=10.5, leading=13, spaceBefore=6, spaceAfter=2, alignment=TA_LEFT, keepWithNext=1),
              cell=ParagraphStyle("c", parent=base, fontSize=size - 1, leading=(size - 1) * 1.25, alignment=TA_LEFT, spaceAfter=0, hyphenationLang=None),
              cap=ParagraphStyle("cap", parent=base, fontName=fb, fontSize=size - 1, alignment=TA_LEFT))
    T = (lambda s: escape(lig(s))) if ligature else escape
    def decorate(c, d):
        c.saveState(); c.setFont(f, 8.5)
        c.drawString(40, H - 28, HEADER); c.drawRightString(W - 40, H - 28, "Grade 8 Science")
        c.drawCentredString(W / 2, 22, f"Page {d.page}"); c.restoreState()
    top, bot, m = H - 50, 40, 40
    if cols == 1: frames = [Frame(m, bot, W - 2 * m, top - bot, id="f", leftPadding=0, rightPadding=0)]
    else:
        gw = 18; cw = (W - 2 * m - gw) / 2
        frames = [Frame(m, bot, cw, top - bot, id="c1", leftPadding=0, rightPadding=0), Frame(m + cw + gw, bot, cw, top - bot, id="c2", leftPadding=0, rightPadding=0)]
    doc = BaseDocTemplate(path, pagesize=A4, leftMargin=m, rightMargin=m, topMargin=50, bottomMargin=40, title="Weather lesson", author="")
    doc.addPageTemplates([PageTemplate(id="p", frames=frames, onPage=decorate)])
    fw = (frames[0]._width)
    flow = []
    for k, p, _ in blocks:
        if k == "table":
            rows, cap = p
            n = len(rows[0]); data = [[Paragraph(T(c), st["cell"]) for c in r] for r in rows]
            tb = Table(data, colWidths=[fw / n] * n, repeatRows=0)
            sty = [("VALIGN", (0, 0), (-1, -1), "TOP"), ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e6e6e6")), ("TOPPADDING", (0, 0), (-1, -1), 2), ("BOTTOMPADDING", (0, 0), (-1, -1), 2)]
            if ruled: sty.append(("GRID", (0, 0), (-1, -1), 0.5, colors.black))
            tb.setStyle(TableStyle(sty))
            flow.append(KeepTogether([tb, Paragraph(T(cap), st["cap"]), Spacer(1, 6)]))
        elif k == "bullet": flow.append(Paragraph(T(p), st["bullet"], bulletText="•"))
        else:
            if k == "h1": flow.append(CondPageBreak(120))
            flow.append(Paragraph(T(p), st[k]))
    doc.build(flow)

def build_docx(blocks, path, styled=True):
    from docx import Document
    from docx.shared import Pt
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    d = Document()
    sec = d.sections[0]
    sec.header.paragraphs[0].text = HEADER + "\tGrade 8 Science"
    fp = sec.footer.paragraphs[0]; fp.add_run("Page ")
    r = fp.add_run(); 
    for t, txt in (("begin", None), (None, "PAGE"), ("end", None)):
        if t: e = OxmlElement("w:fldChar"); e.set(qn("w:fldCharType"), t)
        else: e = OxmlElement("w:instrText"); e.text = txt
        r._r.append(e)
    for k, p, _ in blocks:
        if k in ("h1", "h2", "h3"):
            if styled: d.add_heading(p, level={"h1": 1, "h2": 2, "h3": 3}[k])
            else: d.add_paragraph().add_run(p).bold = True  # teachers often fake headings with bold text
        elif k == "bullet": d.add_paragraph(p, style="List Bullet")
        elif k == "table":
            rows, cap = p
            t = d.add_table(rows=len(rows), cols=len(rows[0])); t.style = "Table Grid"
            for i, rw in enumerate(rows):
                for j, c in enumerate(rw): t.cell(i, j).text = c
            d.add_paragraph(cap)
        else: d.add_paragraph(p)
    d.save(path)

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
    b = blocks_of(src)
    build_pdf(b, f"{out}/v1_twocol.pdf", cols=2, ruled=True)
    build_pdf(b, f"{out}/v2_hyphen.pdf", hyphen=True)
    build_pdf(b, f"{out}/v3_ligature.pdf", ligature=True)
    build_pdf(b, f"{out}/v4_messy.pdf", cols=2, hyphen=True, ligature=True, ruled=True)
    build_docx(b, f"{out}/v5_docx.docx", styled=True)
    build_docx(b, f"{out}/v6_docx_plain.docx", styled=False)
    os.system(f"cp {src} {out}/v0_orig.pdf")
    print(sorted(os.listdir(out)))
