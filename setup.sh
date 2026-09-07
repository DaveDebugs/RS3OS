#!/usr/bin/env bash
#
# Sets up an RS3OS server from a fresh clone: build, keys, client, cache, database.
#
# Runs every setup step that has not already been done, in order, and stops at
# the first failure with an explanation. Safe to run again -- finished steps are
# detected and skipped, so an interrupted download resumes by re-running.
#
#   ./setup.sh --client /path/to/rs2client.exe
#   ./setup.sh --step cache
#   ./setup.sh --help
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BUILD=949
BINARY_TYPE=win64

CLIENT=""
DOWNLOAD_CLIENT=0
HOSTNAME_OPT="127.0.0.1"
ONLY_STEP=""
FORCE=0

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Options:
  --client PATH       A RuneScape NXT client executable you already have.
  --download-client   Fetch a client from Jagex instead of supplying your own.
  --hostname ADDR     Address your client connects to (default 127.0.0.1).
  --step NAME         Run one step only: check build config keys client patch
                      cache database world seed
  --force             Redo steps whose output already exists.
  -h, --help          This text.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --client)          CLIENT="$2"; shift 2 ;;
        --download-client) DOWNLOAD_CLIENT=1; shift ;;
        --hostname)        HOSTNAME_OPT="$2"; shift 2 ;;
        --step)            ONLY_STEP="$2"; shift 2 ;;
        --force)           FORCE=1; shift ;;
        -h|--help)         usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

STEP_NO=0
step()  { STEP_NO=$((STEP_NO + 1)); printf '\n\033[36m[%d] %s\033[0m\n' "$STEP_NO" "$1"; }
ok()    { printf '    \033[32mOK\033[0m    %s\n' "$1"; }
skip()  { printf '    \033[90mskip  %s\033[0m\n' "$1"; }
info()  { printf '          \033[90m%s\033[0m\n' "$1"; }
fail()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }

should_run() { [ -z "$ONLY_STEP" ] || [ "$ONLY_STEP" = "$1" ]; }

RS3OS_BIN="$ROOT/build/install/rs3os/bin/rs3os"
rs3os() {
    [ -x "$RS3OS_BIN" ] || fail "the server is not built yet. Run: ./setup.sh --step build"
    info "run-tool: $*"
    "$RS3OS_BIN" "$@"
}

# --- 1. prerequisites -------------------------------------------------------
if should_run check; then
    step "Checking prerequisites"
    command -v java >/dev/null 2>&1 || fail \
"Java is not on your PATH.

Install a JDK 21 and reopen this shell:
    https://adoptium.net/temurin/releases/?version=21"

    ver_line="$(java -version 2>&1 | head -n1)"
    major="$(printf '%s' "$ver_line" | sed -n 's/.*"\([0-9]\{1,\}\).*/\1/p')"
    if [ -n "$major" ]; then
        [ "$major" -ge 21 ] || fail "Java $major found, but this project needs Java 21 or newer."
        ok "Java $major"
    else
        info "could not parse the Java version from: $ver_line"
    fi

    if command -v python3 >/dev/null 2>&1; then
        ok "Python 3 (needed only to stage your own client)"
    else
        info "python3 not found - needed only if you supply your own client binary"
    fi
fi

# --- 2. build ---------------------------------------------------------------
if should_run build; then
    step "Building the server"
    if [ -x "$RS3OS_BIN" ] && [ "$FORCE" -eq 0 ]; then
        skip "already built (pass --force to rebuild)"
    else
        info "this takes a few minutes the first time"
        ./gradlew installDist --console=plain -q
        ok "built to build/install/rs3os/"
    fi
fi

# --- 3. configuration -------------------------------------------------------
if should_run config; then
    step "Writing data/config/server.toml"
    cfg="data/config/server.toml"
    if [ -f "$cfg" ] && [ "$FORCE" -eq 0 ]; then
        skip "server.toml already exists (pass --force to overwrite)"
    else
        cat > "$cfg" <<EOF
# Where your client will look for this server. 127.0.0.1 means this machine.
hostname = "$HOSTNAME_OPT"

# The NXT build this server speaks. The cache and the client must match it.
build = $BUILD

configUrl = "http://$HOSTNAME_OPT/jav_config.ws?binaryType=2"

[networking.ports]
game = 43594
http = 80
https = 443
EOF
        ok "hostname = $HOSTNAME_OPT, build = $BUILD"
    fi

    if [ ! -f data/config/mods.json ]; then
        cp data/config/mods.example.json data/config/mods.json
        ok "mods.json created - edit it to give your account admin rights"
    fi
fi

