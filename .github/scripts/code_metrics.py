"""Code metrics: size of the Kotlin classes wired by Hilt and length of the Kotlin and Swift functions, next to the
400-line limit (line_limit.py).

Usage: python3 -I code_metrics.py [repository root, default: current directory]
       python3 -I code_metrics.py --self-test   (checks the Kotlin scanner and the annotation escaping)

Scans Kotlin under android/<module>/src/main (tests are not checked) and measures:
* Constructor dependencies: parameters of the primary constructor of every class annotated with @HiltViewModel or
  with an `@Inject constructor`. Above DEPS_CEILING an error (red); evaluate() can add a warning tier (deps_warn),
  the default has none because the ceiling is today's maximum.
* Non-public properties of ViewModels (@HiltViewModel or a subclass of ViewModel/AndroidViewModel): `private` and
  `internal` val/var declared directly in the class body (not in the constructor, not in nested classes, objects or
  functions); `internal` counts because it is the module-wide seam for the screen's extension files and tests, not
  public state. Above PROPS_CEILING an error (red).
Also scans Swift under ios/App, ios/Shared, ios/Widgets and the watch folders (not ios/Tests) and measures:
* Function length in lines, from the line of the keyword to the line that ends the body: Kotlin `fun` with a block
  body or an expression body (`= …`), Swift `func`, `init`, `deinit` and the SwiftUI `var body`. Declarations
  without a body (interface, abstract, protocol) do not count; a nested function counts on its own and inside the
  enclosing one. The TOP_FUNCTIONS longest per platform are listed; above FUNCTION_CEILINGS an error (red).
The ceilings are today's maximum: nothing may grow beyond it; when the maximum shrinks, lower the ceiling. Comments
and string literals are ignored. File names and texts in annotations are escaped like annotate.py.
"""
import os
import re
import sys
import time

DEPS_WARN = None  # no warning tier: the ceiling below is today's maximum
DEPS_CEILING = 13  # AlarmsViewModel, ExplorerViewModel, InfoViewModel, PortfolioSnapshotUpdater, SettingsViewModel
PROPS_CEILING = 26  # ExplorerViewModel

