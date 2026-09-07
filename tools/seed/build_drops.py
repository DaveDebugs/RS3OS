#!/usr/bin/env python3
"""
build_drops.py -- emit data/seed/drops_documented.json

PROVENANCE: "documented". Every drop line below was read off a runescape.wiki
article by WebFetch on the date in RETRIEVED. The URL is recorded per monster.
Drop tables are NOT in the cache -- that was established by five independent
negative tests (see the project documentation, "Also not in the cache:
drop tables") -- so documentation is the only non-invented source there is.

What is and is not transcribed, stated plainly:

  * Quantities and rarities are carried VERBATIM as the wiki printed them
    ("5", "800-1,200", "64/128", "Always", "1/512; 1/496 with tier 2 luck").
    A parsed numerator/denominator is added alongside when the string is a
    plain fraction; when it is not, the parse fields are null and a consumer
    that needs a number must decide for itself, visibly.
  * The wiki's shared sub-tables (herb table, gem/rare drop table, seed
    table...) are recorded as REFERENCES with their access rarity, not
    expanded: their contents were not fetched per-monster, and inventing an
    expansion would be authoring under a documented label.
  * Item names are resolved against `items.name` in rs3.sqlite. Zero matches
    or several distinct ids are recorded as-is (ids: [] or many + ambiguous
    flag), never guessed.
  * Goblin was fetched twice and its drops section was not in the returned
    content either time; it appears with drops: [] and a note saying exactly
    that, because "no data retrieved" and "drops nothing" must not look alike.

    RS3_DB=data/rs3.sqlite python3 tools/seed/build_drops.py
"""
import json
import os
import re
import sqlite3

DB = os.environ.get("RS3_DB", "data/rs3.sqlite")
OUT = os.environ.get("DROPS_OUT", "data/seed/drops_documented.json")
RETRIEVED = ""
BASE = "https://runescape.wiki/w/"

A, MAIN, TERT, REF = "always", "main", "tertiary", "table_ref"

