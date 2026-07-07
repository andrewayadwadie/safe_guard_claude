#!/usr/bin/env python3
"""Generate the two Haris legal static sites from the source .docx files.

Deterministic docx -> semantic HTML converter. Reads word/document.xml, honours
the Word paragraph styles (Heading1 = numbered section, Heading2 = numbered
subsection, ListParagraph+numPr = bullet list item, all-bold short line = named
subheading) and emits a fully self-contained index.html per document. Guarantees
FR-003 fidelity (every source paragraph emitted, in order) and FR-012/SC-005
repeatability (re-run after any docx edit).

Runtime pages make zero external requests (FR-015). CSS/JS/fonts are authored
separately and copied next to each generated index.html.

Usage:  python tools/legal-sites/build_sites.py
"""
import html
import os
import re
import zipfile
import xml.etree.ElementTree as ET

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets")
SITES = os.path.join(ASSETS, "websites")

DOCS = [
    ("privacy_policy.docx", "privacy-policy", "Privacy Policy"),
    ("terms_conditions.docx", "terms", "Terms & Conditions"),
]

ARABIC_NOTICE = (
    "النسخة العربية من هذه الوثيقة قيد الإعداد وستتوفر قريبًا. "
    "النص القانوني الملزم متاح حاليًا باللغة الإنجليزية أدناه."
)

EMAIL_RE = re.compile(r"([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})")
URL_RE = re.compile(r"\b(www\.[A-Za-z0-9.\-]+\.[A-Za-z]{2,})\b")
NUM_RE = re.compile(r"^(\d+(?:\.\d+)*)\.?\s+(.*)$")


# ----------------------------------------------------------------------------
# docx parsing
# ----------------------------------------------------------------------------
class Para:
    __slots__ = ("text", "style", "is_list", "all_bold")

    def __init__(self, text, style, is_list, all_bold):
        self.text = text
        self.style = style
        self.is_list = is_list
        self.all_bold = all_bold


