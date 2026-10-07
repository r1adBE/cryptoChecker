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
    its types (warning, exit 0). Exit 1 only on a programming error.
  - Events of a type are replaced only for the years the source returned.
  - Keeps events from 7 days ago up to about 15 months ahead, sorted, unique.
  - Writes the file only if the event list changed.

Python 3.9+ standard library only.
"""
import argparse
import datetime as dt
import html
import json
import os
import re
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

def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.8",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            return resp.read().decode(charset, errors="replace")
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


def run(output, now=None, fetcher=fetch):
    now = now or dt.datetime.now(UTC).replace(microsecond=0)
    previous = load_previous(output)
    fresh = collect(fetcher)
    for name in ("BLS", "FOMC", "BEA"):
        if name not in fresh:
            print(f"warning: source {name} unavailable - keeping previous events")
    events = merge(previous, fresh, now)
    if os.path.exists(output) and events == previous:
        print(f"unchanged: {len(events)} events")
        return False
    if not events:
        print("warning: no events at all - file not written")
        return False
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    tmp = output + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(events, now))
    os.replace(tmp, output)
    counts = {t: sum(1 for e in events if e["type"] == t) for t in TYPES}
    print(f"written: {output} ({len(events)} events: {counts})")
    return True


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--output", default=os.path.join("docs", "macro", "events.json"))
    args = ap.parse_args(argv)
    run(args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
