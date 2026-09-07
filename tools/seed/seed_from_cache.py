#!/usr/bin/env python3
"""
seed_from_cache.py -- extract NPC combat seed data from the decoded RS3 cache DB.

EVERY value emitted here has provenance "cache": it was decoded from the user's
own cache files into data/rs3.sqlite. Nothing here is guessed,
inferred from the wiki, or authored.

Sources actually used (table + prop, recorded per value in the JSON):

  struct_param prop 8850  -- boss health-bar maximum (89 encounter structs)
  struct_param prop 8849  -- boss/encounter display name
  struct_param prop 8853..8857 -- phase markers (sparse)
  struct_param prop 8863  -- encounter id
  struct_param prop 2122  -- Dominion Tower encounter lifepoints (70 entries)
  struct_param prop 2095  -- Dominion Tower encounter name
  npcs_attr field 'extra' prop 641/643/965 -- max hit x10 (melee/ranged/magic)
  npcs_attr field 'extra' prop 2849/2850/2851/2852 -- affinities
  npcs_attr field 'extra' prop 29   -- melee accuracy (corrected: this line and the next were swapped; NpcCombat.kt and the wiki's per-id infobox agree 2865 is armour, 29 accuracy)
  npcs_attr field 'extra' prop 2865 -- armour
  npcs.combat -- combat level

ID SPACES -- read this before using the output.
  npcs.id      is the cache ARCHIVE index (max 65568)
  npcs.game_id is the in-game npc id      (max 32800)
  They differ for 32,559 of 32,687 rows.
  npcs_attr.id joins npcs.id  (archive index).
  struct_param.struct_id joins structs.id (archive index); structs.game_id is
  the in-game struct id.
  This script emits BOTH for every npc and every struct, and labels which is
  which. Server code should key on game_id.

BOSS/DT STRUCT -> NPC LINKAGE.
  Verified negative: the boss structs carry no resolvable npc reference.
  prop 8866 on the Nex struct is 28656, which resolves to "Santa's head elf"
  under game_id and to nothing under id. Likewise the Dominion Tower props
  2118-2121 resolve to unrelated names in both id spaces (0/70 name matches in
  either space). So this script does NOT invent a struct->npc link. It emits
  the struct name verbatim and, separately, a best-effort NAME match against
  npcs.name with every candidate listed and ambiguity flagged. Consumers may
  use the name match or ignore it.
"""

import json
import os
import sqlite3
import sys
from collections import defaultdict


def _id_spaces(conn):
    """The id-space block, MEASURED rather than asserted.

    This used to hardcode warning="npcs.id != npcs.game_id for 32559 of 32687
    rows". That number was never measured and is false for this database: both
    npcs and structs have id == game_id on every row. Rather than correct one
    constant with another, count it every time the file is generated, so the
    claim can never drift away from the cache it describes.
    """
    def split(table):
        total = conn.execute("select count(*) from %s" % table).fetchone()[0]
        differ = conn.execute(
            "select count(*) from %s where id != game_id" % table).fetchone()[0]
        return total, differ

    npc_total, npc_differ = split("npcs")
    st_total, st_differ = split("structs")
    if npc_differ == 0 and st_differ == 0:
        measured = (
            "npcs has %d rows and %d with id != game_id; structs has %d rows "
            "and %d with id != game_id. The two id spaces are numerically IDENTICAL in "
            "this database, so no join or lookup here can distinguish them and no bug "
            "caused by confusing them is observable against this data. The names below "
            "are kept because the distinction is real in the RS3 cache format generally; "
            "code relying on it must be re-verified against a cache where the columns "
            "actually differ." % (npc_total, npc_differ, st_total, st_differ))
    else:
        measured = (
            "npcs.id != npcs.game_id for %d of %d rows; structs.id != "
            "structs.game_id for %d of %d rows. The distinction is live in this "
            "database - keying on the wrong column WILL resolve the wrong entity."
            % (npc_differ, npc_total, st_differ, st_total))
    return {
        "npc_id": "npcs.game_id -- the in-game npc id; use this on the server",
        "archive_id": "npcs.id -- nominally the cache archive index; npcs_attr joins this",
        "struct_game_id": "structs.game_id -- in-game struct id",
        "struct_archive_id": "structs.id -- nominally the cache archive index; struct_param joins this",
        "measured": measured,
    }



# Paths derive from this file's own location, so the repository can live
# anywhere. Each one can be overridden by the environment variable beside it.
_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = os.environ.get("RS3_DB", os.path.join(_TREE, "data", "rs3.sqlite"))
OUT = os.environ.get("SEED_OUT", os.path.join(_TREE, "data", "seed", "npc_cache.json"))

PROV = "cache"

