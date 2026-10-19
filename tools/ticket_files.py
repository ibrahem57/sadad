#!/usr/bin/env python3
"""Path checks on ticket files, called by the maintainer's local ticket runner and by .github/workflows/ticket-scope.yml.

  ticket_files.py lint TICKET          paths named in Test first / Steps but absent from the Files section
  ticket_files.py overlap T1 T2 ...    editable paths shared between tickets (T1 against the rest)
  ticket_files.py scope TICKET         changed paths on stdin that "Files you may edit" does not allow
Exit 1 when anything is printed.
"""
import re
import sys

EXT = r"\.(md|sh|kts?|java|ts|mjs|cjs|js|json|toml|sql|ya?ml|xml|properties|html|css)$"


def section(text, name):
    m = re.search(rf"^## {name}\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return m.group(1) if m else ""


def line(text, label):
    """Rest of the label line, plus the bullet list (blank lines allowed) right under it."""
    m = re.search(rf"^{label}[^:\n]*:(.*)$", text, re.M)
    if not m:
        return None
    out = [m.group(1)]
    for l in text[m.end() :].split("\n")[1:]:
        if l.strip() and not re.match(r"\s*[-*] ", l):
            break
        out.append(l)
    return "\n".join(out)


def paths(s, bare=False):
    """Backticked tokens that look like repo paths; bare=True also keeps plain file names (`gradlew`)."""
    out = []
    for tok in re.findall(r"`([^`\s]+)`", s or ""):
        tok = tok.rstrip(".,;:")
        if tok.startswith(("/", "./", "-", "http")) or "(" in tok or "=" in tok:
            continue
        if "/" in tok or re.search(EXT, tok) or re.fullmatch(r"\.[\w.-]+", tok) or bare and re.fullmatch(r"[\w.-]+", tok):
            out.append(tok)
    return out


def to_regex(p):
    r = ""
    i = 0
    while i < len(p):
        c = p[i]
        if p.startswith("**", i):
            r += ".*"
            i += 2
            continue
        if c == "*":
            r += "[^/]*"
        elif c == "<":
            j = p.find(">", i)
            r += "[^/]*"
            i = j if j > 0 else i
        elif c == "{":
            j = p.find("}", i)
            r += "(" + "|".join(map(re.escape, p[i + 1 : j].split(","))) + ")"
            i = j
        else:
            r += re.escape(c)
        i += 1
    if p.endswith("/"):
        r += ".*"
    return r


def matches(path, patterns):
    """True when path is covered by a pattern, or is a shorter tail of a path the pattern covers."""
    for p in patterns:
        parts = p.split("/")
        if any(re.fullmatch(to_regex("/".join(parts[k:])), path) for k in range(len(parts))):
            return True
        if re.search(r"(^|/)" + re.escape(path.rstrip("/")) + "(/|$)", p):
            return True
    return False


def prefix(p):
    """Literal part of a pattern, up to its first glob character."""
    return re.split(r"[*<{]", p)[0]


def overlaps(a, b):
    pa, pb = prefix(a), prefix(b)
    if pa == a and pb == b:
        return a == b or a.endswith("/") and b.startswith(a) or b.endswith("/") and a.startswith(b)
    return pa.startswith(pb) or pb.startswith(pa)


def allowed(text):
    return paths(line(text, "Files you may edit"), bare=True)


def main():
    cmd, files = sys.argv[1], sys.argv[2:]
    texts = [open(f).read() for f in files]
    problems = []
    if cmd == "lint":
        t = texts[0]
        if line(t, "Files you may edit") is None:
            problems.append('no "Files you may edit:" line')
        known = allowed(t) + paths(section(t, "Files"))
        # Bare file names (no "/") are skipped: in Steps they are mostly grep arguments, not edits.
        for p in dict.fromkeys(q for q in paths(section(t, "Test first") + section(t, "Steps")) if "/" in q):
            if re.match(r"(.*/)?(build|_site|node_modules)/", p) or matches(p, known):
                continue
            problems.append(f"{p}: named in Test first/Steps, not in the Files section")
    elif cmd == "overlap":
        mine = allowed(texts[0])
        for f, t in zip(files[1:], texts[1:]):
            shared = [p for p in mine if any(overlaps(p, q) for q in allowed(t))]
            if shared:
                problems.append(f"{f.rsplit('/', 1)[-1]}: {', '.join(shared)}")
    elif cmd == "scope":
        ok = allowed(texts[0])
        problems = [p for p in sys.stdin.read().split() if not any(re.fullmatch(to_regex(a), p) for a in ok)]
    print("\n".join(problems)) if problems else None
    sys.exit(1 if problems else 0)


main()