def parse_docx(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml")
    body = ET.fromstring(xml).find(f"{W}body")
    out = []
    for p in body.findall(f"{W}p"):
        ppr = p.find(f"{W}pPr")
        style = ""
        is_list = False
        if ppr is not None:
            st = ppr.find(f"{W}pStyle")
            if st is not None:
                style = st.get(f"{W}val", "")
            is_list = ppr.find(f"{W}numPr") is not None
        # Use iter() so runs nested inside <w:hyperlink> (emails, URLs) are included.
        text_parts = []
        bold_flags = []
        for r in p.iter(f"{W}r"):
            t = "".join(node.text or "" for node in r.findall(f"{W}t"))
            if not t:
                continue
            text_parts.append(t)
            rpr = r.find(f"{W}rPr")
            bold_flags.append(rpr is not None and rpr.find(f"{W}b") is not None)
        text = "".join(text_parts).strip()
        if not text:
            continue  # drop empty spacer paragraphs (no content, no structure)
        all_bold = bool(bold_flags) and all(bold_flags)
        out.append(Para(text, style, is_list, all_bold))
    return out


# ----------------------------------------------------------------------------
# text -> safe HTML (escape, then linkify emails + website)
# ----------------------------------------------------------------------------
def inline(text):
    s = html.escape(text)
    s = EMAIL_RE.sub(r'<a href="mailto:\1">\1</a>', s)
    s = URL_RE.sub(r'<a href="https://\1" rel="noopener">\1</a>', s)
    return s


def slugify(num):
    return "section-" + num.replace(".", "-")


# ----------------------------------------------------------------------------
# build one site's <main> + table of contents
# ----------------------------------------------------------------------------
def build_body(paras):
    # header block = everything before "Table of Contents"
    toc_idx = next(i for i, p in enumerate(paras)
                   if p.text.lower() == "table of contents")
    header = paras[:toc_idx]
    # body starts at first Heading1 after the ToC
    body_start = next(i for i in range(toc_idx + 1, len(paras))
                      if paras[i].style == "Heading1")
    body = paras[body_start:]

    toc = []           # (num, title, anchor)
    used_ids = set()
    html_parts = []
    ul_open = False
    section_open = False

    def close_ul():
        nonlocal ul_open
        if ul_open:
            html_parts.append("      </ul>")
            ul_open = False

    def close_section():
        nonlocal section_open
        close_ul()
        if section_open:
            html_parts.append("    </section>")
            section_open = False

    def unique(anchor):
        a, n = anchor, 2
        while a in used_ids:
            a = f"{anchor}-{n}"
            n += 1
        used_ids.add(a)
        return a

    for p in body:
        if not p.text:
            continue
        m = NUM_RE.match(p.text)
        numbered_sub = m is not None and "." in m.group(1)  # "N.M" style
        short_head = (len(p.text) <= 70 and not p.text.endswith((".", ":", ";")))
        if p.style == "Heading1":
            close_section()
            anchor = unique(slugify(m.group(1)) if m else "section")
            if m:
                toc.append((m.group(1), m.group(2), anchor))
            html_parts.append(f'    <section id="{anchor}" aria-labelledby="{anchor}-h">')
            html_parts.append(f'      <h2 id="{anchor}-h">{inline(p.text)}</h2>')
            section_open = True
        elif numbered_sub:
            # Numbered subsection (e.g. 5.1, 8.2, 1.1) — level regardless of the
            # source's inconsistent Word styling (some are Heading2, some bold).
            close_ul()
            anchor = unique(slugify(m.group(1)))
            html_parts.append(f'      <h3 id="{anchor}">{inline(p.text)}</h3>')
        elif p.is_list:
            if not ul_open:
                html_parts.append("      <ul>")
                ul_open = True
            html_parts.append(f"        <li>{inline(p.text)}</li>")
        elif (p.style == "Heading2" or p.all_bold) and short_head:
            # Named callout subheading (e.g. "Cloud Data", "Active Protection").
            close_ul()
            html_parts.append(f"      <h4>{inline(p.text)}</h4>")
        else:
            close_ul()
            html_parts.append(f"      <p>{inline(p.text)}</p>")

    close_section()
    return header, toc, "\n".join(html_parts)


# ----------------------------------------------------------------------------
# full page template
# ----------------------------------------------------------------------------
def render_page(header, toc, main_html, title):
    brand = html.escape(header[0].text) if header else "حارس · HARIS"
    doc_title = html.escape(header[1].text) if len(header) > 1 else title.upper()
    meta_lines = "\n".join(
        f'        <p class="meta-line">{inline(p.text)}</p>' for p in header[2:] if p.text)
    toc_items = "\n".join(
        f'        <li><a href="#{a}">{html.escape(n)}. {html.escape(t)}</a></li>'
        for n, t, a in toc)
    page_title = f"{title} · Haris"

    return f"""<!doctype html>
<html lang="en" dir="ltr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark light">
<title>{html.escape(page_title)}</title>
<meta name="description" content="{html.escape(title)} for Haris (حارس) — a privacy-by-design digital child safety platform.">
<link rel="stylesheet" href="styles.css">
<script>
/* Apply persisted language before first paint (no FOUC). */
(function(){{try{{var l=localStorage.getItem('haris-legal-lang');
if(l==='ar'){{document.documentElement.lang='ar';document.documentElement.dir='rtl';}}}}catch(e){{}}}})();
</script>
</head>
<body>
<a class="skip-link" href="#main">Skip to content</a>

<header class="site-header">
  <div class="header-inner">
    <div class="brandmark">{brand}</div>
    <h1>{doc_title}</h1>
    <div class="meta">
{meta_lines}
    </div>
    <button id="lang-toggle" class="lang-toggle" type="button" aria-label="Switch language">العربية</button>
  </div>
</header>

<div id="lang-notice" class="lang-notice" lang="ar" dir="rtl" hidden>
  <p>{html.escape(ARABIC_NOTICE)}</p>
</div>

<div class="layout">
  <nav id="toc" class="toc" aria-label="Table of contents">
    <h2 class="toc-title" data-en="Contents" data-ar="المحتويات">Contents</h2>
    <ol>
{toc_items}
    </ol>
  </nav>

  <main id="main" class="content">
{main_html}
  </main>
</div>

<button id="back-to-top" class="back-to-top" type="button"
        aria-label="Back to top" data-en="Back to top" data-ar="العودة للأعلى" hidden>↑</button>

<script src="script.js"></script>
</body>
</html>
"""


def main():
    for docx_name, folder, title in DOCS:
        paras = parse_docx(os.path.join(ASSETS, docx_name))
        header, toc, main_html = build_body(paras)
        page = render_page(header, toc, main_html, title)
        out = os.path.join(SITES, folder, "index.html")
        with open(out, "w", encoding="utf-8") as f:
            f.write(page)
        print(f"  {folder:16} sections={len(toc):>2}  paras={len(paras):>3}  -> {out}")


if __name__ == "__main__":
    main()