# --- 4. RSA keys ------------------------------------------------------------
if should_run keys; then
    step "Generating your RSA key pair"
    if [ -f data/config/rsa.toml ] && [ "$FORCE" -eq 0 ]; then
        skip "rsa.toml already exists"
        info "regenerating it would invalidate any client you have already patched"
    else
        rs3os run-tool rsa-key-generator
        ok "data/config/rsa.toml written"
        info "this is a PRIVATE KEY. It is gitignored. Never publish it."
    fi
fi

# --- 5. the client ----------------------------------------------------------
if should_run client; then
    step "Staging the game client"
    orig="data/clients/$BUILD/$BINARY_TYPE/original"
    if [ -d "$orig" ] && [ -n "$(ls -A "$orig" 2>/dev/null)" ] && [ "$FORCE" -eq 0 ]; then
        skip "a client is already staged"
    elif [ "$DOWNLOAD_CLIENT" -eq 1 ]; then
        rs3os run-tool client-downloader
        ok "client downloaded"
    elif [ -n "$CLIENT" ]; then
        [ -f "$CLIENT" ] || fail "no such file: $CLIENT"
        python3 tools/stage_client.py "$CLIENT" --build "$BUILD" --type "$BINARY_TYPE"
        ok "staged $(basename "$CLIENT")"
    else
        fail \
"No client supplied.

RS3OS patches a client you already have; it does not ship one. Point it at your
own copy of the RuneScape NXT executable:

    ./setup.sh --client /path/to/rs2client.exe

or let the built-in downloader fetch one from Jagex:

    ./setup.sh --download-client"
    fi
fi

# --- 6. patch the client ----------------------------------------------------
if should_run patch; then
    step "Patching the client to trust your key"
    if [ -d "data/clients/$BUILD/$BINARY_TYPE/compressed" ] && [ "$FORCE" -eq 0 ]; then
        skip "a patched client already exists (pass --force to redo)"
    else
        rs3os run-tool client-patcher
        ok "the client now trusts your RSA key and points at your server"
    fi
fi

# --- 7. the cache -----------------------------------------------------------
if should_run cache; then
    step "Downloading the game cache"
    if [ -d data/cache ] && [ -n "$(ls -A data/cache 2>/dev/null)" ] && [ "$FORCE" -eq 0 ]; then
        skip "data/cache already holds files"
        info "run this step again at any time to top it up: ./setup.sh --step cache"
    else
        info "This is tens of gigabytes and will take a long time."
        info "It resumes where it left off, so interrupting it is safe."
        rs3os run-tool cache-downloader
        ok "cache downloaded"
    fi
fi

# --- 8. the definition database ---------------------------------------------
if should_run database; then
    step "Building data/rs3.sqlite from the cache"
    if [ -f data/rs3.sqlite ] && [ "$FORCE" -eq 0 ]; then
        skip "rs3.sqlite already exists (pass --force to rebuild)"
    else
        if [ "$FORCE" -eq 1 ]; then
            rs3os run-tool db-builder --output data/rs3.sqlite --force
        else
            rs3os run-tool db-builder --output data/rs3.sqlite
        fi
        ok "rs3.sqlite built"
    fi
fi

# --- 9. the world: collision, object placements, NPC spawns -----------------
if should_run world; then
    step "Decoding the world map from the cache"
    [ -f data/rs3.sqlite ] || \
        fail "data/rs3.sqlite does not exist yet. Run: ./setup.sh --step database"

    # map-builder refuses a populated set unless forced, so let it make that
    # call rather than second-guessing it from a row count here.
    info "terrain, collision, object placements and NPC spawns - a few minutes"
    if [ "$FORCE" -eq 1 ]; then
        rs3os run-tool map-builder --database data/rs3.sqlite --force \
            || fail "building the world map failed"
    elif rs3os run-tool map-builder --database data/rs3.sqlite; then
        ok "collision, placements and spawns written"
    else
        skip "the map tables already hold data (pass --force to rebuild them)"
    fi
fi

# --- 10. NPC combat seed, from your own cache -------------------------------
if should_run seed; then
    step "Extracting NPC combat data from your database"
    if [ -f data/seed/npc_cache.json ] && [ "$FORCE" -eq 0 ]; then
        skip "npc_cache.json already exists (pass --force to rebuild)"
    else
        command -v python3 >/dev/null 2>&1 ||             fail "Python 3 is needed for this step but was not found on your PATH."
        python3 tools/seed/seed_from_cache.py
        ok "data/seed/npc_cache.json written from your own cache"
    fi
fi

if [ -z "$ONLY_STEP" ]; then
    cat <<EOF

$(printf '\033[32mSetup complete.\033[0m')

  Start the server:
      ./run.sh

  Then launch the patched client:
      data/clients/$BUILD/$BINARY_TYPE/patched/rs2client.exe

  Give yourself admin rights by putting your account name in
      data/config/mods.json

EOF
fi