# (wiki_page, db_name_or_None, [(category, item_or_@table, qty, rarity, note), ...], page_note)
D = [
    ("Chicken", None, [
        (A, "Bones", "1", "Always", ""),
        (A, "Raw chicken", "1", "Always", ""),
        (MAIN, "Feather", "5", "64/128", ""),
        (MAIN, "Feather", "15", "32/128", ""),
        (MAIN, "@nothing", "", "32/128", ""),
        (TERT, "Cornucopia", "1", "1/128", ""),
    ], ""),
    ("Cow", None, [
        (A, "Bones", "1", "Always", ""),
        (A, "Cowhide", "1", "Always", ""),
        (A, "Raw beef", "1", "Always", ""),
        (TERT, "Chargebow", "1", "1/200", "varies; not from Astram Farm cows"),
        (TERT, "Staff of air", "1", "1/200", "varies; not from Astram Farm cows"),
        (TERT, "Spirit sapphire", "1", "5/2000", ""),
        (TERT, "Spirit emerald", "1", "3/2000", ""),
        (TERT, "Spirit ruby", "1", "2/2000", ""),
        (TERT, "Gold charm", "1", "9.95/1000", ""),
        (TERT, "Green charm", "1", "39.8/1000", ""),
        (TERT, "Crimson charm", "1", "2.49/1000", ""),
        (TERT, "Blue charm", "1", "0.498/1000", ""),
    ], ""),
    ("Goblin", None, [], "FETCH RETURNED NO DROPS SECTION: two fetches of /w/Goblin "
     "returned combat stats but no drops content. No drop data recorded."),
    ("Man", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Mind rune", "9", "2/128", ""),
        (MAIN, "Earth rune", "4", "2/128", ""),
        (MAIN, "Fire rune", "6", "2/128", ""),
        (MAIN, "Chaos rune", "2", "1/128", ""),
        (MAIN, "Bronze arrow", "7", "3/128", ""),
        (MAIN, "Bronze bolts", "2-12", "22/128", ""),
        (REF, "@herb_table", "", "23/128", ""),
    ], "wiki page truncated in fetch after the herb table; weapons/armour/coin "
       "rows exist on the wiki but were not retrieved"),
    ("Hill_giant", "Hill giant", [
        (A, "Big bones", "1", "Always", ""),
        (MAIN, "Mind rune", "3", "2/128", ""),
        (MAIN, "Water rune", "7", "3/128", ""),
        (MAIN, "Fire rune", "15", "3/128", ""),
        (MAIN, "Cosmic rune", "2", "2/128", ""),
        (MAIN, "Chaos rune", "2", "1/128", ""),
        (MAIN, "Nature rune", "6", "2/128", ""),
        (MAIN, "Death rune", "2", "1/128", ""),
        (MAIN, "Iron arrow", "3", "6/128", ""),
        (MAIN, "Steel arrow", "10", "5/128", ""),
        (MAIN, "Iron dagger", "1", "2/128", ""),
        (MAIN, "Iron off-hand dagger", "1", "2/128", ""),
        (MAIN, "Steel longsword", "1", "1/128", ""),
        (MAIN, "Steel off-hand longsword", "1", "1/128", ""),
        (MAIN, "Iron full helm", "1", "5/128", ""),
        (MAIN, "Iron kiteshield", "1", "3/128", "5/128 members"),
        (MAIN, "Coins", "8", "6/128", ""),
        (MAIN, "Coins", "15", "8/128", "F2P"),
        (MAIN, "Coins", "38", "14/128", "32/128 F2P"),
        (MAIN, "Coins", "52", "10/128", ""),
        (MAIN, "Body talisman", "1", "2/128", ""),
        (MAIN, "Beer", "1", "6/128", ""),
        (MAIN, "Limpwurt root", "1", "12/128", ""),
        (MAIN, "Grapevine seed", "2", "2/128", "members"),
        (MAIN, "Harralander seed", "2-3", "8/128", ""),
        (MAIN, "@nothing", "", "1/128", ""),
        (REF, "@herb_table", "", "7/128", ""),
        (REF, "@herb_seed_table", "", "18/100", "as printed on the wiki"),
        (REF, "@gem_table", "", "3/128", ""),
    ], ""),
    ("Lesser_demon", "Lesser demon", [
        (A, "Accursed ashes", "1", "Always", ""),
        (MAIN, "Coins", "800-1200", "14/128", ""),
        (MAIN, "Coins", "1120-1680", "8/128", ""),
        (MAIN, "Black 2h sword", "1", "8/128", ""),
        (MAIN, "Black hatchet", "1", "8/128", ""),
        (MAIN, "Black kiteshield", "1", "6/128", ""),
        (MAIN, "Black longsword", "1", "4/128", ""),
        (MAIN, "Off-hand black longsword", "1", "4/128", ""),
        (MAIN, "Fire rune", "27-33", "10/128", ""),
        (MAIN, "Death rune", "10", "6/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "6/128", ""),
        (MAIN, "Medium bladed mithril salvage", "1", "6/128", ""),
        (MAIN, "Tiny plated rune salvage", "1", "2/128", ""),
        (MAIN, "Gold stone spirit", "2", "8/128", ""),
        (MAIN, "Lobster", "1", "10/128", ""),
        (MAIN, "Grapes", "1", "6/128", "noted"),
        (REF, "@herb_table", "", "10/128", ""),
        (REF, "@gem_table", "", "12/128", ""),
    ], ""),
    ("Abyssal_demon", "Abyssal demon", [
        (A, "Infernal ashes", "1", "Always", ""),
        (A, "Binding contract (abyssal demon)", "1", "Always", "requires 85 Summoning/Slayer"),
        (MAIN, "Abyssal wand", "1", "1/6104", "1/3052 on-task; pre-roll"),
        (MAIN, "Abyssal orb", "1", "1/6104", "1/3052 on-task; pre-roll"),
        (MAIN, "Abyssal whip", "1", "1/1024", "1/512 on-task; pre-roll"),
        (MAIN, "Uncut ruby", "1", "14/128", ""),
        (MAIN, "Uncut diamond", "1", "6/128", ""),
        (MAIN, "Uncut dragonstone", "1", "2/128", ""),
        (MAIN, "Orichalcite stone spirit", "1", "4/128", ""),
        (MAIN, "Phasmatite stone spirit", "1", "4/128", ""),
        (MAIN, "Medium bladed adamant salvage", "1", "12/128", ""),
        (MAIN, "Tiny spiky rune salvage", "1", "8/128", ""),
        (MAIN, "Tiny plated rune salvage", "1", "4/128", ""),
        (MAIN, "Large plated rune salvage", "1", "4/128", ""),
        (MAIN, "Coins", "1920-2880", "18/128", ""),
        (MAIN, "Coins", "2400-3600", "14/128", ""),
        (MAIN, "Shark", "1", "14/128", ""),
        (MAIN, "Pure essence", "72-88", "12/128", "noted"),
        (MAIN, "Magic logs", "6", "6/128", "noted"),
        (MAIN, "Fire orb", "2", "6/128", "noted"),
        (REF, "@rare_drop_table", "", "1/150", ""),
    ], ""),
    ("Green_dragon", "Green dragon", [
        (A, "Dragon bones", "1", "Always", ""),
        (A, "Green dragonhide", "1", "Always", ""),
        (MAIN, "Fire rune", "37", "1/128", ""),
        (MAIN, "Law rune", "3", "3/128", ""),
        (MAIN, "Medium plated steel salvage", "1", "4/128", ""),
        (MAIN, "Medium bladed steel salvage", "1", "3/128", ""),
        (MAIN, "Tiny bladed mithril salvage", "1", "3/128", ""),
        (MAIN, "Tiny spiky mithril salvage", "1", "2/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "1/128", ""),
        (MAIN, "Small plated adamant salvage", "1", "1/128", ""),
        (MAIN, "Tiny spiky rune salvage", "1", "1/128", ""),
        (MAIN, "Bass", "1", "3/128", ""),
        (MAIN, "Lobster", "2", "3/128", ""),
        (MAIN, "Adamantite stone spirit", "1", "3/128", ""),
        (TERT, "Gold charm", "1", "66.2/1000", ""),
        (TERT, "Green charm", "1", "166/1000", ""),
        (TERT, "Crimson charm", "1", "66.2/1000", ""),
        (TERT, "Blue charm", "1", "13.2/1000", ""),
        (REF, "@herb_table", "", "15/128", ""),
        (REF, "@gem_table", "", "5/128", ""),
    ], "coin rows 11-750 at 1/128..29/128 summarised by the fetch; individual "
       "coin rows not fully retrieved"),
    ("Imp", None, [
        (A, "Impious ashes", "1", "Always", ""),
        (A, "Imphide", "1", "Always", ""),
        (MAIN, "Black bead", "1", "5/128", ""),
        (MAIN, "Red bead", "1", "5/128", ""),
        (MAIN, "White bead", "1", "5/128", ""),
        (MAIN, "Yellow bead", "1", "5/128", ""),
        (MAIN, "Bread", "2", "6/128", ""),
        (MAIN, "Clay", "2", "6/128", ""),
        (MAIN, "Grimy guam", "1", "6/128", ""),
        (REF, "@imp_wizard_table", "", "30/128", "air/mind/water/earth/fire/body "
         "rune x5 or wizard robe top/skirt, each 1/34.13"),
        (REF, "@imp_junk_table", "", "60/128", "wizard hat/burnt meat/cabbage/"
         "ashes/acne potion/chef's hat/flier/wheat, each 1/17.07"),
        (TERT, "Imp Champion's scroll", "1", "1/5000", "1/4000 with enhancer"),
    ], ""),
    ("Moss_giant", "Moss giant", [
        (A, "Big bones", "1", "Always", ""),
        (MAIN, "Black full helm", "1", "2/128", ""),
        (MAIN, "Black sq shield", "1", "5/128", ""),
        (MAIN, "Earth rune", "27", "3/128", "13/128 F2P"),
        (MAIN, "Air rune", "18", "3/128", ""),
        (MAIN, "Chaos rune", "7", "3/128", ""),
        (MAIN, "Nature rune", "6", "3/128", ""),
        (MAIN, "Cosmic rune", "3", "2/128", ""),
        (MAIN, "Death rune", "3", "1/128", ""),
        (MAIN, "Blood rune", "1", "1/128", ""),
        (MAIN, "Iron arrow", "15", "4/128", ""),
        (MAIN, "Steel arrow", "30", "1/128", ""),
        (MAIN, "Coal stone spirit", "1", "7/128", "9/128 F2P"),
        (MAIN, "Maple wood spirit", "6-7", "1/128", ""),
        (MAIN, "Acadia wood spirit", "5-6", "2/128", "3/128 F2P"),
        (MAIN, "Tiny plated steel salvage", "1", "2/128", ""),
        (MAIN, "Tiny spiky mithril salvage", "1", "2/128", ""),
        (MAIN, "Coins", "37", "8/128", ""),
        (MAIN, "Coins", "2", "19/128", "F2P"),
        (MAIN, "Coins", "300", "2/128", ""),
        (MAIN, "Grapevine seed", "1", "10/128", ""),
        (MAIN, "Grapevine seed", "2", "2/128", ""),
        (MAIN, "Irit seed", "1-2", "19/128", ""),
        (MAIN, "Spirit weed seed", "1", "2/128", ""),
        (MAIN, "Spinach roll", "1", "1/128", ""),
        (REF, "@herb_table", "", "5/128", ""),
        (REF, "@uncommon_seed_table", "", "33/128", ""),
        (REF, "@gem_table", "", "4/128", ""),
    ], ""),
    ("Greater_demon", "Greater demon", [
        (A, "Accursed ashes", "1", "Always", ""),
        (MAIN, "Iron stone spirit", "2", "14/128", ""),
        (MAIN, "Gold stone spirit", "4", "10/128", ""),
        (MAIN, "Mahogany wood spirit", "6", "8/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "8/128", ""),
        (MAIN, "Medium bladed adamant salvage", "1", "8/128", "8-10/128 as printed"),
        (MAIN, "Medium plated adamant salvage", "1", "6/128", ""),
        (MAIN, "Medium plated rune salvage", "1", "2/128", ""),
        (MAIN, "Wine of Zamorak", "1", "2/128", ""),
        (MAIN, "Inert adrenaline crystal", "1", "2/128", "noted"),
        (MAIN, "Coins", "1280-1920", "14/128", ""),
        (MAIN, "Coins", "1600-2400", "8/128", ""),
        (MAIN, "Pure essence", "54-66", "12/128", "noted"),
        (REF, "@herb_table", "", "22/128", ""),
        (REF, "@gem_table", "", "12/128", ""),
    ], ""),
    ("Skeleton", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Bronze arrow", "2", "7/128", ""),
        (MAIN, "Bronze arrow", "5", "4/128", ""),
        (MAIN, "Iron arrow", "1", "4/128", ""),
        (MAIN, "Air rune", "12", "2/128", ""),
        (MAIN, "Earth rune", "3", "2/128", ""),
        (MAIN, "Chaos rune", "3", "2/128", ""),
        (MAIN, "Fire rune", "2", "2/128", ""),
        (MAIN, "Nature rune", "3", "1/128", ""),
        (MAIN, "Steel arrow", "1", "1/128", ""),
        (MAIN, "Coins", "2", "18/128", ""),
        (MAIN, "Coins", "12", "15/128", ""),
        (MAIN, "Coins", "4", "7/128", ""),
        (MAIN, "Coins", "33", "4/128", ""),
        (MAIN, "Coins", "25", "4/128", ""),
        (MAIN, "Coins", "16", "4/128", ""),
        (MAIN, "Coins", "48", "1/128", ""),
        (MAIN, "@nothing", "", "18/128", ""),
        (MAIN, "Iron dagger", "1", "6/256", ""),
        (MAIN, "Iron off-hand dagger", "1", "6/256", ""),
        (MAIN, "Fire talisman", "1", "2/128", ""),
        (MAIN, "Iron ore", "1", "1/128", ""),
        (MAIN, "Wheat", "1", "1/128", ""),
        (REF, "@herb_table", "", "21/128", ""),
        (REF, "@gem_table", "", "1/128", ""),
    ], ""),
    ("Rock_crab", "Rock crab", [
        (MAIN, "Bronze pickaxe", "1", "6/128", ""),
        (MAIN, "Iron pickaxe", "1", "5/128", ""),
        (MAIN, "Tin ore", "3", "4/128", ""),
        (MAIN, "Copper ore", "3", "2/128", ""),
        (MAIN, "Coal", "2", "2/128", ""),
        (MAIN, "Iron ore", "1", "2/128", ""),
        (MAIN, "Seaweed", "1", "4/128", "noted"),
        (MAIN, "Seaweed", "2", "4/128", "noted"),
        (MAIN, "Seaweed", "5", "2/128", "noted"),
        (MAIN, "Edible seaweed", "2", "2/128", ""),
        (MAIN, "Oyster", "2", "12/128", ""),
        (MAIN, "Oyster", "1", "9/128", ""),
        (MAIN, "Empty oyster", "1", "3/128", ""),
        (MAIN, "Empty oyster", "3", "1/128", ""),
        (MAIN, "Oyster pearl", "1", "1/128", ""),
        (MAIN, "Coins", "4", "29/128", ""),
        (MAIN, "Coins", "36", "8/128", ""),
        (MAIN, "Coins", "8", "6/128", ""),
        (MAIN, "@nothing", "", "19/128", ""),
        (MAIN, "Fishing bait", "10", "2/128", ""),
        (MAIN, "Opal bolt tips", "5", "2/128", ""),
        (MAIN, "Casket", "1", "1/128", ""),
        (MAIN, "Spinach roll", "1", "1/128", ""),
        (REF, "@gem_table", "", "1/128", ""),
        (TERT, "Sealed clue scroll (easy)", "1", "1/128", ""),
        (TERT, "Spirit sapphire", "1", "5/2000", ""),
        (TERT, "Spirit emerald", "1", "3/2000", ""),
        (TERT, "Spirit ruby", "1", "2/2000", ""),
        (TERT, "Mimic kill token", "1", "1/9953", ""),
    ], ""),
    ("Gargoyle", None, [
        (A, "Binding contract (gargoyle)", "1", "Always", ""),
        (MAIN, "Dark mystic robe top", "1", "1/512", "1/496 with tier 2 luck"),
        (MAIN, "Granite maul", "1", "1/512", "1/496 with tier 2 luck"),
        (MAIN, "Rune arrow", "40", "8/128", ""),
        (MAIN, "Adamantite stone spirit", "2", "8/128", ""),
        (MAIN, "Luminite stone spirit", "2", "12/128", ""),
        (MAIN, "Runite stone spirit", "1", "8/128", ""),
        (MAIN, "Tiny plated adamant salvage", "1", "16/128", ""),
        (MAIN, "Small plated adamant salvage", "1", "12/128", ""),
        (MAIN, "Medium bladed rune salvage", "1", "6/128", ""),
        (MAIN, "Medium plated rune salvage", "1", "8/128", ""),
        (MAIN, "Large plated rune salvage", "1", "4/128", ""),
        (MAIN, "Coins", "2000-2400", "12/128", ""),
        (MAIN, "Uncut emerald", "1", "18/128", ""),
        (MAIN, "Uncut ruby", "1", "10/128", ""),
        (MAIN, "Mort myre fungus", "2", "6/128", "noted"),
        (TERT, "Gold charm", "1", "97.8/1000", ""),
        (TERT, "Blue charm", "1", "68.4/1000", ""),
        (TERT, "Green charm", "1", "48.9/1000", ""),
        (TERT, "Crimson charm", "1", "48.9/1000", ""),
        (TERT, "Congealed blood", "3-11", "1/15", ""),
        (TERT, "Sealed clue scroll (hard)", "1", "1/128", ""),
        (TERT, "Spirit sapphire", "1", "5/2000", ""),
        (TERT, "Spirit emerald", "1", "3/2000", ""),
        (TERT, "Spirit ruby", "1", "2/2000", ""),
        (TERT, "Mimic kill token", "1", "1/9907", ""),
    ], ""),
    ("Fire_giant", "Fire giant", [
        (A, "Big bones", "1", "Always", ""),
        (MAIN, "Fire battlestaff", "1", "1/128", ""),
        (MAIN, "Law rune", "2", "1/128", ""),
        (MAIN, "Blood rune", "5", "4/128", ""),
        (MAIN, "Rune arrow", "12", "5/128", ""),
        (MAIN, "Tiny bladed steel salvage", "1", "11/128", ""),
        (MAIN, "Small plated mithril salvage", "1", "2/128", ""),
        (MAIN, "Medium bladed rune salvage", "1", "1/128", ""),
        (MAIN, "Coins", "15", "14/128", ""),
        (MAIN, "Coins", "25", "6/128", ""),
        (MAIN, "Coins", "50", "1/128", ""),
        (MAIN, "Coins", "60", "40/128", ""),
        (MAIN, "Coins", "300", "2/128", ""),
        (MAIN, "Lobster", "1", "5/128", ""),
        (MAIN, "Strength potion (2)", "1", "1/128", ""),
        (MAIN, "Coal stone spirit", "1", "2/128", ""),
        (REF, "@herb_table", "", "20/128", ""),
        (REF, "@gem_table", "", "12/128", ""),
        (REF, "@rare_drop_table", "", "1/250", ""),
    ], ""),
    ("Blue_dragon", "Blue dragon", [
        (A, "Dragon bones", "1", "Always", ""),
        (A, "Blue dragonhide", "1", "Always", ""),
        (A, "Perfect blue dragon scale", "1", "Always", "only until obtained on Slayer challenge"),
        (MAIN, "Fire rune", "37", "1/128", ""),
        (MAIN, "Law rune", "3", "3/128", ""),
        (MAIN, "Adamantite stone spirit", "1", "3/128", ""),
        (MAIN, "Bass", "1", "3/128", ""),
        (MAIN, "Lobster", "1", "3/128", "F2P only"),
        (TERT, "Gold charm", "1", "113/1000", ""),
        (TERT, "Green charm", "1", "282/1000", ""),
        (TERT, "Crimson charm", "1", "113/1000", ""),
        (TERT, "Blue charm", "1", "22.5/1000", ""),
        (REF, "@herb_table", "", "15/128", ""),
        (REF, "@gem_table", "", "5/128", ""),
    ], "salvage rows (steel/mithril/adamant/rune at 1/128-4/128) and coin rows "
       "(11-900 coins at 1/128-29/128) summarised by the fetch; individual rows "
       "not retrieved"),
    ("Black_dragon", "Black dragon", [
        (A, "Dragon bones", "1", "Always", ""),
        (A, "Black dragonhide", "1", "Always", ""),
        (A, "Perfect black dragon scale", "1", "Always", ""),
        (MAIN, "Adamant javelin", "30", "26/128", ""),
        (MAIN, "Adamant dart", "16", "7/256", ""),
        (MAIN, "Off-hand adamant dart", "16", "7/256", ""),
        (MAIN, "Rune knife", "2", "3/256", ""),
        (MAIN, "Off-hand rune knife", "2", "3/256", ""),
        (MAIN, "Air rune", "75", "1/128", ""),
        (MAIN, "Fire rune", "50", "8/128", ""),
        (MAIN, "Law rune", "10", "5/128", ""),
        (MAIN, "Blood rune", "5-10", "3/128", ""),
        (MAIN, "Tiny bladed mithril salvage", "1", "3/128", ""),
        (MAIN, "Medium bladed mithril salvage", "1", "7/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "1/128", ""),
        (MAIN, "Huge plated adamant salvage", "1", "1/128", ""),
        (MAIN, "Medium bladed rune salvage", "1", "1/128", ""),
        (MAIN, "Coins", "196", "40/128", ""),
        (MAIN, "Coins", "330", "10/128", ""),
        (MAIN, "Coins", "690", "1/128", ""),
        (MAIN, "Adamantite stone spirit", "1", "3/128", ""),
        (MAIN, "Chocolate cake", "1", "3/128", ""),
        (TERT, "Gold charm", "3", "89.6/1000", ""),
        (TERT, "Green charm", "3", "269/1000", ""),
        (TERT, "Crimson charm", "3", "67.2/1000", ""),
        (TERT, "Blue charm", "3", "13.4/1000", ""),
        (REF, "@gem_table", "", "5/128", ""),
        (REF, "@rare_drop_table", "", "1/250", ""),
    ], ""),
    ("Red_dragon", "Red dragon", [
        (A, "Dragon bones", "1", "Always", ""),
        (A, "Red dragonhide", "1", "Always", ""),
        (A, "Perfect red dragon scale", "1", "Always", ""),
        (MAIN, "Mithril javelin", "20", "1/128", ""),
        (MAIN, "Rune dart", "1-8", "3/256; 7/256", "as printed by the fetch"),
        (MAIN, "Off-hand rune dart", "1-8", "3/256; 7/256", "as printed by the fetch"),
        (MAIN, "Law rune", "4", "5/128", ""),
        (MAIN, "Death rune", "5", "3/128", ""),
        (MAIN, "Blood rune", "2", "4/128", ""),
        (MAIN, "Rune arrow", "4", "8/128", ""),
        (MAIN, "Tiny bladed mithril salvage", "1", "3/128", ""),
        (MAIN, "Medium bladed mithril salvage", "1", "7/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "1/128", ""),
        (MAIN, "Huge plated adamant salvage", "1", "1/128", ""),
        (MAIN, "Medium bladed rune salvage", "1", "1/128", ""),
        (MAIN, "Coins", "66", "29/128", ""),
        (MAIN, "Coins", "196", "40/128", ""),
        (MAIN, "Coins", "330", "10/128", ""),
        (MAIN, "Coins", "690", "1/128", ""),
        (MAIN, "Chocolate cake", "3", "3/128", ""),
        (MAIN, "Adamantite stone spirit", "1", "1/128", ""),
        (REF, "@herb_table", "", "2/128", ""),
        (REF, "@gem_table", "", "5/128", ""),
    ], "tertiary charm rows summarised by the fetch ('rates vary "
       "significantly'); individual charm rows not retrieved"),
    ("Black_demon", "Black demon", [
        (A, "Infernal ashes", "1", "Always", ""),
        (MAIN, "Black scimitar", "1", "4/128", ""),
        (MAIN, "Off-hand black scimitar", "1", "4/128", ""),
        (MAIN, "Black platebody", "1", "8/128", ""),
        (MAIN, "Law rune", "10", "4/128", ""),
        (MAIN, "Blood rune", "10", "6/128", ""),
        (MAIN, "Grimy ranarr", "1", "10/128", "noted"),
        (MAIN, "Grimy spirit weed", "1", "8/128", "noted"),
        (MAIN, "Grimy irit", "1", "8/128", "noted"),
        (MAIN, "Chilli potato", "1", "10/128", ""),
        (MAIN, "Wine of Zamorak", "1", "4/128", ""),
        (MAIN, "Tiny plated rune salvage", "1", "4/128", ""),
        (MAIN, "Large plated rune salvage", "1", "2/128", ""),
        (MAIN, "Coins", "1600-2400", "14/128", ""),
        (MAIN, "Coins", "2240-3360", "8/128", ""),
        (MAIN, "Grapes", "2", "8/128", "noted"),
        (MAIN, "Uncut ruby", "1", "8/128", ""),
        (MAIN, "Adamantite stone spirit", "1", "4/128", ""),
        (REF, "@gem_table", "", "14/128", ""),
        (REF, "@rare_drop_table", "", "1/200", ""),
    ], ""),
    ("Ice_giant", "Ice giant", [
        (A, "Big bones", "1", "Always", ""),
        (MAIN, "Coins", "8", "7/128", ""),
        (MAIN, "Coins", "22", "6/128", ""),
        (MAIN, "Coins", "52", "4/128", ""),
        (MAIN, "Coins", "53", "13/128", ""),
        (MAIN, "Coins", "117", "32/128", ""),
        (MAIN, "Coins", "196", "13/128", ""),
        (MAIN, "Coins", "400", "2/128", ""),
        (MAIN, "Mind rune", "24", "3/128", ""),
        (MAIN, "Law rune", "3", "2/128", ""),
        (MAIN, "Water rune", "12", "1/128", ""),
        (MAIN, "Death rune", "3", "1/128", ""),
        (MAIN, "Cosmic rune", "4", "1/128", ""),
        (MAIN, "Medium plated iron salvage", "1", "1/128", ""),
        (MAIN, "Medium bladed iron salvage", "1", "5/128", ""),
        (MAIN, "Tiny spiky steel salvage", "1", "4/128", ""),
        (MAIN, "Tiny bladed steel salvage", "1", "4/128", ""),
        (MAIN, "Tiny blunt mithril salvage", "1", "1/128", ""),
        (MAIN, "Small plated mithril salvage", "1", "1/128", ""),
        (MAIN, "Jug of wine", "1", "3/128", ""),
        (MAIN, "Banana", "1", "1/128", ""),
        (MAIN, "Mithril stone spirit", "1", "2/128", ""),
        (MAIN, "Adamant arrow", "5", "6/128", ""),
        (MAIN, "Black kiteshield", "1", "4/128", ""),
        (REF, "@uncommon_seed_table", "", "8/128", "rows 1/120.5-1/16384 "
         "summarised by the fetch"),
        (REF, "@gem_table", "", "4/128", "fetch listed the gem table under a "
         "tertiary heading"),
    ], ""),
    ("Dust_devil", "Dust devil", [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Dragon chainbody", "1", "1/32768", "1/31744 with tier 2+ luck"),
        (MAIN, "Rune arrow", "15", "8/128", ""),
        (MAIN, "Soul rune", "5", "4/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "6/128", ""),
        (MAIN, "Tiny spiky rune salvage", "1", "2/128", ""),
        (MAIN, "Coins", "600", "32/128", ""),
        (MAIN, "Coins", "1500", "10/128", ""),
        (MAIN, "Bucket of sand", "4", "10/128", "noted"),
        (MAIN, "Soda ash", "4", "10/128", "noted"),
        (MAIN, "Ugthanki kebab", "2", "12/128", ""),
        (MAIN, "Mithril stone spirit", "1", "4/128", ""),
        (REF, "@herb_table", "", "14/128", ""),
        (REF, "@seed_table", "", "14/128", "limpwurt through torstol seeds per "
         "the fetch, rows 1/68.8-1/9362.3 summarised"),
        (REF, "@gem_table", "", "2/128", ""),
    ], ""),
    ("Nechryael", None, [
        (A, "Infernal ashes", "1", "Always", ""),
        (MAIN, "Death rune", "10", "8/128", ""),
        (MAIN, "Chaos rune", "37", "1/128", ""),
        (MAIN, "Fellstalk seed", "1", "3/128", ""),
        (MAIN, "Morchella mushroom spore", "4", "2/128", ""),
        (MAIN, "Toadflax seed", "1", "1/33.1", ""),
        (MAIN, "Irit seed", "1", "1/49", ""),
        (MAIN, "Cave nightshade seed", "1", "1/49", ""),
        (MAIN, "Poison ivy seed", "1", "1/71.1", ""),
        (MAIN, "Avantoe seed", "1", "1/71.1", ""),
        (MAIN, "Cactus seed", "1", "1/74.9", ""),
        (MAIN, "Kwuarm seed", "1", "1/101.6", ""),
        (MAIN, "Snapdragon seed", "1", "1/158", ""),
        (MAIN, "Cadantine seed", "1", "1/237", ""),
        (MAIN, "Lantadyme seed", "1", "1/474.1", ""),
        (MAIN, "Dwarf weed seed", "1", "1/474.1", ""),
        (MAIN, "Spirit weed seed", "1", "1/474.1", ""),
        (MAIN, "Torstol seed", "1", "1/711.1", ""),
        (MAIN, "Tiny bladed steel salvage", "1", "3/128", ""),
        (MAIN, "Medium bladed steel salvage", "1", "7/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "1/128", ""),
        (MAIN, "Medium plated adamant salvage", "1", "1/128", ""),
        (MAIN, "Tiny plated rune salvage", "1", "1/128", "1/124 with tier 2 luck"),
        (MAIN, "Medium plated rune salvage", "1", "1/128", ""),
        (MAIN, "Sealed clue scroll (hard)", "1", "1/128", ""),
        (MAIN, "Tuna", "1", "3/128", ""),
        (MAIN, "Gold stone spirit", "1", "2/128", ""),
        (MAIN, "Thread", "10", "1/128", ""),
        (MAIN, "Coins", "11", "7/128", ""),
        (MAIN, "Coins", "44", "29/128", ""),
        (MAIN, "Coins", "132", "17/128", ""),
        (MAIN, "Coins", "220", "18/128", ""),
        (MAIN, "Coins", "460", "1/128", ""),
        (REF, "@gem_table", "", "4/128", ""),
        (REF, "@rare_drop_table", "", "1/250", ""),
    ], ""),
    ("Dark_beast", "Dark beast", [
        (A, "Big bones", "1", "Always", ""),
        (MAIN, "Dark bow", "1", "1/1024", "1/1014 with tier 3 luck; pre-roll"),
        (MAIN, "Death rune", "10", "10/128", ""),
        (MAIN, "Grimy toadflax", "2", "10/128", "noted"),
        (MAIN, "Grimy lantadyme", "1", "8/128", "noted"),
        (MAIN, "Grimy dwarf weed", "1", "8/128", "noted"),
        (MAIN, "Fellstalk seed", "3", "6/128", ""),
        (MAIN, "Toadflax seed", "1", "1/49.6", ""),
        (MAIN, "Irit seed", "1", "1/73.6", ""),
        (MAIN, "Cave nightshade seed", "1", "1/73.6", ""),
        (MAIN, "Poison ivy seed", "1", "1/106.7", ""),
        (MAIN, "Avantoe seed", "1", "1/106.7", ""),
        (MAIN, "Cactus seed", "1", "1/112.3", ""),
        (MAIN, "Kwuarm seed", "1", "1/152.4", ""),
        (MAIN, "Snapdragon seed", "1", "1/237", ""),
        (MAIN, "Cadantine seed", "1", "1/355.6", ""),
        (MAIN, "Lantadyme seed", "1", "1/711.1", ""),
        (MAIN, "Dwarf weed seed", "1", "1/711.1", ""),
        (MAIN, "Spirit weed seed", "1", "1/711.1", ""),
        (MAIN, "Torstol seed", "1", "1/1066.7", ""),
        (MAIN, "Medium plated rune salvage", "1", "4/128", ""),
        (MAIN, "Large plated rune salvage", "1", "4/128", ""),
        (MAIN, "Coins", "8000-10000", "18/128", ""),
        (MAIN, "Coins", "13000-17000", "6/128", ""),
        (MAIN, "Death talisman", "1", "12/128", ""),
        (MAIN, "Shark", "2", "12/128", ""),
        (MAIN, "Necrite stone spirit", "1", "10/128", ""),
        (MAIN, "Dark arrowheads", "5-15", "8/128", ""),
        (REF, "@rare_drop_table", "", "1/200", ""),
    ], "tertiary charm rows not detailed in the fetched content"),
    ("Bloodveld", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Black boots", "1", "1/128", ""),
        (MAIN, "Rune arrow", "40", "7/128", ""),
        (MAIN, "Grimy kwuarm", "1", "12/128", ""),
        (MAIN, "Wergali seed", "1", "12/128", ""),
        (MAIN, "Luminite stone spirit", "4", "8/128", ""),
        (MAIN, "Runite stone spirit", "2", "10/128", ""),
        (MAIN, "Acadia wood spirit", "2", "6/128", ""),
        (MAIN, "Large bladed steel salvage", "1", "8/128", ""),
        (MAIN, "Huge plated steel salvage", "1", "4/128", ""),
        (MAIN, "Medium plated mithril salvage", "1", "6/128", ""),
        (MAIN, "Coins", "600-1000", "18/128", ""),
        (MAIN, "Coins", "1200-1800", "8/128", ""),
        (MAIN, "Coins", "3000-3800", "4/128", ""),
        (MAIN, "Meat pizza", "1", "14/128", ""),
        (MAIN, "Big bones", "4", "2/128", "noted"),
        (REF, "@gem_table", "", "8/128", ""),
    ], "charm rows listed on the wiki but their rates were not present in the "
       "fetched content"),
    ("Ghoul", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Law talisman", "1", "1/128", ""),
        (TERT, "Congealed blood", "3-11", "1/20", ""),
        (TERT, "Ghoul bone", "1", "1/4", "Rag and Bone Man wish list only"),
        (TERT, "Ghoul Champion's scroll", "1", "1/5000", "1/4000 with enhancer"),
        (TERT, "Starved ancient effigy", "1", "1/72000-1/360000",
         "special conditions apply"),
        (TERT, "Spirit sapphire", "1", "5/2000", ""),
        (TERT, "Spirit emerald", "1", "3/2000", ""),
        (TERT, "Spirit ruby", "1", "2/2000", ""),
        (TERT, "Mimic kill token", "1", "1/9950", ""),
        (TERT, "Gold charm", "1", "189/1000", ""),
        (TERT, "Crimson charm", "1", "11.8/1000", ""),
        (TERT, "Green charm", "1", "5.89/1000", ""),
        (TERT, "Blue charm", "1", "1.18/1000", ""),
    ], ""),
    ("Zombie", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Iron arrow", "8", "7/128", "level 12"),
        (MAIN, "Body rune", "6", "5/128", "level 12"),
        (MAIN, "Mind rune", "5", "5/128", "level 12"),
        (MAIN, "Air rune", "13", "4/128", "level 12"),
        (MAIN, "Iron arrow", "8", "4/128", "level 12"),
        (MAIN, "Steel arrow", "5", "2/128", "level 12"),
        (MAIN, "Nature rune", "6", "1/128", "level 12"),
        (MAIN, "Bronze med helm", "1", "4/128", "level 12"),
        (MAIN, "Bronze longsword", "1", "1/256", "level 12"),
        (MAIN, "Bronze off hand longsword", "1", "1/256", "level 12"),
        (MAIN, "Fishing bait", "5", "37/128", "level 12"),
        (MAIN, "Copper ore", "1", "2/128", "level 12"),
        (MAIN, "Iron hatchet", "1", "1/128", "level 12"),
        (REF, "@herb_table", "", "25/128", "level 12"),
        (MAIN, "Iron mace", "1", "3/256", "level 29"),
        (MAIN, "Iron off hand mace", "1", "3/256", "level 29"),
        (MAIN, "Iron dagger", "1", "2/256", "level 29"),
        (MAIN, "Iron off hand dagger", "1", "2/256", "level 29"),
        (MAIN, "Bronze kiteshield", "1", "1/128", "level 29"),
        (MAIN, "Air rune", "3", "3/128", "level 29"),
        (MAIN, "Mithril arrow", "1", "3/128", "level 29"),
        (MAIN, "Body rune", "3", "2/128", "level 29"),
        (MAIN, "Fire rune", "7", "1/128", "level 29"),
        (MAIN, "Chaos rune", "4", "1/128", "level 29"),
        (MAIN, "Cosmic rune", "2", "1/128", "level 29"),
        (MAIN, "Fishing bait", "7", "26/128", "level 29"),
        (MAIN, "Tinderbox", "1", "2/128", "level 29"),
        (MAIN, "Tin ore", "1", "1/128", "level 29"),
        (MAIN, "Eye of newt", "1", "1/128", "level 29"),
        (REF, "@herb_table", "", "30/128", "level 29"),
        (TERT, "Zombie bone", "1", "1/4", "Rag and Bone Man wish list only"),
        (TERT, "Zombie Champion's scroll", "1", "1/5000", "1/4000 with enhancer"),
        (TERT, "Starved ancient effigy", "1", "Very rare", ""),
    ], "coin rows for levels 12 and 29 summarised by the fetch (4-28 coins at "
       "2/128-11/128 and 1-35 coins at 2/128-21/128); individual coin rows not "
       "retrieved; level 22 zombies have only the bones drop plus tertiaries"),
    ("Giant_rat", "Giant rat", [
        (A, "Bones", "1", "Always", ""),
        (A, "Raw rat meat", "1", "Always", "not dropped by certain variants in "
         "Stronghold of Security or Daemonheim"),
        (TERT, "Giant rat bone", "1", "Always", "only during Rag and Bone Man"),
        (TERT, "Rat's tail", "1", "Always", "only during Witch's Potion, or "
         "Stronghold of Security variant"),
    ], ""),
    ("Cave_crawler", "Cave crawler", [
        (MAIN, "Fire rune", "12", "5/128", ""),
        (MAIN, "Nature rune", "3", "5/128", ""),
        (MAIN, "Earth rune", "9", "2/128", ""),
        (MAIN, "Potato seed", "1-4", "1/16.3", ""),
        (MAIN, "Onion seed", "1-3", "1/32.5", ""),
        (MAIN, "Cabbage seed", "1-3", "1/65", ""),
        (MAIN, "Tomato seed", "1-2", "1/130", ""),
        (MAIN, "Sweetcorn seed", "1-2", "1/260", ""),
        (MAIN, "Strawberry seed", "1", "1/520", ""),
        (MAIN, "Evil turnip seed", "1-4", "1/520", ""),
        (MAIN, "Watermelon seed", "1", "1/1040", ""),
        (MAIN, "Vial of water", "1", "13/128", ""),
        (MAIN, "White berries", "1", "5/128", ""),
        (MAIN, "Unicorn horn dust", "1", "2/128", ""),
        (MAIN, "Red spiders' eggs", "1", "1/128", ""),
        (MAIN, "Eye of newt", "1", "1/128", ""),
        (MAIN, "Limpwurt root", "1", "1/128", ""),
        (MAIN, "Snape grass", "1", "1/128", ""),
        (TERT, "Gold charm", "1", "98.8/1000", ""),
        (TERT, "Green charm", "1", "12.4/1000", ""),
        (TERT, "Crimson charm", "1", "8.23/1000", ""),
        (TERT, "Blue charm", "1", "2.47/1000", ""),
        (REF, "@herb_table", "", "33/128", ""),
        (REF, "@gem_table", "", "1/128", "fetch: 'gem and rare drop table'"),
    ], "seed rows above are the fetch's itemised allotment-table rows (16/128 "
       "access); coin rows (3, 8, 10, 29 coins at 1/128-5/128) summarised by "
       "the fetch; individual coin rows not retrieved"),
    ("Banshee", None, [
        (MAIN, "Chaos rune", "10", "6/128", ""),
        (MAIN, "Mithril arrow", "40", "6/128", ""),
        (MAIN, "Grapevine seed", "2", "4/128", ""),
        (MAIN, "Coins", "500-700", "22/128", ""),
        (MAIN, "Coins", "1000-1400", "4/128", ""),
        (MAIN, "Pike", "1", "12/128", ""),
        (MAIN, "Iron stone spirit", "1", "8/128", ""),
        (MAIN, "Oak wood spirit", "1-2", "6/128", ""),
        (MAIN, "Uncut sapphire", "1", "4/128", ""),
        (MAIN, "Thin snail", "1", "4/128", ""),
        (REF, "@herb_table", "", "30/128", "fetch also itemised the herb rows "
         "at effective rates 1/17.1-1/182; kept as a ref per this file's "
         "sub-table policy"),
        (REF, "@uncommon_seed_table", "", "22/128", "limpwurt through torstol "
         "rows 1/43.8-1/5957.8 summarised by the fetch"),
        (TERT, "Bone fragments", "1", "15/150", ""),
        (TERT, "Sealed clue scroll (easy)", "1", "1/128", ""),
        (TERT, "Ghostly essence", "1-4", "1/256 to 1/96", ""),
        (TERT, "Ectoplasmator", "1", "1/1000", ""),
        (TERT, "Deployable herb burner", "1", "1/1024", ""),
        (TERT, "Banshee Champion's scroll", "1", "1/5000", ""),
        (TERT, "Dark mystic gloves", "1", "1/512", ""),
    ], "ghost hunter equipment rows (1/2000; October 1/1500), a 'Cremation "
       "unlock' row (1/1000) and spirit gem/charm rows summarised by the "
       "fetch; individual rows not retrieved"),
    ("Cockatrice", None, [
        (A, "Bones", "1", "Always", ""),
        (MAIN, "Cockatrice egg", "1", "1/10", "pre-roll"),
        (MAIN, "Light mystic boots", "1", "1/512", "1/496 with tier 1 luck; pre-roll"),
        (MAIN, "Cockatrice head", "1", "1/1000", "1/500 with Mask of Stone/Helm "
         "of Petrification; pre-roll"),
        (MAIN, "Nature rune", "2", "6/128", ""),
        (MAIN, "Nature rune", "4", "4/128", ""),
        (MAIN, "Nature rune", "6", "2/128", ""),
        (MAIN, "Law rune", "2", "3/128", ""),
        (MAIN, "Fire rune", "7", "2/128", ""),
        (MAIN, "Water rune", "2", "2/128", ""),
        (MAIN, "Limpwurt root", "1", "21/128", ""),
        (MAIN, "Grapevine seed", "4", "12/128", ""),
        (MAIN, "Grapevine seed", "2", "1/128", ""),
        (MAIN, "Iron javelin", "5", "1/128", ""),
        (MAIN, "Spirit weed seed", "1", "1/128", ""),
        (MAIN, "Willow wood spirit", "4", "3/128", ""),
        (MAIN, "Teak wood spirit", "3", "2/128", ""),
        (MAIN, "Tiny plated iron salvage", "1", "2/128", ""),
        (MAIN, "Small bladed steel salvage", "1", "1/128", ""),
        (REF, "@herb_table", "", "10/128", ""),
        (REF, "@allotment_seed_table", "", "18/128", "potato through watermelon "
         "seeds per the fetch, rows 1/19.3-1/646.4 summarised"),
        (REF, "@gem_table", "", "2/128", ""),
    ], "coin rows (5-62 coins at 3/128-16/128) summarised by the fetch; "
       "individual coin rows not retrieved"),
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

    npc_ids = {}
    for i, n in con.execute("SELECT id, name FROM npcs WHERE name IS NOT NULL"):
        npc_ids.setdefault(n.lower(), []).append(i)

    monsters = []
    unresolved = 0
    lines = 0
    for wiki_page, db_name, drops, page_note in D:
        mname = db_name or wiki_page.replace("_", " ")
        ids = npc_ids.get(mname.lower(), [])
        rows = []
        for cat, item, qty, rarity, note in drops:
            num, den = parse_rarity(rarity)
            row = {
                "category": cat if not item.startswith("@") or cat == REF else cat,
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
            rows.append(row)
            lines += 1
        monsters.append({
            "name": mname,
            "wiki_page": wiki_page,
            "npc_ids": ids,
            "npc_id_space": "npcs.id == game id (unified database)",
            "npc_match_ambiguous": len(ids) > 1,
            "drops": rows,
            "note": page_note or None,
            "provenance": "documented",
            "source": BASE + wiki_page,
            "retrieved": RETRIEVED,
        })

    out = {
        "_schema": "opennxt.seed.drops_documented/1",
        "_provenance": "documented",
        "_provenance_meaning": (
            "every drop line was read off the runescape.wiki article in `source` "
            "by WebFetch on `retrieved`; quantities and rarities are verbatim "
            "wiki strings with a parsed fraction alongside when parseable; "
            "shared sub-tables are references, not expansions"),
        "counts": {
            "monsters": len(monsters),
            "drop_lines": lines,
            "unresolved_item_names": unresolved,
        },
        "monsters": monsters,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    print("wrote %s: %d monsters, %d drop lines, %d unresolved item names"
          % (OUT, len(monsters), lines, unresolved))
    for m in monsters:
        bad = [r["item"] for r in m["drops"] if not r.get("is_table_ref") and not r["item_ids"]]
        if bad:
            print("  %-18s unresolved: %s" % (m["name"], ", ".join(bad)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
