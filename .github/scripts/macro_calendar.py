#!/usr/bin/env python3
"""Builds docs/macro/events.json: dates of high-impact US economic releases.

Sources (official, public, no key):
  - BLS release schedule (CPI, PPI, Employment Situation = NFP), 08:30 ET
      https://www.bls.gov/schedule/news_release/current_year.asp
      plus the per-release pages (cpi.htm, ppi.htm, empSit.htm), which also list
      the next year's dates as soon as BLS publishes them
  - Federal Reserve FOMC calendar, decision = 2nd meeting day 14:00 ET
      https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm
  - BEA release schedule (Personal Income and Outlays = PCE), 08:30 ET
      https://www.bea.gov/news/schedule

The file is read by the Crypto Checker apps once a day. Schema:
  {"version": 1, "generated": "...Z", "source": "...",
   "events": [{"type": "CPI", "time": "2026-10-14T12:30:00Z"}, ...]}

Behaviour:
  - A source that is unreachable or yields nothing keeps the previous events of
    its types (warning, exit 0). Exit 1 only on a programming error or when the
    health-check issue could not be handled (see below).
  - Events of a type are replaced only for the years the source returned.
  - Keeps events from 7 days ago up to about 15 months ahead, sorted, unique.
  - Writes the file only if the event list changed.
  - Health check: a source that failed on 2 or more consecutive runs (counters in
    docs/macro/status.json next to the output, committed by the workflow), or an event
    type whose newest event is older than 14 days, prints ::error:: and opens or
    updates ONE issue "Macro calendar check failed" (gh CLI, like the exchange API
    check); once everything is healthy again the issue is closed. The job stays green
    for these findings; exit 1 only if the issue itself could not be handled.
    Without GitHub Actions / GH_TOKEN (local run) or with --dry-run: print only.
  - Two steps in the workflow, so the token never shares a step with
    the third-party pages: --skip-health builds events.json and status.json without
    a token and without the issue; --health-only downloads nothing, reads both files
    and handles the issue (needs GH_TOKEN).

Python 3.9+ standard library only.
"""
import argparse
import datetime as dt
import html
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

USER_AGENT = "cryptoChecker-macro-calendar (+https://github.com/r1adBE/cryptoChecker)"
ET = ZoneInfo("America/New_York")
UTC = dt.timezone.utc

BLS_URLS = [
    "https://www.bls.gov/schedule/news_release/current_year.asp",
    "https://www.bls.gov/schedule/news_release/cpi.htm",
    "https://www.bls.gov/schedule/news_release/ppi.htm",
    "https://www.bls.gov/schedule/news_release/empSit.htm",
]
FOMC_URL = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"
BEA_URL = "https://www.bea.gov/news/schedule"

SOURCE_TEXT = ("BLS release schedule (CPI, PPI, Employment Situation); Federal Reserve FOMC calendar "
               "(statement 14:00 ET, 2nd meeting day); BEA release schedule (Personal Income and Outlays)")

TYPES = ("CPI", "PPI", "NFP", "FOMC", "PCE")
SOURCES = ("BLS", "FOMC", "BEA")
SOURCE_TYPES = {"BLS": ("CPI", "PPI", "NFP"), "FOMC": ("FOMC",), "BEA": ("PCE",)}
# Health check: alarm after this many failed runs in a row, or when the newest event of a type is
# older than STALE_AFTER (normally every type has dates months ahead)
FAIL_RUNS_ALERT = 2
STALE_AFTER = dt.timedelta(days=14)
STATUS_FILE = "status.json"
ISSUE_TITLE = "Macro calendar check failed"
KEEP_PAST = dt.timedelta(days=7)
KEEP_FUTURE = dt.timedelta(days=457)  # about 15 months

MONTHS = {m: i + 1 for i, m in enumerate(
    ["january", "february", "march", "april", "may", "june", "july", "august", "september",
     "october", "november", "december"])}
MONTH_RE = (r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|"
            r"Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)")
