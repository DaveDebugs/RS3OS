#!/usr/bin/env python3
"""
fetch_wiki_dialogue.py -- store raw runescape.wiki NPC-dialogue transcripts.

    python tools/seed/fetch_wiki_dialogue.py --enumerate      # counts only, no content
    python tools/seed/fetch_wiki_dialogue.py --fetch          # fetch/refresh content
    python tools/seed/fetch_wiki_dialogue.py --fetch --limit 200

Sibling of tools/seed/fetch_wiki_pages.py (the xp pass's fetcher) and it copies that
file's shape: the same API, the same descriptive User-Agent carrying the caller's
contact, a manifest keyed by title carrying the revision id and the retrieval
date. Nothing is parsed here;
tools/seed/build_dialogue_seed.py reads the store.

WHAT THIS FETCHES, AND WHY NOT ALL 12,676
-----------------------------------------
Namespace 120 (`Transcript:`) held **12,676** pages on . Most of them
are not NPC dialogue at all: `Transcript:"Our Africa" - sub to support!` is a
YouTube auto-transcription, and there are ~960 like it. The namespace is split by
the first argument of the `{{Dialogue|...}}` banner every transcript carries -
`{{Dialogue|NPC}}` vs `{{Dialogue|irl}}` - and that banner files the page into a
category, which the API can enumerate without downloading a byte of content:

    Category:NPC dialogue            3,203     <- the set this fetches
    Category:Tutorial transcripts       14     <- also fetched, see below
    Category:Real-life transcripts     960
    Category:Quest transcripts         323

Tutorial transcripts are in the fetch set on purpose: a conversation is not
always on the page named after its NPC. Turael's, for instance, lives on
`Transcript:Achievement Paths` -- a tutorial transcript named for the content --
so that category is fetched alongside the main ones.

The four counts above are printed by --enumerate and written to
data/wiki/dialogue/manifest.json under "_counts" on every run. They move as the
wiki is edited, so the manifest, not this docstring, is the number of record.

THE STORE
---------
    data/wiki/dialogue/manifest.json   title -> {title, revid, retrieved, chars, key, categories}
    data/wiki/dialogue/pages.jsonl.gz  one {"title","revid","retrieved","wikitext"} per line

The xp pass wrote one `.wikitext` file per page under `data/wiki/xp/raw/`. At
3,203 pages averaging ~8 KB that is ~26 MB in small files, so this pass follows
the OTHER precedent in the tree for a bulk wiki pull - `data/seed/
npc_wiki_pages.jsonl.gz`, written by tools/seed/build_wiki_seed.py - and stores the
same content gzipped, one JSON record per line. The manifest is unchanged in
kind: every page title with the revision id it was read at.

BEING A GOOD CITIZEN
--------------------
One descriptive User-Agent with a contact address; PAUSE seconds between
requests; enumeration done with `prop=revisions&rvprop=ids` (500 titles per
request, no content) so that a rerun downloads content ONLY for pages whose
revid moved; content fetched 50 titles per request, which is the API's cap for
`rvprop=content`. A full cold fetch is ~65 content requests; a warm rerun is
~7 enumeration requests and nothing else.
"""
import gzip
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request

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

UA = ("Mozilla/5.0 RS3OS-research/1.0 (private server research; runescape.wiki API; "
      "contact " + CONTACT + ")")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "data", "wiki", "dialogue")
MANIFEST = os.path.join(OUT, "manifest.json")
PAGES = os.path.join(OUT, "pages.jsonl.gz")
PAUSE = 0.6

# The categories the `{{Dialogue|<kind>}}` banner files a transcript into. The
# first two are fetched; the rest are enumerated for the report only.
FETCH_CATEGORIES = ["Category:NPC dialogue", "Category:Tutorial transcripts"]
REPORT_CATEGORIES = ["Category:Real-life transcripts", "Category:Quest transcripts"]


def get(params):
    params = dict(params, format="json", formatversion="2")
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params),
                                 headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.load(r)


def category_size(cat):
    r = get(dict(action="query", prop="categoryinfo", titles=cat))
    p = r["query"]["pages"][0]
    return (p.get("categoryinfo") or {}).get("size", 0)


def namespace_total():
    """How many pages namespace 120 holds. `allpages` with aplimit=500."""
    n = 0
    cont = {}
    while True:
        r = get(dict(action="query", list="allpages", apnamespace="120", aplimit="500",
                     **cont))
        n += len(r["query"]["allpages"])
        if "continue" not in r:
            return n
        cont = r["continue"]
        time.sleep(PAUSE)


def enumerate_set():
    """
    Every title in FETCH_CATEGORIES with its CURRENT revision id, in one pass.

    `generator=categorymembers` + `prop=revisions&rvprop=ids` returns 500 titles
    per request and no content, so this is what makes a rerun cheap.
    """
    out = {}
    for cat in FETCH_CATEGORIES:
        cont = {}
        while True:
            r = get(dict(action="query", generator="categorymembers", gcmtitle=cat,
                         gcmnamespace="120", gcmlimit="500", prop="revisions",
                         rvprop="ids", **cont))
            for p in r["query"]["pages"]:
                rv = (p.get("revisions") or [{}])[0]
                rec = out.setdefault(p["title"], dict(title=p["title"],
                                                      revid=rv.get("revid"),
                                                      categories=[]))
                rec["categories"].append(cat)
            if "continue" not in r:
                break
            cont = r["continue"]
            time.sleep(PAUSE)
    return out


