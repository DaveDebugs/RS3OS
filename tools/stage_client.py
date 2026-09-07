#!/usr/bin/env python3
"""
stage_client.py -- lay out data/clients/<build>/<type>/original/ from a client
you already own, so `run-tool client-patcher` has something to patch.

    python3 tools/stage_client.py /path/to/rs2client.exe --build 949 --type win64

WHAT THIS DOES AND DOES NOT DO
------------------------------
It does NOT download anything. `client-downloader` fetches clients from Jagex's
servers and is deliberately never run in this project; this script is the
offline alternative for the case the setup doc calls "your call": you already
have the client, and the only thing missing is the manifest that normally
arrives beside it.

    copies    <client>                -> <build>/<type>/original/<name>
    writes    jav_config.ws           -> <build>/<type>/original/jav_config.ws

THE MANIFEST IS AUTHORED. READ THIS BEFORE TRUSTING IT.
-------------------------------------------------------
`jav_config.ws` normally comes from Jagex and lists every file the launcher
should fetch, with a CRC and a launcher-key signature per file. We do not have
that file and will not fetch it, so this writes a RECONSTRUCTION containing
exactly the fields this server's own code reads, and nothing invented beyond
them:

  * download_name_0 / download_crc_0 / download_hash_0
        The file list. `ClientConfig.getFiles()` requires all three keys to be
        present and the crc to parse as a Long, so crc starts at the real CRC32
        of the file and the hash starts as a placeholder - BOTH are REPLACED by
        `ClientPatcher.patchConfig`, which recomputes them over the patched
        bytes using YOUR launcher key from rsa.toml. Nothing Jagex signed
        survives into the served config, and nothing here pretends to.
  * param=0, a 32-character js5 token
        `ClientConfig.getJs5Token()` returns the first 32-character param, so
        one has to exist for the server to answer a js5 handshake at all. This
        server does not validate the token (Js5Handler still carries the
        "TODO: Check build & js5 token" warning), so the VALUE is arbitrary -
        it is derived deterministically from the build number below purely so
        two runs agree, and it is AUTHORED, not a Jagex token.
  * codebase
        Overwritten by the patcher with http://<hostname>/ from server.toml.

`server_version` is written because the client REQUIRES it: it selects the
ClientProt family, and without it the client speaks the legacy one while this
server speaks >=949, so interface clicks are dropped and inventories mis-parse.

WHAT IS NOT HERE, STATED PLAINLY: every other param a real jav_config carries.
A real NXT client may require params this manifest does not contain, and if it
does, the failure will be client-side and this file is the first place to look.
That is a known unknown, not a solved problem - the point of this script is to
get the HTTP endpoints serving real bytes so the client experiment can start
producing evidence, not to claim the config is complete.
"""
import argparse
import hashlib
import os
import shutil
import zlib

# The one place the authored token is defined. Deterministic from the build so
# repeated runs are byte-identical, and obviously not a Jagex value.
def authored_js5_token(build: int) -> str:
    seed = f"opennxt-authored-js5-token-{build}".encode()
    return hashlib.sha256(seed).hexdigest()[:32]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("client", help="path to the client binary you own (e.g. rs2client.exe)")
    ap.add_argument("--build", type=int, default=949)
    ap.add_argument("--type", default="win64",
                    help="BinaryType directory name, lowercase (win64 == binaryType=2)")
    ap.add_argument("--data", default="data", help="server data directory")
    ap.add_argument("--name", default=None,
                    help="name to publish the file under (defaults to the file's own name)")
    a = ap.parse_args()

    if not os.path.isfile(a.client):
        raise SystemExit(f"no such client binary: {a.client}")

    name = a.name or os.path.basename(a.client)
    out = os.path.join(a.data, "clients", str(a.build), a.type, "original")
    os.makedirs(out, exist_ok=True)

    raw = open(a.client, "rb").read()
    crc = zlib.crc32(raw) & 0xFFFFFFFF

    dest = os.path.join(out, name)
    if os.path.abspath(dest) != os.path.abspath(a.client):
        shutil.copyfile(a.client, dest)

    token = authored_js5_token(a.build)
    lines = [
        # Overwritten by ClientPatcher.patchConfig with the configured hostname.
        "codebase=http://127.0.0.1/",
        f"download_name_0={name}",
        # Real CRC32 of the UNPATCHED file; the patcher recomputes it over the
        # patched bytes. Present because getFiles() requires it to parse.
        f"download_crc_0={crc}",
        # Placeholder. patchConfig replaces this with a signature made using
        # YOUR launcher key. It is not, and never was, a Jagex signature.
        "download_hash_0=AUTHORED-PLACEHOLDER-REPLACED-BY-CLIENT-PATCHER",
        # 32 chars => ClientConfig.getJs5Token() finds this one. AUTHORED.
        f"param=0={token}",
        # REQUIRED. The client reads this once at startup and uses it to choose
        # which ClientProt family to speak. Absent, it falls back to the LEGACY
        # family: an interface click goes out as opcode 10 instead of 55, a drag
        # as 153 instead of 25, and inventory object ids are written u16 where
        # this server writes u24. The server registers only the >=949 opcodes,
        # so every panel click is dropped in the framer before it reaches a
        # handler, and backpack contents decode as the wrong items.
        f"server_version={a.build}",
    ]
    cfg = os.path.join(out, "jav_config.ws")
    with open(cfg, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    print(f"staged   {dest}  ({len(raw):,} bytes, crc32 {crc})")
    print(f"wrote    {cfg}")
    print(f"  download_name_0 = {name}")
    print(f"  js5 token       = {token}  (AUTHORED - this server does not validate it)")
    print()
    print("next:  run-tool client-patcher --version %d" % a.build)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