BLS_RELEASES = {
    "Consumer Price Index": "CPI",
    "Producer Price Index": "PPI",
    "Employment Situation": "NFP",
}
BLS_PAGE_TYPES = {"cpi.htm": "CPI", "ppi.htm": "PPI", "empsit.htm": "NFP"}


class SourceError(Exception):
    """A source could not be fetched or parsed (not a bug)."""


# --------------------------------------------------------------------------- helpers

def month_number(token):
    """'Sept.' / 'Dec' / 'December' -> 9 / 12 / 12; None if unknown."""
    t = token.strip().rstrip(".").lower()
    if len(t) < 3:
        return None
    for name, num in MONTHS.items():
        if name.startswith(t):
            return num
    return None


def html_to_text(page):
    """Strip tags/scripts and collapse whitespace, so parsers don't depend on markup."""
    page = re.sub(r"(?is)<(script|style|noscript)\b.*?</\1\s*>", " ", page)
    page = re.sub(r"(?s)<!--.*?-->", " ", page)
    page = re.sub(r"(?i)<br\s*/?>", " ", page)
    page = re.sub(r"<[^>]+>", " ", page)
    page = html.unescape(page).replace(" ", " ")
    return re.sub(r"\s+", " ", page).strip()


def et_to_utc(year, month, day, hour, minute):
    local = dt.datetime(year, month, day, hour, minute, tzinfo=ET)
    return local.astimezone(UTC)


def parse_clock(text):
    """'08:30 AM' / '8:30 a.m.' -> (8, 30)."""
    m = re.match(r"\s*(\d{1,2}):(\d{2})\s*([AaPp])\.?\s*[Mm]\.?", text)
    if not m:
        return None
    hour, minute = int(m.group(1)), int(m.group(2))
    if hour == 12:
        hour = 0
    if m.group(3).lower() == "p":
        hour += 12
    return hour, minute


def iso(instant):
    return instant.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def event(kind, instant):
    return {"type": kind, "time": iso(instant)}


# --------------------------------------------------------------------------- parsers

def parse_bls_schedule(page):
    """BLS 'current_year.asp' (monthly tables: Date | Time | Release).

    Text rows look like: 'Tuesday, October 14, 2026 08:30 AM Consumer Price Index for September 2026'.
    """
    text = html_to_text(page)
    names = "|".join(re.escape(n) for n in BLS_RELEASES)
    rx = re.compile(
        r"(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\s+"
        r"(" + MONTH_RE + r")\.?\s+(\d{1,2}),\s+(\d{4})\s+"
        r"(\d{1,2}:\d{2}\s*[AaPp]\.?\s*[Mm]\.?)\s+"
        r"(" + names + r")\s+for\s+" + MONTH_RE + r"\.?\s+\d{4}")
    out = []
    for m in rx.finditer(text):
        month = month_number(m.group(1))
        clock = parse_clock(m.group(4))
        if not month or not clock:
            continue
        try:
            instant = et_to_utc(int(m.group(3)), month, int(m.group(2)), *clock)
        except ValueError:
            continue
        out.append(event(BLS_RELEASES[m.group(5)], instant))
    return out


def parse_bls_release_page(page, kind):
    """BLS per-release page (cpi.htm, ...): Reference Month | Release Date | Release Time.

    Text rows look like: 'September 2026 Oct. 14, 2026 08:30 AM'.
    """
    text = html_to_text(page)
    rx = re.compile(
        r"\b" + MONTH_RE + r"\s+\d{4}\s+"
        r"(" + MONTH_RE + r")\.?\s+(\d{1,2}),\s+(\d{4})\s+"
        r"(\d{1,2}:\d{2}\s*[AaPp]\.?\s*[Mm]\.?)")
    out = []
    for m in rx.finditer(text):
        month = month_number(m.group(1))
        clock = parse_clock(m.group(4))
        if not month or not clock:
            continue
        try:
            instant = et_to_utc(int(m.group(3)), month, int(m.group(2)), *clock)
        except ValueError:
            continue
        out.append(event(kind, instant))
    return out


