#!/usr/bin/env python3
"""
build_wiki_xp.py -- turn the runescape.wiki Lua data modules stored by fetch_wiki_pages.py
(data/wiki/xp/raw/Module_*.lua) into JSON seeds:

    data/seed/skill_xp_wiki.json      per-action xp for every skill: {skill: {category: [rows]}}
                                      rows carry name, level, xp and whatever else the module
                                      states (materials, members, ...), verbatim
    data/seed/xp_curve_wiki.json      the level curve (Module:Experience/data, /elitedata)
    data/seed/skill_chance_wiki.json  the gathering success data (TreeData, HatchetData,
                                      Fishing/Thieving chance data, Seed data, MiningPicks)

PROVENANCE: "documented". Every number is read off a wiki Lua module at the revision id the
manifest records (data/wiki/xp/manifest.json). Nothing is typed in here. The parser is a small
Lua table-literal reader (strings, numbers, booleans, nil, nested tables, [key]= and key= forms,
comments); it does NOT execute Lua, so modules that compute their tables (Skill calc/Herblore is
one) are read only as far as their literal tables go and the report says what was skipped.

    python tools/seed/build_wiki_xp.py            # build all three, print the census
"""
import io, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW = os.path.join(ROOT, "data", "wiki", "xp", "raw")
MANIFEST = os.path.join(ROOT, "data", "wiki", "xp", "manifest.json")
SEED = os.path.join(ROOT, "data", "seed")

