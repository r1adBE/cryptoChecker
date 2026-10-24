#!/usr/bin/env python3
"""Mutation tests for the Android portfolio and tax logic (workflow «Mutation tests», mutation.yml).

For every target file (pure Kotlin logic under android/app/src/main/java/.../domain/portfolio: FIFO, average cost,
fee expenses, display-currency conversion, the tax engines and the tax export rows) the script creates mutants by
fixed rules, runs the unit-test classes configured for that file with Gradle and reports whether the tests noticed
the change («killed», tests red) or not («survived»). The file is always restored afterwards (try/finally, also on
Ctrl-C / SIGTERM of a cancelled run; the restored bytes are checked).

Rules (only in code: never inside strings, character literals, comments or generics):
  <  <->  <=        >  <->  >=        ==  <->  !=        &&  <->  ||
  +  <->  -         +=  <->  -=       *  <->  /          *=  <->  /=
  true  <->  false
  .subtract(  ->  .add(        .multiply(  <->  .divide(      .min(  <->  .max(      (BigDecimal arithmetic)
  return <expr>  ->  return <constant of the declared type> (Boolean: the opposite literal, BigDecimal: ZERO,
                     nullable: null, Int/Long: 0, List: emptyList()); also single-line expression bodies.
Binary operators are only taken in the code style of the sources (one space on both sides), so `List<Lot>`,
`compareBy<X> {`, `-1`, `->` and `!!` are never mutated.

Selection: per file at most --per-file mutants, chosen deterministically (sorted by a hash of file, line, column and
rule — the same sources always give the same mutants), at most --max-total over all files (round robin, so every
file gets its share) and no new mutant after --time-limit minutes. A mutant that does not compile is reported as
«not compilable» and not counted; a Gradle run that hits --mutant-timeout counts as killed.

Report: findings for annotate.py (one line «level<TAB>file<TAB>line<TAB>message»): a notice per file with the share
of killed mutants, a warning per surviving mutant (file:line, the change), a notice with the total. Errors (exit 1)
only when the script itself fails: target file missing, a test pattern that matches no test, a file that could not
be restored. Surviving mutants never fail the run.

Usage (from the repository root, after a green `./gradlew :app:testDebugUnitTest` in android/):
  python3 -I .github/scripts/mutation_test.py --findings FILE [--per-file 4] [--max-total 50] [--time-limit 60]
          [--mutant-timeout 900] [--only TEXT] [--target PATH=PATTERN[,PATTERN…]] [--online] [--repo DIR]
  python3 -I .github/scripts/mutation_test.py --list [--only TEXT]   (show the selected mutants, run nothing)
  python3 -I .github/scripts/mutation_test.py --self-test             (no Gradle: scanner, rules, restore, report)
"""
import argparse
import contextlib
import hashlib
import os
import re
import signal
import subprocess
import sys
import tempfile
import time

PORTFOLIO = "android/app/src/main/java/com/cryptochecker/app/domain/portfolio/"
PARITY_PORTFOLIO = "*.PortfolioParityTest"
PARITY_TAX = "*.TaxTemplatesParityTest"
PARITY_EXPORT = "*.TaxExportParityTest"

# Target file (relative to the repository root) -> Gradle test filters (--tests) that cover it
TARGETS = [
    (PORTFOLIO + "PortfolioFifo.kt", ["*.PortfolioFifoTest", PARITY_PORTFOLIO, "*.HoldingsMethodParityTest"]),
    (PORTFOLIO + "PortfolioFifoLots.kt", ["*.PortfolioFifoTest", PARITY_PORTFOLIO]),
    (PORTFOLIO + "PortfolioCalculatorAverage.kt", ["*.PortfolioCalculatorTest", PARITY_PORTFOLIO]),
    (PORTFOLIO + "PortfolioFeeExpenses.kt", [PARITY_PORTFOLIO, PARITY_TAX]),
    (PORTFOLIO + "PortfolioCostFx.kt", ["*.PortfolioCostFxParityTest", "*.PortfolioDisplayFxTest"]),
    (PORTFOLIO + "TaxEngineCore.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxEngineUk.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxEngineCa.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxEngineFr.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxEngineAt.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxEngineJp.kt", [PARITY_TAX]),
    (PORTFOLIO + "TaxExportRows.kt", [PARITY_EXPORT, "*.TaxCsvRoundingTest", "*.TaxExportWindowParityTest"]),
]

