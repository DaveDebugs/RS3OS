#!/usr/bin/env python3
"""
build_wiki_drops.py -- emit data/seed/drops_wiki.json: drop tables for every monster page
whose infobox names npc ids, parsed OFFLINE from the wiki pages already stored by
tools/seed/build_wiki_seed.py (data/seed/npc_wiki_pages.jsonl.gz).

    python tools/seed/build_wiki_drops.py

PROVENANCE: "documented" - the same runescape.wiki pages the lifepoints came from, so the
retrieval date is the pages file's. Nothing is fetched here.

WHAT IS TRANSCRIBED, the same rules as tools/seed/build_drops.py (the 30-monster hand file):
  * `{{DropsLine|name=..|quantity=..|rarity=..}}` rows become drop lines. Quantity and
    rarity are kept VERBATIM ("3-15", "1,000", "64/128", "Always", "Common"); a fraction
    is parsed alongside when the string is one, `Always` is 1/1, a word rarity has no
    numbers. The category is the nearest heading: `100%` -> always, `Tertiary` ->
    tertiary, `Charms` -> tertiary, anything else -> main.
  * `{{CharmDropTable|gold=..|green=..|crimson=..|blue=..}}` becomes four item lines
    (the charms are items and the rates are stated).
  * Every other drop template - herb/gem/seed/rare-drop tables, spirit gems, mimic, effigy,
    summoning, clue, dungeon tables, beginner weapons - is a REFERENCE line `@<slug>` with
    the template's own `rarity` when it has one, never an expansion.
  * Item names resolve against `items.name` (case-insensitive); zero or several ids are
    recorded as-is.
  * npc ids come from the page's infobox (`|id=`), the same id-keyed rule as npc_wiki.json -
    NOT a name match - so `npc_match_ambiguous` is false and the loader can trust the ids.

Schema is the hand file's (`monsters[]` with the same drop-line fields), under
`opennxt.seed.drops_wiki/1`; DropData loads it BELOW the hand file, so the 30 hand-checked
monsters keep their lines and everything else gains one.
"""

import gzip
import json
import os
import re
import sqlite3
import sys
import urllib.parse
import urllib.request
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_wiki_seed as seed  # noqa: E402  (infobox_blocks, fields, versions, ints, clean)

_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = os.environ.get("RS3_DB", os.path.join(_TREE, "data", "rs3.sqlite"))
PAGES = os.path.join(_TREE, "data", "seed", "npc_wiki_pages.jsonl.gz")
OUT = os.path.join(_TREE, "data", "seed", "drops_wiki.json")
BASE = "https://runescape.wiki/w/"

FRACTION = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*/\s*([\d,]+(?:\.\d+)?)")
HEADING = re.compile(r"^(={2,5})\s*(.*?)\s*\1\s*$", re.M)
_NOTE = re.compile(r"\{\{DropNote\|(.*?)\}\}", re.S)
_LINK = re.compile(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]")

CHARMS = [("gold", "Gold charm"), ("green", "Green charm"), ("crimson", "Crimson charm"), ("blue", "Blue charm")]


def parse_rarity(s):
    if s.strip().lower() == "always":
        return 1.0, 1.0
    m = FRACTION.match(s)
    if not m:
        return None, None
    return float(m.group(1)), float(m.group(2).replace(",", ""))


def plain(v):
    """Strip refs, DropNote wrappers, wiki links and the block's own closing braces from a value."""
    v = seed.clean(v or "")
    v = _NOTE.sub(lambda m: m.group(1).rsplit("|", 1)[-1], v)   # {{DropNote|name=x|text}} -> text
    v = _LINK.sub(lambda m: m.group(1), v)
    v = re.sub(r"\{\{[^{}]*\}\}", "", v)          # any remaining inline template
    v = v.strip()
    while v.endswith("}}"):                          # the last parameter carries the template's close
        v = v[:-2].strip()
    return v.strip()


def slug(name):
    return "@" + re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def category_for(heading):
    h = (heading or "").lower()
    if "100%" in h or "always" in h:
        return "always"
    if "tertiary" in h or "charm" in h:
        return "tertiary"
    return "main"


def templates(wt):
    """Every top-level `{{...}}` block in document order as (start, name, block)."""
    out, i = [], 0
    n = len(wt)
    while i < n - 1:
        if wt.startswith("{{", i):
            depth, j = 0, i
            while j < n - 1:
                if wt.startswith("{{", j):
                    depth += 1; j += 2; continue
                if wt.startswith("}}", j):
                    depth -= 1; j += 2
                    if depth == 0:
                        break
                    continue
                j += 1
            block = wt[i:j]
            m = re.match(r"\{\{\s*([^|}]+)", block)
            out.append((i, (m.group(1).strip() if m else ""), block))
            i = j
        else:
            i += 1
    return out


