#!/usr/bin/env python3
"""
fetch_wiki_pages.py -- store raw runescape.wiki wikitext for a list of titles under
data/wiki/xp/raw/<title>.wikitext (LF), with a manifest of retrieval dates.

    python tools/seed/fetch_wiki_pages.py <title> [<title> ...]
    python tools/seed/fetch_wiki_pages.py --list titles.txt

Nothing is parsed here; build_wiki_xp.py and the combat-formula notes read the stored
pages so every number can be traced to a page, a revision and a date (PROVENANCE:
documented, the same tier as build_wiki_seed.py).
"""
import io, json, os, re, sys, time, urllib.parse, urllib.request

API = "https://runescape.wiki/api.php"
# The RuneScape Wiki API asks callers to identify themselves with a contact
# address. Set RS3OS_CONTACT to your own email address or project URL before
# running this script; it refuses to start without one.
import os as _os
CONTACT = _os.environ.get("RS3OS_CONTACT", "").strip()
if not CONTACT:
    raise SystemExit(
        "Set RS3OS_CONTACT to an email address or project URL before scraping.\n"
        "  Windows : $env:RS3OS_CONTACT = 'you@example.com'\n"
        "  Linux   : export RS3OS_CONTACT='you@example.com'")

UA = "Mozilla/5.0 RS3OS-research/1.0 (private server research; runescape.wiki API; contact " + CONTACT + ")"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW = os.path.join(ROOT, "data", "wiki", "xp", "raw")
MANIFEST = os.path.join(ROOT, "data", "wiki", "xp", "manifest.json")
PAUSE = 0.6

def get(params):
    params = dict(params, format="json", formatversion="2")
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)

def safe(title):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", title)

def main(argv):
    titles = []
    i = 0
    while i < len(argv):
        if argv[i] == "--list":
            titles += [l.strip() for l in io.open(argv[i + 1], encoding="utf-8") if l.strip() and not l.startswith("#")]
            i += 2
        else:
            titles.append(argv[i]); i += 1
    os.makedirs(RAW, exist_ok=True)
    manifest = json.load(io.open(MANIFEST, encoding="utf-8")) if os.path.exists(MANIFEST) else {}
    for t in titles:
        try:
            if t.startswith("Module:"):
                # Lua pages: action=parse has no wikitext for Scribunto content; read the revision.
                q = get(dict(action="query", prop="revisions", titles=t, rvslots="main", rvprop="content|ids", redirects=1))
                pg = q["query"]["pages"][0]
                if "missing" in pg or not pg.get("revisions"):
                    print("MISSING", t); continue
                rv = pg["revisions"][0]
                p = dict(title=pg["title"], revid=rv["revid"], wikitext=rv["slots"]["main"]["content"], sections=[])
            else:
                r = get(dict(action="parse", page=t, prop="wikitext|revid|sections", redirects=1))
                p = r.get("parse")
                if not p:
                    print("MISSING", t, r.get("error", {}).get("info")); continue
        except Exception as e:
            print("FAIL", t, e); continue
        wt = p["wikitext"]
        path = os.path.join(RAW, safe(t) + (".lua" if t.startswith("Module:") else ".wikitext"))
        io.open(path, "w", encoding="utf-8", newline="\n").write(wt)
        manifest[t] = dict(title=p["title"], revid=p["revid"], retrieved=time.strftime("%Y-%m-%d"),
                           chars=len(wt), file=os.path.relpath(path, ROOT).replace("\\", "/"),
                           sections=[s["line"] for s in p.get("sections", [])])
        print("%-48s rev %-9s %6d chars  %d sections" % (p["title"], p["revid"], len(wt), len(p.get("sections", []))))
        time.sleep(PAUSE)
    io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write(json.dumps(manifest, indent=1, sort_keys=True) + "\n")

if __name__ == "__main__":
    main(sys.argv[1:])