OPERATOR_SWAPS = {
    "<": "<=", "<=": "<", ">": ">=", ">=": ">", "==": "!=", "!=": "==", "&&": "||", "||": "&&",
    "+": "-", "-": "+", "+=": "-=", "-=": "+=", "*": "/", "/": "*", "*=": "/=", "/=": "*=",
}
# Longest first: a token is read as a whole («->» is never «-» + «>», «===» never «==»)
OPERATORS = sorted(["===", "!==", "->", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "++", "--",
                    "?:", "?.", "::", "..", "!!", "<", ">", "+", "-", "*", "/", "!", "=", "%"], key=len, reverse=True)
# «.add(» is not turned into «.subtract(»: in these files it is mostly a collection or pool call (would not compile)
METHOD_SWAPS = {".subtract(": ".add(", ".multiply(": ".divide(", ".divide(": ".multiply(", ".min(": ".max(",
                ".max(": ".min("}
# Default value of a parameter or property («val interest: Boolean = false,»): not a computation, not mutated
DEFAULT_VALUE = re.compile(
    r"^\s*(?:(?:private|internal|override)\s+)*(?:val|var)?\s*\w+\s*:\s*[\w.<>?]+\s*=\s*(?:true|false)\s*,?\s*$")
LITERALS = {"true": "false", "false": "true"}
FUN_SIGNATURE = re.compile(r"\)\s*:\s*([A-Za-z_][\w.]*(?:<[^{}=]*>)?\??)\s*$")
EXPR_BODY = re.compile(r"^(\s*(?:[a-z]+\s+)*fun\b[^=]*\)\s*:\s*([A-Za-z_][\w.]*(?:<[^{}=]*>)?\??)\s*=\s*)(\S.*)$")
COMPILE_ERROR = re.compile(r"^e: |Compilation error|compile\w*Kotlin\w* FAILED|Execution failed for task '[^']*compile",
                           re.M)
NO_TESTS = re.compile(r"No tests found for given includes")


# ---------------------------------------------------------------- scanner

def code_mask(text):
    """True for every character that is Kotlin code (not in a string, character literal or comment)."""
    mask = [True] * len(text)
    n = len(text)

    def blank(a, b):
        for k in range(a, min(b, n)):
            mask[k] = False

    def skip_template(i):
        # i points behind «${»: code up to the matching «}» (nested strings handled), returns index behind it
        depth = 1
        while i < n:
            c = text[i]
            if c == '"':
                i = skip_string(i)
                continue
            if c == "'":
                i = skip_char(i)
                continue
            if text.startswith("//", i):
                j = text.find("\n", i)
                i = n if j < 0 else j
                continue
            if text.startswith("/*", i):
                i = skip_block_comment(i)
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
        return n

    def skip_string(i):
        raw = text.startswith('"""', i)
        j = i + (3 if raw else 1)
        while j < n:
            if raw and text.startswith('"""', j):
                j += 3
                while j < n and text[j] == '"':   # «""""» ends with the last three quotes
                    j += 1
                return j
            c = text[j]
            if not raw and c == "\\":
                j += 2
                continue
            if not raw and c in '"\n':
                return j + 1
            if text.startswith("${", j):
                j = skip_template(j + 2)
                continue
            j += 1
        return n

    def skip_char(i):
        j = i + 1
        if j < n and text[j] == "\\":
            j += 2
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 1
        else:
            j += 1
        return min(n, j + 1) if j < n and text[j] == "'" else i + 1

    def skip_block_comment(i):
        depth = 0
        j = i
        while j < n:
            if text.startswith("/*", j):
                depth += 1
                j += 2
            elif text.startswith("*/", j):
                depth -= 1
                j += 2
                if depth == 0:
                    return j
            else:
                j += 1
        return n

    i = 0
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif text.startswith("/*", i):
            j = skip_block_comment(i)
            blank(i, j)
            i = j
        elif c == '"':
            j = skip_string(i)
            blank(i, j)
            i = j
        elif c == "'":
            j = skip_char(i)
            blank(i, j)
            i = j
        else:
            i += 1
    return mask