# Longest function per platform (lines). New code stays under FUNCTION_TARGET; the ceiling only keeps the remaining
# long functions from growing and goes down with every split.
FUNCTION_CEILINGS = {"Kotlin": 326, "Swift": 356}  # AppNavHost; PriceRefresher.refresh
FUNCTION_TARGET = 80
TOP_FUNCTIONS = 30

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
# One annotation in front of a property: `@Name`, optionally `()` (blanks allowed before and inside), then at least
# one blank. The blanks after the name are consumed by ONE loop: either `()` and its blanks follow, or the look-behind
# confirms that a blank was consumed. Two adjacent loops over the same characters (`[ \t]*(?:\(\s*\))?[ \t]+`, the
# earlier form) match the same text in many ways and backtrack needlessly (CodeQL «inefficient regular
# expression»); the self-test compares both forms on the Kotlin sources of the repository.
PROPERTY_ANNOTATION = r"@\w+[ \t]*(?:\(\s*\)[ \t]+|(?<=[ \t]))"
PROPERTY_RE = re.compile(
    r"(?:^|[\n;])[ \t]*((?:" + PROPERTY_ANNOTATION + r")*)"
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



# --- Function length -------------------------------------------------------------------------------------------------

IOS_AREAS = ("App", "Shared", "Widgets", "WatchCore", "Watch", "WatchWidgets")


def swift_code_only(source):
    """Swift source with comments and string literals replaced by spaces (line breaks kept): // and nested /* */
    comments, "…" with escapes and interpolations \\(…), multi-line \"\"\"…\"\"\" and raw strings (#"…"#)."""
    out = []
    i, n = 0, len(source)

    def blank(j):
        out.append(re.sub(r"[^\n]", " ", source[i:j]))
        return j

    while i < n:
        c = source[i]
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = blank(n if end < 0 else end)
        elif source.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth > 0:
                if source.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif source.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            i = blank(j)
        elif c == '"' or (c == "#" and re.match(r'#+"', source[i:])):
            i = blank(_skip_swift_string(source, i))
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _skip_swift_string(source, i):
    """Index after the Swift string literal starting at i (optional # marks, "…" or \"\"\"…\"\"\")."""
    n = len(source)
    hashes = 0
    while source[i] == "#":
        hashes, i = hashes + 1, i + 1
    multi = source.startswith('"""', i)
    quote = '"""' if multi else '"'
    i += len(quote)
    close = quote + "#" * hashes
    escape = "\\" + "#" * hashes
    while i < n:
        if source.startswith(close, i):
            return i + len(close)
        if source.startswith(escape, i):
            j = i + len(escape)
            if j < n and source[j] == "(":  # interpolation: skip to the matching parenthesis, strings inside included
                depth, j = 1, j + 1
                while j < n and depth:
                    if source[j] == '"' or (source[j] == "#" and re.match(r'#+"', source[j:])):
                        j = _skip_swift_string(source, j)
                        continue
                    depth += {"(": 1, ")": -1}.get(source[j], 0)
                    j += 1
                i = j
            else:
                i = j + 1
            continue
        if not multi and source[i] == "\n":
            return i
        i += 1
    return i


KOTLIN_FUN_RE = re.compile(r"\bfun\b(?!\s+interface\b)")
SWIFT_FUNC_RE = re.compile(r"(?<![.\w])(?:func\b|init\b[?!]?(?=\s*[(<])|deinit\b|var\s+body\s*:)")
SWIFT_NAME_RE = re.compile(r"func\s+(`[^`]+`|\w+|[^\s\w(]+)|(init)[?!]?|(deinit)|var\s+(body)\s*:")
CONTINUES_AT_END = re.compile(r"(?:[=+\-*/%<>&|^!?:,.({\[]|->|\b(?:else|in|is|as|return|by))\s*$")
CONTINUES_AT_START = re.compile(r"\s*(?:[.?:+\-*/%&|^)\]}]|\b(?:else|as|is|in|catch|finally)\b)")


def _skip_spaces(code, k):
    while k < len(code) and code[k] in " \t\n":
        k += 1
    return k


def _signature_end(code, k):
    """From the position after `fun`/`func`/`init`: (index of the body's `{` or of the `=`, kind) or None for a
    declaration without body (abstract, interface, protocol requirement)."""
    n = len(code)
    depth = angle = 0
    while k < n:
        c = code[k]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
            if depth < 0:
                return None
        elif depth == 0:
            if c == "<":
                angle += 1
            elif c == ">" and angle > 0 and code[k - 1] != "-":
                angle -= 1
            elif angle == 0:
                if c == "{":
                    return k, "block"
                if c == "=" and code[k + 1:k + 2] != "=" and code[k - 1] not in "=!<>":
                    return k, "expression"
                if c in "};":
                    return None
                if c == "\n":
                    rest = code[k:]
                    nxt = _skip_spaces(code, k)
                    word = re.match(r"(?:where\b|[:{=,]|->|throws\b|rethrows\b|async\b)", code[nxt:])
                    before = code[:k].rstrip(" \t")
                    if not word and not before.endswith((",", ":", "->", ">", "where")) and rest.strip():
                        return None
        k += 1
    return None


def _expression_end(code, k):
    """Index of the last character of the expression body starting after the `=` at k."""
    n = len(code)
    depth = 0
    k += 1
    seen = False
    while k < n:
        c = code[k]
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            if depth == 0:
                return k - 1
            depth -= 1
        elif c == "\n" and depth == 0 and seen:
            line_start = code.rfind("\n", 0, k) + 1
            nxt = code.find("\n", k + 1)
            following = code[k + 1:n if nxt < 0 else nxt]
            if not CONTINUES_AT_END.search(code[line_start:k]) and not CONTINUES_AT_START.match(following):
                return k - 1
        if c not in " \t\n":
            seen = True
        k += 1
    return n - 1


def function_lengths(code, swift=False):
    """[(name, first line, number of lines)] of every function with a body in comment- and string-free code.

    Kotlin: `fun` with a block body or an expression body (`= …`, up to the end of the expression). Swift: `func`,
    `init`, `deinit` and the SwiftUI `var body:`. The length runs from the line of the keyword to the line of the
    closing brace; nested functions count on their own and inside the enclosing one."""
    result = []
    pattern = SWIFT_FUNC_RE if swift else KOTLIN_FUN_RE
    for m in pattern.finditer(code):
        if swift:
            groups = SWIFT_NAME_RE.match(code, m.start())  # the name may be an operator (`func < (…)`)
            name = next((g for g in groups.groups() if g), "?") if groups else "?"
            start = groups.end() if groups else m.end()
        else:
            start = m.end()
            name = None
        found = _signature_end(code, start)
        if found is None:
            continue
        at, kind = found
        if swift and kind == "expression":
            continue
        if not swift:
            named = re.search(r"(`[^`]+`|\w+)\s*\(", code[start:at])  # after type parameters and the receiver
            name = named.group(1) if named else "?"
        end = matching(code, at) if kind == "block" else _expression_end(code, at)
        first = code.count("\n", 0, m.start()) + 1
        last = code.count("\n", 0, end) + 1
        result.append((name, first, last - first + 1))
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


def swift_files(root):
    for area in IOS_AREAS:
        for dirpath, dirnames, files in os.walk(os.path.join(root, "ios", area)):
            dirnames.sort()
            for name in sorted(files):
                if name.endswith(".swift"):
                    yield os.path.join(dirpath, name)


def evaluate_functions(platform, rows, ceiling, top=TOP_FUNCTIONS, target=FUNCTION_TARGET):
    """Report lines and exit code for rows (file, function, line, length) of one platform."""
    lines, failed = [], False
    ordered = sorted(rows, key=lambda r: (-r[3], r[0], r[2]))
    for path, name, line, length in ordered:
        if length <= ceiling:
            break
        failed = True
        lines.append(annotation("error", f"{name}: {length} lines, at most {ceiling} - split the function into named "
                                f"helpers (target: {target} lines)", path, line))
    if not ordered:
        return lines, 0
    longest = ordered[0]
    over = sum(1 for r in ordered if r[3] > target)
    lines.append(f"{platform} function length: {len(ordered)} functions, max {longest[3]} ({longest[1]}), "
                 f"{over} over {target}, ceiling {ceiling}")
    for path, name, line, length in ordered[:top]:
        lines.append(f"  {length:4d}  {path}:{line}  {name}")
    if longest[3] < ceiling and not failed:
        lines.append(annotation("notice", f"longest {platform} function {longest[3]} < ceiling {ceiling} - lower "
                                "FUNCTION_CEILINGS in .github/scripts/code_metrics.py"))
    return lines, (1 if failed else 0)


def evaluate(rows, deps_warn=DEPS_WARN, deps_ceiling=DEPS_CEILING, props_ceiling=PROPS_CEILING):
    """Annotation lines and exit code for rows (file, class, line, deps, props)."""
    lines, failed = [], False
    for path, name, line, deps, props in rows:
        if deps is not None and deps > deps_ceiling:
            failed = True
            lines.append(annotation("error", f"{name}: {deps} constructor dependencies, at most {deps_ceiling} - "
                                    "split the class (use case, helper) instead of injecting more", path, line))
        elif deps is not None and deps_warn is not None and deps >= deps_warn:
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
        warn = f"warning from {deps_warn}, " if deps_warn is not None else ""
        lines.append(f"Constructor dependencies: {len(deps_rows)} injected classes, max {top[3]} ({top[1]}), "
                     f"{warn}ceiling {deps_ceiling}")
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


KOTLIN_FUNCTIONS = '''
interface Port { fun open(a: Int): Flow<List<Int>>
    fun close()
    fun withDefault(a: Int = 1) = a
}
abstract class Base { abstract fun render(): String }
@Composable
fun <T : Any> Screen(
    items: List<T>,
    onClick: (T) -> Unit = {},
) {
    val text = "} not a brace ${items.map { "{" }}"  // } neither
    fun local() { }
    /* } */
}
fun Map<String, Int>.total(): Int = values
    .filter { it > 0 }
    .sum()
fun single() = 1
val after = 2
fun chained(a: Int) =
    if (a > 0) a
    else -a
'''

SWIFT_FUNCTIONS = '''
protocol Port { func open(_ a: Int) -> [Int]
    func close()
    init(a: Int)
}
struct Row: View {
    init(a: Int) { self.a = a; super.init(a: a) }
    var body: some View {
        Text("} \\(items.map { "{" }.count) {")
        // }
    }
    static func < (l: Row, r: Row) -> Bool { l.a < r.a }
    func load<T: Decodable>(
        _ type: T.Type
    ) async throws -> T where T: Sendable {
        let raw = #"{ "a": "}" }"#
        return try decode(raw)
    }
}
'''


def self_test_functions():
    kotlin = {name: (line, length) for name, line, length in function_lengths(code_only(KOTLIN_FUNCTIONS))}
    expected = {"withDefault": (4, 1), "Screen": (8, 8), "local": (13, 1), "total": (16, 3), "single": (19, 1),
                "chained": (21, 3)}
    assert kotlin == expected, kotlin
    swift = function_lengths(swift_code_only(SWIFT_FUNCTIONS), swift=True)
    assert swift == [("init", 7, 1), ("body", 8, 4), ("<", 12, 1), ("load", 13, 6)], swift

    rows = [("a.kt", "big", 3, 120), ("b.kt", "small", 1, 10), ("c.kt", "mid", 9, 90)]
    lines, code = evaluate_functions("Kotlin", rows, ceiling=100, top=2)
    assert code == 1 and sum(x.startswith("::error ") for x in lines) == 1, lines
    assert "max 120 (big), 2 over 80, ceiling 100" in lines[1] and len(lines) == 4, lines
    lines, code = evaluate_functions("Kotlin", rows, ceiling=130)
    assert code == 0 and lines[-1].startswith("::notice "), lines
    lines, code = evaluate_functions("Kotlin", rows, ceiling=120)
    assert code == 0 and not any(x.startswith("::") for x in lines), lines
    assert evaluate_functions("Swift", [], ceiling=1) == ([], 0)


PROPERTY_SAMPLES = (
    "val a = 1", "  var b: Int", "\nprivate val c", ";internal lateinit var d", "@Inject val e", "@Inject() var f",
    "@Inject ( ) private val g", "@A @B()  @C\tprotected override val h", "@A(\n) val i", "@A(x) val j", "@Aval k",
    "@A()val l", "x val m", "privateval n", "private  \t final var o", "@A  \t\n val p", "val", "var  ", "@ val q",
    "fun f() { val r = 1 }; val s", "@A.B val t", "@A @ val u; @B @C() const val v",
)


def property_matches(pattern, text):
    return [(m.start(), m.end(), m.group(1), m.group(2)) for m in pattern.finditer(text)]


def self_test_property_regex():
    """PROPERTY_RE matches exactly what its earlier form matched (samples, flattened class bodies and whole
    files of the repository's Kotlin sources, when the script lies in a checkout) and stays fast on hostile input."""
    # The earlier form, rebuilt here from its annotation part only for this comparison (never used for scanning)
    earlier_annotation = "@\\w+[ \\t]*" + "(?:\\(\\s*\\))?" + "[ \\t]+"
    assert PROPERTY_ANNOTATION in PROPERTY_RE.pattern
    earlier = re.compile(PROPERTY_RE.pattern.replace(PROPERTY_ANNOTATION, earlier_annotation), re.M)
    assert earlier.pattern != PROPERTY_RE.pattern
    texts = list(PROPERTY_SAMPLES) + ["\n".join(PROPERTY_SAMPLES), ";".join(PROPERTY_SAMPLES), SAMPLE]
    assert len(property_matches(PROPERTY_RE, "\n".join(PROPERTY_SAMPLES))) == 13
    here = os.path.dirname(os.path.abspath(__file__))
    files = 0
    for root in (os.path.join(here, "..", ".."), os.path.join(here, "..", "..", "..")):  # published / private layout
        if files or not os.path.isdir(os.path.join(root, "android")):
            continue
        for path in kotlin_files(root):
            files += 1
            with open(path, encoding="utf-8") as f:
                source = f.read()
            code = code_only(source)
            texts += [source, code] + [flatten_body(body) for _, _, _, _, body, _ in classes(code) if body]
    for text in texts:
        assert property_matches(PROPERTY_RE, text) == property_matches(earlier, text), text[:200]
    hostile = (
        "\n@a" + " " * 200000 + "x", ";" + " \t" * 100000 + "x", "@a(" + " \n" * 100000 + "x",
        "\n" + "@a " * 50000 + "x", "\n" + "@a() " * 40000 + "x", "private " * 30000 + "x",
        "\n@a" + " " * 100000 + "(" + " " * 100000 + "x", ";@a " * 50000, "\n" * 100000 + " " * 100000,
    )
    started = time.monotonic()
    for text in hostile:
        assert property_matches(PROPERTY_RE, text) == [], text[:40]
    elapsed = time.monotonic() - started
    assert elapsed < 5, f"PROPERTY_RE needs {elapsed:.1f} s on hostile input"
    return files


def self_test():
    compared = self_test_property_regex()
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
    self_test_functions()
    print(f"code_metrics self-test ok (property pattern compared on {compared} Kotlin files)")
    return 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    rows, files = [], 0
    functions = {"Kotlin": [], "Swift": []}
    for path in kotlin_files(root):
        files += 1
        with open(path, encoding="utf-8") as f:
            source = f.read()
        relative = os.path.relpath(path, root)
        for name, line, deps, props in measure(source):
            rows.append((relative, name, line, deps, props))
        for name, line, length in function_lengths(code_only(source)):
            functions["Kotlin"].append((relative, name, line, length))
    if files == 0:
        print(annotation("error", f"no Kotlin sources found under {os.path.abspath(root)}/android"))
        return 1
    swift = 0
    for path in swift_files(root):  # an Android-only checkout has none: nothing to report
        swift += 1
        with open(path, encoding="utf-8") as f:
            for name, line, length in function_lengths(swift_code_only(f.read()), swift=True):
                functions["Swift"].append((os.path.relpath(path, root), name, line, length))
    lines, code = evaluate(rows)
    print(f"Code metrics: {files} Kotlin files, {swift} Swift files")
    for line in lines:
        print(line)
    for platform in ("Kotlin", "Swift"):
        more, failed = evaluate_functions(platform, functions[platform], FUNCTION_CEILINGS[platform])
        code = code or failed
        for line in more:
            print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
