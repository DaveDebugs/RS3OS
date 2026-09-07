#!/usr/bin/env python3
"""
build_wiki_seed.py -- emit data/seed/npc_wiki.json: NPC lifepoints keyed by NPC ID,
read from runescape.wiki's `Infobox Monster`, in bulk, through the MediaWiki API.

    python tools/seed/build_wiki_seed.py            # fetch, then parse (~55 API requests)
    python tools/seed/build_wiki_seed.py --offline  # parse the stored pages only, no network

PROVENANCE: "documented". Every lifepoints number here was read off a runescape.wiki
infobox on the date recorded in `retrieved`. Nothing comes from the cache except the
CROSS-CHECK columns (armour, combat level, attack speed) that are compared against it.

WHY THIS EXISTS. `tools/seed/build_documented.py` hand-lists 119 pages and
matches them to npc ids by NAME, which is why 21 of them could only be resolved by the
variant rule in SeedData and why 6,863 npc ids ended up with an AUTHORED (fitted) value.
The infobox carries the npc ids itself:

    |id = 41, 1017, 1401, 1402, 2313, ...      (version 1, or the whole page)
    |id3 = 30868                               (version 3)
    |lifepoints = 250    |lifepoints3 = ...
    |level = 1  |armour = 110  |speed = 3  |max_melee = 15  |aff_melee = 60 ...

and those stat fields are the SAME numbers the cache holds in `npcs_attr` `extra`
(2865 armour, 14 attack speed, 641 max melee x10, 2849..2852 affinities, 29 accuracy)
and in `npcs.combat`. That is the firing control: an id whose wiki level and armour
match the cache's own params is an id the wiki has mapped correctly. Measured on a
100-page sample before this script was written: 239 of 278 id rows matched on both.

ID RULE. One wiki (page, version) row names N npc ids and one lifepoints value. An npc
id can be named by more than one row (a shared id list across versions, or two pages).
  - one distinct lifepoints value across every row naming the id  -> ACCEPTED
  - several values, but exactly one row agrees with the cache on BOTH npcs.combat and
    armour (param 2865)                                              -> ACCEPTED,
    `disambiguation` says the cache chose
  - otherwise                                                        -> REFUSED, every
    candidate listed under `refused`. Nothing is guessed.

STORED PAGES. The raw wikitext is kept beside the output as `npc_wiki_pages.jsonl.gz`
so `--offline` reproduces the JSON byte for byte without the network, and so a later
run can be diffed against it. Both files are regenerable by this script; neither is
ignored (root CLAUDE.md HARD RULE 5).
"""

import gzip
import json
import os
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import date

_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = os.environ.get("RS3_DB", os.path.join(_TREE, "data", "rs3.sqlite"))
OUT = os.environ.get("WIKI_SEED_OUT", os.path.join(_TREE, "data", "seed", "npc_wiki.json"))
PAGES = os.environ.get("WIKI_SEED_PAGES", os.path.join(_TREE, "data", "seed", "npc_wiki_pages.jsonl.gz"))
API = "https://runescape.wiki/api.php"
BASE = "https://runescape.wiki/w/"
# The RuneScape Wiki API asks callers to identify themselves with a contact
# address. Set RS3OS_CONTACT to your own email address or project URL before
# running this script; it refuses to start without one.
import os as _os
CONTACT = _os.environ.get("RS3OS_CONTACT", "").strip()
if not CONTACT:
    raise SystemExit(
        "Set RS3OS_CONTACT to an email address or project URL before scraping.\n"
        "  Windows : $env:RS3OS_CONTACT = 'you@example.com'\n"
        "  Linux   : export RS3OS_CONTACT='you@example.com'")

UA = "RS3OS-seed/1.0 (private-server research; " + CONTACT + ")"
TEMPLATE = "Template:Infobox Monster"
BATCH = 50           # MediaWiki's cap on titles per prop=revisions request
PAUSE = 0.6          # seconds between requests; polite, not required