class Mutant:
    def __init__(self, path, offset, line, col, rule, original, replacement, source_line):
        self.path = path
        self.offset = offset
        self.line = line
        self.col = col
        self.rule = rule
        self.original = original
        self.replacement = replacement
        self.source_line = source_line

    def key(self):
        return f"{self.path}:{self.line}:{self.col}:{self.rule}"

    def apply(self, text):
        assert text[self.offset:self.offset + len(self.original)] == self.original, self.key()
        return text[:self.offset] + self.replacement + text[self.offset + len(self.original):]

    def describe(self):
        return f"`{self.original}` -> `{self.replacement}` ({self.rule})"


def constant_for(type_name, expr):
    t = type_name.strip()
    base = t.rstrip("?")
    if base == "Boolean":
        if expr in ("true", "false"):
            return None                       # already covered by the literal rule
        return "false"
    if t.endswith("?"):
        return None if expr == "null" else "null"
    if base in ("BigDecimal", "java.math.BigDecimal"):
        return None if expr == "BigDecimal.ZERO" else "BigDecimal.ZERO"
    if base in ("Int", "Long"):
        return None if expr == "0" else "0"
    if base.startswith("List<"):
        return None if expr == "emptyList()" else "emptyList()"
    return None


def balanced(expr):
    """Brackets closed and the expression complete on this line (no trailing operator or comma)."""
    if expr.rstrip().endswith(("+", "-", "*", "/", "&&", "||", ",", "?:", "=", ".", "(", "{", "->")):
        return False
    depth = 0
    for c in expr:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth < 0:
                return False
    return depth == 0


def continues(lines, index):
    """The statement on lines[index] goes on in the next code line (chained call, operator at the start)."""
    for nxt in lines[index + 1:]:
        s = nxt.strip()
        if not s:
            continue
        return s.startswith((".", "?.", "?:", "&&", "||", "+", "-", "*", "/"))
    return False


def enclosing_type(text, mask, offset):
    """Declared return type of the nearest «fun» before offset (None: unknown or Unit)."""
    for m in reversed(list(re.finditer(r"\bfun\b", text[:offset]))):
        if not mask[m.start()]:
            continue
        depth = 0
        for k in range(m.end(), offset):
            if not mask[k]:
                continue
            c = text[k]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            elif depth == 0 and c in "{=":
                # The return must lie in THIS body (a local fun that ended before it does not count)
                if c == "=" or close_of(text, mask, k) < offset:
                    break
                sig = FUN_SIGNATURE.search(text[m.end():k].rstrip())
                return sig.group(1) if sig else None
    return None


def close_of(text, mask, k):
    """Index of the «}» matching the «{» at k (code only)."""
    depth = 0
    for j in range(k, len(text)):
        if not mask[j]:
            continue
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return j
    return len(text)