def page_drops(wt):
    """-> [line dict] from one page's wikitext, categorised by the nearest heading."""
    heads = [(m.start(), m.group(2)) for m in HEADING.finditer(wt)]
    def heading_at(pos):
        h = None
        for p, t in heads:
            if p < pos:
                h = t
            else:
                break
        return h
    lines = []
    for pos, name, block in templates(wt):
        low = name.lower()
        cat = category_for(heading_at(pos))
        f = seed.fields(block)
        if low == "dropsline":
            item = plain(f.get("name", ""))
            if not item:
                continue
            note = plain(f.get("raritynotes", "")) or plain(f.get("quantitynotes", "")) or plain(f.get("namenotes", "")) or None
            if item.lower() == "nothing":                # the wiki's explicit empty roll, as the hand file writes it
                lines.append(dict(category=cat, item="@nothing", quantity="", rarity=plain(f.get("rarity", "")), note=note, ref=True))
                continue
            lines.append(dict(category=cat, item=item, quantity=plain(f.get("quantity", "")),
                              rarity=plain(f.get("rarity", "")), note=note))
        elif low == "charmdroptable":
            qty = plain(f.get("quantity", "")) or "1"
            for key, item in CHARMS:
                if key in f:
                    lines.append(dict(category="tertiary", item=item, quantity=qty, rarity=plain(f[key]), note="charm"))
        elif ("drop" in low and low not in ("droplogproject", "dropnote", "dropstablehead", "dropstablebottom", "average drop value")) \
                or low in ("rare drop table",):
            lines.append(dict(category=cat, item=slug(name), quantity="", rarity=plain(f.get("rarity", "")),
                              note="wiki template {{%s}}" % name, ref=True))
    return lines


def main():
    pages, retrieved = {}, None
    with gzip.open(PAGES, "rt", encoding="utf-8") as fh:
        for line in fh:
            rec = json.loads(line)
            if "_retrieved" in rec:
                retrieved = rec["_retrieved"]; continue
            if rec.get("wikitext"):
                pages[rec["title"]] = rec["wikitext"]
    con = sqlite3.connect("file:%s?mode=ro" % urllib.request.pathname2url(os.path.abspath(DB)), uri=True)
    name_ids = {}
    for i, n in con.execute("SELECT id, name FROM items WHERE name IS NOT NULL"):
        name_ids.setdefault(n.strip('"').lower(), []).append(i)
    npc_names = {i: n for i, n in con.execute("SELECT id, name FROM npcs")}

    monsters, stats = [], Counter()
    for title in sorted(pages):
        wt = pages[title]
        if "DropsLine" not in wt and "CharmDropTable" not in wt:
            stats["pages_without_drop_lines"] += 1; continue
        ids = set()
        for blk in seed.infobox_blocks(wt):
            f = seed.fields(blk)
            for suf in seed.versions(f):
                ids |= set(seed.ints((f.get("id" + suf, f.get("id")) if suf else f.get("id")) or ""))
        ids = sorted(i for i in ids if i in npc_names)
        if not ids:
            stats["pages_with_drops_but_no_ids"] += 1; continue
        raw = page_drops(wt)
        if not raw:
            stats["pages_with_templates_but_no_lines"] += 1; continue
        rows = []
        for ln in raw:
            num, den = parse_rarity(ln["rarity"])
            row = {"category": ln["category"], "item": ln["item"], "quantity": ln["quantity"],
                   "rarity": ln["rarity"], "rarity_num": num, "rarity_den": den, "note": ln.get("note")}
            if ln.get("ref"):
                row["is_table_ref"] = True
                row["item_ids"] = []
                stats["table_refs"] += 1
            else:
                matched = name_ids.get(ln["item"].lower(), [])
                row["item_ids"] = matched
                row["item_match_ambiguous"] = len(matched) > 1
                if not matched:
                    stats["unresolved_item_names"] += 1
                if num is None:
                    stats["word_rarities"] += 1
            stats["by_category_" + ln["category"]] += 1
            rows.append(row)
        stats["drop_lines"] += len(rows)
        stats["monsters"] += 1
        monsters.append({
            "name": title,
            "wiki_page": title,
            "npc_ids": ids,
            "npc_id_space": "wiki infobox ids (id-keyed, no name match)",
            "npc_match_ambiguous": False,
            "drops": rows,
            "note": None,
            "provenance": "documented",
            "source": BASE + urllib.parse.quote(title.replace(" ", "_")),
            "retrieved": retrieved,
        })
    stats["npc_ids_covered"] = len({i for m in monsters for i in m["npc_ids"]})
    out = {
        "_schema": "opennxt.seed.drops_wiki/1",
        "_provenance": "documented",
        "_provenance_meaning": (
            "every drop line was parsed from the runescape.wiki page in `source`, as stored on "
            "`retrieved` by tools/seed/build_wiki_seed.py; quantities and rarities are verbatim wiki "
            "strings with a parsed fraction alongside when parseable; shared sub-tables are "
            "references (@...), not expansions; npc ids are the infobox's own"),
        "counts": {k: stats[k] for k in sorted(stats)},
        "monsters": monsters,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
    print("wrote %s" % OUT)
    for k in sorted(stats):
        print("  %-36s %d" % (k, stats[k]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
