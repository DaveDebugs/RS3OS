#!/usr/bin/env python3
"""
build_subtables.py -- emit data/seed/drop_subtables.json

PROVENANCE: "documented". Every entry below was read off a runescape.wiki
article by WebFetch on the date in RETRIEVED; the URL is recorded per table.
These are the CONTENTS of the shared sub-tables that drops_documented.json
records only as references (@herb_table, @gem_table, @rare_drop_table).
A table that was NOT fetched (e.g. @herb_seed_table, @uncommon_seed_table,
@seed_table, @allotment_seed_table, @imp_wizard_table, @imp_junk_table,
@nothing) does NOT appear here at all -- a consumer resolving such a ref
gets null, which is the honest answer.

Conventions, identical to build_drops.py:

  * Quantities and rarities are carried VERBATIM as the fetch printed them
    ("250-499", "63/128", "8/64-8/52", "~1/6400", "Common"). A parsed
    numerator/denominator rides alongside only when the string is a plain
    fraction; otherwise the parse fields are null and a consumer that needs
    a number must decide for itself, visibly.
  * The wiki marks rows "(m)" members / "(f)" free-to-play; those markers are
    NOT part of the item name -- they are carried in the note field instead.
  * Rows that are themselves references to another table ("Rare table",
    "Super rare table") are kept as "@..." refs, never expanded in place.
  * Item names are resolved against `items.name` in rs3.sqlite. Zero matches
    or several distinct ids are recorded as-is (ids: [] or many + ambiguous
    flag), never guessed.
  * The Rare_drop_table page's own leading gem-table section is recorded here
    (section "gem") verbatim from THAT fetch; it differs slightly from the
    Gem_drop_table page fetch (coins 250-499 vs 250-500; f2p coins 70/128 vs
    71/128; sapphire "31/128; 32/128" vs "31/128"). Both are kept verbatim
    from their respective fetches; neither was reconciled by hand.
  * A table whose fetch failed twice would be recorded with entries: [] and
    a note saying exactly what happened (none did on ).

    RS3_DB=data/rs3.sqlite python3 tools/seed/build_subtables.py
"""
import json
import os
import re
import sqlite3

DB = os.environ.get("RS3_DB", "data/rs3.sqlite")
OUT = os.environ.get("SUBTABLES_OUT", "data/seed/drop_subtables.json")
RETRIEVED = ""

