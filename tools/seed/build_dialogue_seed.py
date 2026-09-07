#!/usr/bin/env python3
"""
build_dialogue_seed.py -- turn the stored runescape.wiki transcripts into
data/seed/dialogue_wiki.json, the flat page model
com.opennxt.content.impl.Dialogue renders.

    python tools/seed/build_dialogue_seed.py             # build from the store
    python tools/seed/build_dialogue_seed.py --report    # counts only, write nothing
    python tools/seed/build_dialogue_seed.py --page "Transcript:Turael" --dump

Input  : data/wiki/dialogue/pages.jsonl.gz + manifest.json (fetch_wiki_dialogue.py)
         data/rs3.sqlite  npcs.name -> npcs.game_id       (the name -> id bridge)
         data/seed/npc_wiki.json  wiki_page -> npc_id      (the second bridge)
Output : data/seed/dialogue_wiki.json

A transcript is a wiki editor's record of what an NPC said on some build of
the live game, not an observation of the wire, so treat this seed as approximate.
Two consequences are worth knowing before reading the rules:

 1. A conversation is not reliably on the page named after its NPC -- some
    live on pages named for the content instead. The NPC is therefore
    derived from the speakers inside a conversation, not the page title
    (see NPC_RULE below).
 2. The wiki merges consecutive NPC pages into a single bullet, so page
    boundaries are not recoverable from it. WRAP below is a rendering
    necessity, not a reconstruction of what the real client displayed.

=================================================================== THE RULES
Each is stated with the number it costs, printed by --report and recorded in the
seed's `counts` block.

CONDITIONS (the hard one). `{{Topt|cond=...|...}}`, `{{Tcond|...}}` and the old
    dialect's fully-italic `''If the player has...''` bullets gate a branch on
    quest state, items, events and instance flags this server cannot evaluate.
    **RULE: a conditional node and its whole subtree are DROPPED, and counted per
    conversation as `dropped_conditional`.** Nothing about quest state is
    invented, and no branch is silently promoted to unconditional.
    `--conditional=splice` inlines them instead (children spliced in at the
    parent's depth); it exists so the cost of the strict rule is measurable, and
    it is NOT the default.

ACTIONS. `{{Tact|...}}`, `{{Qact|...}}`, `{{Tbox|...}}`, and the old dialect's
    `''(Slayer Equipment interface opens)''` / `''(Same as above)''` /
    `''(Continues below)''` are stage directions and cross-references, not
    speech. DROPPED and counted as `dropped_action`. A branch that consisted
    only of a cross-reference therefore ends where the reference was, which is
    honest: this builder does not follow "same as above".

SPEAKER. `'''Player:'''` (any case) -> Speaker.PLAYER, rendered on 1191. Every
    other `'''Name:'''` -> Speaker.NPC on 1184, carrying that name into
    IF_SETTEXT 1184:4. **A conversation with more than one distinct non-Player
    speaker is REFUSED** (`refused_multi_speaker`): this server draws one npc
    head per conversation, and putting a second character's words under the
    first character's head is the kind of dressing-up the project's method
    forbids. That rule is also what makes NPC_RULE work.

NPC_RULE. A conversation's npc is its single distinct non-Player speaker. That
    name is joined to npc ids through, in order:
      1. `data/rs3.sqlite` `npcs.name` -> `npcs.game_id`  (exact, case-sensitive)
      2. `data/seed/npc_wiki.json` `wiki_page` -> `npc_id`
    and the ids from both are unioned. MEASURED : bridge 1 joins
    2,687 of the 3,216 stored pages by title alone and bridge 2 adds 32, which
    is why the cache name is first: npc_wiki.json is built from
    `Infobox Monster` and most dialogue NPCs are not monsters.
    A conversation that joins to no id is still written, with `npc_ids: []`, and
    `DialogueSeed` can still find it BY NAME at runtime - the running server
    reads its npc names out of `data/cache`, which is newer than rs3.sqlite
    (npc 8480, Turael, is nameless in rs3.sqlite).

OPTIONS. `Page.Choose` holds at most len(OPTION_ROWS) = 5 options, fixed by the
    cache. A node with more option children keeps the first 5 and is counted as
    `truncated_options`.

WRAP. A `Say` longer than PAGE_CHARS is split at sentence boundaries into
    consecutive pages. PAGE_CHARS = 170 is FITTED to the six recorded Turael
    pages (longest 153 chars, three rendered rows of ~52-59). See the tier note
    above: this is so a 700-character merged bullet renders at all, NOT a claim
    about where the wire breaks.

SHAPE. A conversation is refused unless page 0 is a `Say` and it has >= 2 pages.
    Page 0 is always a Say; the renderer casts the first page to `Page.Say`.

HEAD ANIMATIONS AND `<p=N>` EXPRESSIONS ARE NOT IN THE TRANSCRIPTS. The client picks
    both per line (measured: anims 9827/9843/9809/9840/9808/9833 and expressions
    3/310/2/7/2/6 across the six Turael pages). No transcript records either, so
    every page here ships without them and `Dialogue.headAnimOf` /
    `Dialogue.expressionOf` supply the defaults. Stated, not hidden.
"""
import argparse
import collections
import gzip
import io
import json
import os
import re
import sqlite3
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STORE = os.path.join(ROOT, "data", "wiki", "dialogue", "pages.jsonl.gz")
MANIFEST = os.path.join(ROOT, "data", "wiki", "dialogue", "manifest.json")
SQLITE = os.path.join(ROOT, "data", "rs3.sqlite")
NPC_WIKI = os.path.join(ROOT, "data", "seed", "npc_wiki.json")
OUT = os.path.join(ROOT, "data", "seed", "dialogue_wiki.json")
MIRROR = os.path.join(ROOT, "rig", "boottest", "data", "seed", "dialogue_wiki.json")