def find_mutants(path, text):
    """All mutants of one file in source order."""
    mask = code_mask(text)
    out = []
    lines = text.split("\n")
    offset = 0
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("import ", "package ", "@")):
            offset += len(line) + 1
            continue

        def code(a, b):
            return all(mask[offset + k] for k in range(a, b))

        def add(col, rule, original, replacement):
            out.append(Mutant(path, offset + col, index + 1, col + 1, rule, original, replacement, stripped))

        col = 0
        while col < len(line):
            if not mask[offset + col]:
                col += 1
                continue
            op = next((o for o in OPERATORS if line.startswith(o, col)), None)
            if op is None:
                col += 1
                continue
            end = col + len(op)
            if (op in OPERATOR_SWAPS and col > 0 and line[col - 1] == " " and end < len(line) and line[end] == " "
                    and code(col, end)):
                add(col, "operator", op, OPERATOR_SWAPS[op])
            col = end
        for word, repl in ({} if DEFAULT_VALUE.match(line) else LITERALS).items():
            for m in re.finditer(r"(?<![\w.])" + word + r"\b", line):
                if code(m.start(), m.end()):
                    add(m.start(), "literal", word, repl)
        for meth, repl in METHOD_SWAPS.items():
            for m in re.finditer(re.escape(meth), line):
                if code(m.start(), m.end()):
                    add(m.start(), "bigdecimal", meth, repl)
        ret = re.match(r"^(\s*)return (\S.*?)\s*$", line)
        if ret and code(ret.start(2), ret.end(2)) and balanced(ret.group(2)) and not continues(lines, index):
            t = enclosing_type(text, mask, offset + ret.start(2))
            const = constant_for(t, ret.group(2)) if t else None
            if const:
                add(ret.start(2), "return", ret.group(2), const)
        body = EXPR_BODY.match(line)
        if body and code(body.start(3), body.end(3)) and balanced(body.group(3)) and not continues(lines, index):
            expr = body.group(3).rstrip()
            const = constant_for(body.group(2), expr)
            if const and not expr.endswith("{"):
                add(body.start(3), "return", expr, const)
        offset += len(line) + 1
    out.sort(key=lambda m: (m.offset, m.rule))
    return out


def select(mutants, per_file, seed=""):
    """Deterministic choice of at most per_file mutants (hash order), returned in source order."""
    ranked = sorted(mutants, key=lambda m: hashlib.sha256((seed + m.key()).encode("utf-8")).hexdigest())
    return sorted(ranked[:per_file], key=lambda m: m.offset)


def interleave(groups, max_total):
    """Round robin over the files until max_total mutants."""
    out = []
    queues = [list(g) for g in groups]
    while len(out) < max_total and any(queues):
        for q in queues:
            if q and len(out) < max_total:
                out.append(q.pop(0))
    return out


# ---------------------------------------------------------------- running

class Terminated(Exception):
    pass


def _on_signal(signum, frame):
    raise Terminated(f"signal {signum}")


@contextlib.contextmanager
def mutated(file_path, original_bytes, new_text):
    """Writes the mutant; the original bytes are always written back (and checked)."""
    try:
        with open(file_path, "wb") as f:
            f.write(new_text.encode("utf-8"))
        yield
    finally:
        with open(file_path, "wb") as f:
            f.write(original_bytes)
        with open(file_path, "rb") as f:
            if f.read() != original_bytes:
                raise RuntimeError(f"could not restore {file_path}")


def gradle_runner(cmd, cwd, timeout):
    try:
        p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                           text=True, errors="replace")
        return p.returncode, p.stdout
    except subprocess.TimeoutExpired as e:
        out = e.stdout if isinstance(e.stdout, str) else (e.stdout or b"").decode("utf-8", "replace")
        return None, out


def classify(returncode, output):
    if returncode is None:
        return "killed (timeout)"
    if returncode == 0:
        return "survived"
    if NO_TESTS.search(output or ""):
        return "no tests"
    if COMPILE_ERROR.search(output or ""):
        return "not compilable"
    return "killed"


def gradle_command(android_dir, patterns, online):
    gradlew = os.path.join(android_dir, "gradlew")
    cmd = [gradlew if os.path.isfile(gradlew) else "gradle", ":app:testDebugUnitTest"]
    for p in patterns:
        cmd += ["--tests", p]
    cmd.append("-q")
    if not online:
        cmd.append("--offline")
    return cmd