def parse_fomc(page):
    """Federal Reserve FOMC calendar: one panel per year ('2026 FOMC Meetings'), entries
    'January 27-28', 'March 17-18*', 'Apr/May 30-1'. Only two-day scheduled meetings are
    taken (one-day notation votes / unscheduled calls are not announced decisions).
    Decision time: statement at 14:00 ET on the second day."""
    text = html_to_text(page)
    heads = list(re.finditer(r"\b(\d{4}) FOMC Meetings\b", text))
    rx = re.compile(
        r"\b(" + MONTH_RE + r")\.?(?:\s*/\s*(" + MONTH_RE + r")\.?)?\s+"
        r"(\d{1,2})\s*[-–]\s*(\d{1,2})\b(\*?)(?:,\s*(\d{4}))?"
        r"(\s*\((?:notation vote|unscheduled)[^)]*\))?", re.I)
    out = []
    for i, head in enumerate(heads):
        year = int(head.group(1))
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        section = text[head.end():end]
        for m in rx.finditer(section):
            if m.group(7):
                continue
            m1 = month_number(m.group(1))
            m2 = month_number(m.group(2)) if m.group(2) else m1
            if not m1 or not m2:
                continue
            d1, d2 = int(m.group(3)), int(m.group(4))
            y = int(m.group(6)) if m.group(6) else year
            if y != year:
                continue
            # second day: same month (27-28) or next month (Apr/May 30-1)
            if m2 == m1 and d2 != d1 + 1:
                continue
            if m2 != m1 and not (d2 == 1 and m2 == m1 % 12 + 1):
                continue
            y2 = y + 1 if (m2 == 1 and m1 == 12) else y
            try:
                instant = et_to_utc(y2, m2, d2, 14, 0)
            except ValueError:
                continue
            out.append(event("FOMC", instant))
    return out


def parse_bea(page):
    """BEA release schedule: 'October 29 8:30 AM News Personal Income and Outlays, September 2026'.
    The date cell has no year: release year = reference year, +1 if the release month is
    before the reference month (December data released in January)."""
    text = html_to_text(page)
    rx = re.compile(
        r"(" + MONTH_RE + r")\.?\s+(\d{1,2})(?:,\s*(\d{4}))?\s+"
        r"(\d{1,2}:\d{2}\s*[AaPp]\.?\s*[Mm]\.?)\s+(?:(?:News|Data|Article|Release)\s+)?"
        r"Personal Income and Outlays,\s+(" + MONTH_RE + r")\.?\s+(\d{4})")
    out = []
    for m in rx.finditer(text):
        month = month_number(m.group(1))
        ref_month = month_number(m.group(5))
        clock = parse_clock(m.group(4))
        if not month or not ref_month or not clock:
            continue
        year = int(m.group(3)) if m.group(3) else int(m.group(6)) + (1 if month < ref_month else 0)
        try:
            instant = et_to_utc(year, month, int(m.group(2)), *clock)
        except ValueError:
            continue
        out.append(event("PCE", instant))
    return out


# --------------------------------------------------------------------------- network

# Largest page read: the schedule pages are well below 1 MB; more is cut off as an error
MAX_PAGE_BYTES = 5 * 1024 * 1024


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.8",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            data = resp.read(MAX_PAGE_BYTES + 1)
            if len(data) > MAX_PAGE_BYTES:
                raise SourceError(f"{url}: page larger than {MAX_PAGE_BYTES} bytes")
            return data.decode(charset, errors="replace")
    except (urllib.error.URLError, OSError, ValueError) as exc:  # HTTPError is a URLError
        raise SourceError(f"{url}: {exc}") from exc


def collect(fetcher=fetch, warn=print):
    """Returns {source_name: [events]} for every source that yielded events."""
    results = {}

    bls = []
    for url in BLS_URLS:
        try:
            page = fetcher(url)
        except SourceError as exc:
            warn(f"warning: {exc}")
            continue
        page_name = url.rsplit("/", 1)[-1].lower()
        if page_name in BLS_PAGE_TYPES:
            found = parse_bls_release_page(page, BLS_PAGE_TYPES[page_name])
        else:
            found = parse_bls_schedule(page)
        if not found:
            warn(f"warning: no events parsed from {url}")
        bls.extend(found)
    if bls:
        results["BLS"] = bls

    for name, url, parser in (("FOMC", FOMC_URL, parse_fomc), ("BEA", BEA_URL, parse_bea)):
        try:
            found = parser(fetcher(url))
        except SourceError as exc:
            warn(f"warning: {exc}")
            continue
        if found:
            results[name] = found
        else:
            warn(f"warning: no events parsed from {url}")
    return results


