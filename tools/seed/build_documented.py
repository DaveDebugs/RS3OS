#!/usr/bin/env python3
"""
build_documented.py -- emit data/seed/npc_documented.json

PROVENANCE: "documented". Every lifepoints / combat level number below was read
off a runescape.wiki article by WebFetch on the date in RETRIEVED. The URL is
recorded per NPC. Nothing here comes from the cache, and nothing here is
authored: where the wiki did not state a value, or stated several conflicting
ones, that is recorded as-is (value null + ambiguity note) rather than filled in.

The npc id matching is done here against the cache database by NAME only,
because the wiki does not publish the cache archive index. Both npcs.game_id
and npcs.id are emitted; game_id is the id a server should use. Every candidate
is listed and the match is flagged ambiguous when more than one distinct
game_id carries the name.
"""

import json
import os
import sqlite3
from collections import defaultdict


# Paths derive from this file's own location, so the repository can live
# anywhere. Each one can be overridden by the environment variable beside it.
_TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = os.environ.get("RS3_DB", os.path.join(_TREE, "data", "rs3.sqlite"))
OUT = os.environ.get("SEED_OUT", os.path.join(_TREE, "data", "seed", "npc_documented.json"))
RETRIEVED = ""
BASE = "https://runescape.wiki/w/"

