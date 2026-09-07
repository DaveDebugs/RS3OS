#!/usr/bin/env python3
"""
build_bestiary_seed.py -- emit data/seed/npc_bestiary.json from Jagex's own Bestiary API.

    python tools/seed/build_bestiary_seed.py            # fetch (~10k ids, 8 threads, ~15 min), then parse
    python tools/seed/build_bestiary_seed.py --offline  # parse the stored responses only

PROVENANCE: "documented" - but DOCUMENTED BY JAGEX, not by a wiki editor. The endpoint

    https://secure.runescape.com/m=itemdb_rs/bestiary/beastData.json?beastid=<npc id>

is the data behind the RuneScape Bestiary web app Jagex retired in 2015; the JSON endpoint
still answers. Per npc id it publishes: name, description, level, lifepoints,
attack/defence/magic/ranged levels, xp per kill, weakness, size, aggressive, poisonous,
attackable, members, slayer category, areas, and ANIMATIONS - `death`, `attack` and for
some npcs `range`. Those animation ids are the ones the NXT cache does not carry, and the
the observed animation table agrees with them on every kill it covers:
836 / 6182 / 4659 for Warrior woman 15, Goblins 4265+4275, Moss giants 112/1587/1588/4688.

WHICH IDS. Every npc id the cache marks attackable (an 'Attack' action), every id with
npcs.combat > 0, every id the wiki layer names, and every id the animation table names - the
union - not all 32,762, because a non-combat npc has no bestiary row and the endpoint costs
~0.7 s per id. An id that returns an empty body is recorded as absent, not guessed.

CROSS-CHECKS, so the id mapping can be trusted the same way the wiki's was:
  - `level` against npcs.combat and `name` against npcs.name (the cache);
  - `lifepoints` against the wiki-by-id layer (data/seed/npc_wiki.json);
  - `animations.death` against the reference client's kill candidates (data/seed/npc_anims_observed.tsv).
Nothing here feeds a value from the cross-check; it only counts agreement.

STORED RESPONSES: data/seed/npc_bestiary_raw.jsonl.gz beside the output, one line per id
(the body, or null), so `--offline` reproduces the JSON and a later pull can be diffed.
"""

import csv
import gzip
import json
import os
import sqlite3
import sys
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date

_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = os.environ.get("RS3_DB", os.path.join(_TREE, "data", "rs3.sqlite"))
OUT = os.path.join(_TREE, "data", "seed", "npc_bestiary.json")
RAW = os.path.join(_TREE, "data", "seed", "npc_bestiary_raw.jsonl.gz")
WIKI = os.path.join(_TREE, "data", "seed", "npc_wiki.json")
OBSERVED = os.path.join(_TREE, "data", "seed", "npc_anims_observed.tsv")
URL = "https://secure.runescape.com/m=itemdb_rs/bestiary/beastData.json?beastid=%d"
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

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/128.0 Safari/537.36 RS3OS-seed/1.0 (private-server research; " + CONTACT + ")",
      "Accept": "application/json"}
THREADS = 8


def fetch(nid):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(URL % nid, headers=UA), timeout=30) as r:
                body = r.read().decode("utf-8", "replace").strip()
            if not body:
                return nid, None
            return nid, json.loads(body)
        except Exception:  # noqa: BLE001 - retry any transport/JSON error, then give up loudly
            if attempt == 3:
                return nid, {"_error": True}
            time.sleep(2 * (attempt + 1))


def wanted_ids(conn):
    ids = set()
    for (i,) in conn.execute("select id from npcs where actions_0='Attack' or actions_1='Attack' or actions_2='Attack' or combat > 0"):
        ids.add(i)
    if os.path.exists(WIKI):
        for e in json.load(open(WIKI, encoding="utf-8"))["npcs"]:
            ids.add(e["npc_id"])
    if os.path.exists(OBSERVED):
        for r in csv.DictReader((l for l in open(OBSERVED, encoding="utf-8") if not l.startswith("#")), delimiter="\t"):
            ids.add(int(r["npc_id"]))
    return sorted(ids)