# ------------------------------------------------------------------ a Lua literal reader
class LuaReader:
    def __init__(self, text):
        self.s = text; self.i = 0; self.n = len(text)

    def skip(self):
        while self.i < self.n:
            c = self.s[self.i]
            if c in " \t\r\n": self.i += 1
            elif self.s.startswith("--[=[", self.i) or self.s.startswith("--[[", self.i):
                close = "]=]" if self.s.startswith("--[=[", self.i) else "]]"
                j = self.s.find(close, self.i); self.i = self.n if j < 0 else j + len(close)
            elif self.s.startswith("--", self.i):
                j = self.s.find("\n", self.i); self.i = self.n if j < 0 else j + 1
            else: break

    def peek(self):
        self.skip(); return self.s[self.i] if self.i < self.n else ""

    def value(self):
        self.skip()
        c = self.peek()
        if c == "{": return self.table()
        if c in "\"'": return self.string(c)
        if self.s.startswith("[[", self.i) or self.s.startswith("[=[", self.i):
            close = "]]" if self.s.startswith("[[", self.i) else "]=]"
            j = self.s.index(close, self.i); v = self.s[self.i + len(close):j]; self.i = j + len(close); return v
        if c == "(":
            # a parenthesised expression: swallow it balanced, evaluate when it is pure arithmetic
            depth = 0; start = self.i
            while self.i < self.n:
                ch = self.s[self.i]
                if ch == "(": depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0: self.i += 1; break
                self.i += 1
            m2 = re.match(r"(?:\s*[-+*/]\s*\(?[\d.]+\)?)*", self.s[self.i:]); self.i += m2.end()
            t = self.s[start:self.i]
            if re.fullmatch(r"[\d.\s()+*/-]+", t):
                try: return eval(t, {"__builtins__": {}})
                except Exception: pass
            return {"__expr__": t.strip()}
        m = re.match(r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?(?:\s*[-+*/]\s*(?:\d+\.?\d*|\.\d+))*", self.s[self.i:])
        if m and (c.isdigit() or c in "-."):
            self.i += m.end(); t = m.group(0)
            if re.search(r"[-+*/]", t[1:]):
                v = eval(t, {"__builtins__": {}})      # a numeric expression such as 1/150; digits and operators only
                return v
            return float(t) if ("." in t or "e" in t or "E" in t) else int(t)
        m = re.match(r"[A-Za-z_][A-Za-z0-9_.]*", self.s[self.i:])
        if m:
            w = m.group(0); self.i += m.end()
            if w == "true": return True
            if w == "false": return False
            if w == "nil": return None
            # an expression we cannot evaluate: swallow to the next , or } at depth 0 and keep the text
            depth = 0; start = self.i - len(w)
            while self.i < self.n:
                ch = self.s[self.i]
                if ch in "({[": depth += 1
                elif ch in ")}]":
                    if depth == 0: break
                    depth -= 1
                elif ch == "," and depth == 0: break
                self.i += 1
            return {"__expr__": self.s[start:self.i].strip()}
        raise ValueError("unexpected %r at %d: %s" % (c, self.i, self.s[self.i:self.i + 60]))

    def string(self, q):
        self.i += 1; out = []
        while self.i < self.n:
            ch = self.s[self.i]
            if ch == "\\":
                nxt = self.s[self.i + 1]; out.append({"n": "\n", "t": "\t"}.get(nxt, nxt)); self.i += 2; continue
            if ch == q: self.i += 1; return "".join(out)
            out.append(ch); self.i += 1
        raise ValueError("unterminated string")

    def table(self):
        assert self.peek() == "{"; self.i += 1
        items = []; mapping = {}; idx = 0
        while True:
            self.skip()
            if self.i >= self.n: raise ValueError("unterminated table")
            c = self.s[self.i]
            if c == "}": self.i += 1; break
            if c in ",;": self.i += 1; continue
            if c == "[":
                self.i += 1; k = self.value(); self.skip(); assert self.s[self.i] == "]"; self.i += 1
                self.skip(); assert self.s[self.i] == "="; self.i += 1
                mapping[str(k)] = self.value(); continue
            m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", self.s[self.i:])
            if m:
                self.i += m.end(); mapping[m.group(1)] = self.value(); continue
            idx += 1; items.append(self.value())
        if mapping and items:
            mapping["__items__"] = items; return mapping
        return mapping if mapping else items

def read(path):
    return io.open(path, encoding="utf-8").read()

def find_table_after(text, pos):
    j = text.find("{", pos)
    r = LuaReader(text); r.i = j
    return r.table(), r.i

# ------------------------------------------------------------------ Skill calc/<skill>/data
def named_tables(text, report, who):
    """Every `local X = {`, `X = {`, `p.X = {`, `methods["X"] = {` table literal in the module whose
    rows are dicts with a level and an xp field: Cooking, Magic, Herblore and Archaeology keep their
    data in named locals or on a `p` table instead of trainMethod branches."""
    out = {}
    for m in re.finditer(r'(?m)^[ 	]*(?:local\s+)?([A-Za-z_][\w.]*(?:\[\s*"[^"]+"\s*\])?)\s*=\s*\{', text):
        name = m.group(1)
        try:
            tbl, _ = find_table_after(text, m.end() - 1)
        except Exception as e:
            report.append("%s/%s: %s" % (who, name, e)); continue
        rows = tbl if isinstance(tbl, list) else (tbl.get("__items__") or list(tbl.values()))
        rows = [r for r in rows if isinstance(r, dict) and "xp" in r and ("level" in r or "name" in r)]
        if rows:
            key = re.sub(r'^(?:p\.|methods)', "", name).strip('[]"')
            out.setdefault(key, []).extend(rows)
    return out

def skill_calc(skill, text, report):
    """The modules are `return function(trainMethod) if trainMethod == "Cat" then local methods = {...}`.
    Read every category's literal table."""
    out = {}
    for m in re.finditer(r'trainMethod\s*==\s*"([^"]+)"', text):
        cat = m.group(1)
        k = text.find("methods", m.end())
        if k < 0 or k - m.end() > 400: continue
        try:
            tbl, _ = find_table_after(text, k)
        except Exception as e:
            report.append("%s/%s: %s" % (skill, cat, e)); continue
        rows = tbl if isinstance(tbl, list) else tbl.get("__items__", [])
        rows = [r for r in rows if isinstance(r, dict)]
        if rows: out.setdefault(cat, []).extend(rows)
    if not out:
        out = named_tables(text, report, skill)
    return out

def main():
    manifest = json.load(io.open(MANIFEST, encoding="utf-8"))
    def prov(title):
        m = manifest.get(title, {})
        return dict(title=title, revid=m.get("revid"), retrieved=m.get("retrieved"),
                    url="https://runescape.wiki/w/" + title.replace(" ", "_"))
    report = []

    # ---- 1. per-action xp
    skills = {}
    for f in sorted(os.listdir(RAW)):
        m = re.match(r"Module_Skill_calc_(.+)_data\.lua$", f)
        if not m: continue
        skill = m.group(1)
        text = read(os.path.join(RAW, f))
        cats = skill_calc(skill, text, report)
        n = sum(len(v) for v in cats.values())
        lv = sum(1 for v in cats.values() for r in v if "level" in r and "xp" in r)
        skills[skill] = dict(provenance=prov("Module:Skill calc/%s/data" % skill), categories=cats,
                             rows=n, rows_with_level_and_xp=lv)
        print("  %-14s %3d categories %5d rows, %5d with level+xp" % (skill, len(cats), n, lv))
    io.open(os.path.join(SEED, "skill_xp_wiki.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(dict(provenance="documented: runescape.wiki Module:Skill calc/<skill>/data, revision ids per skill",
                        built_by="tools/seed/build_wiki_xp.py", skills=skills), indent=1, sort_keys=True) + "\n")

    # ---- 2. the level curve
    curve = {}
    for name, title in [("standard", "Module:Experience/data"), ("elite", "Module:Experience/elitedata")]:
        text = read(os.path.join(RAW, title.replace(":", "_").replace("/", "_").replace(" ", "_") + ".lua"))
        tbl, _ = find_table_after(text, text.rfind("return"))
        if isinstance(tbl, dict) and "__items__" not in tbl:
            vals = [tbl[k] for k in sorted(tbl, key=lambda k: int(k))]   # the elite table is written [level] = xp
        else:
            vals = tbl if isinstance(tbl, list) else tbl.get("__items__", [])
        curve[name] = dict(provenance=prov(title), total_xp_to_be_level=[v for v in vals if isinstance(v, (int, float))])
        print("  curve %-8s %d entries, last = %s" % (name, len(curve[name]["total_xp_to_be_level"]), curve[name]["total_xp_to_be_level"][-1]))
    io.open(os.path.join(SEED, "xp_curve_wiki.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(curve, indent=1, sort_keys=True) + "\n")

    # ---- 3. gathering chance data
    chance = {}
    for key, title in [("trees", "Module:SkillUtils/TreeData"), ("hatchets", "Module:SkillUtils/HatchetData"),
                       ("seeds", "Module:SkillUtils/SeedData"), ("fishing", "Module:Fishing chance calculator/data"),
                       ("thieving", "Module:Thieving chance calculator/data"), ("pickaxes", "Module:MiningPicks/Data"),
                       ("woodcutting_chance_sandbox", "Module:Woodcutting chance calculator/sandbox/data")]:
        path = os.path.join(RAW, title.replace(":", "_").replace("/", "_").replace(" ", "_") + ".lua")
        if not os.path.exists(path): report.append("missing " + title); continue
        text = read(path)
        tbl = None
        try:
            if re.search(r"(?m)^return\s*\{", text):
                tbl, _ = find_table_after(text, re.search(r"(?m)^return\s*\{", text).start())
        except Exception as e:
            report.append("%s: %r" % (title, e))
        if tbl is None:
            # `local p = {}` ... `p.x = {...}` ... `return p`: keep every named table
            tbl = {}
            for m in re.finditer(r'(?m)^[ 	]*(?:local\s+)?([A-Za-z_][\w.]*)\s*=\s*\{', text):
                try:
                    t2, _ = find_table_after(text, m.end() - 1)
                except Exception as e:
                    report.append("%s/%s: %r" % (title, m.group(1), e)); continue
                if t2: tbl[re.sub(r"^p\.", "", m.group(1))] = t2
        chance[key] = dict(provenance=prov(title), data=tbl)
        print("  chance %-28s %s" % (key, "%d entries" % len(tbl) if isinstance(tbl, (list, dict)) else type(tbl).__name__))
    io.open(os.path.join(SEED, "skill_chance_wiki.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(chance, indent=1, sort_keys=True) + "\n")

    if report:
        print("REPORT (not parsed):")
        for r in report: print("  " + r)

if __name__ == "__main__":
    main()
