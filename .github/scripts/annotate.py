#!/usr/bin/env python3
"""Turns a findings file into GitHub annotations and sets the exit code.

The release checks (android_release_audit.sh, android_smoke_test.sh, ios_release_audit.py,
release_readiness.py) do not print workflow commands themselves; they append one finding per
line to a file:

    <level>\t<file or empty>\t<line or empty>\t<message>

level is error, warning or notice; a literal "\\n" in the message stands for a line break.
GitHub shows only about 10 annotations per level and step, so this script prints at most
MAX_PER_LEVEL per level and folds the rest into one last annotation. The full list goes to the
log (and the job summary) in plain text.

Exit code: 1 if there is at least one error, else 0 (missing or empty file = no findings).
Usage: python3 -I annotate.py <findings file> [title]
"""
import os
import sys

MAX_PER_LEVEL = 9
LEVELS = ("error", "warning", "notice")


def escape_data(text):
    # Workflow command data: %, CR and LF must be escaped, otherwise the message is cut off
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def read_findings(path):
    findings = []
    if not os.path.isfile(path):
        return findings
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            raw = raw.rstrip("\n")
            if not raw.strip():
                continue
            parts = raw.split("\t", 3)
            if len(parts) != 4:
                parts = ["warning", "", "", raw]
            level, file, line, message = parts
            level = level.strip().lower()
            if level not in LEVELS:
                level = "warning"
            findings.append((level, file.strip(), line.strip(), message.replace("\\n", "\n").strip()))
    return findings


def command(level, file, line, message, title):
    props = []
    if file:
        props.append("file=" + escape_property(file))
        if line.isdigit():
            props.append("line=" + line)
    if title:
        props.append("title=" + escape_property(title))
    head = "::" + level + (" " + ",".join(props) if props else "") + "::"
    # Keep a single annotation readable (GitHub truncates very long ones anyway)
    if len(message) > 3500:
        message = message[:3500] + " …"
    return head + escape_data(message)


def main():
    if len(sys.argv) < 2:
        print("usage: annotate.py <findings file> [title]")
        return 2
    findings = read_findings(sys.argv[1])
    title = sys.argv[2] if len(sys.argv) > 2 else ""
    counts = {lvl: 0 for lvl in LEVELS}
    for level in LEVELS:
        items = [f for f in findings if f[0] == level]
        counts[level] = len(items)
        if not items:
            continue
        print(f"=== {level}: {len(items)} ===")
        for _, file, line, message in items:
            where = f"{file}:{line}: " if file and line else (f"{file}: " if file else "")
            print("  " + where + message.replace("\n", "\n    "))
        shown = items[:MAX_PER_LEVEL - 1] if len(items) > MAX_PER_LEVEL else items
        for _, file, line, message in shown:
            print(command(level, file, line, message, title))
        rest = items[len(shown):]
        if rest:
            text = f"{len(rest)} more {level}(s):\n" + "\n".join(
                "- " + (f"{f}:{l} " if f and l else (f"{f} " if f else "")) + m.replace("\n", " | ")
                for _, f, l, m in rest)
            print(command(level, "", "", text, title))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and findings:
        try:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(f"### {title or 'Findings'}\n\n")
                for level, file, line, message in findings:
                    where = f"`{file}:{line}` " if file and line else (f"`{file}` " if file else "")
                    f.write(f"- **{level}** {where}{message.splitlines()[0] if message else ''}\n")
                f.write("\n")
        except OSError:
            pass
    print(f"findings: {counts['error']} error(s), {counts['warning']} warning(s), {counts['notice']} notice(s)")
    return 1 if counts["error"] else 0


if __name__ == "__main__":
    sys.exit(main())