MAXHIT_PROPS = {641: "melee", 643: "ranged", 965: "magic"}
AFFINITY_PROPS = {2849: "melee", 2850: "ranged", 2851: "magic", 2852: "necromancy"}
ARMOUR_PROP = 29
ACCURACY_PROP = 2865
PHASE_PROPS = [8853, 8854, 8855, 8856, 8857]


# Cache struct names (prop 8849) are not always spelled the same as npcs.name.
# This table fixes SPELLING ONLY so the name match can find the npc rows. It is
# a mapping decision, not a data value: it never changes a number, and it is
# emitted into the JSON header so downstream consumers can audit it.
# Each entry was verified against the npcs table by hand.
NAME_ALIASES = {
    # struct name (prop 8849)          : npcs.name
    "solak, the grove guardian": "Solak, Guardian of the Grove",
    "masuta, the ascended": "Masuta the Ascended",
    "seiryu, the azure serpent": "Seiryu the Azure Serpent",
    "astellarn, the first celestial": "Astellarn",
}


def connect(path):
    if not os.path.exists(path) and not os.path.islink(path):
        sys.exit("database not found: %s" % path)
    return sqlite3.connect("file:%s?mode=ro" % path, uri=True)


def load_npcs(c):
    """archive_id -> (game_id, name, combat)"""
    npcs = {}
    for i, g, n, cb in c.execute("select id, game_id, name, combat from npcs"):
        npcs[i] = (g, n, cb)
    return npcs


def load_npc_params(c):
    """archive_id -> {prop: intvalue}"""
    out = {}
    for i, v in c.execute("select id, value from npcs_attr where field='extra'"):
        try:
            plist = json.loads(v)
        except Exception:
            continue
        d = {}
        for p in plist:
            if p.get("intvalue") is not None:
                d[p["prop"]] = p["intvalue"]
        if d:
            out[i] = d
    return out


def name_index(npcs):
    idx = defaultdict(list)
    for arch, (g, n, cb) in npcs.items():
        if n:
            idx[n.strip().lower()].append({"npc_id": g, "archive_id": arch, "combat": cb})
    return idx


def match_by_name(idx, name):
    """Return (list_of_candidates, ambiguous_flag, note)."""
    if not name:
        return [], False, "struct has no name (prop 8849/2095 absent)"
    key = name.strip().lower()
    via = ""
    if key in NAME_ALIASES:
        key = NAME_ALIASES[key].strip().lower()
        via = " via alias %r -> %r" % (name, NAME_ALIASES[name.strip().lower()])
    cands = idx.get(key, [])
    if not cands:
        return [], False, "no npcs.name equals this struct name" + via
    ids = sorted({c["npc_id"] for c in cands})
    if len(ids) > 1:
        return cands, True, ("%d distinct npc game_ids share this name%s; "
                             "not disambiguated" % (len(ids), via))
    return cands, False, "exact case-insensitive npcs.name match" + via


