#!/usr/bin/env python3
"""Generate the browsable views of the knowledge base:
  knowledge/html/index.html   — progress dashboard + open-gap counts
  knowledge/html/<table>.html — every table, searchable (client-side filter)
  knowledge/GAPS.md           — the open gaps, grouped, with a notes column
Rerun after any harvest; everything is derived from knowledge.db."""

import html
import json
import os

import kb

OUT = os.path.join(kb.ROOT, "knowledge")
HTML_OUT = os.path.join(OUT, "html")

TABLES = {
    "equipment": ("Equipment", ["name", "slot", "members", "equip_reqs", "effects",
                                "obtain", "examine", "src", "flags"]),
    "poh_furniture": ("PoH furniture", ["name", "room", "level", "materials", "xp",
                                        "flatpackable", "src", "flags"]),
    "boosts": ("Temporary boosts", ["name", "skill", "amount", "circumstances", "src", "flags"]),
    "diary_tasks": ("Diary tasks", ["region", "tier", "task", "reqs", "notes", "src", "flags"]),
    "consumables": ("Consumables", ["name", "kind", "effects", "make_reqs", "obtain", "src", "flags"]),
    "clog_items": ("Collection log", ["item_id", "name", "activity", "drop_rate",
                                      "what_it_does", "gates", "src", "flags"]),
    "ca_tasks": ("Combat achievements", ["name", "tier", "type", "monster",
                                         "description", "reqs", "src", "flags"]),
    "boat_upgrades": ("Boat upgrades", ["part", "tier", "boat_type", "reqs",
                                        "materials", "effects", "src", "flags"]),
    "qol_items": ("QoL items", ["name", "effect", "sources", "reqs", "src", "flags"]),
    "training_methods": ("Training methods", ["skill", "method", "xp_hr", "reqs",
                                              "inputs", "outputs", "notes", "src", "flags"]),
    "money_methods": ("Money making", ["method", "gp_hr", "category", "intensity",
                                       "reqs", "inputs", "outputs", "src", "flags"]),
    "recipes": ("Recipes / processing", ["page", "output", "output_qty", "materials",
                                         "skills", "facilities", "tools", "src", "flags"]),
    "materials": ("Resources & materials", ["name", "obtained", "used_in",
                                            "makes_count", "src", "flags"]),
    "gaps": ("Gaps (help wanted)", ["category", "subject", "field", "why", "status", "notes"]),
}

STYLE = """
body { font-family: -apple-system, Segoe UI, sans-serif; margin: 24px; background: #1b1b1b; color: #ddd; }
a { color: #e8a33d; } h1, h2 { color: #e8a33d; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { border: 1px solid #3a3a3a; padding: 4px 8px; text-align: left; vertical-align: top; }
th { background: #2a2a2a; position: sticky; top: 0; }
tr:nth-child(even) { background: #222; }
.flag { color: #e05b5b; font-weight: 600; }
.ok { color: #7bc96f; }
.muted { color: #888; }
input { width: 100%; padding: 8px; margin: 12px 0; background: #2a2a2a; color: #ddd;
        border: 1px solid #444; font-size: 14px; }
pre { margin: 0; white-space: pre-wrap; font-size: 12px; }
.count { color: #888; font-size: 13px; }
"""

FILTER_JS = """
function filt() {
  const q = document.getElementById('q').value.toLowerCase().split(/\\s+/).filter(Boolean);
  let shown = 0;
  for (const tr of document.querySelectorAll('tbody tr')) {
    const t = tr.textContent.toLowerCase();
    const hit = q.every(w => w.startsWith('flag:') ? tr.dataset.flags.includes(w.slice(5)) : t.includes(w));
    tr.style.display = hit ? '' : 'none';
    if (hit) shown++;
  }
  document.getElementById('count').textContent = shown + ' rows shown';
}
"""


def cell(value):
    if value is None:
        return "<td class='muted'>—</td>"
    text = str(value)
    if text.startswith(("[", "{")):
        try:
            parsed = json.loads(text)
            pretty = json.dumps(parsed, indent=None, separators=(", ", ": "))
            if len(pretty) > 400:
                pretty = pretty[:400] + " …"
            return f"<td><pre>{html.escape(pretty)}</pre></td>"
        except (json.JSONDecodeError, ValueError):
            pass
    if len(text) > 500:
        text = text[:500] + " …"
    return f"<td>{html.escape(text)}</td>"


