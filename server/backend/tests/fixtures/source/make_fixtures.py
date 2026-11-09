"""Builds the lesson fixtures for the document parser tests (#10): lesson.docx, lesson.pdf and
scanned.pdf, next to this folder. Standard library plus a local Chrome, no network.

    py tests/fixtures/source/make_fixtures.py [--chrome PATH]

lesson.docx is written by hand (WordprocessingML, deflated like Word saves it): a Title, three
Heading 1 sections, "Paraño" split across two runs, a tab, a line break, a 2x2 table, and a page
header that must not be read as lesson text. lesson.pdf is the same lesson printed by headless
Chrome (3 pages, one section each). scanned.pdf is one page holding only a picture of the lesson,
with no text layer, like a phone scan.
"""

import argparse
import pathlib
import subprocess
import tempfile
import zipfile
from xml.sax.saxutils import escape

OUT = pathlib.Path(__file__).resolve().parent.parent
CHROME_PATHS = [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/usr/bin/google-chrome", "/usr/bin/chromium"]

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def run(text):
    return f'<w:r><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def para(*runs, style=None, page_break=False):
    props = ""
    if style or page_break:
        props = "<w:pPr>" + (f'<w:pStyle w:val="{style}"/>' if style else "") + ("<w:pageBreakBefore/>" if page_break else "") + "</w:pPr>"
    return f"<w:p>{props}{''.join(runs)}</w:p>"


def table(rows):
    cells = lambda row: "".join(f'<w:tc><w:tcPr><w:tcW w:w="2400" w:type="dxa"/></w:tcPr>{para(run(c))}</w:tc>' for c in row)
    return "<w:tbl><w:tblPr><w:tblW w:w=\"4800\" w:type=\"dxa\"/></w:tblPr>" + "".join(f"<w:tr>{cells(r)}</w:tr>" for r in rows) + "</w:tbl>"


def make_docx(path):
    body = "".join([
        para(run("The Water Cycle"), style="Title"),
        para(run("Water moves between the land, the sea and the sky. This journey never stops, and it is called the water cycle.")),
        para(run("Evaporation"), style="Heading1"),
        para(run("When the sun heats rivers, lakes and the sea, some of the water turns into water vapour. "
                 "The vapour is a gas, so we cannot see it as it rises into the air.")),
        # Word often splits a word across runs, here at the ñ.
        para(run("In Barangay Para"), run("ño, the fish pond gets smaller on hot, sunny days because of evaporation.")),
        para(run("Remember:"), "<w:r><w:tab/></w:r>", run("the sun drives the whole cycle.")),
        para(run("Condensation"), style="Heading1", page_break=True),
        para(run("High in the sky the air is cold. The water vapour cools and turns back into tiny drops of water.")),
        para(run("Clouds form when vapour cools."), "<w:r><w:br/></w:r>", run("Tiny droplets join together and the cloud grows darker.")),
        para(run("Precipitation"), style="Heading1", page_break=True),
        para(run("When the drops become too heavy, they fall back to the ground. Water that falls from clouds is called precipitation.")),
        table([["Rain", "Snow"], ["Hail", "Sleet"]]),
        para(run("The water flows into rivers and the sea, and the cycle begins again.")),
        f'<w:sectPr><w:headerReference w:type="default" r:id="rId2"/></w:sectPr>',
    ])
    parts = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
            '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
            '</Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'<Relationship Id="rId1" Type="{REL}/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/_rels/document.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'<Relationship Id="rId1" Type="{REL}/styles" Target="styles.xml"/>'
            f'<Relationship Id="rId2" Type="{REL}/header" Target="header1.xml"/></Relationships>',
        "word/styles.xml": f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles {W}>'
            '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
            '<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:sz w:val="56"/></w:rPr></w:style>'
            '<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>'
            '<w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>',
        "word/header1.xml": f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr {W}>{para(run("Grade 5 Science, Quarter 1"))}</w:hdr>',
        "word/document.xml": f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W} {R}><w:body>{body}</w:body></w:document>',
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, xml in parts.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 9, 0, 0, 0))  # fixed, so rebuilding gives the same bytes
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, xml.encode("utf-8"))


LESSON_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>The Water Cycle</title>
<style>body { font: 13pt/1.5 Arial, sans-serif; margin: 0; } h1 { font-size: 24pt; } h2 { font-size: 17pt; margin-top: 0; }
section + section { break-before: page; } table { border-collapse: collapse; } td { border: 1px solid #555; padding: 4px 16px; }</style></head>
<body>
<section><h1>The Water Cycle</h1>
<p>Water moves between the land, the sea and the sky. This journey never stops, and it is called the water cycle.</p>
<h2>Evaporation</h2>
<p>When the sun heats rivers, lakes and the sea, some of the water turns into water vapour. The vapour is a gas, so we cannot see it as it rises into the air.</p>
<p>In Barangay Paraño, the fish pond gets smaller on hot, sunny days because of evaporation.</p>
<p>Remember: the sun drives the whole cycle.</p></section>
<section><h2>Condensation</h2>
<p>High in the sky the air is cold. The water vapour cools and turns back into tiny drops of water.</p>
<p>Clouds form when vapour cools. Tiny droplets join together and the cloud grows darker.</p></section>
<section><h2>Precipitation</h2>
<p>When the drops become too heavy, they fall back to the ground. Water that falls from clouds is called precipitation.</p>
<table><tr><td>Rain</td><td>Snow</td></tr><tr><td>Hail</td><td>Sleet</td></tr></table>
<p>The water flows into rivers and the sea, and the cycle begins again.</p></section>
</body></html>"""


def chrome(binary, profile, *args):
    subprocess.run([binary, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                    f"--user-data-dir={profile}", *args], check=True, capture_output=True, timeout=120)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--chrome", default=next((p for p in CHROME_PATHS if pathlib.Path(p).exists()), None))
    args = parser.parse_args()
    if not args.chrome:
        parser.error("Chrome not found; pass --chrome PATH")
    make_docx(OUT / "lesson.docx")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        lesson = tmp / "lesson.html"
        lesson.write_text(LESSON_HTML, encoding="utf-8")
        chrome(args.chrome, tmp / "profile", "--no-pdf-header-footer", f"--print-to-pdf={OUT / 'lesson.pdf'}", lesson.as_uri())
        shot = tmp / "page.png"
        chrome(args.chrome, tmp / "profile", "--hide-scrollbars", "--window-size=816,1056", f"--screenshot={shot}", lesson.as_uri())
        scan = tmp / "scan.html"
        scan.write_text(f'<!doctype html><html><body style="margin:0"><img src="{shot.as_uri()}" style="width:100%"></body></html>', encoding="utf-8")
        chrome(args.chrome, tmp / "profile", "--no-pdf-header-footer", "--allow-file-access-from-files",
               f"--print-to-pdf={OUT / 'scanned.pdf'}", scan.as_uri())
    for name in ("lesson.docx", "lesson.pdf", "scanned.pdf"):
        print(f"{name}: {(OUT / name).stat().st_size} bytes")


if __name__ == "__main__":
    main()