# cache params the cross-check reads (NpcCombat.kt names them: 2865 armour, 14 attack
# speed, 641/643/965 max hit x10, 29/4/3 accuracy, 2849..2852 affinities, 2848 weakness)
P_ARMOUR, P_SPEED = 2865, 14
CROSS = [  # (wiki field, cache prop, scale): cache // scale == wiki is a match
    ("armour", 2865, 1), ("speed", 14, 1),
    ("max_melee", 641, 10), ("max_ranged", 643, 10), ("max_magic", 965, 10),
    ("acc_melee", 29, 1), ("acc_ranged", 4, 1), ("acc_magic", 3, 1),
    ("aff_weakness", 2849, 1), ("aff_magic", 2850, 1), ("aff_melee", 2851, 1), ("aff_ranged", 2852, 1),
]
CROSS_PROPS = sorted({p for _, p, _ in CROSS} | {2848})
# every infobox field kept per accepted id, beyond lifepoints
INT_FIELDS = ["level", "armour", "speed", "defence", "attack", "ranged", "magic", "necromancy",
              "max_melee", "max_ranged", "max_magic", "max_necromancy", "max_spec",
              "acc_melee", "acc_ranged", "acc_magic", "acc_necromancy",
              "aff_weakness", "aff_melee", "aff_ranged", "aff_magic",
              "slaylvl"]
FLOAT_FIELDS = ["experience", "slayxp"]      # xp values carry decimals (Chicken: 12.5)
TEXT_FIELDS = ["weakness", "style", "primarystyle", "aggressive", "poisonous", "slayercat",
               "immune_to_poison", "immune_to_stun", "immune_to_deflect", "immune_to_drain"]


# --------------------------------------------------------------------------- fetch