def run(args, targets, runner=gradle_runner, clock=time.monotonic):
    """Runs the mutants; returns (findings, exit code). findings = list of (level, file, line, message)."""
    findings = []
    errors = 0
    repo = os.path.abspath(args.repo)
    android_dir = os.path.join(repo, "android")
    groups = []
    patterns_of = {}
    texts = {}
    for rel, patterns in targets:
        if args.only and args.only not in rel:
            continue
        full = os.path.join(repo, rel)
        if not os.path.isfile(full):
            findings.append(("error", rel, "", "mutation target missing (update TARGETS in mutation_test.py)"))
            errors += 1
            continue
        with open(full, "rb") as f:
            raw = f.read()
        texts[rel] = raw
        patterns_of[rel] = patterns
        chosen = select(find_mutants(rel, raw.decode("utf-8")), args.per_file, args.seed)
        if not chosen:
            findings.append(("notice", rel, "", "no mutants found in this file"))
        groups.append(chosen)
    plan = interleave(groups, args.max_total)
    stats = {}
    start = clock()
    not_run = 0
    bad_patterns = set()
    for i, m in enumerate(plan):
        if clock() - start > args.time_limit * 60:
            not_run = len(plan) - i
            break
        if m.path in bad_patterns:
            continue
        full = os.path.join(repo, m.path)
        new_text = m.apply(texts[m.path].decode("utf-8"))
        cmd = gradle_command(android_dir, patterns_of[m.path], args.online)
        print(f"[{i + 1}/{len(plan)}] {m.path}:{m.line} {m.describe()}", flush=True)
        try:
            with mutated(full, texts[m.path], new_text):
                code, output = runner(cmd, android_dir, args.mutant_timeout)
        except RuntimeError as e:
            findings.append(("error", m.path, str(m.line), str(e)))
            return findings, 1
        result = classify(code, output)
        print(f"    -> {result}", flush=True)
        s = stats.setdefault(m.path, {"killed": 0, "survived": 0, "not compilable": 0})
        if result == "no tests":
            findings.append(("error", m.path, "", "test filter matches no test: " + " ".join(patterns_of[m.path])))
            errors += 1
            bad_patterns.add(m.path)
            continue
        if result.startswith("killed"):
            s["killed"] += 1
        elif result == "survived":
            s["survived"] += 1
            findings.append(("warning", m.path, str(m.line),
                             f"Mutant survived: {m.describe()} — the tests ({', '.join(patterns_of[m.path])}) "
                             f"did not notice it.\\n{m.source_line[:200]}"))
        else:
            s["not compilable"] += 1
    total_killed = total_counted = 0
    per_file = []
    for rel, s in stats.items():
        counted = s["killed"] + s["survived"]
        total_killed += s["killed"]
        total_counted += counted
        share = f"{100 * s['killed'] // counted} %" if counted else "–"
        extra = f", {s['not compilable']} not compilable (not counted)" if s["not compilable"] else ""
        per_file.append(("notice", rel, "",
                         f"{os.path.basename(rel)}: {s['killed']} of {counted} mutants killed ({share}){extra}"))
    elapsed = int(clock() - start)
    share = f"{100 * total_killed // total_counted} %" if total_counted else "–"
    summary = (f"Mutation score {total_killed}/{total_counted} ({share}) over {len(stats)} file(s) in "
               f"{elapsed // 60} min {elapsed % 60} s")
    if not_run:
        summary += f"; time limit reached, {not_run} mutant(s) not run"
    # The total first: GitHub shows only the first notices of a step as annotations
    findings.append(("notice", "", "", summary))
    findings.extend(per_file)
    return findings, 1 if errors else 0


def write_findings(path, findings):
    with open(path, "a", encoding="utf-8") as f:
        for level, file, line, message in findings:
            clean = message.replace("\t", " ").replace("\r", " ").replace("\n", "\\n")
            f.write(f"{level}\t{file}\t{line}\t{clean}\n")


def parse_targets(values):
    if not values:
        return TARGETS
    out = []
    for v in values:
        path, _, pats = v.partition("=")
        if not path or not pats:
            raise SystemExit(f"--target needs PATH=PATTERN[,PATTERN]: {v}")
        out.append((path, [p for p in pats.split(",") if p]))
    return out


# ---------------------------------------------------------------- self-test

