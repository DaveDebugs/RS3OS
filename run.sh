#!/usr/bin/env bash
#
# Starts the RS3OS server with the feature set this project has built and tested.
#
# Most gameplay lives behind an -Dopennxt.experiment.* switch so a half-finished
# system can be turned off without touching code; the defaults below turn on
# everything that works.
#
#   ./run.sh
#   ./run.sh --cache /mnt/rs3cache --flags '-Dopennxt.experiment.npcs.aggro=off'
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

CACHE=""
EXTRA=""
MEMORY="3g"

while [ $# -gt 0 ]; do
    case "$1" in
        --cache)  CACHE="$2"; shift 2 ;;
        --flags)  EXTRA="$2"; shift 2 ;;
        --memory) MEMORY="$2"; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

BIN="$ROOT/build/install/rs3os/bin/rs3os"
if [ ! -x "$BIN" ]; then
    echo "The server is not built yet. Run ./setup.sh first." >&2
    exit 1
fi

[ -n "$CACHE" ] || CACHE="$ROOT/data/cache"
if [ ! -d "$CACHE" ]; then
    echo "No cache at $CACHE" >&2
    echo "Download one with:  ./setup.sh --step cache" >&2
    exit 1
fi

for f in rsa.toml server.toml; do
    if [ ! -f "data/config/$f" ]; then
        echo "Missing data/config/$f - run ./setup.sh first." >&2
        exit 1
    fi
done

# Interfaces and dispatch.
#   world.interfaces=all      open every gameframe panel, not just the HUD
#   world.skipInterfaces=653  653 is the one panel that breaks the frame
#   loc.dispatch/npc.dispatch route clicks on scenery and NPCs into content
#                             handlers (doors, banks, fishing spots, "Talk to")
#
# Not set on purpose: -Dopennxt.experiment.ui.armPanels. It arms drag, resize
# and close on several hundred components, which makes holding the left mouse
# button start a panel drag and hide the frame. Its own default is off.
#
# Gameplay.
#   combat=true          hit splats, damage, death, drops, combat xp
#   sendStats=true       push skill levels and xp to the skills panel
#   doors.swing=turn     doors swing on their hinge rather than sliding
#   banks.ui=true        the bank window, deposits and withdrawals
#   npcs.aggro=4         aggressive NPCs; 4 is only the fallback scan radius,
#                        per-NPC aggression comes from the seed data
export RS3OS_OPTS="-Xmx$MEMORY \
\"-Dopennxt.cache=$CACHE\" \
-Dopennxt.diag=true \
-Dorg.slf4j.simpleLogger.logFile=System.out \
-Dopennxt.world.interfaces=all \
-Dopennxt.world.skipInterfaces=653 \
-Dopennxt.experiment.loc.dispatch=true \
-Dopennxt.experiment.npc.dispatch=true \
-Dopennxt.experiment.combat=true \
-Dopennxt.experiment.sendStats=true \
-Dopennxt.experiment.doors.swing=turn \
-Dopennxt.experiment.banks.ui=true \
-Dopennxt.experiment.npcs.aggro=4 \
-Dopennxt.experiment.groundItems.demo=false \
$EXTRA"

printf '\033[36mStarting RS3OS  (cache: %s)\033[0m\n' "$CACHE"
exec "$BIN" run-server