#: Dialogue.OPTION_ROWS.size - the cache fixes 1188 at five option rows.
MAX_OPTIONS = 5
#: See WRAP above. Fitted to the six recorded Turael pages, longest 153 chars.
PAGE_CHARS = 170
#A conversation longer than this is truncated; nothing observed comes close.
MAX_PAGES = 250
#: A BARE bullet (the old dialect's un-templated option line) longer than this is
#narration, not a menu entry. the longest option on the wire in
# One observed conversation opened with "I want to fish and cook crayfish on a
#: fire." at 43 characters, and 1188 has five short rows. Without this rule lines
#: like "Path Complete: 'Call to Adventure'! Your reward has been added..." and
#: "You finish smithing: Bronze full helm." become options. A {{Topt}} keeps its
#: text however long it is - the template says it IS an option.
MAX_OPTION_CHARS = 80

PLAYER_NAMES = {"player", "players", "player name", "player echo"}

#: Templates that are stage directions or cross-references, never speech.
ACTION_TEMPLATES = {"tact", "qact", "tbox", "tmissing", "transcript missing", "pop-up",
                    "popup", "rsfontachievement", "listen", "listen inline", "audio button",
                    "tinput", "interface tooltip", "incomplete", "construction", "clear",
                    "trandom", "main", "toc", "defaultsort", "external", "dialogue",
                    # {{RSChat}} is overhead chat and {{RSFont}} is a game message
                    # ("You finish smithing: Bronze ore box."); neither is a
                    # chathead page. 2,344 and 5,310 lines respectively.
                    "rsfont", "rschat",
                    "transcript npc", "transcript npc single", "transcript", "anchor",
                    "mentioned in transcript", "het", "floornumber"}

#: An italic bullet that opens with one of these is a condition, not a direction.
COND_OPENERS = ("if ", "when ", "during ", "after ", "before ", "only ", "while ",
                "unless ", "with ", "without ", "having ", "on the ", "once ",
                "for players", "provided ")


# --------------------------------------------------------------------------
# wikitext -> plain text
# --------------------------------------------------------------------------

def strip_templates(s):
    """Remove balanced {{...}} runs. Nested braces are handled by counting."""
    out = []
    depth = 0
    i = 0
    while i < len(s):
        if s.startswith("{{", i):
            depth += 1
            i += 2
        elif s.startswith("}}", i) and depth:
            depth -= 1
            i += 2
        else:
            if depth == 0:
                out.append(s[i])
            i += 1
    return "".join(out)


def clean(s, rootname=""):
    """Wikitext -> the string that goes on the wire. No markup survives this."""
    s = s.replace("{{ROOTPAGENAME}}", rootname)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    s = re.sub(r"<ref[^>]*/>", "", s)
    # {{Colour|c|text}} / {{RSChat|...|text}} keep their LAST argument.
    s = re.sub(r"\{\{\s*(?:[Cc]olou?r|RSFont|RSChat|Contrast)\s*\|[^{}|]*\|([^{}|]*)\}\}",
               r"\1", s)
    s = re.sub(r"\{\{\s*[Ss]ic\s*\}\}", "", s)
    s = strip_templates(s)
    s = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"</?(?:br|BR)\s*/?>", " ", s)
    s = re.sub(r"<[^>]{1,40}>", "", s)
    s = s.replace("'''", "").replace("''", "")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", '"')
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def template_name(body):
    m = re.match(r"^\{\{\s*([^|}]+)", body)
    return m.group(1).strip().lower() if m else None