def load_store():
    """title -> record, from pages.jsonl.gz. Missing file = empty store."""
    store = {}
    if os.path.exists(PAGES):
        with gzip.open(PAGES, "rt", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                store[rec["title"]] = rec
    return store


def write_store(store):
    os.makedirs(OUT, exist_ok=True)
    tmp = PAGES + ".tmp"
    # mtime=0 so a rerun that changes nothing produces a byte-identical file.
    with gzip.GzipFile(tmp, "wb", mtime=0) as gz:
        w = io.TextIOWrapper(gz, encoding="utf-8", newline="\n")
        for title in sorted(store):
            w.write(json.dumps(store[title], sort_keys=True, ensure_ascii=False) + "\n")
        w.flush()
    if os.path.exists(PAGES):
        os.remove(PAGES)
    os.rename(tmp, PAGES)


def fetch_content(titles, store, today):
    """Fetch wikitext for `titles`, 50 at a time (the API cap for rvprop=content)."""
    got = 0
    for i in range(0, len(titles), 50):
        chunk = titles[i:i + 50]
        try:
            r = get(dict(action="query", titles="|".join(chunk), prop="revisions",
                         rvslots="main", rvprop="content|ids"))
        except Exception as e:
            print("FAIL batch %d-%d %s" % (i, i + len(chunk), e))
            time.sleep(5.0)
            continue
        for p in r["query"]["pages"]:
            if "missing" in p or not p.get("revisions"):
                print("MISSING", p.get("title"))
                continue
            rv = p["revisions"][0]
            store[p["title"]] = dict(title=p["title"], revid=rv["revid"],
                                     retrieved=today,
                                     wikitext=rv["slots"]["main"]["content"])
            got += 1
        print("  fetched %5d / %5d" % (min(i + 50, len(titles)), len(titles)))
        time.sleep(PAUSE)
    return got


def main(argv):
    do_enum = "--enumerate" in argv
    do_fetch = "--fetch" in argv
    limit = None
    if "--limit" in argv:
        limit = int(argv[argv.index("--limit") + 1])
    if not (do_enum or do_fetch):
        print(__doc__)
        return 0

    today = time.strftime("%Y-%m-%d")
    counts = {}
    for cat in FETCH_CATEGORIES + REPORT_CATEGORIES:
        counts[cat] = category_size(cat)
        print("%-34s %6d" % (cat, counts[cat]))
        time.sleep(PAUSE)
    if do_enum:
        counts["namespace_120_total"] = namespace_total()
        print("%-34s %6d" % ("namespace 120 (Transcript:)", counts["namespace_120_total"]))

    want = enumerate_set()
    print("fetch set: %d distinct titles over %s" % (len(want), ", ".join(FETCH_CATEGORIES)))
    counts["fetch_set"] = len(want)

    if not do_fetch:
        return 0

    store = load_store()
    stale = [t for t, rec in sorted(want.items())
             if t not in store or store[t].get("revid") != rec["revid"]]
    gone = [t for t in store if t not in want]
    print("store holds %d; %d to fetch; %d in the store are no longer in the categories"
          % (len(store), len(stale), len(gone)))
    if limit is not None:
        stale = stale[:limit]
        print("  --limit %d: fetching %d" % (limit, len(stale)))
    if stale:
        fetch_content(stale, store, today)
        write_store(store)

    manifest = {"_source": "runescape.wiki API, action=query&prop=revisions",
                "_categories_fetched": FETCH_CATEGORIES,
                "_store": "data/wiki/dialogue/pages.jsonl.gz",
                "_retrieved": today,
                "_counts": counts,
                "pages": {}}
    for t in sorted(want):
        rec = store.get(t)
        if not rec:
            continue
        # NO `file` FIELD. The xp pass's manifest names a per-page .wikitext
        # file; this pass stores the pages in one gzipped jsonl (see THE STORE
        # above), and writing a filename that does not exist would plant exactly
        # the REFERENCED-but-absent signal `tools/ignore_audit.py` hunts for
        # (root CLAUDE.md, failure mode 7). `key` is the record's key in
        # pages.jsonl.gz, which IS the title.
        manifest["pages"][t] = dict(title=t, revid=rec["revid"],
                                    retrieved=rec["retrieved"],
                                    chars=len(rec["wikitext"]),
                                    key=t,
                                    categories=want[t]["categories"])
    os.makedirs(OUT, exist_ok=True)
    io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write(
        json.dumps(manifest, indent=1, sort_keys=True, ensure_ascii=False) + "\n")
    print("manifest: %d pages, %.1f MB of wikitext"
          % (len(manifest["pages"]),
             sum(v["chars"] for v in manifest["pages"].values()) / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
