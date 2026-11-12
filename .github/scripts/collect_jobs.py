"""Result of a collector job («Android build», «iOS build», «Android device tests»): one required check that stands
for several jobs of its workflow.

Usage: python3 -I collect_jobs.py TITLE LABEL=RESULT[:may-skip] ...
       python3 -I collect_jobs.py --self-test

RESULT is `needs.<job>.result` (success, failure, cancelled, skipped; empty when the job has no result). A job is
fine when it succeeded, or when it was skipped and the caller marked it `may-skip` (the job «changes» found nothing
for it to do, or the event does not run it). Everything else is an error with a hint what it means:
* skipped without `may-skip`: a job it needs failed or never started, so it did not run although it should have;
* failure: see its log - a job whose log shows no step never started (GitHub outage): «Re-run failed jobs»;
* cancelled: time limit, a newer push, or it never started (GitHub outage): «Re-run failed jobs»;
* no result: it never started (GitHub outage): «Re-run all jobs».
If the collector itself never starts, the required check stays «Expected» until the run is re-run (nothing a
workflow can catch; DEVELOPMENT.md, «What to do when CI fails without a code reason»).
Exit 1 when any job is not fine. Messages are escaped like annotate.py.
"""
import sys

MAY_SKIP = "may-skip"
HINTS = {
    "skipped": "was skipped although it should have run - a job it needs failed or never started",
    "failure": "failed - see its log; a log without any step means it never started (GitHub outage): "
               "Re-run failed jobs",
    "cancelled": "was cancelled (time limit, newer push) or never started (GitHub outage): Re-run failed jobs",
}
NO_RESULT = "has no result - it never started (GitHub outage?): Re-run all jobs"


def escape_data(text):
    """Message part of a workflow command - same as annotate.py (run with -I, so no import)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    """Property value (title=) - same as annotate.py."""
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def judge(pairs):
    """[(label, result, problem or None)] for arguments LABEL=RESULT[:may-skip]."""
    rows = []
    for pair in pairs:
        label, _, value = pair.partition("=")
        result, _, flag = value.partition(":")
        result = result.strip()
        if result == "success" or (result == "skipped" and flag == MAY_SKIP):
            problem = None
        else:
            problem = HINTS.get(result, NO_RESULT)
        rows.append((label, result or "none", problem))
    return rows


def report(title, pairs):
    """Output lines and exit code."""
    rows = judge(pairs)
    lines = [f"{title}: " + ", ".join(f"{label} {result}" for label, result, _ in rows)]
    failed = [(label, problem) for label, _, problem in rows if problem]
    for label, problem in failed:
        lines.append(f"::error title={escape_property(title)}::{escape_data(label + ' ' + problem)}")
    if not rows:
        lines.append(f"::error title={escape_property(title)}::no jobs given")
        return lines, 1
    return lines, (1 if failed else 0)


def self_test():
    lines, code = report("Android", ["changes=success", "unit tests=success", "lint=skipped:may-skip"])
    assert code == 0 and lines == ["Android: changes success, unit tests success, lint skipped"], lines
    lines, code = report("Android", ["changes=success", "lint=skipped"])
    assert code == 1 and "a job it needs failed or never started" in lines[1], lines
    for result, words in (("failure", "never started"), ("cancelled", "never started"), ("", "no result")):
        lines, code = report("iOS, build", [f"ui={result}:{MAY_SKIP}"])
        assert code == 1 and words in lines[1] and lines[1].startswith("::error title=iOS%2C build::"), lines
    lines, code = report("x", ["a\nb=failure"])
    assert code == 1 and "\n" not in lines[1], lines
    assert report("x", [])[1] == 1
    print("collect_jobs self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if not argv:
        print(__doc__)
        return 2
    lines, code = report(argv[0], argv[1:])
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
