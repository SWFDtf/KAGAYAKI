#!/usr/bin/env python3
"""Minimal Markdown -> KAGAYAKI-docs HTML converter (no dependencies).

Usage: python3 docs/md2html.py   (run from the repository root)
Converts docs/sprachreferenz.md and docs/getting-started.md into styled HTML pages and
mirrors the Markdown sources into docs/md/.
"""
import html
import re
import shutil
from pathlib import Path

DOCS = Path(__file__).resolve().parent

PAGES = [
    # (markdown source, html name, title, description)
    ("getting-started", "getting-started",
     "Erste Schritte mit vjava",
     "Voraussetzungen, Setup, CLI, Hello World und Quellcode-Struktur von vjava."),
    ("sprachreferenz", "sprachreferenz",
     "vjava-Sprachreferenz",
     "Alle vjava-Befehle, Schlüsselwörter und Operatoren mit Argumenten und Beispielen."),
]
NAV = [
    ("index", "Startseite"),
    ("erste-schritte", "Erste Schritte"),
    ("konzepte", "Konzepte"),
    ("anleitungen", "Anleitungen &amp; FAQ"),
    ("getting-started", "vjava Setup"),
    ("sprachreferenz", "Sprachreferenz"),
    ("mitmachen", "Mitmachen"),
]
LINK_MAP = {"getting-started.md": "getting-started.html", "sprachreferenz.md": "sprachreferenz.html"}


def slug(text):
    text = re.sub(r"[`*_]", "", text).lower()
    text = re.sub(r"[^\w\s-]", "", text)
    return text.lstrip().replace(" ", "-")


def inline(text):
    codes = []

    def stash(m):
        codes.append("<code>" + html.escape(m.group(1)) + "</code>")
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", stash, text)
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)

    def link(m):
        href = m.group(2)
        base, _, frag = href.partition("#")
        base = LINK_MAP.get(base, base)
        if base.endswith(".md"):
            base = base[:-3] + ".md"
        href = base + ("#" + frag if frag else "")
        return f'<a href="{href}">{m.group(1)}</a>'

    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)


def split_row(line):
    line = line.strip().strip("|")
    cells, cur, i = [], "", 0
    while i < len(line):
        if line[i] == "\\" and i + 1 < len(line) and line[i + 1] == "|":
            cur += "|"
            i += 2
            continue
        if line[i] == "`":  # keep pipes inside inline code
            j = line.find("`", i + 1)
            if j > 0:
                cur += line[i:j + 1]
                i = j + 1
                continue
        if line[i] == "|":
            cells.append(cur.strip())
            cur = ""
        else:
            cur += line[i]
        i += 1
    cells.append(cur.strip())
    return cells


def convert(md):
    lines = md.split("\n")
    out, i, para = [], 0, []
    title = None

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()

    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            flush()
            lang = line[3:].strip()
            i += 1
            buf = []
            while i < len(lines) and not lines[i].startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            cls = f' class="language-{lang}"' if lang and lang != "mermaid" else ""
            code = html.escape("\n".join(buf))
            out.append(f"<pre><code{cls}>{code}</code></pre>")
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            flush()
            lvl, txt = len(m.group(1)), m.group(2)
            if lvl == 1 and title is None:
                title = re.sub(r"`", "", txt)
            out.append(f'<h{lvl} id="{slug(txt)}">{inline(txt)}</h{lvl}>')
            i += 1
            continue
        if re.match(r"^-{3,}$", line.strip()):
            flush()
            out.append("<hr>")
            i += 1
            continue
        if line.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:|-]+\|$", lines[i + 1].strip()):
            flush()
            head = split_row(line)
            i += 2
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            t = ['<div class="table-wrap"><table>', "<thead><tr>"]
            t += [f"<th>{inline(c)}</th>" for c in head]
            t.append("</tr></thead><tbody>")
            for r in rows:
                t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t))
            continue
        if line.startswith(">"):
            flush()
            buf = []
            while i < len(lines) and lines[i].startswith(">"):
                buf.append(lines[i].lstrip(">").strip())
                i += 1
            text = " ".join(b for b in buf if b)
            out.append("<blockquote><p>" + inline(text) + "</p></blockquote>")
            continue
        m = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", line)
        if m:
            flush()
            ordered = m.group(2)[0].isdigit()
            tag = "ol" if ordered else "ul"
            items = []
            while i < len(lines):
                mm = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", lines[i])
                if mm:
                    items.append(mm.group(3))
                    i += 1
                elif lines[i].startswith("  ") and lines[i].strip() and items:
                    items[-1] += " " + lines[i].strip()
                    i += 1
                else:
                    break
            out.append(f"<{tag}>" + "".join(f"<li>{inline(x)}</li>" for x in items) + f"</{tag}>")
            continue
        if not line.strip():
            flush()
            i += 1
            continue
        para.append(line.strip())
        i += 1
    flush()
    return title or "", "\n".join(out)