# (wiki_page, db_name_or_None, [ (variant, combat, lifepoints), ... ], primary_variant_or_None, note)
# combat / lifepoints of None means the wiki page did not state it.
# primary_variant: the variant to treat as the single canonical value. Set to
#   None when the wiki genuinely offers several and none is canonical -> ambiguous.
D = [
    # ---------------- low level / slayer ----------------
    ("Chicken", None, [("all variants", 1, 250)], "all variants", ""),
    ("Cow", None, [("all variants", 2, 1000)], "all variants", ""),
    ("Goblin", None, [("Varrock", 11, 1750), ("Port Sarim lvl 2", 2, 550),
                      ("Port Sarim lvl 5", 5, 1300)], None,
     "wiki lists three goblins with different combat levels and LP"),
    ("Giant_rat", "Giant rat", [("level 4", 4, 1100), ("level 7", 7, 1450),
                                ("level 9 (Stronghold of Security)", 9, 1650)], None,
     "three combat levels with different LP"),
    ("Man", None, [("standard (Varrock/Al Kharid/Musa Point/...)", 4, 150),
                   ("Lumbridge", 2, 100)], None,
     "wiki lists level 4 (150 LP) and level 2 Lumbridge (100 LP)"),
    ("Guard", None, [], None,
     "FETCH RETURNED NO COMBAT INFOBOX: the /w/Guard page is a disambiguation-"
     "style overview with no combat stats. See the Guard (Varrock) entry."),
    ("Guard_(Varrock)", "Guard", [("Varrock (male/female/captain)", 25, 900)],
     "Varrock (male/female/captain)",
     "db_name 'Guard' matches many unrelated guards; match is ambiguous"),
    ("Hill_giant", "Hill giant", [("standard", 26, 4500)], "standard", ""),
    ("Moss_giant", "Moss giant", [("all variants", 40, 6400)], "all variants", ""),
    ("Lesser_demon", "Lesser demon", [("all variants", 70, 5000)], "all variants", ""),
    ("Greater_demon", "Greater demon", [("all variants", 82, 6000)], "all variants", ""),
    ("Skeleton", None, [("level 15", 15, 550), ("level 16", 16, 600),
                        ("level 32", 32, 1150), ("level 46", 46, 5300),
                        ("level 51", 51, 5950), ("level 58", 58, 2100)], None,
     "six documented skeletons with different LP"),
    ("Zombie", None, [("level 12", 12, 450), ("level 22", 22, 800),
                      ("level 29", 29, 1050)], None,
     "three documented zombies with different LP"),
    ("Green_dragon", "Green dragon", [("all variants", 63, 4500)], "all variants", ""),
    ("Blue_dragon", "Blue dragon", [("all variants", 74, 5000)], "all variants", ""),
    ("Red_dragon", "Red dragon", [("all variants", 84, 6000)], "all variants", ""),
    ("Black_dragon", "Black dragon", [("all variants", 100, 7000)], "all variants", ""),
    ("Abyssal_demon", "Abyssal demon", [("standard", 98, 8500)], "standard", ""),
    ("Gargoyle", None, [("regular (Kuradal's Dungeon etc)", 93, 6700),
                        ("Wilderness (Abandoned Farm)", 105, 13500)], None,
     "regular and Wilderness variants have different LP"),
    ("Nechryael", None, [("standard", 96, 8000)], "standard", ""),
    ("Dark_beast", "Dark beast", [("8,500 LP version", 101, 8500),
                                  ("19,000 LP version", 101, 19000)], None,
     "two documented LP values at the same combat level"),
    ("Bloodveld", None, [("normal", 52, 10250), ("smaller", 47, 9050)], None,
     "normal and smaller variants differ in both fields"),
    ("Imp", None, [("standard", 5, 200)], "standard", ""),
    ("Rock_crab", "Rock crab", [("standard", 47, 8400)], "standard", ""),
    ("Fire_giant", "Fire giant", [("all weapon variants", 85, 6700)], "all weapon variants", ""),
    ("Ice_giant", "Ice giant", [("all variants", 47, 7450)], "all variants", ""),
    ("Dust_devil", "Dust devil", [("standard", 85, 8000)], "standard", ""),
    ("Aberrant_spectre", "Aberrant spectre",
     [("Slayer Tower", 72, 6000), ("Pollnivneach Slayer Dungeon", 78, 6000)], None,
     "same LP, two different combat levels"),
    ("Banshee", None, [("standard", 24, 2500)], "standard", ""),
    ("Turoth", None, [("level 60", 60, 3600), ("level 68", 68, 4200)], None,
     "two combat levels with different LP"),
    ("Cockatrice", None, [("standard", 31, 4050)], "standard", ""),
    ("Chaos_druid", "Chaos druid", [("all variants", 32, 1150)], "all variants", ""),
    ("Jelly", None, [("all variants", 61, 8200)], "all variants", ""),
    ("Infernal_mage", "Infernal Mage", [("all variants", 63, 6500)], "all variants", ""),
    ("Crawling_hand", "Crawling hand", [("level 6", 6, 1450), ("level 10", 10, 1750)], None,
     "two combat levels with different LP"),
    ("Basilisk", None, [("all variants", 49, 4800)], "all variants", ""),
    ("Airut", None, [("melee/ranged", 122, 16875)], "melee/ranged", ""),
    ("Ganodermic_beast", "Ganodermic beast", [("all variants", 112, 12500)], "all variants", ""),

    # ---------------- bosses that also appear in the cache boss-struct set ----------------
    ("Nex", None, [("standard and deflect forms", 1001, 200000)],
     "standard and deflect forms", ""),
    ("King_Black_Dragon", "King Black Dragon", [("standard", 276, 45000)], "standard", ""),
    ("Giant_Mole", "Giant Mole", [("normal", 230, 78000), ("hard mode", 230, 78000)],
     "normal", "hard mode has identical LP"),
    ("Araxxor", None, [("all styles", 2500, 100000)], "all styles", ""),
    ("Araxxi", None, [("standard", 3000, 100000)], "standard", ""),
    ("Gregorovic", None, [("normal", 1000, 200000), ("hard mode", 1000, 200000),
                          ("Sliske's Endgame", 1000, 200000)], "normal",
     "all documented variants share 200,000"),
    ("Helwyr", None, [("normal mode", 1000, 200000), ("hard mode", 1000, 300000)],
     "normal mode", "hard mode differs (300,000)"),
    ("Vindicta", None, [("normal mode", 1000, 200000), ("hard mode", 1000, 300000)],
     "normal mode", "hard mode differs (300,000)"),
    ("The_Twin_Furies", "The Twin Furies", [], None,
     "FETCH FAILED: two attempts (/w/Twin_Furies and /w/The_Twin_Furies) both "
     "returned page text without a parseable combat infobox. No value recorded."),
    ("Kalphite_King", "Kalphite King", [("all styles", 2500, 260000)], "all styles", ""),
    ("Vorkath", None, [("story mode", 789, 375000), ("normal mode", 789, 750000),
                       ("hard mode", 789, 1500000),
                       ("Requiem for a Dragon", 789, 75001)], "normal mode",
     "four documented difficulty variants"),
    ("Croesus", None, [("active", 6000, None)], "active",
     "wiki infobox does not state lifepoints for Croesus"),
    ("Telos,_the_Warden", "Telos, the Warden",
     [("standard (0% enrage)", 2000, 400000), ("scales with enrage", 2000, 800000)],
     "standard (0% enrage)", "LP scales 400,000 -> 800,000 with enrage"),
    ("Solak", "Solak, Guardian of the Grove", [("Solak, Guardian of the Grove", 7000, 2000000),
                     ("withering", 7000, 125000),
                     ("left arm", 2000, 45000), ("right arm", 2000, 45000),
                     ("left leg", 2000, 35000), ("right leg", 2000, 35000)],
     "Solak, Guardian of the Grove", "wiki body-part sub-entities have own LP"),
    ("Nomad", None, [("Nomad's Requiem", 699, 45000),
                     ("Nomad's Elegy", 799, 100000),
                     ("Memory of Nomad", 1001, 200000),
                     ("Sliske's Endgame", 799, 100000)], None,
     "four documented Nomad encounters with different LP"),
    ("Rasial,_the_First_Necromancer", "Rasial, the First Necromancer",
     [("normal", 8462, 800000), ("Alpha vs Omega", 8462, 400000)], "normal", ""),
    ("Raksha,_the_Shadow_Colossus", "Raksha, the Shadow Colossus",
     [("solo", 6000, 800000), ("duo", 6000, 1600000)], "solo", ""),
    ("TzKal-Zuk", None, [("normal mode", 14000, 600000),
                         ("hard mode", 14000, 1200000)], "normal mode", ""),
    ("The_Magister", "The Magister", [("boss", 899, 200000),
                                      ("Succession quest", None, 17500)], "boss", ""),
    ("Arch-Glacor", None, [("normal mode", 7000, 65000),
                           ("hard mode", 7000, 370000)], "normal mode", ""),
    ("Masuta_the_Ascended", "Masuta the Ascended",
     [("phase 1 normal", 1000, 550000), ("phase 2 normal", 1000, 275000),
      ("phase 1 story", 1000, 275000), ("phase 2 story", 1000, 137500)],
     "phase 1 normal", "per-phase LP"),
    ("Seiryu_the_Azure_Serpent", "Seiryu the Azure Serpent",
     [("normal mode", 10000, 7500000), ("story mode", 10000, 3750000)],
     "normal mode", ""),
    ("Beastmaster_Durzag", "Beastmaster Durzag", [("standard", 2000, 1500000)],
     "standard", ""),
    ("Zamorak,_Lord_of_Chaos", "Zamorak, Lord of Chaos",
     [("normal mode", 14000, 300000), ("story mode", 14000, 150000),
      ("hard mode", 14000, 300000)], "normal mode", ""),
    ("Zemouregal", None, [("Ritual of the Mahjarrat", 0, 32400),
                          ("The World Wakes", 98, 40000),
                          ("Dominion Tower", 98, 30000),
                          ("Dimension of Disaster", 98, 35000),
                          ("Battle of Forinthry", 789, 125000)], None,
     "five documented encounters, all different; no canonical value"),
    ("Hermod,_the_Spirit_of_War", "Hermod, the Spirit of War",
     [("normal", 732, 200000), ("quest", 732, 100000)], "normal", ""),
    ("Astellarn,_the_First_Celestial", "Astellarn",
     [("standard", 1200, 250000)], "standard", ""),
    ("Avaryss,_the_Unceasing", "Avaryss, the Unceasing",
     [("normal mode", 1000, 250000), ("hard mode", 1000, 350000),
      ("Daughter of Chaos", 1000, 500000)], "normal mode",
     "three documented variants"),
    ("Verak_Lith", "Verak Lith", [("standard", 1450, 600000)], "standard", ""),
    ("Black_stone_dragon", "Black Stone Dragon", [("standard", 2500, 650000)],
     "standard", ""),
    ("Yakamaru", None, [("all forms", 10000, 1000000)], "all forms", ""),
    ("Avatar_of_Amascut", "Avatar of Amascut",
     [("Ode of the Devourer", 2416, None), ("Sanctum of Rebirth", None, None)], None,
     "wiki infobox lifepoints field is unfilled ('?') for both variants"),
    ("Sliske", None, [("combat parts 1-4", None, 100000),
                      ("World Guardian unleashed", None, 150000)], "combat parts 1-4",
     "wiki infobox does not state a combat level"),
    ("Har-Aken", None, [("main body", 800, 150000),
                        ("magic tentacle", 800, 7000),
                        ("ranged tentacle", 800, 7000)], "main body", ""),
    ("Osseous", None, [("standard", 888, 350000)], "standard", ""),

    # ---------------- spawn-coverage round, retrieved ----------------
    # These 46 names cover every spawned NPC (map_keyed) with a combat level
    # that had no documented entry, minus the Meiyerditch/Canifis citizen
    # family (only Boris was fetched; the others were NOT and get no entry).
    ("Paladin", None, [("standard", 49, 1750)], "standard", ""),
    ("Zombie_pirate", None, [("Braindeath Island", 50, 1800),
                             ("Mos Le'Harmless", 35, 1250)], None,
     "two documented variants with different LP"),
    ("Duck", None, [("all variants (male/female/land)", 1, 250)],
     "all variants (male/female/land)", ""),
    ("Woman", None, [("standard", 4, 150), ("Lumbridge", 2, 100)], None,
     "level 4 (150 LP) and level 2 Lumbridge (100 LP)"),
    ("Monkey", None, [("Karamja/Ardougne Zoo", 8, 300)], "Karamja/Ardougne Zoo", ""),
    ("Sorebones", None, [("standard", 49, 1750)], "standard", ""),
    ("Giant_spider", None, [("level 2", 2, 550), ("level 4", 4, 1000),
                            ("level 29", 29, 3150), ("level 33", 33, 3900)], None,
     "four documented levels with different LP"),
    ("Dark_wizard", None, [("bearded (Wilderness/Tower)", 35, 2000),
                           ("young (Wilderness/Tower)", 21, 1300),
                           ("young (Varrock circle, 9)", 9, 800),
                           ("bearded (Varrock circle, 11)", 11, 900),
                           ("young (Varrock circle, 12)", 12, 950),
                           ("bearded (Varrock circle, 14)", 14, 1000)], None,
     "six documented level/LP combinations across locations"),
    ("Mutated_zygomite", "Fungi", [("level 58", 58, 3600), ("level 65", 65, 4200)], None,
     "db name 'Fungi' is the unpicked form; two documented levels"),
    ("Deadly_red_spider", "Deadly red spider", [("standard", 95, 6150)], "standard", ""),
    ("Thug", None, [("Wilderness", 19, 700), ("Edgeville Dungeon", 11, 180)], None,
     "two documented variants with different LP"),
    ("Mutated_bloodveld", "Mutated bloodveld", [("standard", 81, 14550)], "standard", ""),
    ("Ghost", None, [("all seven variants", 25, 900)], "all seven variants", ""),
    ("Iron_dragon", "Iron dragon", [("standard", 98, 7500)], "standard",
     "wiki notes a level-112 Construction pit variant without an infobox"),
    ("Poison_Scorpion", "Poison Scorpion", [("standard", 49, 5650)], "standard", ""),
    ("Steel_dragon", "Steel dragon", [("standard", 100, 10000)], "standard", ""),
    ("Crocodile", None, [("River Elid", 77, 2900), ("eastern marshes", 40, 1450)], None,
     "two documented variants with different LP"),
    ("Otherworldly_being", "Otherworldly being", [("standard", 55, 5650)], "standard", ""),
    ("Rat", None, [("common", 1, 50), ("Rat Catchers", 0, 10)], "common",
     "the Rat Catchers variant is combat 0 / 10 LP; the common rat is canonical"),
    ("Skeletal_hand", "Skeletal hand", [("standard", 63, 8400)], "standard", ""),
    ("Zombie_hand", "Zombie hand", [("standard", 69, 9200)], "standard", ""),
    ("Knight_of_Ardougne", "Knight of Ardougne", [("standard", 53, 1900)], "standard",
     "the West Ardougne variant is a separate wiki article, not fetched"),
    ("Warrior_woman", "Warrior woman", [("standard", 43, 1550)], "standard", ""),
    ("Calf", None, [("all four variants", 1, 500)], "all four variants", ""),
    ("Earth_warrior", "Earth warrior", [("standard", 61, 9650)], "standard",
     "Daemonheim and elite variants carry no infobox stats"),
    ("Guard_dog", "Guard dog", [("standard", 33, 1200)], "standard", ""),
    ("Pit_Scorpion", "Pit Scorpion", [("standard", 56, 3250)], "standard", ""),
    ("Black_demon", "Black demon", [("standard", 98, 9000),
                                    ("Wilderness", 112, 17000)], "standard",
     "the spawned cb-98 rows match the standard variant"),
    ("Highwayman", None, [("all three variants", 11, 400)], "all three variants", ""),
    ("Poison_spider", "Poison spider", [("common", 53, 3700),
                                        ("Observatory", 15, 550)], "common",
     "the spawned cb-53 rows match the common variant"),
    ("Scorpion", None, [("level 14", 14, 2000),
                        ("Stronghold of Security", 26, 3150)], "level 14", ""),
    ("Undead_chicken", "Undead chicken", [("standard", 7, 700)], "standard", ""),
    ("Grizzly_bear", "Grizzly bear", [("standard and Tirannwn", 32, 3750)],
     "standard and Tirannwn", ""),
    ("Ogress", None, [("club/spear", 58, 2100)], "club/spear", ""),
    ("Ogress_warrior", "Ogress warrior", [("club/spear", 63, 2250)], "club/spear", ""),
    ("Unicorn", None, [("standard", 15, 2100)], "standard", ""),
    ("Wolf", None, [("level 8 (Stronghold of Security)", 8, 1300),
                    ("level 11", 11, 1550)], None,
     "two documented levels with different LP"),
    ("Snake", None, [("standard", 5, 200)], "standard", ""),
    ("New_Varrock_guard", "New Varrock guard", [("New Varrock", 90, 16250),
                          ("summoned by Zemouregal", 56, 10000)], "New Varrock", ""),
    ("Scabaras_locust", "Scabaras locust", [("standard", 77, 2750)], "standard", ""),
    ("Locust_lancer", "Locust lancer", [("standard", 77, 2750)], "standard", ""),
    ("Locust_ranger", "Locust ranger", [("standard", 77, 2750)], "standard", ""),
    ("Boris", None, [("Canifis citizen", 28, 1000)], "Canifis citizen",
     "the other Canifis/Meiyerditch citizens were NOT fetched and get no entry"),
    ("Penguin", None, [], None,
     "FETCH RETURNED NO COMBAT INFOBOX: /w/Penguin is a disambiguation page and "
     "/w/Attackable_penguin returned 404. No documented LP."),
    ("Hero", None, [("East Ardougne", 51, 1850)], "East Ardougne", ""),
    ("Farmer", None, [("standard", 7, 250)], "standard", ""),
]


