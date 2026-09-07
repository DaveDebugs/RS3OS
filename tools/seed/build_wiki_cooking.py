#!/usr/bin/env python3
"""
build_wiki_cooking.py -- the WIKI layer for Cooking: what each raw food becomes, the Cooking
level it needs, the xp it pays, and the level at which it STOPS BURNING.

    python tools/seed/build_wiki_cooking.py            # writes data/seed/cooking_wiki.json + census
    python tools/seed/build_wiki_cooking.py --check    # rebuild into memory and diff, write nothing

WHY THIS FILE EXISTS AND WHAT IT IS NOT

The brief for this pass named `data/wiki/xp/raw/Module_Cooking_burn_level_table.lua` as the
burn-level source. IT HOLDS NO DATA. That module is a RENDERER: it runs a live
`bucket('recipe').where('Category:Food with conditional burn immunity')` query at page-render time
and formats the answer. Nothing in the 121 lines is a number. So the burn levels here come from
the only two sources that are actually on disk:

  1. `data/wiki/xp/raw/Cooking.wikitext` (revid 37159342) -- the article's own per-food tables,
     each with a "Stops burning at level" column. Read here as the FIRE stop level.
  2. `data/wiki/xp/raw/Module_Skill_calc_Cooking_data.lua` (revid 37105460) -- the skill
     calculator's data, which carries `noBurn` on 28 of its rows, plus `level`, `xp` and the raw
     `material` for every row. Read here as the RANGE stop level.

WHICH COLUMN IS WHICH IS **PLAUSIBLE**, NOT CONFIRMED. The two disagree where both speak
(Crayfish: article 34, calc noBurn 32) and a range burns less than a fire, so the LOWER number is
attributed to the range and the higher to the fire. `Module:Cooking burn level table`'s header row
- "Range | Fire | Cook-o-matic 25 | Gauntlets (range) | Gauntlets (fire)" - is the corroboration
that the two really are different numbers for the same food, nothing more. What settles it: cook
one food on a range and one on a fire with the same account at a level between the two, and see
which one burns.

THE LEVEL AND THE XP IN THIS SEED ARE NOT THE SERVER'S FIRST CHOICE. `content/impl/Cooking.kt`
resolves them CACHE > WIKI, because `data/rs3.sqlite` holds them GROUNDED on the cooked item:
param 2655 = the raw item id, param 2645 = the Cooking level, param 2697 = the xp in tenths,
param 2640 = 16 (the cooking recipe marker). 407 recipes carry all of it. This seed's level and
xp are here so the two sources can be cross-checked against each other, and so a food
the cache does not carry still has a number. The BURN LEVELS have no cache source at all and are
the reason the seed exists.

PROVENANCE: "documented" - every number is read off a wiki page at the revision id
`data/wiki/xp/manifest.json` records. Nothing is typed in.
"""
import io
import json
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
RAW = os.path.join(ROOT, "data", "wiki", "xp", "raw")
MANIFEST = os.path.join(ROOT, "data", "wiki", "xp", "manifest.json")
SEED = os.path.join(ROOT, "data", "seed")
BOOTTEST_SEED = os.path.join(ROOT, "rig", "boottest", "data", "seed")
SQLITE = os.path.join(ROOT, "data", "rs3.sqlite")

# The Lua literal reader is build_wiki_xp's; importing it rather than copying it is the point of
# HARD RULE "fix the class, not the instance" - one reader, one set of bugs.
import build_wiki_xp as wx  # noqa: E402