# (table_key, source_url, [(section, item_or_@ref, qty, rarity, note), ...], table_note)
T = [
    ("herb_table", "https://runescape.wiki/w/Herb_drop_table", [
        ("members", "Grimy guam", "1", "32/128", ""),
        ("members", "Grimy marrentill", "1", "24/128", ""),
        ("members", "Grimy tarromin", "1", "18/128", ""),
        ("members", "Grimy harralander", "1", "14/128", ""),
        ("members", "Grimy ranarr", "1", "11/128", ""),
        ("members", "Grimy irit", "1", "8/128", ""),
        ("members", "Grimy avantoe", "1", "6/128", ""),
        ("members", "Grimy kwuarm", "1", "5/128", ""),
        ("members", "Grimy cadantine", "1", "4/128", ""),
        ("members", "Grimy lantadyme", "1", "3/128", ""),
        ("members", "Grimy dwarf weed", "1", "3/128", ""),
        ("f2p", "Grimy guam", "1", "Common", ""),
        ("f2p", "Grimy tarromin", "1", "Common", ""),
    ], ""),
    ("gem_table", "https://runescape.wiki/w/Gem_drop_table", [
        ("gem", "Coins", "250-500", "63/128", "members"),
        ("gem", "Coins", "50", "71/128", "F2P receive coins (50) instead"),
        ("gem", "Uncut sapphire", "1", "31/128", ""),
        ("gem", "Uncut emerald", "1", "16/128", ""),
        ("gem", "Uncut ruby", "1", "8/128", ""),
        ("gem", "Uncut diamond", "1", "2/128", ""),
        ("gem", "Rune javelin", "5", "4/128", "members"),
        ("gem", "Uncut dragonstone", "1", "1/128", "members"),
        ("gem", "Tooth half of a key", "1", "1/128", "members"),
        ("gem", "Loop half of a key", "1", "1/128", "members"),
        ("gem", "@rare_drop_table", "1", "1/128",
         "members; the page's 'Rare drop table (m)' row"),
    ], ""),
    ("rare_drop_table", "https://runescape.wiki/w/Rare_drop_table", [
        # -- the page's own gem-table access section, verbatim from this fetch
        ("gem", "Coins", "250-499", "63/128", "members"),
        ("gem", "Coins", "50", "70/128", "F2P"),
        ("gem", "Uncut sapphire", "1", "31/128; 32/128", "as printed by the fetch"),
        ("gem", "Uncut emerald", "1", "16/128", ""),
        ("gem", "Uncut ruby", "1", "8/128", ""),
        ("gem", "Uncut diamond", "1", "2/128", ""),
        ("gem", "Rune javelin", "5", "4/128", "members"),
        ("gem", "Uncut dragonstone", "1", "1/128", "members"),
        ("gem", "Tooth half of a key", "1", "1/128", "members"),
        ("gem", "Loop half of a key", "1", "1/128", "members"),
        ("gem", "@rare_table", "1", "1/128",
         "members; resolves to this table's 'rare' section"),
        # -- rare table
        ("rare", "Uncut dragonstone", "1", "8/64-8/52", "members"),
        ("rare", "Loop half of a key", "1", "6/64-6/52", "members"),
        ("rare", "Tooth half of a key", "1", "6/64-6/52", "members"),
        ("rare", "Huge plated rune salvage", "1", "5/64-5/52", "members; noted"),
        ("rare", "Magic logs", "65-85", "4/64-4/52", "members; noted"),
        ("rare", "Rune arrowheads", "110-140", "4/64-4/52", "members"),
        ("rare", "Soft clay", "35-45", "4/64-4/52", "members; noted"),
        ("rare", "Small bladed orikalkum salvage", "1", "2/64-2/52", "members; noted"),
        ("rare", "Catalytic anima stone", "35-45", "2/64-2/52", "members"),
        ("rare", "Teak plank", "45-55", "2/64-2/52", "members; noted"),
        ("rare", "Dragon bones", "35-45", "2/64-2/52", "members; noted"),
        ("rare", "Dragon helm", "1", "2/64-2/52", "members"),
        ("rare", "Dragon longsword", "1", "1/128-1/104", "members"),
        ("rare", "Off-hand dragon longsword", "1", "1/128-1/104", "members"),
        ("rare", "Molten glass", "45-55", "4/64; 0", "members; noted; as printed"),
        ("rare", "Runite stone spirit", "25-35", "4/64; 4/60; 0", "members; as printed"),
        ("rare", "Raw lobster", "135-165", "4/64-4/56; 0", "members; noted; as printed"),
        ("rare", "@super_rare_table", "1", "4/64-4/52",
         "members; resolves to this table's 'super_rare' section"),
        # -- super rare table
        ("super_rare", "Hazelmere's signet ring", "1", "~1/6400", "members"),
        ("super_rare", "Blurberry Special", "1", "~9/6400", "members"),
        ("super_rare", "Cheese+tom batta", "1", "~1/640", "members"),
        ("super_rare", "Dragon helm", "1", "39/640", "members"),
        ("super_rare", "Shield left half", "1", "20/640", "members"),
        ("super_rare", "Dragon spear", "1", "35/640", "members"),
        ("super_rare", "Dragon longsword", "1", "39/1280", "members"),
        ("super_rare", "Off-hand dragon longsword", "1", "39/1280", "members"),
        ("super_rare", "Huge plated rune salvage", "18-22", "15/640", "members; noted"),
        ("super_rare", "Yew logs", "675-825", "20/640", "members; noted"),
        ("super_rare", "Super restore (4)", "45-55", "30/640", "members; noted"),
        ("super_rare", "Prayer potion (4)", "45-55", "30/640", "members; noted"),
        ("super_rare", "Raw rocktail", "180-220", "25/640", "members; noted"),
        ("super_rare", "Mahogany plank", "270-330", "15/640", "members; noted"),
        ("super_rare", "Magic seed", "3-5", "10/640", "members"),
        ("super_rare", "Water talisman", "68-82", "8/640", "members; noted"),
        ("super_rare", "Battlestaff", "180-220", "8/640", "members; noted"),
        ("super_rare", "Hardened dragon bones", "45-55", "10/640", "members; noted"),
        ("super_rare", "Onyx bolt tips", "135-165", "8/640", "members"),
        ("super_rare", "Ciku seed", "1", "8/640", "members"),
        ("super_rare", "Golden dragonfruit seed", "14-16", "15/640", "members"),
        ("super_rare", "Uncut diamond", "45-55", "25/640", "members; noted"),
        ("super_rare", "Uncut dragonstone", "45-55", "10/640", "members; noted"),
        ("super_rare", "Soul rune", "450-550", "8/640", "members"),
        ("super_rare", "Light animica stone spirit", "270-330", "20/640", "members"),
        ("super_rare", "Dark animica stone spirit", "270-330", "20/640", "members"),
        ("super_rare", "Primal stone spirit", "90-140", "20/640", "members"),
        ("super_rare", "Crystal key", "9-11", "60/640", "members; noted"),
        ("super_rare", "White berries", "65-85", "15/640", "members; noted"),
        ("super_rare", "Ectoplasm", "270-330", "20/640", "members"),
        ("super_rare", "Medium spiky orikalkum salvage", "3-5", "20/640", "members; noted"),
        ("super_rare", "Large blunt necronium salvage", "2", "15/640", "members; noted"),
        ("super_rare", "Wine of Saradomin", "14-16", "8/640", "members; noted"),
        ("super_rare", "Starbloom flower seed", "5-8", "25/640", "members"),
        ("super_rare", "Distraction & Diversion reset token (daily)", "1", "10/640", "members"),
        ("super_rare", "Distraction & Diversion reset token (weekly)", "1", "10/640", "members"),
        ("super_rare", "Distraction & Diversion reset token (monthly)", "1", "10/640", "members"),
        ("super_rare", "Vecna skull", "1", "8/640", "members"),
        ("super_rare", "Coins", "7500-12500", "8/640", "members"),
        # -- free-to-play rare table
        ("f2p_rare", "Raw swordfish", "30-50", "20/128", "F2P; noted"),
        ("f2p_rare", "Uncut diamond", "1", "16/128", "F2P"),
        ("f2p_rare", "Huge plated rune salvage", "1", "10/128", "F2P"),
        ("f2p_rare", "Yew logs", "68-82", "8/128", "F2P; noted"),
        ("f2p_rare", "Rune arrowheads", "113-137", "8/128", "F2P"),
        ("f2p_rare", "Raw lobster", "135-165", "8/128", "F2P; noted"),
        ("f2p_rare", "Green dragon leather", "30-40", "8/128", "F2P; noted"),
        ("f2p_rare", "Flax", "450-550", "8/128", "F2P; noted"),
        ("f2p_rare", "Uncut emerald", "20-30", "8/128", "F2P; noted"),
        ("f2p_rare", "Chocolate dust", "20-40", "8/128", "F2P; noted"),
        ("f2p_rare", "Runite stone spirit", "3", "8/128", "F2P"),
        ("f2p_rare", "Uncut ruby", "10-20", "4/128", "F2P; noted"),
        ("f2p_rare", "Oak logs", "78-88", "4/128", "F2P; noted"),
        ("f2p_rare", "Gold stone spirit", "45-55", "4/128", "F2P"),
        ("f2p_rare", "Big bones", "68-82", "4/128", "F2P; noted"),
        ("f2p_rare", "Blue dragon leather", "20-30", "2/128", "F2P; noted"),
    ], "the leading 'gem' section is this page's own gem-table access block, "
       "kept verbatim from this fetch even where it differs slightly from the "
       "Gem_drop_table page fetch"),
]

