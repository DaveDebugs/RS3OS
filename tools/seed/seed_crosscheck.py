#!/usr/bin/env python3
"""
seed_crosscheck.py -- compare the cache-sourced seed against the wiki-sourced seed.

RULE: this script NEVER edits either source. It only reports. A disagreement is
a finding, not a bug to be smoothed over.

Two comparisons:
  A. LIFEPOINTS -- cache boss health-bar maxima (struct_param prop 8850) vs
     documented lifepoints (runescape.wiki infobox).
  B. COMBAT LEVEL -- npcs.combat vs documented combat level.

Comparison A is joined by NAME, because the boss structs contain no resolvable
npc reference (verified: prop 8866 and DT props 2118-2121 resolve to unrelated
npcs in both id spaces). The cache struct name and the wiki/db npc name are not
always spelled the same, so a small explicit alias table is used. The alias
table is a MAPPING decision, not a data value -- it is printed so it can be
audited, and it never changes a number.

Comparison B is joined by npc game_id (npcs.game_id), taken from the documented
file's name-match. Where a name maps to several game_ids every distinct cache
combat level is reported.
"""

import json
import os
import sys
from collections import OrderedDict


# Paths derive from this file's own location, so the repository can live
# anywhere. Each one can be overridden by the environment variable beside it.
_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEED = os.environ.get("SEED_DIR", os.path.join(_TREE, "data", "seed"))
CACHE_JSON = os.path.join(SEED, "npc_cache.json")
DOC_JSON = os.path.join(SEED, "npc_documented.json")
REPORT = os.path.join(SEED, "crosscheck.json")

# cache struct name (prop 8849) -> npcs.name (== the documented file's name).
# Read from the cache JSON header so there is exactly ONE alias table in the
# repo (defined in seed_from_cache.py). Spelling only; never a number.
ALIASES = {}


def norm(s):
    if not s:
        return None
    s = s.strip().lower()
    return ALIASES.get(s, s)