def split_params(body):
    """Split a single {{...}} call's parameters at top-level pipes."""
    inner = body.strip()
    if not (inner.startswith("{{") and inner.endswith("}}")):
        return []
    inner = inner[2:-2]
    parts, depth, cur = [], 0, []
    i = 0
    while i < len(inner):
        c = inner[i]
        if inner.startswith("{{", i) or inner.startswith("[[", i):
            depth += 1
            cur.append(inner[i:i + 2])
            i += 2
            continue
        if (inner.startswith("}}", i) or inner.startswith("]]", i)) and depth:
            depth -= 1
            cur.append(inner[i:i + 2])
            i += 2
            continue
        if c == "|" and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    parts.append("".join(cur))
    return parts


# --------------------------------------------------------------------------
# wikitext -> a tree of nodes
# --------------------------------------------------------------------------

class Node(object):
    __slots__ = ("kind", "speaker", "text", "cond", "children", "depth")

    def __init__(self, kind, depth, text="", speaker=None, cond=None):
        self.kind = kind            # say | option | select | cond | action | narration
        self.depth = depth
        self.text = text
        self.speaker = speaker
        self.cond = cond
        self.children = []


SAY_RE = re.compile(r"^'''\s*([^']{1,60}?)\s*'''\s*:\s*(.*)$")
SAY_RE2 = re.compile(r"^'''\s*([^':]{1,60}?)\s*:\s*'''\s*(.*)$")
#: The third dialect: a bold name with NO colon at all - "'''Turael''' Mountain
#trolls are hard as rocks..". over the 3,216 stored pages:
#: 75,979 lines in the two colon dialects, 454 in this one. Guarded to a
#: capitalised name of at most 40 characters followed by real text, because an
#: unguarded rule would swallow every bullet that opens with a bold word.
SAY_RE3 = re.compile(r"^'''\s*([A-Z][^'|:]{0,39}?)\s*'''\s+(\S.*)$")


def classify(body, depth, rootname):
    """One bullet line -> a Node, or None if it carries nothing."""
    body = body.strip()
    if not body:
        return None

    tname = template_name(body)
    if tname == "tselect":
        p = split_params(body)
        title = clean(p[1], rootname) if len(p) > 1 and "=" not in p[1] else ""
        return Node("select", depth, text=title)
    if tname == "topt":
        p = split_params(body)[1:]
        cond = None
        pos = []
        for a in p:
            if a.strip().lower().startswith("cond="):
                cond = clean(a.split("=", 1)[1], rootname)
            elif "=" in a.split("|")[0][:12] and re.match(r"^\s*\w+\s*=", a):
                continue
            else:
                pos.append(a)
        text = clean(pos[-1], rootname) if pos else ""
        return Node("option", depth, text=text, cond=cond)
    if tname == "tcond":
        p = split_params(body)
        return Node("cond", depth, text=clean(p[1], rootname) if len(p) > 1 else "")
    if tname in ACTION_TEMPLATES:
        return Node("action", depth, text=clean(body, rootname))

    m = SAY_RE2.match(body) or SAY_RE.match(body) or SAY_RE3.match(body)
    if m:
        name = clean(m.group(1), rootname)
        text = clean(m.group(2), rootname)
        if not name:
            return None
        return Node("say", depth, text=text, speaker=name)

    # A fully italic bullet: a condition if it reads like one, else a direction.
    it = re.match(r"^''(.*)''$", body)
    if it:
        inner = clean(it.group(1), rootname)
        low = inner.lower()
        if low.startswith(COND_OPENERS):
            return Node("cond", depth, text=inner)
        return Node("action", depth, text=inner)

    txt = clean(body, rootname)
    if not txt:
        return None
    if depth == 0 or len(txt) > MAX_OPTION_CHARS:
        return Node("narration", depth, text=txt)
    return Node("option", depth, text=txt)


