"""Code metrics: size of the Kotlin classes wired by Hilt, next to the 400-line limit (line_limit.py).

Usage: python3 -I code_metrics.py [repository root, default: current directory]
       python3 -I code_metrics.py --self-test   (checks the Kotlin scanner and the annotation escaping)

Scans Kotlin under android/<module>/src/main (tests are not checked) and measures:
* Constructor dependencies: parameters of the primary constructor of every class annotated with @HiltViewModel or
  with an `@Inject constructor`. From DEPS_WARN on a warning annotation (green), above DEPS_CEILING an error (red).
* Non-public properties of ViewModels (@HiltViewModel or a subclass of ViewModel/AndroidViewModel): `private` and
  `internal` val/var declared directly in the class body (not in the constructor, not in nested classes, objects or
  functions); `internal` counts because it is the module-wide seam for the screen's extension files and tests, not
  public state. Above PROPS_CEILING an error (red).
The ceilings are today's maximum: a class must not grow beyond it; when a class shrinks, lower the ceiling. Comments
and string literals are ignored. File names and texts in annotations are escaped like annotate.py.
"""
import os
import re
import sys

DEPS_WARN = 15
DEPS_CEILING = 15  # lowered from 20 in build 157 (largest today: 14, BackupManager)
PROPS_CEILING = 35

TITLE = "Code metrics"
VISIBILITY_COUNTED = ("private", "internal")