SAMPLE = '''package x.y
import a.b.C // a < b
/* block > comment with a + b
   /* nested - */ still comment * here */
fun isBig(a: Int, b: Int): Boolean {
    val s = "a < b and ${if (a > b) "x + y" else "z"} - end"
    val c = '+'
    val list: List<Map<String, Int>> = emptyList()
    val f = compareBy<Int> { it }.thenBy { -it }
    if (a < b && b >= 0) return true
    val r = """raw > text + "quoted" """
    val g = { v: Int -> v - 1 }
    return a * 2 == b
}

fun next(x: BigDecimal): BigDecimal = x.subtract(BigDecimal.ONE)

data class D(val on: Boolean = false, val n: Int = 1)
class E(
    val off: Boolean = true,
)

fun maybe(x: BigDecimal?): BigDecimal? {
    if (x == null) return null
    return x
        .negate()
}

fun outer(): List<Int> {
    fun inner(x: Int): Int {
        return x
    }
    val out = listOf(inner(1))
    return out
}

fun sum(a: Int): Int {
    return a +
        1
}
'''

SAMPLE_EXPECTED = [
    (10, "<", "<="), (10, "&&", "||"), (10, ">=", ">"), (10, "true", "false"),
    (12, "-", "+"),
    (13, "a * 2 == b", "false"), (13, "*", "/"), (13, "==", "!="),
    (16, "x.subtract(BigDecimal.ONE)", "BigDecimal.ZERO"), (16, ".subtract(", ".add("),
    (18, "false", "true"),
    (24, "==", "!="),
    (31, "x", "0"), (34, "out", "emptyList()"),     # the local fun «inner» ends before «return out»
]