def main():
    offline = "--offline" in sys.argv
    conn = sqlite3.connect("file:%s?mode=ro" % urllib.request.pathname2url(os.path.abspath(DB)), uri=True)
    cache = {i: (n, c) for i, n, c in conn.execute("select id, name, combat from npcs")}

    if offline:
        raw, retrieved = {}, None
        with gzip.open(RAW, "rt", encoding="utf-8") as fh:
            for line in fh:
                rec = json.loads(line)
                if "_retrieved" in rec:
                    retrieved = rec["_retrieved"]; continue
                raw[rec["id"]] = rec["body"]
        print("offline: %d stored responses, retrieved %s" % (len(raw), retrieved))
    else:
        ids = wanted_ids(conn)
        print("fetching %d ids with %d threads" % (len(ids), THREADS))
        raw, retrieved, done = {}, date.today().isoformat(), 0
        t0 = time.time()
        with ThreadPoolExecutor(THREADS) as pool:
            for nid, body in as_completed_iter(pool, ids):
                raw[nid] = body
                done += 1
                if done % 250 == 0:
                    sys.stderr.write("\r  %d / %d  (%.0fs)" % (done, len(ids), time.time() - t0))
        sys.stderr.write("\n")
        with gzip.open(RAW, "wt", encoding="utf-8") as fh:
            fh.write(json.dumps({"_retrieved": retrieved, "_url": URL, "_ids": len(ids)}) + "\n")
            for nid in sorted(raw):
                fh.write(json.dumps({"id": nid, "body": raw[nid]}, ensure_ascii=False) + "\n")
        print("stored %d responses -> %s" % (len(raw), RAW))

    wiki = {}
    if os.path.exists(WIKI):
        wiki = {e["npc_id"]: e["lifepoints"] for e in json.load(open(WIKI, encoding="utf-8"))["npcs"]}
    kills = {}
    if os.path.exists(OBSERVED):
        for r in csv.DictReader((l for l in open(OBSERVED, encoding="utf-8") if not l.startswith("#")), delimiter="\t"):
            if r["kill_candidate"] == "1":
                kills.setdefault(int(r["npc_id"]), set()).add(int(r["anim0"]))

    stats, entries = Counter(), []
    for nid in sorted(raw):
        body = raw[nid]
        if body is None:
            stats["ids_absent"] += 1; continue
        if body.get("_error"):
            stats["ids_fetch_error"] += 1; continue
        if body.get("id") != nid:
            stats["ids_echoed_wrong_id"] += 1; continue
        stats["ids_present"] += 1
        anims = body.get("animations") or {}
        cname, ccombat = cache.get(nid, (None, None))
        try:
            xp = float(body.get("xp")) if body.get("xp") not in (None, "") else None
        except ValueError:
            xp = None
        e = {
            "npc_id": nid,
            "name": body.get("name"),
            "cache_name": cname,
            "level": body.get("level"),
            "lifepoints": body.get("lifepoints"),
            "attack": body.get("attack"), "defence": body.get("defence"),
            "magic": body.get("magic"), "ranged": body.get("ranged"),
            "xp": xp,
            "weakness": body.get("weakness"),
            "size": body.get("size"),
            "aggressive": body.get("aggressive"),
            "poisonous": body.get("poisonous"),
            "attackable": body.get("attackable"),
            "members": body.get("members"),
            "slayercat": body.get("slayercat"),
            "areas": body.get("areas") or [],
            "death_anim": anims.get("death"),
            "attack_anim": anims.get("attack"),
            "range_anim": anims.get("range"),
            "cache_level_match": (ccombat is not None and body.get("level") == ccombat),
            "cache_name_match": (cname is not None and body.get("name") == cname),
            "wiki_lifepoints": wiki.get(nid),
            "wiki_lifepoints_match": (nid in wiki and body.get("lifepoints") == wiki[nid]),
            "observed_death_match": (nid in kills and anims.get("death") in kills[nid]) if nid in kills else None,
        }
        entries.append(e)
        stats["with_death_anim"] += e["death_anim"] is not None
        stats["with_attack_anim"] += e["attack_anim"] is not None
        stats["with_lifepoints"] += e["lifepoints"] is not None
        stats["cache_level_match"] += e["cache_level_match"]
        stats["cache_name_match"] += e["cache_name_match"]
        if nid in wiki:
            stats["wiki_comparable"] += 1
            stats["wiki_lifepoints_match"] += e["wiki_lifepoints_match"]
        if nid in kills:
            stats["observed_comparable"] += 1
            stats["observed_death_match"] += bool(e["observed_death_match"])
        stats["aggressive_true"] += e["aggressive"] is True

    doc = {
        "_schema": "opennxt.seed.npc_bestiary/1",
        "_provenance": "documented",
        "_provenance_meaning": (
            "every value was returned by Jagex's Bestiary API for that npc id on the `retrieved` "
            "date (secure.runescape.com/m=itemdb_rs/bestiary/beastData.json?beastid=N). Official, "
            "id-keyed, no matching of any kind. Generated by tools/seed/build_bestiary_seed.py; the raw "
            "responses are stored beside this file (--offline reproduces it)."),
        "_cross_check": (
            "cache_level_match / cache_name_match compare the row with npcs.combat / npcs.name; "
            "wiki_lifepoints_match with data/seed/npc_wiki.json; observed_death_match with the "
            "observation's kill candidates in data/seed/npc_anims_observed.tsv (null = not observed). "
            "They are controls on the id mapping; they do not feed any value."),
        "retrieved": retrieved,
        "url": URL,
        "counts": dict(stats),
        "npcs": entries,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
    print("wrote %s" % OUT)
    for k in sorted(doc["counts"]):
        print("  %-26s %d" % (k, doc["counts"][k]))
    return 0


def as_completed_iter(pool, ids):
    futures = [pool.submit(fetch, i) for i in ids]
    for f in as_completed(futures):
        yield f.result()


if __name__ == "__main__":
    sys.exit(main())