def parse_sections(wikitext, rootname):
    """
    The page -> [(heading path, [top-level Nodes])].

    A conversation is the run of content lines under one heading. Nesting comes
    from the `*` count; a content line with no `*` is depth 0.
    """
    sections = []
    path = []
    cur = []

    def flushed():
        if cur:
            sections.append((" / ".join(path), cur[:]))
        del cur[:]

    for raw in wikitext.split("\n"):
        s = raw.rstrip()
        h = re.match(r"^(={2,6})\s*(.*?)\s*\1\s*$", s.strip())
        if h:
            flushed()
            level = len(h.group(1)) - 2
            path = path[:level] + [clean(h.group(2), rootname)]
            continue
        if not s.strip():
            continue
        m = re.match(r"^(\*+)\s*(.*)$", s.strip())
        depth = len(m.group(1)) if m else 0
        body = m.group(2) if m else s.strip()
        node = classify(body, depth, rootname)
        if node is not None:
            cur.append(node)
    flushed()

    out = []
    for name, flat in sections:
        out.append((name, nest(flat)))
    return out


def nest(flat):
    """Depth numbers -> parent/child links. A jump of more than one is clamped."""
    roots = []
    stack = []
    for n in flat:
        while stack and stack[-1].depth >= n.depth:
            stack.pop()
        if stack:
            stack[-1].children.append(n)
        else:
            roots.append(n)
        stack.append(n)
    return roots


# --------------------------------------------------------------------------
# the tree -> the flat page list
# --------------------------------------------------------------------------

SENT_RE = re.compile(r"(?<=[.!?])\s+")


def wrap(text):
    """See WRAP. Greedy sentence packing at PAGE_CHARS."""
    if len(text) <= PAGE_CHARS:
        return [text]
    out, cur = [], ""
    for sent in SENT_RE.split(text):
        if not cur:
            cur = sent
        elif len(cur) + 1 + len(sent) <= PAGE_CHARS:
            cur = cur + " " + sent
        else:
            out.append(cur)
            cur = sent
    if cur:
        out.append(cur)
    # A single sentence longer than PAGE_CHARS is left whole rather than cut
    # mid-clause; the client wraps it and the page scrolls off. Counted.
    return out


class Flattener(object):
    def __init__(self, conditional="drop"):
        self.conditional = conditional
        self.pages = []
        self.stats = collections.Counter()

    def add_say(self, speaker, name, text):
        for piece in wrap(text):
            if len(self.pages) >= MAX_PAGES:
                self.stats["truncated_pages"] += 1
                return
            self.pages.append({"t": "say", "s": speaker, "n": name, "x": piece})

    def count_subtree(self, node):
        n = 1
        for c in node.children:
            n += self.count_subtree(c)
        return n

    def emit(self, siblings):
        """
        Emit a sibling run. Returns the index of the FIRST page it produced, or
        -1 if it produced none (an empty branch ends the conversation).
        """
        first = -1
        i = 0
        while i < len(siblings):
            n = siblings[i]

            if n.kind in ("cond",) or (n.kind == "option" and n.cond):
                if self.conditional == "drop":
                    self.stats["dropped_conditional"] += 1
                    self.stats["dropped_conditional_lines"] += self.count_subtree(n)
                    i += 1
                    continue
                # --conditional=splice: the gate is discarded, the body is kept.
                self.stats["spliced_conditional"] += 1
                if n.kind == "cond":
                    got = self.emit(n.children)
                    first = first if first >= 0 else got
                    i += 1
                    continue
                n = Node("option", n.depth, text=n.text)
                n.children = siblings[i].children

            if n.kind in ("action", "narration"):
                self.stats["dropped_action"] += 1
                i += 1
                continue

            if n.kind == "say":
                if n.text:
                    idx = len(self.pages)
                    self.add_say("player" if n.speaker.lower() in PLAYER_NAMES else "npc",
                                 n.speaker, n.text)
                    if len(self.pages) > idx and first < 0:
                        first = idx
                got = self.emit(n.children)
                if first < 0:
                    first = got
                i += 1
                continue

            if n.kind == "select":
                # The children of a {{Tselect}} are its options.
                got = self.emit_choice(n.children, title=n.text)
                if first < 0:
                    first = got
                i += 1
                continue

            if n.kind == "option":
                run = []
                while i < len(siblings) and siblings[i].kind == "option":
                    if siblings[i].cond and self.conditional == "drop":
                        self.stats["dropped_conditional"] += 1
                        self.stats["dropped_conditional_lines"] += self.count_subtree(siblings[i])
                    else:
                        run.append(siblings[i])
                    i += 1
                got = self.emit_choice(run, title=None)
                if first < 0:
                    first = got
                continue

            i += 1
        return first

    def emit_choice(self, options, title):
        options = [o for o in options if o.kind == "option" and o.text]
        if not options:
            return -1
        if len(options) > MAX_OPTIONS:
            self.stats["truncated_options"] += 1
            options = options[:MAX_OPTIONS]
        if len(self.pages) >= MAX_PAGES:
            self.stats["truncated_pages"] += 1
            return -1
        here = len(self.pages)
        page = {"t": "choose", "o": [o.text for o in options], "g": [-1] * len(options)}
        if title:
            page["i"] = title
        self.pages.append(page)
        for k, o in enumerate(options):
            target = self.emit(o.children)
            page["g"][k] = target
            # A branch that produced pages must END there, not fall through into
            # the next branch. Dialogue.Page.Say.next carries that jump; -1 on
            # the LAST page of the branch means "past the end", i.e. close.
            if target >= 0:
                last = self.pages[-1]
                if last["t"] == "say":
                    last["e"] = True
        return here


