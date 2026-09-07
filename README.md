# RS3OS

A private game server for the RuneScape 3 **NXT** client, build **949**.

You point your own copy of the game at it, log in, and play on your own machine
— no Jagex servers involved after the initial cache download.

RS3OS builds on [OpenNXT](https://github.com/Techdaan/OpenNXT) by Techdaan, and
keeps all of its original tooling: the cache downloader, the client downloader,
the client patcher and the RSA key generator.

> **This is a hobby and research project.** It is not affiliated with, endorsed
> by, or connected to Jagex Ltd. RuneScape is a trademark of Jagex. No game
> assets are distributed here — you supply your own client, and the cache is
> downloaded from Jagex by a tool you run yourself. See [Legal](#legal).

---

## What you need

| | |
|---|---|
| **Java 21** | [Temurin 21](https://adoptium.net/temurin/releases/?version=21) is the usual choice |
| **Python 3** | only if you want to use a client you already have, rather than downloading one |
| **Disk space** | around 30 GB — the game cache is most of it |
| **A game client** | your own copy of the RuneScape NXT executable, **build 949** |
| **A game cache** | a **949** cache — see the note on the downloaders below |

## Getting it running

Clone the repository, then run one command.

**Windows**

```powershell
.\setup.ps1 -Client 'C:\Program Files\Jagex\RuneScape\rs2client.exe'
```

**Linux / macOS**

```bash
./setup.sh --client /path/to/rs2client.exe
```

That does everything in order: builds the server, generates your own RSA key
pair, copies in your client and patches it to trust that key, downloads the game
cache, builds the definition database the server reads, decodes the world map
out of it, and extracts the NPC combat data.

The cache download is tens of gigabytes and takes a long time. It resumes where
it left off, so stopping it is safe — just run the same command again.

### About the bundled downloaders

`client-downloader` and `cache-downloader` came from OpenNXT and still work, but
they fetch **whatever Jagex is serving today** — which is not build 949. A client
and cache from the current build will not run against this server; the handshake
fails with a build mismatch.

So in practice you need your own copy of the 949 client and a 949 cache. The
downloaders are kept because they are useful for moving the project to a newer
build, not because they will set up a 949 server for you.

If you point `setup` at a client of the wrong build, you will see this on the
first connection, which is the server telling you exactly what happened:

```
reason=build mismatch: client 947 vs server 949
```

When setup finishes:

```powershell
.\run.ps1          # Windows
./run.sh           # Linux / macOS
```

Then launch the patched client, which setup wrote to
`data/clients/949/win64/patched/rs2client.exe`.

To give yourself in-game admin powers, put your account name in
`data/config/mods.json`.

### If something goes wrong

Every step can be run on its own, which is the quickest way to retry one thing:

```powershell
.\setup.ps1 -Step cache        # just resume the download
.\setup.ps1 -Step database     # just rebuild the definition database
.\setup.ps1 -Step world        # just re-decode the world map
.\setup.ps1 -Step patch -Force # re-patch the client from scratch
```

---

## What's in the box

### Working

**Getting in.** Account creation, the lobby, the world login handshake, and
character saves that survive a restart.

**The game frame.** The RS3 interface comes up the way it does in the real game
— the HUD, the side panels, the ribbon, the options menu, the world map, the
tool belt, the loot window. Your interface layout is remembered between logins.

**The world itself.** Terrain, collision, every object placement and 1,388 NPC
spawns are decoded straight from your cache, so walls stop you; doors, banks,
ladders, stairs and trees stand where they should; and the towns have people and
animals in them.

**Moving around.** Walking and running, run energy, pathfinding, doors that
swing the right way, ladders, stairs, and lodestone teleports.

**Fighting.** Attacking NPCs and being attacked back, hit splats, accuracy and
damage rolls, weapon animations, death and respawn, drop tables for around 1,500
monsters, and combat experience split across the skills you actually trained.

**Skills.** Woodcutting, Mining, Fishing, Cooking, Firemaking, Smithing,
Fletching and Prayer (bone burying) are implemented as real gathering and
production loops — with tool checks, level requirements, success rolls, resource
depletion and the make-X panel. Experience rates come from the RuneScape Wiki's
own calculator data, corrected against the protocol wherever the two
disagreed.

**Things to do with items.** Backpack and equipment, the bank, shops, ground
items and pickup, and item actions like eating and burying.

**Talking to people.** NPC dialogue, seeded with 4,514 conversations scraped
from the wiki, following the same page-by-page sequence the real client uses.

**Odds and ends.** Emotes, cosmetic overrides, and a set of admin commands
gated behind `mods.json`.

### Being worked on

**NPC coverage is partial.** The cache carries 1,388 NPC spawns and
`map-builder` decodes all of them, correctly — sheep and Fred the Farmer on the
Lumbridge farms, Zaff and Thessalia in Varrock, cave goblins in Dorgesh-Kaan.
But they cover only 33 map squares, so towns and landmarks are populated and the
countryside between them is empty. Filling the gaps needs spawn data the cache
does not carry.

**Combat abilities and adrenaline.** The modern RS3 ability system — the action
bar, adrenaline, thresholds and ultimates — is not modelled at all. Combat is
auto-attack only.

**NPC versus NPC combat.** NPCs will fight players but not each other.

**Music.** The server sends no music; the client falls back to its own player.

**Quests.** No quest engine.

**Grand Exchange.** Not implemented. Shops work; player trading does not.

**Player versus player.** Not implemented.

**More skills.** Farming, Crafting, Herblore, Runecrafting and the rest are not
built yet, though the wiki data that drives the finished ones already covers
23 skills.

**The Hero window's tabs.** The window opens and its tabs respond, but they all
show the same content rather than their own — Calendar, Quests, Challenges,
Minigames, Beasts and Activity Tracker will each show whichever panel was
mounted last, and the same is true of Wardrobe, Animations, Appearance, Titles
and Pets. This is not a rendering fault: the tabs all mount the *same*
interface, and the real game tells them apart by running a follow-up script
after the mount. This server does not send those scripts yet. Cosmetics
themselves do work, through a command rather than through the wardrobe.

**Lodestone activation.** Teleporting works and all 34 destinations are wired,
but every lodestone is usable from the start. The real game requires you to
visit one before you can teleport to it, and this server does not track that
yet — `-Dopennxt.lodestone.teleport.requireactive=true` turns the
requirement on if you would rather have it.

**Members areas and membership.** There is no membership model. Every character
is treated as a member, and no area, item or shop is gated behind it — the
data carries the members flags, but nothing reads them to stop you.

---

## How the project is laid out

```
src/main/kotlin/com/opennxt/
  net/            the 949 protocol: packet codecs, login, the JS5 file server
  model/          the world — players, NPCs, movement, combat, the tick loop
  content/        gameplay: skills, banks, shops, dialogue, doors, teleports
  resources/      reading the game cache and the definition database
  tools/          the command line tools, including everything from OpenNXT

data/
  config/         your server settings and keys (examples are committed)
  prot/949/       the protocol tables — packet names, sizes and field layouts
  seed/           game data used to drive content (see Data and credits)
  cache/          the game cache, once you have downloaded it

tools/
  stage_client.py     prepares a client you already own for patching
  seed/               the scrapers that rebuild everything in data/seed
```

### The command line

Everything runs through one executable, built to `build/install/rs3os/bin/`.

```
rs3os run-server                    start the server
rs3os run-tool --help               list every bundled tool
rs3os run-tool rsa-key-generator    generate your key pair
rs3os run-tool client-downloader    download a client from Jagex
rs3os run-tool client-patcher       patch the client to trust your key
rs3os run-tool cache-downloader     download the game cache
rs3os run-tool db-builder           build the definition database from the cache
rs3os run-tool map-builder          decode the world map into that database
```

The first four are OpenNXT's, unchanged in purpose.

### Feature switches

Most gameplay sits behind a `-Dopennxt.experiment.*` switch so unfinished work
can be turned off without editing code. `run.ps1` and `run.sh` turn on
everything that works, and document each switch inline. To override one:

```powershell
.\run.ps1 -Flags '-Dopennxt.experiment.npcs.aggro=off'
```

---

## Data and credits

**OpenNXT.** RS3OS is derived from [Techdaan/OpenNXT](https://github.com/Techdaan/OpenNXT)
and is licensed under the **GNU General Public License v3.0**, the same licence.
See [LICENSE](LICENSE).

**The RuneScape Wiki.** Much of `data/seed/` — monster stats, drop tables,
experience rates and dialogue transcripts — is derived from the
[RuneScape Wiki](https://runescape.wiki), used under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). The
scrapers that produced it are in `tools/seed/`, so every file can be
regenerated and checked. If you rerun them, set a contact address first — the
wiki's API asks callers to identify themselves:

```bash
export RS3OS_CONTACT='you@example.com'
python3 tools/seed/build_wiki_seed.py
```

**Jagex.** The client, the cache and the game are Jagex's. None of it is
redistributed here. Anything derived from the cache — the definition database
and `data/seed/npc_cache.json` — is built on your machine from your own copy
during setup, which is why neither is committed.

---

## Legal

This project exists to study how the RuneScape 3 client works and to run a
private server for personal use. It ships no Jagex code, no Jagex assets and no
game cache. The client you patch is your own copy; the cache is fetched from
Jagex's public content servers by a tool you choose to run.

It is not affiliated with or endorsed by Jagex Ltd. Running a private server may
conflict with Jagex's terms of service — that is between you and them. Don't run
this as a public service, and don't use it to take anything away from the real
game.

---

## Contributing

Bug reports and pull requests are welcome. A few things worth knowing before you
start are in [CONTRIBUTING.md](CONTRIBUTING.md) — chiefly that the client and its
cache are the specification, so protocol changes need evidence rather than
guesses.