# ------------------------------------------------------------------ Cooking.wikitext tables
def article_rows(text):
    """Every row of every wikitable in the article whose header carries "Stops burning".

    Returns {product name: {"level": int|None, "stop": int|None, "xp": float|None, "table": n}}.

    The tables differ in width (the Meat table has 6 columns, the Fish table 9), so the column
    INDEX is read out of the header rather than assumed. The item column is `colspan="2"` in the
    header and one `{{plinkt|...}}` cell in the body, which is why a header index maps straight
    onto a body index.
    """
    out = {}
    tables = 0
    for m in re.finditer(r"\{\|(.*?)\n\|\}", text, re.S):
        body = m.group(1)
        rows = re.split(r"\n\|-", body)
        if not rows:
            continue
        header = rows[0]
        heads = []
        for line in header.split("\n"):
            line = line.strip()
            if not line.startswith("!"):
                continue
            for cell in line[1:].split("!!"):
                heads.append(cell)
        if not any("Stops burning" in h for h in heads):
            continue
        tables += 1

        def idx(pred):
            for i, h in enumerate(heads):
                if pred(h):
                    return i
            return None

        i_level = idx(lambda h: re.search(r"\blevel\b", h, re.I) and "Stops" not in h and "burning" not in h)
        i_stop = idx(lambda h: "Stops burning" in h)
        i_xp = idx(lambda h: "xperience" in h)
        for row in rows[1:]:
            cells = []
            for line in row.split("\n"):
                line = line.strip()
                if line.startswith("|") and not line.startswith("|}") and not line.startswith("|-"):
                    cells.append(line[1:].strip())
            if not cells:
                continue
            pm = re.match(r"\{\{[Pp]linkt\|([^}|]+)", cells[0])
            if not pm:
                continue
            name = pm.group(1).strip()

            def num(i, cast):
                if i is None or i >= len(cells):
                    return None
                v = re.sub(r"<[^>]+>|\{\{[^}]*\}\}|\[\[|\]\]", " ", cells[i])
                # "30 (33)" = base (bonfire); the first number is the base
                mm = re.search(r"-?\d+(?:\.\d+)?", v)
                return cast(mm.group(0)) if mm else None

            never = i_stop is not None and i_stop < len(cells) and "Never" in cells[i_stop]
            out.setdefault(name, dict(
                level=num(i_level, int),
                stop=(None if never else num(i_stop, int)),
                never_stops=never,
                xp=num(i_xp, float),
                table=tables,
            ))
    return out, tables


# ------------------------------------------------------------------ the skill calculator's data
def calc_rows():
    """[(category, product, level, xp, raw material name, noBurn)] from Module:Skill calc/Cooking/data."""
    text = wx.read(os.path.join(RAW, "Module_Skill_calc_Cooking_data.lua"))
    cats = wx.skill_calc("Cooking", text, [])
    out = []
    for cat, rows in cats.items():
        for r in rows:
            if not isinstance(r, dict):
                continue
            mat = r.get("material")
            raw = None
            if isinstance(mat, list) and len(mat) >= 2 and isinstance(mat[-1], str):
                raw = mat[-1]
            out.append(dict(category=cat, product=r.get("name"), title=r.get("title"),
                            level=r.get("level"), xp=r.get("xp"), raw=raw,
                            no_burn=r.get("noBurn"), members=bool(r.get("members"))))
    return out


# ------------------------------------------------------------------ item ids
def item_ids():
    """name -> the LOWEST item id carrying it. Lowest because the low ids are the originals and the
    high duplicates ('Raw crayfish' 43853, 'Raw rabbit' 43846) are later reskins."""
    by_name = {}
    if not os.path.exists(SQLITE):
        return by_name
    db = sqlite3.connect(SQLITE)
    for i, n in db.execute("SELECT id, name FROM items WHERE name IS NOT NULL AND name <> ''"):
        if n not in by_name or i < by_name[n]:
            by_name[n] = i
    db.close()
    return by_name