def escape_data(text):
    """Message part of a workflow command - same as annotate.py (run with -I, so no import)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    """Property value (file=, title=) - same as annotate.py."""
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def annotation(level, message, file=None, line=None):
    """One ::error / ::warning line with escaped file and message."""
    props = ""
    if file:
        props += f"file={escape_property(file)},"
        if line:
            props += f"line={line},"
    props += "title=" + escape_property(TITLE)
    return f"::{level} {props}::{escape_data(message)}"


def _skip_string(source, i):
    """Index after the Kotlin string literal starting at i ("…" or \"\"\"…\"\"\", templates ${…} included)."""
    n = len(source)
    raw = source.startswith('"""', i)
    i += 3 if raw else 1
    while i < n:
        if raw and source.startswith('"""', i):
            i += 3
            while i < n and source[i] == '"':  # """…"""" ends with the last quote
                i += 1
            return i
        c = source[i]
        if not raw and c == "\\":
            i += 2
            continue
        if not raw and c == '"':
            return i + 1
        if not raw and c == "\n":
            return i
        if source.startswith("${", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if source[i] == '"':
                    i = _skip_string(source, i)
                    continue
                depth += {"{": 1, "}": -1}.get(source[i], 0)
                i += 1
            continue
        i += 1
    return i


def code_only(source):
    """Kotlin source with comments, strings and char literals replaced by spaces (line breaks kept)."""
    out = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if source.startswith("//", i):
            end = source.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif source.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth > 0:
                if source.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif source.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(re.sub(r"[^\n]", " ", source[i:j]))
            i = j
        elif c == '"':
            j = _skip_string(source, i)
            out.append(re.sub(r"[^\n]", " ", source[i:j]))
            i = j
        elif c == "'":
            m = re.match(r"'(\\.[^']*|[^'\\])'", source[i:])
            j = i + (len(m.group(0)) if m else 1)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


OPEN, CLOSE = "([{", ")]}"


def matching(code, start):
    """Index of the bracket closing the one at `start` (code without comments/strings)."""
    depth = 0
    for k in range(start, len(code)):
        if code[k] in OPEN:
            depth += 1
        elif code[k] in CLOSE:
            depth -= 1
            if depth == 0:
                return k
    return len(code) - 1


def top_level_parameters(text):
    """Parameters of a parameter list (text between the parentheses); trailing comma allowed."""
    parts, depth, angle, current = [], 0, 0, []
    for k, c in enumerate(text):
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
        elif c == "<" and depth == 0:
            angle += 1
        elif c == ">" and depth == 0 and angle > 0 and text[k - 1] != "-":
            angle -= 1
        if c == "," and depth == 0 and angle == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
    parts.append("".join(current))
    return [p for p in parts if p.strip()]


def flatten_body(body):
    """Class body with the contents of every nested bracket blanked: only the class-level declarations stay."""
    out, depth = [], 0
    for c in body:
        if c in OPEN:
            depth += 1
            out.append(c if depth == 1 else " ")
        elif c in CLOSE:
            out.append(c if depth == 1 else " ")
            depth -= 1
        else:
            out.append(c if depth == 0 or c == "\n" else " ")
    return "".join(out)


CLASS_RE = re.compile(r"\bclass\s+(\w+)")
PROPERTY_RE = re.compile(
    r"(?:^|[\n;])[ \t]*((?:@\w+[ \t]*(?:\(\s*\))?[ \t]+)*)"
    r"((?:(?:private|internal|protected|public|override|lateinit|const|open|final|abstract)[ \t]+)*)"
    r"(?:val|var)[ \t]+\w", re.M)


PREFIX_RE = re.compile(r"(?:(?:@[\w.]+(?:\([^()]*\))?|private|internal|public|protected|data|open|abstract|sealed"
                       r"|inner|final|enum|annotation|value)\s+)*$")


def supertype_text(code, k):
    """Text after the constructor up to a body brace: the rest of the line plus continuation lines (a line ending
    with `,` or `:`, or the next line starting with one)."""
    lines = code[k:].split("\n")
    text = lines[0]
    index = 1
    while index < len(lines) and "{" not in text and "}" not in text:
        nxt = lines[index].strip()
        if text.rstrip().endswith((",", ":")) or nxt.startswith((",", ":")):
            text += "\n" + lines[index]
            index += 1
        else:
            break
    brace = text.find("{")
    return text if brace < 0 else text[:brace]


def classes(code):
    """(name, line, annotations text, constructor parameters or None, body text, superclass text) per class."""
    for m in CLASS_RE.finditer(code):
        name = m.group(1)
        line = code.count("\n", 0, m.start()) + 1
        # Annotations and modifiers right before `class`
        prefix_match = PREFIX_RE.search(code[max(0, m.start() - 600):m.start()])
        prefix = prefix_match.group(0) if prefix_match else ""
        k = m.end()
        n = len(code)
        # Type parameters
        while k < n and code[k] in " \t\n":
            k += 1
        if k < n and code[k] == "<":
            depth = 0
            while k < n:
                depth += {"<": 1, ">": -1}.get(code[k], 0)
                k += 1
                if depth == 0:
                    break
        ctor_head_match = re.match(r"\s*((?:@\w+(?:\([^)]*\))?\s+)*)(?:(?:private|internal|protected|public)\s+)?"
                                   r"(constructor)?\s*\(", code[k:])
        params, ctor_annotations = None, ""
        if ctor_head_match:
            open_at = k + ctor_head_match.end() - 1
            close_at = matching(code, open_at)
            params = top_level_parameters(code[open_at + 1:close_at])
            ctor_annotations = ctor_head_match.group(1)
            k = close_at + 1
        supertypes = supertype_text(code, k)
        body = ""
        after = k + len(supertypes)
        m_body = re.match(r"\s*\{", code[after:])
        if m_body:
            open_at = after + m_body.end() - 1
            body = code[open_at + 1:matching(code, open_at)]
        yield name, line, prefix + " " + ctor_annotations, params, body, supertypes


def is_injected(annotations):
    return "@HiltViewModel" in annotations or re.search(r"@Inject\b", annotations) is not None


def is_view_model(annotations, supertypes):
    return "@HiltViewModel" in annotations or re.search(r":\s*(?:\w+\.)*(Android)?ViewModel\s*\(", supertypes)


def non_public_properties(body):
    flat = flatten_body(body)
    count = 0
    for m in PROPERTY_RE.finditer(flat):
        modifiers = m.group(2).split()
        if any(v in modifiers for v in VISIBILITY_COUNTED):
            count += 1
    return count


def measure(source):
    """[(class, line, dependencies or None, non-public properties or None)] of one Kotlin file."""
    code = code_only(source)
    result = []
    for name, line, annotations, params, body, supertypes in classes(code):
        deps = len(params) if is_injected(annotations) and params is not None else None
        props = non_public_properties(body) if is_view_model(annotations, supertypes) else None
        if deps is not None or props is not None:
            result.append((name, line, deps, props))
    return result


def kotlin_files(root):
    android = os.path.join(root, "android")
    if not os.path.isdir(android):
        return
    for module in sorted(os.listdir(android)):
        main = os.path.join(android, module, "src", "main")
        for dirpath, dirnames, files in os.walk(main):
            dirnames.sort()
            for name in sorted(files):
                if name.endswith(".kt"):
                    yield os.path.join(dirpath, name)


def evaluate(rows, deps_warn=DEPS_WARN, deps_ceiling=DEPS_CEILING, props_ceiling=PROPS_CEILING):
    """Annotation lines and exit code for rows (file, class, line, deps, props)."""
    lines, failed = [], False
    for path, name, line, deps, props in rows:
        if deps is not None and deps > deps_ceiling:
            failed = True
            lines.append(annotation("error", f"{name}: {deps} constructor dependencies, at most {deps_ceiling} - "
                                    "split the class (use case, helper) instead of injecting more", path, line))
        elif deps is not None and deps >= deps_warn:
            lines.append(annotation("warning", f"{name}: {deps} constructor dependencies (warning from {deps_warn}, "
                                    f"ceiling {deps_ceiling})", path, line))
        if props is not None and props > props_ceiling:
            failed = True
            lines.append(annotation("error", f"{name}: {props} private/internal properties, at most "
                                    f"{props_ceiling} - move state into a helper or state holder", path, line))
    deps_rows = [r for r in rows if r[3] is not None]
    props_rows = [r for r in rows if r[4] is not None]
    if deps_rows:
        top = max(deps_rows, key=lambda r: r[3])
        lines.append(f"Constructor dependencies: {len(deps_rows)} injected classes, max {top[3]} ({top[1]}), "
                     f"warning from {deps_warn}, ceiling {deps_ceiling}")
        if top[3] < deps_ceiling and not failed:
            lines.append(annotation("notice", f"max constructor dependencies {top[3]} < ceiling {deps_ceiling} - "
                                    "lower DEPS_CEILING in .github/scripts/code_metrics.py"))
    if props_rows:
        top = max(props_rows, key=lambda r: r[4])
        lines.append(f"ViewModel private/internal properties: {len(props_rows)} ViewModels, max {top[4]} ({top[1]}), "
                     f"ceiling {props_ceiling}")
        if top[4] < props_ceiling and not failed:
            lines.append(annotation("notice", f"max ViewModel properties {top[4]} < ceiling {props_ceiling} - "
                                    "lower PROPS_CEILING in .github/scripts/code_metrics.py"))
    return lines, (1 if failed else 0)


SAMPLE = '''
package x

/* class Fake @Inject constructor(a: A, b: B) */
@HiltViewModel
class SampleViewModel @Inject constructor(
    private val repo: Repo,
    /** comment, with comma */
    internal val map: Map<String, List<Int>>,
    callback: (Int, String) -> Unit,
    @param:Named("x, y") val named: String = "a, b",
) : ViewModel() {
    private val a = MutableStateFlow(0)
    internal var b: Int = 0
        private set
    val c = 1
    private lateinit var d: String
    internal val e by lazy { val inner = 1; inner }
    private val f = combine(a, a) { x, y ->
        val notCounted = x + y
        notCounted
    }
    private fun g() { val local = 1 }
    private class Nested(private val n: Int) { private val m = 1 }
    companion object { private const val K = 1 }
    @Volatile private var h = 0
    protected val notCounted = 0
}

class Repo @Inject constructor(private val dao: Dao, @ApplicationContext context: Context)

class Plain(a: Int, b: Int)

class OtherViewModel(private val s: S) : AndroidViewModel(app) {
    private val one = 1; private val two = "}"
}

class Generic<T : Any> @Inject constructor(t: T)
'''


def self_test():
    rows = measure(SAMPLE)
    by_name = {name: (deps, props) for name, _, deps, props in rows}
    assert by_name.get("SampleViewModel") == (4, 6), by_name
    assert by_name.get("Repo") == (2, None), by_name
    assert "Plain" not in by_name and "Fake" not in by_name and "Nested" not in by_name, by_name
    assert by_name.get("OtherViewModel") == (None, 2), by_name
    assert by_name.get("Generic") == (1, None), by_name
    assert top_level_parameters("a: (A, B) -> C, b: Map<K, V>, ") == ["a: (A, B) -> C", " b: Map<K, V>"]

    line = annotation("error", "a\nb%c", file="x,y:z\r\n::error ::boom.kt", line=3)
    expected = "::error file=x%2Cy%3Az%0D%0A%3A%3Aerror %3A%3Aboom.kt,line=3,title=Code metrics::a%0Ab%25c"
    assert line == expected, line
    assert "\n" not in line and "\r" not in line

    rows = [("a.kt", "Big", 1, 21, None), ("b.kt", "Mid", 2, 15, 3), ("c.kt", "Fat", 3, None, 62)]
    lines, code = evaluate(rows, deps_warn=15, deps_ceiling=20, props_ceiling=61)
    assert code == 1, lines
    assert sum(x.startswith("::error ") for x in lines) == 2, lines
    assert any(x.startswith("::warning ") and "Mid" in x for x in lines), lines
    lines, code = evaluate([("b.kt", "Mid", 2, 14, 3)], deps_warn=15, deps_ceiling=20, props_ceiling=61)
    assert code == 0 and not any(x.startswith(("::error ", "::warning ")) for x in lines), lines
    print("code_metrics self-test ok")
    return 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    rows, files = [], 0
    for path in kotlin_files(root):
        files += 1
        with open(path, encoding="utf-8") as f:
            for name, line, deps, props in measure(f.read()):
                rows.append((os.path.relpath(path, root), name, line, deps, props))
    if files == 0:
        print(annotation("error", f"no Kotlin sources found under {os.path.abspath(root)}/android"))
        return 1
    lines, code = evaluate(rows)
    print(f"Code metrics: {files} Kotlin files")
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