def nav_html(current, indent):
    pad = " " * indent
    items = []
    for key, label in NAV:
        cur = ' aria-current="page"' if key == current else ""
        items.append(f'{pad}<li><a href="{key}.html"{cur}>{label}</a></li>')
    return "\n".join(items)


def page(name, title, desc, body):
    return f"""<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)} — KAGAYAKI Dokumentation</title>
  <meta name="description" content="{html.escape(desc)}">
  <link rel="stylesheet" href="css/style.css">
</head>
<body>
  <a class="skip-link" href="#hauptinhalt">Direkt zum Inhalt springen</a>

  <header class="site-header">
    <div class="container header-inner">
      <a class="brand" href="index.html">
        KAGAYAKI
        <span class="brand-badge">Doku</span>
      </a>

      <nav class="site-nav" aria-label="Hauptnavigation">
        <ul>
{nav_html(name, 10)}
          <li><a href="md/{name}.md">Markdown</a></li>
        </ul>
      </nav>

      <details class="nav-toggle-details">
        <summary aria-label="Menü öffnen">Menü</summary>
        <nav aria-label="Mobile Hauptnavigation">
          <ul>
{nav_html(name, 12)}
            <li><a href="md/{name}.md">Markdown</a></li>
          </ul>
        </nav>
      </details>
    </div>
  </header>

  <main id="hauptinhalt" class="container prose">
{body}
  </main>

  <footer class="site-footer">
    <div class="container footer-inner">
      <span>KAGAYAKI Dokumentation · <a href="md/{name}.md">Diese Seite als Markdown</a></span>
      <div class="footer-links">
        <a href="impressum.html">Impressum</a>
        <a href="datenschutz.html">Datenschutz</a>
        <a href="lizenz.html">Lizenz</a>
      </div>
    </div>
  </footer>
</body>
</html>
"""


def add_nav_to_existing():
    """Insert the two new nav entries into the existing pages (idempotent)."""
    for f in DOCS.glob("*.html"):
        if f.stem in {p[1] for p in PAGES}:
            continue
        text = open(f, encoding="utf-8", newline="").read()
        if "getting-started.html" in text:
            continue
        pattern = re.compile(r'^([ \t]*)<li><a href="mitmachen\.html"( aria-current="page")?>', re.M)
        text = pattern.sub(
            lambda m: f'{m.group(1)}<li><a href="getting-started.html">vjava Setup</a></li>\n'
                      f'{m.group(1)}<li><a href="sprachreferenz.html">Sprachreferenz</a></li>\n'
                      + m.group(0), text)
        open(f, "w", encoding="utf-8", newline="").write(text)


def main():
    (DOCS / "md").mkdir(exist_ok=True)
    for src, name, title, desc in PAGES:
        md = (DOCS / f"{src}.md").read_text(encoding="utf-8")
        _, body = convert(md)
        (DOCS / f"{name}.html").write_text(page(name, title, desc, body), encoding="utf-8")
        shutil.copyfile(DOCS / f"{src}.md", DOCS / "md" / f"{src}.md")
        print("wrote", name + ".html")
    add_nav_to_existing()


if __name__ == "__main__":
    main()