def main():
    c = sqlite3.connect("file:%s?mode=ro" % DB, uri=True)
    by_name = defaultdict(list)
    for i, g, n, cb in c.execute("select id, game_id, name, combat from npcs"):
        if n:
            by_name[n.strip().lower()].append(
                {"npc_id": g, "archive_id": i, "cache_combat": int(cb or 0)})

    out = []
    for page, dbname, variants, primary, note in D:
        name = (dbname or page.replace("_", " "))
        cands = by_name.get(name.strip().lower(), [])
        ids = sorted({x["npc_id"] for x in cands})
        if not cands:
            match_method = "no npcs.name equals %r -- unmatched" % name
            match_ambiguous = False
        elif len(ids) > 1:
            match_method = ("case-insensitive exact match on npcs.name == %r; "
                            "%d distinct npcs.game_id share it, NOT disambiguated"
                            % (name, len(ids)))
            match_ambiguous = True
        else:
            match_method = "case-insensitive exact match on npcs.name == %r" % name
            match_ambiguous = False

        vlist = [{"variant": v, "combat_level": cb, "lifepoints": lp}
                 for (v, cb, lp) in variants]
        prim = None
        for (v, cb, lp) in variants:
            if v == primary:
                prim = (cb, lp)
        distinct_lp = sorted({lp for (_, _, lp) in variants if lp is not None})
        distinct_cb = sorted({cb for (_, cb, _) in variants if cb is not None})

        lp_amb = primary is None and len(distinct_lp) > 1
        cb_amb = primary is None and len(distinct_cb) > 1

        e = {
            "name": name,
            "wiki_page": page,
            "npc_ids": ids,
            "npc_id_space": "npcs.game_id (in-game id)",
            "npc_archive_ids": sorted({x["archive_id"] for x in cands}),
            "npc_archive_id_space": "npcs.id (cache archive index)",
            "match_method": match_method,
            "match_ambiguous": match_ambiguous,
            "lifepoints": (prim[1] if prim else None),
            "combat_level": (prim[0] if prim else None),
            "primary_variant": primary,
            "variants": vlist,
            "distinct_documented_lifepoints": distinct_lp,
            "distinct_documented_combat_levels": distinct_cb,
            "value_ambiguous": bool(lp_amb or cb_amb),
            "note": note,
            "provenance": "documented",
            "source": BASE + page,
            "retrieved": RETRIEVED,
        }
        if not variants:
            e["status"] = "no_value_recorded"
        out.append(e)

    doc = {
        "_schema": "opennxt.seed.npc_documented/1",
        "_provenance": "documented",
        "_provenance_meaning": (
            "lifepoints and combat levels read from runescape.wiki article "
            "infoboxes via WebFetch on %s. Public reference material, not the "
            "user's cache. Per-value source URL below." % RETRIEVED),
        "_matching": (
            "npc ids were matched by case-insensitive exact npcs.name equality "
            "against the cache database; the wiki does not publish cache ids. "
            "Matches with >1 distinct game_id are flagged match_ambiguous=true "
            "and are NOT narrowed by guessing."),
        "_semantics": {
            "lifepoints/combat_level": "the primary_variant's value, or null when "
                                       "the wiki offers several and none is canonical",
            "value_ambiguous": "true when the wiki documents conflicting values and "
                               "no single one was chosen",
            "status=no_value_recorded": "fetch failed or page had no combat infobox",
        },
        "counts": {
            "entries": len(out),
            "with_lifepoints": sum(1 for e in out if e["lifepoints"] is not None),
            "value_ambiguous": sum(1 for e in out if e["value_ambiguous"]),
            "fetch_failed_or_no_infobox": sum(1 for e in out if e.get("status")),
            "id_match_ambiguous": sum(1 for e in out if e["match_ambiguous"]),
            "id_unmatched": sum(1 for e in out if not e["npc_ids"]),
        },
        "npcs": out,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=1)
    print("wrote %s" % OUT)
    for k, v in doc["counts"].items():
        print("  %-28s %d" % (k, v))
    for e in out:
        if not e["npc_ids"]:
            print("  UNMATCHED name: %s" % e["name"])
        if e.get("status"):
            print("  NO VALUE: %s -- %s" % (e["name"], e["note"][:70]))


if __name__ == "__main__":
    main()