# --------------------------------------------------------------------------- merge

def parse_time(value):
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)


def valid_events(events):
    out = []
    for e in events or []:
        if not isinstance(e, dict) or e.get("type") not in TYPES:
            continue
        try:
            parse_time(e.get("time", ""))
        except (TypeError, ValueError):
            continue
        out.append({"type": e["type"], "time": e["time"]})
    return out


def merge(previous, fresh_by_source, now):
    """previous: list of events; fresh_by_source: {source: [events]}.

    Fresh events replace previous events of the same type in the same (ET) year,
    so moved dates disappear; everything else from before is kept."""
    fresh = [e for events in fresh_by_source.values() for e in valid_events(events)]
    covered = {(e["type"], parse_time(e["time"]).astimezone(ET).year) for e in fresh}
    merged = [e for e in valid_events(previous)
              if (e["type"], parse_time(e["time"]).astimezone(ET).year) not in covered]
    merged.extend(fresh)
    lo, hi = now - KEEP_PAST, now + KEEP_FUTURE
    unique = {}
    for e in merged:
        t = parse_time(e["time"])
        if lo <= t <= hi:
            unique[(e["type"], e["time"])] = e
    return sorted(unique.values(), key=lambda e: (e["time"], TYPES.index(e["type"])))


def render(events, now):
    """One event per line: small, readable diffs in the weekly commits."""
    head = {"version": 1, "generated": iso(now), "source": SOURCE_TEXT}
    lines = ["{"] + [f" {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}," for k, v in head.items()]
    rows = ["  " + json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in events]
    lines.append(' "events": [')
    lines.append(",\n".join(rows))
    lines.append(" ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def load_previous(path):
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        return valid_events(doc.get("events")) if isinstance(doc, dict) else []
    except FileNotFoundError:
        return []
    except (OSError, ValueError) as exc:
        print(f"warning: previous file unreadable ({exc}), starting fresh")
        return []


# --------------------------------------------------------------------------- health

def load_failures(path):
    """Consecutive failed runs per source from status.json (missing/unreadable = all 0)."""
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        raw = doc.get("consecutiveFailures") if isinstance(doc, dict) else None
    except (OSError, ValueError):
        raw = None
    raw = raw if isinstance(raw, dict) else {}
    return {name: raw[name] if isinstance(raw.get(name), int) and raw[name] >= 0 else 0 for name in SOURCES}


def save_failures(path, failures):
    """Written only when the counters change, so a healthy week adds no commit."""
    if load_failures(path) == failures and os.path.exists(path):
        return
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"consecutiveFailures": failures}, f, indent=1, sort_keys=True)
        f.write("\n")


def health(failures, events, now):
    """Problems as text lines: sources failing FAIL_RUNS_ALERT+ runs in a row, stale event types."""
    problems = []
    for name in SOURCES:
        if failures.get(name, 0) >= FAIL_RUNS_ALERT:
            problems.append(f"Source {name} ({', '.join(SOURCE_TYPES[name])}) failed on "
                            f"{failures[name]} consecutive runs.")
    for kind in TYPES:
        times = [parse_time(e["time"]) for e in events if e["type"] == kind]
        newest = max(times) if times else None
        if newest is None or now - newest > STALE_AFTER:
            when = iso(newest) if newest else "none"
            problems.append(f"{kind}: newest event is older than {STALE_AFTER.days} days (newest: {when}).")
    return problems


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def open_issue_number():
    out = gh("issue", "list", "--state", "open", "--search", f'"{ISSUE_TITLE}" in:title',
             "--json", "number,title", "--limit", "20")
    for item in json.loads(out or "[]"):
        if item.get("title") == ISSUE_TITLE:
            return item["number"]
    return None


