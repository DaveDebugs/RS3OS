# Where the seed data comes from

Everything in this directory is *derived* data: it was produced by the scripts in
`tools/seed/`, not written by hand, and any of it can be regenerated from source.

## RuneScape Wiki — CC BY-NC-SA 3.0

These files are derived from the [RuneScape Wiki](https://runescape.wiki) and are
used under the [Creative Commons Attribution-NonCommercial-ShareAlike 3.0
licence](https://creativecommons.org/licenses/by-nc-sa/3.0/), which is the
wiki's own licence. Attribution is to the RuneScape Wiki and its contributors.

| File | What it holds | Built by |
|---|---|---|
| `npc_wiki.json` | Monster stats by NPC id, from Infobox Monster | `build_wiki_seed.py` |
| `npc_wiki_pages.jsonl.gz` | The raw wiki pages the above was parsed from | `fetch_wiki_pages.py` |
| `drops_wiki.json` | Drop tables for around 1,500 monsters | `build_wiki_drops.py` |
| `skill_xp_wiki.json` | Per-action level and experience, 23 skills | `build_wiki_xp.py` |
| `skill_chance_wiki.json` | Per-action success chance formulas | `build_wiki_xp.py` |
| `xp_curve_wiki.json` | The experience-to-level curve | `build_wiki_xp.py` |
| `cooking_wiki.json` | Cooking levels, burn stops and venues | `build_wiki_cooking.py` |
| `dialogue_wiki.json` | 4,514 conversations from the Transcript namespace | `build_dialogue_seed.py` |

Because the source is licensed **NonCommercial**, so is any use of these files.

## Your own cache — not committed

`npc_cache.json` is **not** in this repository, and never will be: it is decoded
from Jagex's cache, which is not ours to redistribute. Setup generates it on your
machine from the database you built, and the server refuses to start without it:

```bash
python3 tools/seed/seed_from_cache.py        # or: ./setup.sh --step seed
```

It holds boss health-bar maxima, max hits, affinities, armour, accuracy and
combat levels — every value decoded from your own cache, none of it guessed.

## Jagex Bestiary

`npc_bestiary.json` and `npc_bestiary_raw.jsonl.gz` come from Jagex's public
Bestiary API, queried per NPC id by `build_bestiary_seed.py`. They carry
lifepoints, experience, aggression and animation ids.

## Derived tables

`npc_anims_observed.tsv` and `tree_stumps_observed.tsv` are small derived
tables: animation ids by NPC, and which stump a felled tree becomes. Only the
derived rows are committed, and the sessions behind them are referred to by
anonymous ids.

## Regenerating any of this

The wiki's API asks callers to identify themselves, so set a contact address
first:

```bash
export RS3OS_CONTACT='you@example.com'      # or: $env:RS3OS_CONTACT on Windows
python3 tools/seed/build_wiki_seed.py
```

The scrapers rate-limit themselves. Please leave that alone — the wiki is run by
volunteers.