def self_test():
    got = [(m.line, m.original, m.replacement) for m in find_mutants("Sample.kt", SAMPLE)]
    assert got == SAMPLE_EXPECTED, got
    mask = code_mask(SAMPLE)
    assert not mask[SAMPLE.index("x + y")] and not mask[SAMPLE.index("'+'")] and mask[SAMPLE.index("a > b")] is False
    assert enclosing_type(SAMPLE, mask, SAMPLE.index("return a")) == "Boolean"
    assert enclosing_type(SAMPLE, mask, SAMPLE.index("return x")) == "BigDecimal?"

    # Determinism and caps
    many = find_mutants("Sample.kt", SAMPLE)
    a, b = select(many, 3), select(list(reversed(many)), 3)
    assert [m.key() for m in a] == [m.key() for m in b] and len(a) == 3
    assert [m.offset for m in a] == sorted(m.offset for m in a)
    mixed = interleave([[1, 2, 3], [4], [5, 6]], 5)
    assert mixed == [1, 4, 5, 2, 6], mixed

    with tempfile.TemporaryDirectory() as tmp:
        rel = "android/app/src/main/java/Sample.kt"
        full = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(full))
        original = SAMPLE.replace("\n", "\r\n").encode("utf-8")      # CRLF must survive byte for byte
        with open(full, "wb") as f:
            f.write(original)
        # Apply and restore every mutant; exactly one place changes
        for m in find_mutants(rel, original.decode("utf-8")):
            new_text = m.apply(original.decode("utf-8"))
            with mutated(full, original, new_text):
                with open(full, "rb") as f:
                    now = f.read().decode("utf-8")
                assert now != original.decode("utf-8") and now == new_text
            with open(full, "rb") as f:
                assert f.read() == original
        # Restored also when the run is interrupted
        try:
            with mutated(full, original, "broken"):
                raise Terminated("test")
        except Terminated:
            pass
        with open(full, "rb") as f:
            assert f.read() == original

        # Full run with a fake Gradle: classification, file restored, findings format
        calls = []

        def fake(cmd, cwd, timeout):
            with open(full, "rb") as f:
                text = f.read().decode("utf-8")
            calls.append(cmd)
            assert text != original.decode("utf-8"), "runner must see the mutant"
            n = len(calls)
            if n == 1:
                return 0, "BUILD SUCCESSFUL"                               # survived
            if n == 2:
                return 1, "e: file.kt:3:1 Type mismatch"                  # not compilable
            if n == 3:
                return None, ""                                           # timeout = killed
            return 1, "SampleTest > x FAILED"                             # killed

        ns = argparse.Namespace(repo=tmp, only="", per_file=5, max_total=50, time_limit=60, mutant_timeout=10,
                                seed="", online=False)
        findings, code = run(ns, [(rel, ["*.SampleTest"])], runner=fake)
        assert code == 0, findings
        assert calls[0][1] == ":app:testDebugUnitTest" and "--offline" in calls[0] and "*.SampleTest" in calls[0]
        with open(full, "rb") as f:
            assert f.read() == original
        levels = [f[0] for f in findings]
        assert levels.count("warning") == 1 and levels.count("error") == 0, findings
        warn = next(f for f in findings if f[0] == "warning")
        assert warn[1] == rel and warn[2].isdigit()
        per_file = next(f for f in findings if f[0] == "notice" and f[1] == rel)
        assert "3 of 4 mutants killed (75 %), 1 not compilable" in per_file[3], per_file
        out = os.path.join(tmp, "findings.txt")
        write_findings(out, findings)
        with open(out, encoding="utf-8") as f:
            rows = [r.rstrip("\n").split("\t") for r in f]
        assert all(len(r) == 4 and r[0] in ("error", "warning", "notice") for r in rows), rows
        assert all("\n" not in r[3] for r in rows)

        # Time limit: nothing runs after it, reported as a notice
        ticks = iter([0, 10_000, 10_000, 10_000])
        findings, code = run(ns, [(rel, ["*.SampleTest"])], runner=fake, clock=lambda: next(ticks))
        assert code == 0 and any("time limit reached, 5 mutant(s) not run" in f[3] for f in findings), findings

        # Pattern without tests and missing target are errors (exit 1); the file is still restored
        findings, code = run(ns, [(rel, ["*.Nope"]), ("android/missing.kt", ["*.X"])],
                             runner=lambda c, d, t: (1, "No tests found for given includes: [*.Nope]"))
        assert code == 1 and sum(1 for f in findings if f[0] == "error") == 2, findings
        with open(full, "rb") as f:
            assert f.read() == original

    # The configured targets exist next to this script (repository checkout), when it is one
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    if os.path.isdir(os.path.join(repo, "android", "app")):
        missing = [rel for rel, _ in TARGETS if not os.path.isfile(os.path.join(repo, rel))]
        assert not missing, missing
    print("mutation_test self-test ok")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Mutation tests for the Android portfolio and tax logic")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list", action="store_true", help="show the selected mutants, run nothing")
    ap.add_argument("--findings", help="findings file for annotate.py (appended)")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--per-file", type=int, default=4)
    ap.add_argument("--max-total", type=int, default=50)
    ap.add_argument("--time-limit", type=float, default=60, help="minutes; no new mutant after this")
    ap.add_argument("--mutant-timeout", type=int, default=900, help="seconds per Gradle run")
    ap.add_argument("--only", default="", help="only target files whose path contains this text")
    ap.add_argument("--seed", default="", help="changes the deterministic choice of mutants")
    ap.add_argument("--target", action="append", help="PATH=PATTERN[,PATTERN] (replaces the built-in list)")
    ap.add_argument("--online", action="store_true", help="run Gradle without --offline")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    targets = parse_targets(args.target)
    if args.list:
        groups = []
        for rel, _ in targets:
            full = os.path.join(args.repo, rel)
            if (args.only and args.only not in rel) or not os.path.isfile(full):
                continue
            with open(full, "rb") as f:
                all_mutants = find_mutants(rel, f.read().decode("utf-8"))
            print(f"{rel}: {len(all_mutants)} candidate(s)")
            groups.append(select(all_mutants, args.per_file, args.seed))
        for m in interleave(groups, args.max_total):
            print(f"  {m.path}:{m.line}:{m.col} {m.describe()}  | {m.source_line[:100]}")
        return 0
    if not args.findings:
        ap.error("--findings is required")
    for sig in (signal.SIGTERM, signal.SIGINT, getattr(signal, "SIGHUP", None)):
        if sig is not None:
            signal.signal(sig, _on_signal)
    try:
        findings, code = run(args, targets)
    except Terminated as e:
        findings, code = [("error", "", "", f"mutation run cancelled ({e}); all files restored")], 1
    write_findings(args.findings, findings)
    return code


if __name__ == "__main__":
    sys.exit(main())