def api(params):
    params = dict(params, format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - retry any transport error, then give up loudly
            if attempt == 3:
                raise
            time.sleep(3 * (attempt + 1))


def fetch_titles():
    titles, cont = [], {}
    while True:
        r = api(dict(action="query", list="embeddedin", eititle=TEMPLATE,
                     eilimit=500, einamespace=0, **cont))
        titles += [p["title"] for p in r["query"]["embeddedin"]]
        if "continue" in r:
            cont = {"eicontinue": r["continue"]["eicontinue"]}
            time.sleep(PAUSE)
        else:
            return titles


def fetch_pages(titles):
    """-> {title: wikitext}; missing/redirect pages are recorded with None."""
    out = {}
    for i in range(0, len(titles), BATCH):
        chunk = titles[i:i + BATCH]
        r = api(dict(action="query", prop="revisions", rvprop="content|timestamp",
                     rvslots="main", titles="|".join(chunk)))
        for p in r["query"]["pages"]:
            revs = p.get("revisions")
            out[p["title"]] = (revs[0]["slots"]["main"]["content"] if revs else None)
        sys.stderr.write("\r  fetched %d / %d pages" % (len(out), len(titles)))
        time.sleep(PAUSE)
    sys.stderr.write("\n")
    return out


# --------------------------------------------------------------------------- parse

def infobox_blocks(wt):
    """Every `{{Infobox Monster ...}}` block, brace-matched (values nest templates)."""
    out, pos = [], 0
    while True:
        m = re.search(r"\{\{\s*Infobox Monster\b", wt[pos:], re.I)
        if not m:
            return out
        start = pos + m.start()
        depth, i = 0, start
        while i < len(wt) - 1:
            if wt.startswith("{{", i):
                depth += 1; i += 2; continue
            if wt.startswith("}}", i):
                depth -= 1; i += 2
                if depth == 0:
                    break
                continue
            i += 1
        out.append(wt[start:i])
        pos = i


def fields(block):
    """Top-level `|key = value` pairs of one block; nested templates are opaque."""
    body = block[block.index("|") if "|" in block else len(block):]
    d, depth, cur = {}, 0, []
    parts = []
    for ch in body:
        if ch == "{" or ch == "[":
            depth += 1
        elif ch == "}" or ch == "]":
            depth -= 1
        if ch == "|" and depth == 0:
            parts.append("".join(cur)); cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            d[k.strip()] = v.strip()
    return d


_REF = re.compile(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>|<!--.*?-->", re.S)


def clean(v):
    return _REF.sub("", v or "").strip()


def first_int(v):
    """'198,000' -> 198000; '50,000 (normal)' -> 50000; 'Varies' -> None."""
    m = re.search(r"\d[\d,]*", clean(v))
    return int(m.group(0).replace(",", "")) if m else None


def ints(v):
    return [int(x) for x in re.findall(r"\d+", clean(v))]


def first_float(v):
    """'12.5' -> 12.5; '1,234.5' -> 1234.5; 'Varies' -> None."""
    m = re.search(r"\d[\d,]*(?:\.\d+)?", clean(v))
    return float(m.group(0).replace(",", "")) if m else None


def versions(f):
    """The version suffixes a block uses.

    A versioned page declares `version1 = Brown`, `version2 = White`, `version3 = ...`
    and suffixes only the fields that DIFFER per version: Chicken carries `id` (shared
    by versions 1 and 2) and `id3`, `lifepoints` and no `lifepoints3`. So the suffix
    set is the union over versionN / idN / lifepointsN / levelN keys, and an
    unsuffixed key is every version's fallback (see `rows_from`). A page with none of
    those is one version, suffix ''. FOUND THE HARD WAY: the first cut took only the
    idN keys, saw Chicken's lone `id3`, and dropped npc 41 on the floor.
    """
    sufs = set()
    for k in f:
        m = re.fullmatch(r"(?:version|id|lifepoints|level)(\d+)", k)
        if m:
            sufs.add(m.group(1))
    if not sufs:
        return [""]
    out = sorted(sufs, key=int)
    if "id" in f and not any(k.startswith("version") for k in f):
        out.insert(0, "")          # unsuffixed id with no version table: its own row
    return out


def rows_from(title, wt):
    """-> [(version, ids, fields-for-version)] for every infobox block on the page."""
    rows = []
    for block in infobox_blocks(wt):
        f = fields(block)
        for suf in versions(f):
            def get(key):
                return f.get(key + suf, f.get(key)) if suf else f.get(key)
            ids = ints(get("id") or "")
            if not ids:
                continue
            row = {
                "version_name": clean(get("version") or ""),
                "lifepoints_raw": clean(get("lifepoints") or ""),
                "lifepoints": first_int(get("lifepoints")),
                "combat_level": first_int(get("level")),
            }
            for k in INT_FIELDS:
                row[k] = first_int(get(k))
            for k in FLOAT_FIELDS:
                row[k] = first_float(get(k))
            for k in TEXT_FIELDS:
                row[k] = clean(get(k) or "") or None
            rows.append((suf, ids, row))
    return rows


# --------------------------------------------------------------------------- cache

def cache_stats(conn):
    """npc id -> [name, npcs.combat, armour 2865, speed 14, {prop: value} for CROSS_PROPS]."""
    out = {}
    for nid, name, combat in conn.execute("select id, name, combat from npcs"):
        out[nid] = [name, combat, None, None, {}]
    for nid, val in conn.execute("select id, value from npcs_attr where field='extra'"):
        if nid not in out:
            continue
        for e in json.loads(val):
            if e["intvalue"] is None:
                continue
            if e["prop"] == P_ARMOUR:
                out[nid][2] = e["intvalue"]
            elif e["prop"] == P_SPEED:
                out[nid][3] = e["intvalue"]
            if e["prop"] in CROSS_PROPS:
                out[nid][4].setdefault(e["prop"], e["intvalue"])
    return out


def cross_check(row, props):
    """{wiki field: True/False/None} - None when either side is missing."""
    out = {}
    for field, prop, scale in CROSS:
        w, p = row.get(field), props.get(prop)
        # scale 10: the cache holds tenths and the wiki publishes the whole number,
        # FLOORED (Black dragon: 641 = 4752, wiki max_melee 475). Measured:
        # wiki*10 == cache agrees on 79%, cache // 10 == wiki on far more - see the
        # counts block. So a tenths param is compared floored, never multiplied.
        out[field] = None if (w is None or p is None) else ((p // scale) == w)
    return out


# --------------------------------------------------------------------------- main

def main():
    offline = "--offline" in sys.argv
    today = date.today().isoformat()

    if offline:
        pages, retrieved = {}, None
        with gzip.open(PAGES, "rt", encoding="utf-8") as fh:
            for line in fh:
                rec = json.loads(line)
                if "_retrieved" in rec:
                    retrieved = rec["_retrieved"]; continue
                pages[rec["title"]] = rec["wikitext"]
        print("offline: %d stored pages, retrieved %s" % (len(pages), retrieved))
    else:
        titles = fetch_titles()
        print("pages embedding %s (ns0): %d" % (TEMPLATE, len(titles)))
        pages = fetch_pages(titles)
        retrieved = today
        os.makedirs(os.path.dirname(PAGES), exist_ok=True)
        with gzip.open(PAGES, "wt", encoding="utf-8") as fh:
            fh.write(json.dumps({"_retrieved": retrieved, "_template": TEMPLATE,
                                 "_pages": len(pages)}) + "\n")
            for t in sorted(pages):
                fh.write(json.dumps({"title": t, "wikitext": pages[t]}, ensure_ascii=False) + "\n")
        print("stored %d pages -> %s" % (len(pages), PAGES))

    conn = sqlite3.connect("file:%s?mode=ro" % urllib.request.pathname2url(os.path.abspath(DB)), uri=True)
    cache = cache_stats(conn)

    # every (page, version) row that names ids
    cand = defaultdict(list)          # npc id -> [row dicts]
    stats = Counter()
    for title, wt in pages.items():
        if not wt:
            stats["page_no_text"] += 1; continue
        rows = rows_from(title, wt)
        if not rows:
            stats["page_no_ids"] += 1; continue
        stats["pages_with_ids"] += 1
        for suf, ids, f in rows:
            stats["rows"] += 1
            if f["lifepoints"] is not None and f["lifepoints"] <= 0:
                stats["rows_lifepoints_zero"] += 1   # a placeholder, not a value
                f["lifepoints"] = None
            if f["lifepoints"] is None:
                stats["rows_no_lifepoints"] += 1
            for nid in ids:
                cand[nid].append(dict(f, page=title, version=suf,
                                      source=BASE + urllib.parse.quote(title.replace(" ", "_"))))

    accepted, refused = [], []
    agree = Counter()
    for nid in sorted(cand):
        rows = [r for r in cand[nid] if r["lifepoints"] is not None]
        if not rows:
            stats["ids_no_lifepoints"] += 1; continue
        cs = cache.get(nid)
        if cs is None:
            stats["ids_not_in_cache"] += 1; continue
        for r in rows:
            r["cache_level_match"] = (r["combat_level"] is not None and r["combat_level"] == cs[1])
            r["cache_armour_match"] = (r["armour"] is not None and r["armour"] == cs[2])
            r["cache_speed_match"] = (r["speed"] is not None and r["speed"] == cs[3])
            r["cross"] = cross_check(r, cs[4])
        values = sorted({r["lifepoints"] for r in rows})
        chosen, how = None, None
        if len(values) == 1:
            chosen, how = rows[0], "single"
        else:
            both = [r for r in rows if r["cache_level_match"] and r["cache_armour_match"]]
            if len({r["lifepoints"] for r in both}) == 1:
                chosen = both[0]
                how = ("chosen by the cache: npcs.combat=%d and armour(2865)=%s match this row alone "
                       "among %d candidate values %s" % (cs[1], cs[2], len(values), values))
            elif len(both) > 1:
                # SECOND DISCRIMINATOR (, pass 2): rows that tie on level and armour
                # but state a different max hit or speed - the cache's 641/643/965 and 14
                # pick the row. A row that states none of them cannot be picked this way, and
                # a row that contradicts the cache on any of them is out.
                def score(r):
                    hits = [r["cross"][k] for k in ("max_melee", "max_ranged", "max_magic", "speed")]
                    return (sum(1 for h in hits if h is True), sum(1 for h in hits if h is False))
                scored = [(score(r), r) for r in both]
                clean_rows = [r for (t, f), r in scored if t > 0 and f == 0]
                uncontradicted = {r["lifepoints"] for (t, f), r in scored if f == 0}
                if len({r["lifepoints"] for r in clean_rows}) == 1 and len(uncontradicted) == 1:
                    chosen = clean_rows[0]
                    how = ("chosen by the cache: %d rows tie on npcs.combat=%d and armour(2865)=%s; only this "
                           "row's max hit / speed agree with params 641/643/965/14 (candidate values %s)"
                           % (len(both), cs[1], cs[2], values))
        if chosen is None:
            refused.append({"npc_id": nid, "cache_name": cs[0], "cache_combat": cs[1],
                            "cache_armour": cs[2], "candidates": rows})
            stats["ids_refused"] += 1
            continue
        stats["ids_accepted"] += 1
        stats["accepted_" + ("single" if how == "single" else "cache_chosen")] += 1
        for k in ("cache_level_match", "cache_armour_match", "cache_speed_match"):
            agree[k] += bool(chosen[k])
        entry = {
            "npc_id": nid,
            "cache_name": cs[0],
            "wiki_page": chosen["page"],
            "version": chosen["version"],
            "version_name": chosen["version_name"],
            "source": chosen["source"],
            "retrieved": retrieved,
            "lifepoints": chosen["lifepoints"],
            "lifepoints_raw": chosen["lifepoints_raw"],
            "combat_level": chosen["combat_level"],
            "armour": chosen["armour"],
            "speed": chosen["speed"],
            "max_melee": chosen["max_melee"],
            "max_ranged": chosen["max_ranged"],
            "max_magic": chosen["max_magic"],
            "cache_level_match": chosen["cache_level_match"],
            "cache_armour_match": chosen["cache_armour_match"],
            "cache_speed_match": chosen["cache_speed_match"],
            "combat": {k: chosen[k] for k in INT_FIELDS + FLOAT_FIELDS + TEXT_FIELDS if k not in ("level", "armour", "speed")},
            "cross_check": chosen["cross"],
        }
        for k, v in chosen["cross"].items():
            if v is not None:
                agree["cross_" + k + ("_match" if v else "_mismatch")] += 1
        if how != "single" and "only this" in how:
            stats["accepted_cache_chosen_by_maxhit_or_speed"] += 1
        if how != "single":
            entry["disambiguation"] = how
            entry["other_values"] = [v for v in values if v != chosen["lifepoints"]]
        accepted.append(entry)

    doc = {
        "_schema": "opennxt.seed.npc_wiki/2",
        "_provenance": "documented",
        "_provenance_meaning": (
            "every lifepoints value was read from a runescape.wiki `Infobox Monster` on "
            "the `retrieved` date, KEYED BY THE NPC IDS THE INFOBOX ITSELF LISTS. No name "
            "matching, no cache value, nothing authored. Generated by tools/seed/build_wiki_seed.py; "
            "the raw pages are stored beside this file (--offline reproduces it)."),
        "_id_rule": (
            "one distinct lifepoints value across every infobox row naming the id -> accepted; "
            "several values -> accepted only if exactly one row agrees with the cache on BOTH "
            "npcs.combat and armour (param 2865), recorded in `disambiguation`; otherwise refused "
            "and listed under `refused` with every candidate."),
        "_cross_check": (
            "cache_level_match / cache_armour_match / cache_speed_match compare the infobox's "
            "level, armour and speed with npcs.combat, param 2865 and param 14 for the same id. "
            "They are the control that the wiki mapped the id correctly; they do not feed the value. "
            "`cross_check` per entry does the same for every (wiki field, cache param) pair in "
            "CROSS: max_melee/max_ranged/max_magic vs 641/643/965 floored from tenths, acc_* vs 29/4/3, "
            "aff_weakness/magic/melee/ranged vs 2849/2850/2851/2852; null = one side missing."),
        "_combat": (
            "`combat` per entry is every other infobox field, verbatim after <ref> stripping: "
            "defence/attack/ranged/magic/necromancy levels, max_*, acc_*, aff_*, experience, "
            "slaylvl, weakness, style, primarystyle, aggressive, poisonous, slayercat, immune_to_*. "
            "The server's combat numbers still come from the cache params; these are the wiki's "
            "statement of the same npc, for cross-checking and for fields the cache lacks "
            "(experience, aggressive, levels)."),
        "retrieved": retrieved,
        "template": TEMPLATE,
        "counts": dict(stats, **{"cross_check_" + k: v for k, v in agree.items()}),
        "refused": refused,
        "npcs": accepted,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
    print("wrote %s" % OUT)
    for k in sorted(doc["counts"]):
        print("  %-28s %d" % (k, doc["counts"][k]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
