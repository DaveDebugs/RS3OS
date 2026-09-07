# How RS3OS is put together

A short tour for anyone reading the code for the first time.

## The shape of it

A player's client opens two kinds of connection. One asks for game files; the
other plays the game.

```
   NXT client
       |
       |--- JS5 --->  the file server      serves cache data on demand
       |
       |--- HTTP -->  jav_config.ws        tells the client where to connect
       |
       '--- game -->  login  ->  lobby  ->  world
                                              |
                                         the tick loop
```

Everything after login runs on a single **tick**. The world advances in fixed
steps; nothing that changes game state happens between them.

## Where things live

| Package | What it does |
|---|---|
| `net/` | The 949 protocol. Packet encoders and decoders, the login handshake, the JS5 file server, and the registry that maps opcodes to handlers. |
| `model/` | The world itself: players, NPCs, movement, combat, the map, and the tick loop that drives them. |
| `content/` | Gameplay. Each module owns one feature — `Skilling`, `Banks`, `Fishing`, `Dialogue`, `Doors` — and registers itself with `ContentRegistry`. |
| `resources/` | Reading the game cache and the definition database. |
| `tools/` | Command-line tools, run via `run-tool`. |

## The tick

`World.tick()` runs a fixed sequence of phases:

1. **NPCs** — movement, combat, aggression, respawns.
2. **Ground items** — despawn timers and visibility.
3. **Content modules** — `Skilling`, `Fishing`, `Firemaking`, `Cooking`,
   `Smithing`, `Fletching`, `Lodestones`, each advancing its own cycles.
4. **Inbound packets** — everything the clients sent since the last tick is
   decoded and dispatched.
5. **Players** — movement, then the update blocks describing what changed.
6. **Retire and flush** — this tick's update blocks are cleared and the
   outbound buffers written to the sockets.
7. **Autosave**, periodically.

Order matters, and it is not the order you might guess: NPCs and content
modules run *before* this tick's client input is read, so an action a player
requests takes effect on the following tick. Each phase is wrapped so that an
uncaught exception in one is contained and logged rather than killing the rest
of the tick.

If you are adding something that happens over time, give it a phase here rather
than a thread of its own.

## How a click becomes an action

Say a player clicks "Chop down" on a tree.

1. The client sends an `OPLOC` packet naming the object and which menu option.
2. `OpLocHandler` decodes it and asks `ContentRegistry` whether anything handles
   that option on that object.
3. The registry looks up the object's real options **in the cache**, not in
   anything the client sent, and rejects the request if they don't match. This
   is the anti-cheat boundary: the client asks, the server decides.
4. If it is accepted, the request goes to `Skilling`, which walks the player to
   the tree and starts a repeating cycle.
5. Each cycle rolls for success, awards experience, and adds a log to the
   backpack — or stops, if the backpack is full or the player moved away.

`ContentRegistry.DispatchResult.Rejected` is the thing to look for when a click
appears to do nothing.

## One action at a time

A player can only be doing one thing. `ActionSlot` enforces that across every
content module: claiming it cancels whatever held it before, through *that*
module's own cancel path, so a displaced action cleans up after itself rather
than leaking state.

## Where the game data comes from

Three sources, in order of authority:

1. **The cache** — Jagex's own definitions for items, NPCs, objects and
   interfaces, decoded into `data/rs3.sqlite` by `run-tool db-builder`, and the
   world itself — terrain, collision and object placements — decoded into the
   same database by `run-tool map-builder`. This is the truth wherever it has
   an answer.
2. **Seed data** (`data/seed/`) — things the cache does not state, such as NPC
   lifepoints, drop tables and experience rates. Mostly derived from the
   RuneScape Wiki.
3. **Authored values** — a small set of gap-fillers, tagged as such so they can
   be told apart from measured data.

`SeedData` layers these and records where each value came from, which is why a
number in the logs usually says whether it was decoded, looked up, or invented.

## Feature switches

Most content sits behind `-Dopennxt.experiment.<name>`. This is how unfinished
work lives in the tree without breaking a working server: default it off, turn
it on when it holds up. `run.ps1` and `run.sh` list the ones that are on.

## The protocol tables

`data/prot/949/` holds the packet definitions:

- `clientProtNames.toml` / `serverProtNames.toml` — opcode to name
- `clientProtSizes.toml` / `serverProtSizes.toml` — fixed length, or `-1`/`-2`
  for a byte- or short-prefixed variable length
- `clientProt/*.txt`, `serverProt/*.txt` — the field layout of one packet

These were worked out from the client binary and from watching real traffic.
They are the specification this server is written against, so changing one
should come with evidence — see [CONTRIBUTING.md](../CONTRIBUTING.md).