# --------------------------------------------------------------------------
# npc name -> ids
# --------------------------------------------------------------------------

def load_bridges():
    cache = collections.defaultdict(list)
    if os.path.exists(SQLITE):
        c = sqlite3.connect(SQLITE)
        for gid, name in c.execute("select game_id, name from npcs "
                                   "where name is not null and name != '' "
                                   "and game_id is not null"):
            cache[name].append(int(gid))
        c.close()
    wiki = collections.defaultdict(list)
    if os.path.exists(NPC_WIKI):
        for r in json.load(io.open(NPC_WIKI, encoding="utf-8"))["npcs"]:
            wiki[r["wiki_page"]].append(int(r["npc_id"]))
    return cache, wiki


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def build(conditional="drop", only_page=None, dump=False):
    manifest = json.load(io.open(MANIFEST, encoding="utf-8"))
    cache_names, wiki_pages = load_bridges()

    counts = collections.Counter()
    conversations = []
    for line in gzip.open(STORE, "rt", encoding="utf-8"):
        rec = json.loads(line)
        title = rec["title"]
        if only_page and title != only_page:
            continue
        counts["pages_read"] += 1
        rootname = title.split(":", 1)[1] if ":" in title else title
        for section, roots in parse_sections(rec["wikitext"], rootname):
            counts["sections"] += 1
            f = Flattener(conditional=conditional)
            f.emit(roots)
            pages = f.pages
            if len(pages) < 2:
                counts["refused_too_short"] += 1
                continue
            if pages[0]["t"] != "say":
                counts["refused_not_say_first"] += 1
                continue
            speakers = sorted({p["n"] for p in pages
                               if p["t"] == "say" and p["s"] == "npc"})
            if not speakers:
                counts["refused_no_npc_speaker"] += 1
                continue
            if len(speakers) > 1:
                counts["refused_multi_speaker"] += 1
                continue
            npc_name = speakers[0]
            ids = sorted(set(cache_names.get(npc_name, [])) |
                         set(wiki_pages.get(npc_name, [])))
            if ids:
                counts["joined_conversations"] += 1
            else:
                counts["unjoined_conversations"] += 1
            counts["conversations"] += 1
            counts["seeded_pages"] += len(pages)
            for k, v in f.stats.items():
                counts[k] += v
            conversations.append({
                "page": title,
                "revid": rec["revid"],
                "section": section,
                "npc_name": npc_name,
                "npc_ids": ids,
                "id_source": ("cache_name" if cache_names.get(npc_name)
                              else ("npc_wiki" if wiki_pages.get(npc_name) else "none")),
                "dropped_conditional": f.stats.get("dropped_conditional", 0),
                "dropped_action": f.stats.get("dropped_action", 0),
                "truncated_options": f.stats.get("truncated_options", 0),
                "pages": pages,
            })
            if dump:
                print("### %s # %s   npc %s %s" % (title, section, npc_name, ids))
                for k, p in enumerate(pages):
                    print("  %3d %s" % (k, json.dumps(p, ensure_ascii=False)))

    # Rank: the longest conversation for an npc first, so DialogueSeed's
    # "first match wins" picks the richest one. Stable within a page.
    conversations.sort(key=lambda c: (-len(c["pages"]), c["page"], c["section"]))
    counts["distinct_npc_names"] = len({c["npc_name"] for c in conversations})
    counts["distinct_npc_ids"] = len({i for c in conversations for i in c["npc_ids"]})
    return manifest, conversations, counts