def issue_body(problems, now):
    lines = ["The weekly macro calendar update (`.github/scripts/macro_calendar.py`) found problems:", ""]
    lines += [f"- {p}" for p in problems]
    lines += ["", "Check the source pages (BLS, Federal Reserve, BEA) and the parsers in the script.",
              f"Updated {now.strftime('%Y-%m-%d %H:%M UTC')}.", "",
              "This issue is closed automatically once all sources work and every type has current dates again."]
    return "\n".join(lines)


def manage_issue(problems, now):
    number = open_issue_number()
    if problems:
        body = issue_body(problems, now)
        if number is None:
            gh("issue", "create", "--title", ISSUE_TITLE, "--body", body)
            print("Opened issue.")
        else:
            gh("issue", "edit", str(number), "--body", body)
            gh("issue", "comment", str(number), "--body", "Still failing - list above updated.")
            print(f"Updated issue #{number}.")
    elif number is not None:
        gh("issue", "close", str(number), "--comment", "Macro calendar sources work again.")
        print(f"Closed issue #{number}.")


def report(problems, now, dry_run=False):
    """Annotations always; the issue only in GitHub Actions / with GH_TOKEN. Returns the exit code."""
    for p in problems:
        print(f"::error::{p}")
    if dry_run or (os.environ.get("GITHUB_ACTIONS") != "true" and not os.environ.get("GH_TOKEN")):
        if problems:
            print("(no issue: dry run or not in GitHub Actions)")
        return 0
    try:
        manage_issue(problems, now)
    except (subprocess.CalledProcessError, OSError) as exc:
        detail = getattr(exc, "stderr", "") or str(exc)
        print(f"::error::could not manage the issue: {detail.strip()[:300]}")
        return 1
    return 0


# --------------------------------------------------------------------------- main

def run(output, now=None, fetcher=fetch, status_path=None):
    """Updates the output file; returns (changed, health problems)."""
    now = now or dt.datetime.now(UTC).replace(microsecond=0)
    status_path = status_path or os.path.join(os.path.dirname(os.path.abspath(output)), STATUS_FILE)
    previous = load_previous(output)
    fresh = collect(fetcher)
    failures = load_failures(status_path)
    for name in SOURCES:
        if name not in fresh:
            print(f"warning: source {name} unavailable - keeping previous events")
            failures[name] += 1
        else:
            failures[name] = 0
    save_failures(status_path, failures)
    events = merge(previous, fresh, now)
    problems = health(failures, events, now)
    if os.path.exists(output) and events == previous:
        print(f"unchanged: {len(events)} events")
        return False, problems
    if not events:
        print("warning: no events at all - file not written")
        return False, problems
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    tmp = output + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(events, now))
    os.replace(tmp, output)
    counts = {t: sum(1 for e in events if e["type"] == t) for t in TYPES}
    print(f"written: {output} ({len(events)} events: {counts})")
    return True, problems


def health_only(output, now, status_path=None):
    """Health problems from the files a previous --skip-health run wrote; downloads nothing."""
    status_path = status_path or os.path.join(os.path.dirname(os.path.abspath(output)), STATUS_FILE)
    return health(load_failures(status_path), load_previous(output), now)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--output", default=os.path.join("docs", "macro", "events.json"))
    ap.add_argument("--dry-run", action="store_true", help="no GitHub issue, only print problems")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--skip-health", action="store_true",
                      help="build only: no health report, no issue (step without token)")
    mode.add_argument("--health-only", action="store_true",
                      help="no download: health check from events.json/status.json and the issue")
    args = ap.parse_args(argv)
    now = dt.datetime.now(UTC).replace(microsecond=0)
    if args.health_only:
        return report(health_only(args.output, now), now, dry_run=args.dry_run)
    _, problems = run(args.output, now=now)
    if args.skip_health:
        if problems:
            print(f"{len(problems)} health problem(s); reported by the --health-only step")
        return 0
    return report(problems, now, dry_run=args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