def table_page(conn, table, title, cols):
    # quoted identifiers: bucket fields include SQL keywords (exchange has
    # "limit", varbit has "index")
    rows = conn.execute(
        f"SELECT {', '.join(chr(34) + c + chr(34) for c in cols)}"
        f' FROM "{table}"').fetchall()
    parts = [f"<html><head><meta charset='utf-8'><title>{title}</title>",
             f"<style>{STYLE}</style></head><body>",
             f"<h1>{title}</h1><p><a href='index.html'>&larr; dashboard</a></p>",
             "<input id='q' placeholder='filter (space = AND; flag:xyz filters flags)' oninput='filt()'>",
             f"<div class='count' id='count'>{len(rows)} rows</div>",
             "<table><thead><tr>" + "".join(f"<th>{c}</th>" for c in cols) + "</tr></thead><tbody>"]
    flag_idx = cols.index("flags") if "flags" in cols else None
    for row in rows:
        flags = (row[flag_idx] or "") if flag_idx is not None else ""
        parts.append(f"<tr data-flags='{html.escape(flags)}'>")
        for i, value in enumerate(row):
            if flag_idx is not None and i == flag_idx and value:
                parts.append(f"<td class='flag'>{html.escape(str(value))}</td>")
            else:
                parts.append(cell(value))
        parts.append("</tr>")
    parts.append(f"</tbody></table><script>{FILTER_JS}</script></body></html>")
    with open(os.path.join(HTML_OUT, f"{table}.html"), "w", encoding="utf-8") as f:
        f.write("".join(parts))
    return len(rows)


def bucket_tables(conn):
    """Auto-discover the raw bucket_* tables (harvest_buckets.py) with
    their columns — new buckets appear on the dashboard without edits."""
    out = {}
    for (name,) in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
            " AND name LIKE 'bucket_%' ORDER BY name").fetchall():
        cols = [r[1] for r in conn.execute(f'PRAGMA table_info("{name}")').fetchall()
                if r[1] != "src"]
        out[name] = (name.replace("bucket_", "raw: "), cols)
    return out


def main():
    os.makedirs(HTML_OUT, exist_ok=True)
    conn = kb.db()

    counts = {}
    for table, (title, cols) in TABLES.items():
        counts[table] = table_page(conn, table, title, cols)
    raw = bucket_tables(conn)
    for table, (title, cols) in raw.items():
        counts[table] = table_page(conn, table, title, cols)

    progress = conn.execute(
        "SELECT category, expected, harvested, flagged, source, updated_at, notes"
        " FROM progress ORDER BY category").fetchall()
    open_gaps = conn.execute(
        "SELECT COUNT(*) FROM gaps WHERE status='open'").fetchone()[0]

    parts = ["<html><head><meta charset='utf-8'><title>Iron Hub knowledge base</title>",
             f"<style>{STYLE}</style></head><body>",
             "<h1>Iron Hub knowledge base</h1>",
             "<p>Canonical store: <code>knowledge/knowledge.db</code> (SQLite). "
             "Regenerate these pages with <code>python3 tools/knowledge/report.py</code>. "
             f"<a href='gaps.html'><b>{open_gaps} open gaps</b></a> need review "
             "(also exported to <code>knowledge/GAPS.md</code>).</p>",
             "<h2>Progress</h2><table><thead><tr><th>category</th><th>expected</th>"
             "<th>harvested</th><th>flagged rows</th><th>source</th><th>updated</th>"
             "<th>notes</th></tr></thead><tbody>"]
    for cat, expected, harvested, flagged, source, updated, notes in progress:
        state = "ok" if expected and harvested and harvested >= expected and not flagged else ""
        parts.append(
            f"<tr><td class='{state}'>{html.escape(cat)}</td>"
            f"<td>{expected if expected is not None else '?'}</td>"
            f"<td>{harvested}</td>"
            f"<td class='{'flag' if flagged else 'ok'}'>{flagged}</td>"
            f"<td>{html.escape(source or '')}</td><td>{updated}</td>"
            f"<td>{html.escape(notes or '')}</td></tr>")
    parts.append("</tbody></table><h2>Tables</h2><ul>")
    for table, (title, _) in TABLES.items():
        parts.append(f"<li><a href='{table}.html'>{title}</a> "
                     f"<span class='count'>({counts[table]} rows)</span></li>")
    parts.append("</ul><h2>Raw wiki buckets</h2><p class='count'>Unprocessed"
                 " bucket stores the derived tables join from — harvested whole.</p><ul>")
    for table, (title, _) in raw.items():
        parts.append(f"<li><a href='{table}.html'>{title}</a> "
                     f"<span class='count'>({counts[table]} rows)</span></li>")
    parts.append("</ul></body></html>")
    with open(os.path.join(HTML_OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write("".join(parts))

    # GAPS.md — Luke's help-wanted checklist
    gaps = conn.execute(
        "SELECT category, subject, field, why, status, notes FROM gaps"
        " WHERE status='open' ORDER BY category, subject").fetchall()
    lines = ["# Knowledge-base gaps — help wanted",
             "",
             "Everything the harvest could NOT establish. Add what you know in the",
             "Notes column (or tell me in chat) and I'll fold it in; resolved rows",
             "move out on the next harvest.",
             ""]
    current = None
    for cat, subject, field, why, status, notes in gaps:
        if cat != current:
            lines.append(f"\n## {cat}\n")
            current = cat
        lines.append(f"- [ ] **{subject}** — {field}: {why}"
                     + (f"  \n  _notes: {notes}_" if notes else ""))
    lines.append(f"\n\n_{len(gaps)} open gaps · generated from knowledge.db_\n")
    with open(os.path.join(OUT, "GAPS.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    conn.close()
    print(f"dashboard + {len(TABLES)} table pages + {len(raw)} raw bucket pages"
          f" + GAPS.md ({open_gaps} open gaps)")


if __name__ == "__main__":
    main()