FRACTION = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*/\s*([0-9,]+(?:\.[0-9]+)?)\s*$")


def parse_rarity(s):
    if s.strip().lower() == "always":
        return 1.0, 1.0
    m = FRACTION.match(s)
    if not m:
        return None, None
    return float(m.group(1)), float(m.group(2).replace(",", ""))


def main():
    con = sqlite3.connect(DB)
    name_ids = {}
    for i, n in con.execute("SELECT id, name FROM items WHERE name IS NOT NULL"):
        name_ids.setdefault(n.strip('"').lower(), []).append(i)

    tables = {}
    entries = 0
    unresolved = 0
    for key, source, rows, table_note in T:
        out_rows = []
        for section, item, qty, rarity, note in rows:
            num, den = parse_rarity(rarity)
            row = {
                "section": section,
                "item": item,
                "quantity": qty,
                "rarity": rarity,
                "rarity_num": num,
                "rarity_den": den,
                "note": note or None,
            }
            if item.startswith("@"):
                row["is_table_ref"] = True
                row["item_ids"] = []
            else:
                matched = name_ids.get(item.lower(), [])
                row["item_ids"] = matched
                row["item_match_ambiguous"] = len(matched) > 1
                if not matched:
                    unresolved += 1
            out_rows.append(row)
            entries += 1
        tables[key] = {
            "name": key,
            "entries": out_rows,
            "note": table_note or None,
            "provenance": "documented",
            "source": source,
            "retrieved": RETRIEVED,
        }

    out = {
        "_schema": "opennxt.seed.drop_subtables/1",
        "_provenance": "documented",
        "_provenance_meaning": (
            "every entry was read off the runescape.wiki article in `source` "
            "by WebFetch on `retrieved`; quantities and rarities are verbatim "
            "strings with a parsed fraction alongside when parseable; tables "
            "not fetched do not appear here at all"),
        "counts": {
            "tables": len(tables),
            "entries": entries,
            "unresolved_item_names": unresolved,
        },
        "tables": tables,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    print("wrote %s: %d tables, %d entries, %d unresolved item names"
          % (OUT, len(tables), entries, unresolved))
    for key, t in tables.items():
        bad = [r["item"] for r in t["entries"]
               if not r.get("is_table_ref") and not r["item_ids"]]
        if bad:
            print("  %-16s unresolved: %s" % (key, ", ".join(bad)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