def write(manifest, conversations, counts, conditional):
    doc = collections.OrderedDict()
    doc["_schema"] = "opennxt.seed.dialogue_wiki/1"
    doc["_provenance"] = "documented"
    doc["_provenance_meaning"] = (
        "every line of speech in this file was transcribed by a runescape.wiki editor onto the "
        "page and revision named on each conversation, and re-shaped into pages by "
        "tools/seed/build_dialogue_seed.py. It is derived from wiki transcripts rather than "
        "recorded from the wire, so treat it as approximate; nothing here is invented.")
    doc["_rules"] = {
        "conditional": ("DROPPED with its whole subtree, counted per conversation as "
                        "dropped_conditional. Quest/item/event state is not modelled and is "
                        "not invented." if conditional == "drop" else
                        "SPLICED - the gate is discarded and the body kept. NOT the default."),
        "action": ("stage directions and cross-references ({{Tact}}, {{Qact}}, {{Tbox}}, "
                   "''(Same as above)'') are DROPPED and counted as dropped_action; "
                   "'same as above' is NOT followed."),
        "speaker": ("'''Player:''' -> player (interface 1191); any other '''Name:''' -> npc "
                    "(1184) carrying that name. A conversation with two distinct non-Player "
                    "speakers is REFUSED."),
        "npc": ("the conversation's npc is its single distinct non-Player speaker, joined to "
                "ids by data/rs3.sqlite npcs.name -> game_id first, then npc_wiki.json "
                "wiki_page -> npc_id. Not by page title: the one recorded conversation lives "
                "on Transcript:Achievement Paths, not Transcript:Turael."),
        "options": "at most %d per Choose (Dialogue.OPTION_ROWS); the rest are truncated." % MAX_OPTIONS,
        "wrap": ("a Say longer than %d characters is split at sentence boundaries. FITTED to the "
                 "six recorded Turael pages (longest 153). Page boundaries are NOT recoverable "
                 "from the wiki - the wiki merges consecutive pages into one bullet." % PAGE_CHARS),
        "shape": "refused unless page 0 is a Say and the conversation has >= 2 pages.",
        "not_modelled": ("head animations and the <p=N> expression code. The reference client picks both per "
                         "LINE (anims 9827/9843/9809/9840/9808/9833, expressions 3/310/2/7/2/6 "
                         "over the six recorded Turael pages); no transcript records either, so "
                         "every page here ships without them and Dialogue.headAnimOf / "
                         "expressionOf supply the defaults."),
    }
    doc["_page_model"] = {
        "t=say": "s = npc|player, n = the name on 1184:4 / 1191:4, x = the line, "
                 "e = true means this page ENDS the conversation (Dialogue.Page.Say.endsHere)",
        "t=choose": "o = option strings, g = parallel jump targets (page index, -1 = end), "
                    "i = the optional list title (Dialogue.DEFAULT_OPTIONS_TITLE when absent)",
    }
    doc["retrieved"] = manifest.get("_retrieved", time.strftime("%Y-%m-%d"))
    doc["source_manifest"] = "data/wiki/dialogue/manifest.json"
    doc["counts"] = collections.OrderedDict(sorted(counts.items()))
    doc["conversations"] = conversations
    text = json.dumps(doc, indent=1, ensure_ascii=False) + "\n"
    io.open(OUT, "w", encoding="utf-8", newline="\n").write(text)
    print("wrote %s  (%.2f MB)" % (os.path.relpath(OUT, ROOT), len(text.encode("utf-8")) / 1e6))
    if os.path.isdir(os.path.dirname(MIRROR)):
        io.open(MIRROR, "w", encoding="utf-8", newline="\n").write(text)
        print("mirrored %s" % os.path.relpath(MIRROR, ROOT))
    else:
        print("NOTE: %s does not exist; the boottest mirror was NOT written "
              "(rig/boottest is gitignored and rebuilt - see CONSOLIDATION-.md)"
              % os.path.relpath(os.path.dirname(MIRROR), ROOT))


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="counts only, write nothing")
    ap.add_argument("--dump", action="store_true", help="print every conversation")
    ap.add_argument("--page", help="restrict to one transcript title")
    ap.add_argument("--conditional", choices=["drop", "splice"], default="drop")
    a = ap.parse_args(argv)
    manifest, conversations, counts = build(a.conditional, a.page, a.dump)
    for k in sorted(counts):
        print("%-32s %8d" % (k, counts[k]))
    if not a.report and not a.page:
        write(manifest, conversations, counts, a.conditional)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