def main():
    for p in (CACHE_JSON, DOC_JSON):
        if not os.path.exists(p):
            sys.exit("missing %s -- run seed_from_cache.py / build_documented.py first" % p)
    cache = json.load(open(CACHE_JSON))
    doc = json.load(open(DOC_JSON))
    ALIASES.update({k: v.strip().lower()
                    for k, v in cache.get("_name_aliases", {}).items()})

    # ---- cache side: name -> sorted list of boss LP maxima ----
    cache_lp = OrderedDict()
    cache_struct = {}
    for b in cache["boss_encounters"]:
        n = norm(b["name"])
        if n is None:
            continue
        cache_lp.setdefault(n, []).append(b["lifepoints"])
        cache_struct.setdefault(n, []).append(b["struct_game_id"])
    for k in cache_lp:
        cache_lp[k] = sorted(cache_lp[k])

    # ---- cache side: game_id -> combat level ----
    cache_combat = {}
    for s in cache["npc_stats"]:
        cl = s.get("combat_level")
        if cl:
            cache_combat[s["npc_id"]] = cl["value"]

    # =========================== A. LIFEPOINTS ===========================
    lp_rows = []
    for e in doc["npcs"]:
        n = norm(e["name"])
        if n not in cache_lp:
            continue
        c_vals = cache_lp[n]
        d_prim = e["lifepoints"]
        d_all = e["distinct_documented_lifepoints"]
        row = {
            "name": e["name"],
            "cache_lifepoints": c_vals,
            "cache_source": "struct_param prop 8850",
            "cache_struct_game_ids": cache_struct[n],
            "documented_primary": d_prim,
            "documented_primary_variant": e["primary_variant"],
            "documented_all_variants": d_all,
            "documented_source": e["source"],
        }
        if not d_all:
            row["verdict"] = "NO_DOCUMENTED_VALUE"
            row["detail"] = e["note"]
        elif sorted(set(c_vals)) == sorted(set(d_all)):
            row["verdict"] = "AGREE_FULL_SET"
            row["detail"] = "cache LP set == documented LP set"
        elif d_prim is not None and d_prim in c_vals:
            row["verdict"] = "AGREE_PRIMARY"
            row["detail"] = ("documented primary %d is present in the cache set %s"
                             % (d_prim, c_vals))
        elif set(d_all) & set(c_vals):
            both = sorted(set(d_all) & set(c_vals))
            row["verdict"] = "AGREE_ON_VARIANT"
            row["detail"] = ("documented primary %s is NOT in cache, but variant(s) "
                             "%s are" % (d_prim, both))
        else:
            row["verdict"] = "DISAGREE"
            row["detail"] = ("no documented value %s equals any cache value %s"
                             % (d_all, c_vals))
        lp_rows.append(row)

    # ======================== B. COMBAT LEVEL ============================
    cb_rows = []
    for e in doc["npcs"]:
        d_cb = e["combat_level"]
        ids = e["npc_ids"]
        if not ids:
            continue
        present = {i: cache_combat[i] for i in ids if i in cache_combat}
        vals = sorted(set(present.values()))
        row = {
            "name": e["name"],
            "matched_npc_game_ids": ids,
            "cache_combat_levels": vals,
            "cache_source": "npcs.combat",
            "documented_combat": d_cb,
            "documented_all_variants": e["distinct_documented_combat_levels"],
            "documented_source": e["source"],
            "id_match_ambiguous": e["match_ambiguous"],
        }
        if d_cb is None:
            row["verdict"] = "NO_DOCUMENTED_VALUE"
            row["detail"] = e["note"] or "wiki did not state a single combat level"
        elif not vals:
            row["verdict"] = "NO_CACHE_VALUE"
            row["detail"] = "no matched npc row has a non-zero npcs.combat"
        elif vals == [d_cb]:
            row["verdict"] = "AGREE_EXACT"
            row["detail"] = "every matched npc has combat %d" % d_cb
        elif d_cb in vals:
            row["verdict"] = "AGREE_AMONG"
            row["detail"] = ("documented %d is one of the cache values %s "
                             "(name maps to several npcs)" % (d_cb, vals))
        elif set(e["distinct_documented_combat_levels"]) & set(vals):
            row["verdict"] = "AGREE_ON_VARIANT"
            row["detail"] = ("primary %s not in cache %s, but documented variant(s) "
                             "%s are" % (d_cb, vals,
                                         sorted(set(e["distinct_documented_combat_levels"]) & set(vals))))
        else:
            row["verdict"] = "DISAGREE"
            row["detail"] = ("documented %s vs cache %s -- no overlap"
                             % (e["distinct_documented_combat_levels"], vals))
        cb_rows.append(row)

    # ============================ output =================================
    def tally(rows):
        t = OrderedDict()
        for r in rows:
            t[r["verdict"]] = t.get(r["verdict"], 0) + 1
        return t

    W = "=" * 78
    print(W)
    print("SEED CROSS-CHECK   cache <-> documented    (no value was adjusted)")
    print(W)
    print("cache file      : %s" % CACHE_JSON)
    retrieved = doc["npcs"][0]["retrieved"] if doc["npcs"] else "?"
    print("documented file : %s  (retrieved %s)" % (DOC_JSON, retrieved))
    print("name aliases used for the LP join (spelling only, never a number):")
    for k, v in ALIASES.items():
        print("    cache %-34r -> doc %r" % (k, v))
    print()

    print(W)
    print("A. LIFEPOINTS  --  cache struct_param 8850   vs   wiki infobox")
    print(W)
    print("%-34s %-22s %-22s %s" % ("NAME", "CACHE", "DOCUMENTED", "VERDICT"))
    print("-" * 78)
    order = {"DISAGREE": 0, "AGREE_ON_VARIANT": 1, "NO_DOCUMENTED_VALUE": 2,
             "AGREE_PRIMARY": 3, "AGREE_FULL_SET": 4}
    for r in sorted(lp_rows, key=lambda r: (order.get(r["verdict"], 9), r["name"])):
        cv = ",".join(str(x) for x in r["cache_lifepoints"])
        dv = ",".join(str(x) for x in r["documented_all_variants"]) or "-"
        print("%-34s %-22s %-22s %s" % (r["name"][:34], cv[:22], dv[:22], r["verdict"]))
        if r["verdict"] in ("DISAGREE", "AGREE_ON_VARIANT", "NO_DOCUMENTED_VALUE"):
            print("      -> %s" % r["detail"])
    print()
    print("  tally: %s" % dict(tally(lp_rows)))
    print("  overlap: %d of %d documented npcs have a cache boss struct with the "
          "same name" % (len(lp_rows), doc["counts"]["entries"]))
    print()

    print(W)
    print("B. COMBAT LEVEL  --  cache npcs.combat   vs   wiki infobox")
    print(W)
    print("%-34s %-22s %-14s %s" % ("NAME", "CACHE combat", "DOCUMENTED", "VERDICT"))
    print("-" * 78)
    order2 = {"DISAGREE": 0, "AGREE_ON_VARIANT": 1, "NO_CACHE_VALUE": 2,
              "NO_DOCUMENTED_VALUE": 3, "AGREE_AMONG": 4, "AGREE_EXACT": 5}
    for r in sorted(cb_rows, key=lambda r: (order2.get(r["verdict"], 9), r["name"])):
        cv = ",".join(str(x) for x in r["cache_combat_levels"]) or "-"
        dv = str(r["documented_combat"]) if r["documented_combat"] is not None else "-"
        print("%-34s %-22s %-14s %s" % (r["name"][:34], cv[:22], dv, r["verdict"]))
        if r["verdict"] in ("DISAGREE", "AGREE_ON_VARIANT", "NO_CACHE_VALUE",
                            "NO_DOCUMENTED_VALUE"):
            print("      -> %s" % r["detail"])
    print()
    print("  tally: %s" % dict(tally(cb_rows)))
    print()

    print(W)
    print("C. COVERAGE")
    print(W)
    print("  cache  : %d boss encounter structs (prop 8850)"
          % cache["counts"]["boss_encounters"])
    print("  cache  : %d Dominion Tower encounters (prop 2122)"
          % cache["counts"]["dominion_tower"])
    print("  cache  : %d npcs with >=1 combat stat" % cache["counts"]["npc_stat_rows"])
    print("  cache  : %d npcs with a combat level (npcs.combat > 0)" % len(cache_combat))
    print("  doc    : %d wiki entries, %d with a single canonical LP"
          % (doc["counts"]["entries"], doc["counts"]["with_lifepoints"]))
    print("  doc    : %d entries where the wiki itself is ambiguous"
          % doc["counts"]["value_ambiguous"])
    print("  doc    : %d entries with no value at all (fetch failed / no infobox)"
          % doc["counts"]["fetch_failed_or_no_infobox"])
    print("  doc    : %d entries whose name maps to >1 npc game_id"
          % doc["counts"]["id_match_ambiguous"])
    print("  LP comparisons made      : %d" % len(lp_rows))
    print("  combat comparisons made  : %d" % len(cb_rows))
    print()
    print("  NOT COVERED BY EITHER SOURCE: per-npc lifepoints for ordinary npcs.")
    print("  The cache has boss health-bar maxima and Dominion Tower LP only.")
    print()

    dis = [r for r in lp_rows if r["verdict"] == "DISAGREE"] + \
          [r for r in cb_rows if r["verdict"] == "DISAGREE"]
    print(W)
    print("D. DISAGREEMENTS (%d) -- reported, NOT reconciled" % len(dis))
    print(W)
    if not dis:
        print("  none")
    for r in dis:
        print("  %s" % r["name"])
        print("      %s" % r["detail"])
        print("      documented source: %s" % r["documented_source"])
    print()

    json.dump({"_note": "generated by seed_crosscheck.py; neither source was modified",
               "lifepoints": lp_rows, "combat_level": cb_rows,
               "aliases": ALIASES,
               "tally_lifepoints": tally(lp_rows),
               "tally_combat": tally(cb_rows)},
              open(REPORT, "w"), indent=1)
    print("machine-readable report: %s" % REPORT)


if __name__ == "__main__":
    main()