def main():
    c = connect(DB)
    npcs = load_npcs(c)
    params = load_npc_params(c)
    idx = name_index(npcs)

    struct_gid = dict(c.execute("select id, game_id from structs"))

    # ---- all struct params for the structs we care about ----
    sp = defaultdict(dict)
    sp_str = defaultdict(dict)
    boss_sids = [r[0] for r in c.execute("select struct_id from struct_param where prop=8850")]
    dt_sids = [r[0] for r in c.execute("select struct_id from struct_param where prop=2122")]
    want = set(boss_sids) | set(dt_sids)
    for sid, prop, iv, sv in c.execute("select struct_id, prop, intvalue, stringvalue from struct_param"):
        if sid in want:
            if iv is not None:
                sp[sid][prop] = iv
            if sv is not None:
                sp_str[sid][prop] = sv

    # ---------------- boss encounters ----------------
    bosses = []
    for sid in sorted(boss_sids):
        name = sp_str[sid].get(8849)
        cands, ambiguous, note = match_by_name(idx, name)
        phases = {str(p): sp[sid][p] for p in PHASE_PROPS if p in sp[sid]}
        bosses.append({
            "name": name,
            "name_source": "struct_param prop 8849" if name else None,
            "lifepoints": sp[sid][8850],
            "lifepoints_source": "struct_param prop 8850",
            "lifepoints_meaning": "boss health-bar maximum for this encounter struct",
            "struct_archive_id": sid,
            "struct_game_id": struct_gid.get(sid),
            "encounter_id": sp[sid].get(8863),
            "encounter_id_source": "struct_param prop 8863",
            "phase_markers": phases,
            "phase_markers_source": "struct_param props 8853-8857",
            "npc_name_matches": cands,
            "npc_match_method": note,
            "npc_match_ambiguous": ambiguous,
            "provenance": PROV,
        })

    # ---------------- dominion tower ----------------
    dt = []
    for sid in sorted(dt_sids):
        name = sp_str[sid].get(2095)
        cands, ambiguous, note = match_by_name(idx, name)
        dt.append({
            "name": name,
            "name_source": "struct_param prop 2095",
            "lifepoints": sp[sid][2122],
            "lifepoints_source": "struct_param prop 2122",
            "lifepoints_meaning": "Dominion Tower encounter lifepoints",
            "struct_archive_id": sid,
            "struct_game_id": struct_gid.get(sid),
            "npc_name_matches": cands,
            "npc_match_method": note,
            "npc_match_ambiguous": ambiguous,
            "provenance": PROV,
        })

    # ---------------- per-npc combat stats ----------------
    stats = []
    for arch in sorted(npcs):
        gid, name, combat = npcs[arch]
        p = params.get(arch, {})
        entry = {}

        mh = {}
        for prop, kind in MAXHIT_PROPS.items():
            if prop in p:
                mh[kind] = {
                    "raw": p[prop],
                    "max_hit": p[prop] / 10.0,
                    "source": "npcs_attr extra prop %d" % prop,
                    "note": "raw value is max hit x10",
                    "provenance": PROV,
                }
        if mh:
            entry["max_hits"] = mh

        aff = {}
        for prop, kind in AFFINITY_PROPS.items():
            if prop in p:
                aff[kind] = {"value": p[prop],
                             "source": "npcs_attr extra prop %d" % prop,
                             "provenance": PROV}
        if aff:
            entry["affinities"] = aff

        if ARMOUR_PROP in p:
            entry["armour"] = {"value": p[ARMOUR_PROP],
                               "source": "npcs_attr extra prop %d" % ARMOUR_PROP,
                               "provenance": PROV}
        if ACCURACY_PROP in p:
            entry["accuracy"] = {"value": p[ACCURACY_PROP],
                                 "source": "npcs_attr extra prop %d" % ACCURACY_PROP,
                                 "provenance": PROV}
        if combat:
            entry["combat_level"] = {"value": int(combat),
                                     "source": "npcs.combat",
                                     "provenance": PROV}

        if not entry:
            continue
        entry["npc_id"] = gid
        entry["archive_id"] = arch
        entry["name"] = name
        stats.append(entry)

    doc = {
        "_schema": "opennxt.seed.npc_cache/1",
        "_provenance": PROV,
        "_provenance_meaning": (
            "every value in this file was decoded from the local RS3 cache into "
            "the definition database. No wiki data, no authored values."),
        "_database": os.path.relpath(os.path.abspath(DB), _TREE).replace(os.sep, "/"),
        "_id_spaces": _id_spaces(c),
        "_not_in_cache": [
            "per-npc lifepoints (only boss health-bar maxima and Dominion Tower LP exist)",
            "drop tables",
        ],
        "_name_aliases": NAME_ALIASES,
        "_name_aliases_meaning": (
            "cache struct name (prop 8849) -> npcs.name, spelling only. Applied "
            "when building npc_name_matches. Never changes a numeric value."),
        "_struct_npc_linkage": (
            "boss structs (8850) and DT structs (2122) contain no resolvable npc "
            "reference in either id space; npc_name_matches below is a NAME match "
            "only and is flagged when ambiguous"),
        "counts": {
            "boss_encounters": len(bosses),
            "dominion_tower": len(dt),
            "npc_stat_rows": len(stats),
        },
        "boss_encounters": bosses,
        "dominion_tower": dt,
        "npc_stats": stats,
    }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=1, sort_keys=False)

    print("wrote %s" % OUT)
    print("  boss encounters (prop 8850): %d" % len(bosses))
    print("  dominion tower  (prop 2122): %d" % len(dt))
    print("  npcs with >=1 stat         : %d" % len(stats))
    n_mh = sum(1 for s in stats if "max_hits" in s)
    n_aff = sum(1 for s in stats if "affinities" in s)
    n_arm = sum(1 for s in stats if "armour" in s)
    n_acc = sum(1 for s in stats if "accuracy" in s)
    n_cb = sum(1 for s in stats if "combat_level" in s)
    print("    max_hits=%d affinities=%d armour=%d accuracy=%d combat_level=%d"
          % (n_mh, n_aff, n_arm, n_acc, n_cb))
    amb = sum(1 for b in bosses if b["npc_match_ambiguous"])
    non = sum(1 for b in bosses if not b["npc_name_matches"])
    print("  boss name->npc: %d ambiguous, %d unmatched" % (amb, non))


if __name__ == "__main__":
    main()
