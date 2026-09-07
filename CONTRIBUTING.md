# Contributing

Thanks for looking. A few things about this project make it different from a
typical server codebase, and knowing them first will save you time.

## The client is the specification

There is no protocol document. The NXT client and the cache it reads *are* the
spec, and everything in `data/prot/949/` was worked out by reading the client
binary and watching real traffic.

That means a change to a packet name, a field order, a length or an opcode
needs evidence, not a guess. Good evidence is one of:

- the client binary — a decoder function, a vtable slot, a jump table
- the cache — a definition that only parses one way
- the protocol — an observation where the field is unambiguous

If you're unsure, say so in the pull request. A change marked "I think this is
right but haven't proved it" is welcome. A change presented as settled when it
isn't is the thing that costs everyone time later, because the next person
builds on it.

## Say what you actually know

Comments in this codebase try to distinguish what has been measured from what
has been assumed. If you write a number down, say where it came from. If you
turn out to be wrong later, correct the comment rather than leaving it — a
confident comment that is quietly wrong is worse than no comment.

## Keep unfinished work behind a switch

Most gameplay sits behind a `-Dopennxt.experiment.*` system property. If you're
adding something that isn't finished, or that might break an existing setup, put
it behind one and default it off. `run.ps1` and `run.sh` list the ones that are
on by default.

## The tick loop is a boundary

The server runs on a fixed tick. Anything that changes game state should happen
in a tick phase, in `World.tick()`, not on a network thread and not on a timer.
Don't add blocking I/O to the tick thread.

## Server decides, client asks

Everything arriving from the client is a request, never a fact. Validate
distance, level, inventory space and ownership on the server side, every time,
even when the client's own interface would have prevented the action. The client
can be modified; your server can't assume it wasn't.

## Style

- Kotlin, targeting JVM 21.
- Match the surrounding code. There's no formatter to fight with.
- Keep changes focused. A bug fix and a refactor in one pull request is two
  pull requests.

## Building

```bash
./gradlew installDist
```

Needs JDK 21. To run something without a full install:

```bash
./gradlew rt --args="run-tool --help"
```

## What would help most

The open items are listed under "Being worked on" in the [README](README.md).

The most valuable one is probably NPC spawns. `map-builder` reproduces terrain,
collision and object placements from the cache exactly, but the spawn records it
also reads are a puzzle: only 45 map squares carry them, and the field consumed
as an NPC id resolves to a named NPC less than half the time. Either that field
means something else, or spawns live somewhere in the cache nobody here has
found. Settling it would populate the world.