def build():
    manifest = json.load(io.open(MANIFEST, encoding="utf-8"))

    def prov(title):
        m = manifest.get(title, {})
        return dict(title=title, revid=m.get("revid"), retrieved=m.get("retrieved"),
                    file=m.get("file"),
                    url="https://runescape.wiki/w/" + title.replace(" ", "_"))

    article, n_tables = article_rows(wx.read(os.path.join(RAW, "Cooking.wikitext")))
    calc = calc_rows()
    ids = item_ids()

    foods = []
    for r in calc:
        product, raw = r["product"], r["raw"]
        if not product or not raw:
            continue
        a = article.get(product) or {}
        raw_id, product_id = ids.get(raw), ids.get(product)
        # The name derivation for the burnt item. It is REFUTED for Raw rabbit by the protocol
        # (observed: three burns give item 2146 "Burnt meat", not 7222
        # "Burnt rabbit"), so it is emitted as a CANDIDATE and never as the answer; Cooking.kt's
        # map outranks it.
        noun_from_product = re.sub(r"^(Cooked|Roast|Baked|Poorly-cooked)\s+", "", product).strip()
        noun_from_raw = re.sub(r"^Raw\s+", "", raw).strip()
        candidates = []
        for noun in (noun_from_product, noun_from_raw):
            n = "Burnt " + noun[0].lower() + noun[1:] if noun else None
            if n and n in ids and n not in [c["name"] for c in candidates]:
                candidates.append(dict(name=n, id=ids[n]))
        foods.append(dict(
            category=r["category"],
            product=product, product_id=product_id,
            raw=raw, raw_id=raw_id,
            level=r["level"], level_article=a.get("level"),
            xp=r["xp"], xp_tenths=(None if r["xp"] is None else int(round(float(r["xp"]) * 10))),
            xp_article=a.get("xp"),
            # attribution, argued in the module docstring: the article's single
            # "Stops burning at level" column is read as the FIRE level, the calculator's noBurn
            # as the RANGE level. Where only one speaks, the other is null.
            stop_burn_fire=a.get("stop"),
            stop_burn_range=r["no_burn"],
            never_stops_burning=bool(a.get("never_stops")),
            burnt_candidates=candidates,
            members=r["members"],
            in_article=bool(a),
        ))

    # one row per (raw, product); the calculator repeats a few products across categories
    seen, uniq = set(), []
    for f in foods:
        k = (f["raw_id"], f["product_id"], f["level"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(f)
    uniq.sort(key=lambda f: (f["raw_id"] if f["raw_id"] is not None else 1 << 30, f["level"] or 0))

    doc = dict(
        provenance="documented: runescape.wiki, revision ids per source below; nothing typed in",
        built_by="tools/seed/build_wiki_cooking.py",
        note=("level and xp are ALSO in the cache (cooked item params 2655/2645/2697 with 2640=16) "
              "and Cooking.kt prefers the cache; the burn levels have no cache source and are why "
              "this seed exists. The fire/range attribution of the two stop columns is - "
              "see the module docstring."),
        sources=dict(
            article=prov("Cooking"),
            calculator=prov("Module:Skill calc/Cooking/data"),
            burn_table_renderer_holds_no_data=prov("Module:Cooking burn level table"),
        ),
        counts=dict(
            foods=len(uniq),
            with_stop_burn_fire=sum(1 for f in uniq if f["stop_burn_fire"] is not None),
            with_stop_burn_range=sum(1 for f in uniq if f["stop_burn_range"] is not None),
            with_raw_id=sum(1 for f in uniq if f["raw_id"] is not None),
            with_product_id=sum(1 for f in uniq if f["product_id"] is not None),
            with_burnt_candidate=sum(1 for f in uniq if f["burnt_candidates"]),
            article_tables_with_a_stop_column=n_tables,
        ),
        foods=uniq,
    )
    return doc


def main():
    doc = build()
    text = json.dumps(doc, indent=1, sort_keys=True) + "\n"
    if "--check" in sys.argv:
        old = io.open(os.path.join(SEED, "cooking_wiki.json"), encoding="utf-8").read()
        print("IDENTICAL" if old == text else "DIFFERS from data/seed/cooking_wiki.json")
        return
    for d in (SEED, BOOTTEST_SEED):
        if not os.path.isdir(d):
            print("  skipped (no such directory): %s" % d)
            continue
        io.open(os.path.join(d, "cooking_wiki.json"), "w", encoding="utf-8", newline="\n").write(text)
        print("  wrote %s" % os.path.join(d, "cooking_wiki.json"))
    c = doc["counts"]
    print("  %d foods; %d with a fire stop level, %d with a range stop level, %d with a burnt candidate"
          % (c["foods"], c["with_stop_burn_fire"], c["with_stop_burn_range"], c["with_burnt_candidate"]))
    for f in doc["foods"]:
        if f["raw_id"] in (13435, 3226):
            print("    %-16s -> %-16s level %-3s xp %-6s fire %-5s range %-5s burnt %s"
                  % (f["raw"], f["product"], f["level"], f["xp"], f["stop_burn_fire"],
                     f["stop_burn_range"], [c["name"] for c in f["burnt_candidates"]]))


if __name__ == "__main__":
    main()
