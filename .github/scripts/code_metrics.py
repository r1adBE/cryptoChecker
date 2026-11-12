"""Code metrics: size of the Kotlin classes wired by Hilt, size of the Swift types across their extension files and
length of the Kotlin and Swift functions, next to the 400-line limit (line_limit.py).

Usage: python3 -I code_metrics.py [repository root, default: current directory]
       python3 -I code_metrics.py --self-test   (checks the Kotlin scanner and the annotation escaping)

Scans Kotlin under android/<module>/src/main (tests are not checked) and measures:
* Constructor dependencies: parameters of the primary constructor of every class annotated with @HiltViewModel or
  with an `@Inject constructor`, and of every class a `@Provides` function in a `di/` folder returns (classes of the
  modules without Hilt annotations, wired by hand in the Hilt module). The `@Provides` function itself counts with
  its own parameters, listed as `Module.function()` - building a large object inside one provider does not hide it.
  Above DEPS_CEILING an error (red); evaluate() can add a warning tier (deps_warn), the default has none because the
  ceiling is today's maximum.
* Non-public properties of ViewModels (@HiltViewModel or a subclass of ViewModel/AndroidViewModel): `private` and
  `internal` val/var declared directly in the class body (not in the constructor, not in nested classes, objects or
  functions); `internal` counts because it is the module-wide seam for the screen's extension files and tests, not
  public state. Above PROPS_CEILING an error (red).
Also scans Swift under ios/App, ios/Shared, ios/Widgets and the watch folders (not ios/Tests) and measures:
* Function length in lines, from the line of the keyword to the line that ends the body: Kotlin `fun` with a block
  body or an expression body (`= …`), Swift `func`, `init`, `deinit` and the SwiftUI `var body`. Declarations
  without a body (interface, abstract, protocol) do not count; a nested function counts on its own and inside the
  enclosing one. The TOP_FUNCTIONS longest per platform are listed; above FUNCTION_CEILINGS an error (red).
* Length of the logic functions, with their own lower ceiling (LOGIC_CEILINGS): every function that is not user
  interface. User interface is a Kotlin function annotated @Composable or a file in a `ui`, `widget` or `overlay`
  folder; in Swift the SwiftUI `body`, a function returning `some View`/`some Scene`/`ToolbarContent` or marked
  @ViewBuilder, and every file under ios/App/Features, ios/Widgets, ios/Watch and ios/WatchWidgets. Long composables
  and SwiftUI bodies may stay; calculations, refresh and alarm paths are held to the lower ceiling.
* Swift type size: lines and stored properties of every class, struct, actor and enum declared in these folders,
  summed over the declaration and all its `extension` blocks in all files (the 400-line limit per file does not see
  a type that grows across extension files). Stored properties: `let`/`var` declared directly in the type (not
  static, not computed; property wrappers and observers count). A nested type counts on its own and inside the
  enclosing one; extensions of types declared elsewhere (String, View) are not listed. The TOP_TYPES largest are
  listed; above SWIFT_TYPE_CEILINGS an error (red) - the ceiling for stored properties holds for classes and actors
  (objects with state of their own), structs and enums (values, SwiftUI views) are only listed.
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
FUNCTION_CEILINGS = {"Kotlin": 326, "Swift": 247}  # AppNavHost; WatchlistList.list (both user interface)
# Longest logic function (not user interface, see the module text): TaxExportCsv.gainsCsv; PortfolioWidgetStore.compute
LOGIC_CEILINGS = {"Kotlin": 166, "Swift": 240}
FUNCTION_TARGET = 80
TOP_FUNCTIONS = 30
TOP_LOGIC_FUNCTIONS = 12
KOTLIN_UI_FOLDERS = ("ui", "widget", "overlay")
SWIFT_UI_FOLDERS = ("ios/App/Features/", "ios/Widgets/", "ios/Watch/", "ios/WatchWidgets/")

# Largest Swift type across all its extension files: lines (AppData, 23 files); stored properties of a class or actor
# (ExplorerViewModel)
SWIFT_TYPE_CEILINGS = {"lines": 3570, "stored properties": 54}
TOP_TYPES = 15

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


PROVIDES_RE = re.compile(r"@Provides\b")
MODULE_RE = re.compile(r"\b(?:object|class|interface)\s+(\w+)")


def providers(source):
    """[(module, function, line, parameter count, returned type or None)] of the `@Provides` functions of one Kotlin
    file (a Hilt module): the simple name of the declared return type, without type arguments and nullability."""
    code = code_only(source)
    result = []
    for m in PROVIDES_RE.finditer(code):
        head = re.compile(r"\bfun\s+(\w+)\s*\(").search(code, m.end())
        if not head or PROVIDES_RE.search(code, m.end(), head.start()):
            continue
        close_at = matching(code, head.end() - 1)
        params = top_level_parameters(code[head.end():close_at])
        returned = re.match(r"\s*:\s*(?:\w+\.)*(\w+)", code[close_at + 1:])
        modules = [x.group(1) for x in MODULE_RE.finditer(code, 0, m.start())]
        result.append((modules[-1] if modules else "?", head.group(1), code.count("\n", 0, head.start()) + 1,
                       len(params), returned.group(1) if returned else None))
    return result


def provider_files(root):
    """Kotlin files in a `di` folder (the Hilt modules)."""
    for path in kotlin_files(root):
        if os.path.basename(os.path.dirname(path)) == "di":
            yield path


def measure(source, provided=frozenset()):
    """[(class, line, dependencies or None, non-public properties or None)] of one Kotlin file. `provided`: names of
    the classes a `@Provides` function returns - their constructor counts like an injected one."""
    code = code_only(source)
    result = []
    for name, line, annotations, params, body, supertypes in classes(code):
        injected = is_injected(annotations) or name in provided
        deps = len(params) if injected and params is not None else None
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


KOTLIN_ANNOTATED_RE = re.compile(r"((?:@[\w.]+(?:\([^()]*\))?\s+|\b(?:private|internal|public|protected|override|open|"
                                 r"inline|suspend|operator|infix|tailrec|final|abstract)\s+)*)$")
SWIFT_ATTRIBUTED_RE = re.compile(r"((?:@\w+(?:\([^()]*\))?\s+|\b(?:private|fileprivate|internal|public|static|final|"
                                 r"override|nonisolated|mutating|class)\s+)*)$")
SWIFT_VIEW_RE = re.compile(r"->\s*some\s+(?:View|Scene|ToolbarContent|Commands)\b|:\s*some\s+(?:View|Scene)\b")


def is_ui_function(code, keyword_at, body_at, swift=False):
    """True for user-interface code by its declaration: Kotlin `@Composable fun`; Swift `var body`, a result of
    `some View`/`some Scene`/`some ToolbarContent`, or `@ViewBuilder` (attributes and modifiers before the keyword)."""
    before = code[max(0, keyword_at - 400):keyword_at]
    if not swift:
        return "@Composable" in KOTLIN_ANNOTATED_RE.search(before).group(1)
    signature = code[keyword_at:body_at]
    return (re.match(r"var\s+body\s*:", signature) is not None or SWIFT_VIEW_RE.search(signature) is not None
            or "@ViewBuilder" in SWIFT_ATTRIBUTED_RE.search(before).group(1))


def is_ui_path(relative, swift=False):
    """True for a file of the user interface by its place (forward slashes): Kotlin folders `ui`, `widget`, `overlay`;
    Swift the screens, widgets and watch targets."""
    path = relative.replace(os.sep, "/")
    if swift:
        return any(folder in "/" + path for folder in ("/" + f for f in SWIFT_UI_FOLDERS))
    return any(part in KOTLIN_UI_FOLDERS for part in path.split("/")[:-1])


def function_lengths(code, swift=False, with_ui=False):
    """[(name, first line, number of lines)] of every function with a body in comment- and string-free code; with
    `with_ui` a fourth entry: user interface by declaration ([is_ui_function]).

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
        row = (name, first, last - first + 1)
        result.append(row + (is_ui_function(code, m.start(), at, swift),) if with_ui else row)
    return result


# --- Swift type size -------------------------------------------------------------------------------------------------

SWIFT_TYPE_RE = re.compile(r"(?<![.\w])(class|struct|actor|enum|extension)[ \t]+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")
SWIFT_NOT_A_TYPE = ("func", "var", "let", "subscript", "init")
SWIFT_STATEFUL = ("class", "actor")
SWIFT_PROPERTY_RE = re.compile(r"\b(?:let|var)\b")


def swift_stored_properties(body):
    """Number of stored instance properties declared directly in a Swift type body (comment- and string-free):
    every `let`, and every `var` that is not computed (a `{` body before any `=`, unless it holds willSet/didSet).
    `static`/`class` properties and everything inside functions and nested types do not count."""
    flat = flatten_body(body)
    count = 0
    for m in SWIFT_PROPERTY_RE.finditer(flat):
        line_start = flat.rfind("\n", 0, m.start()) + 1
        if re.search(r"\b(?:static|class)\b", flat[line_start:m.start()]):
            continue
        stored = True
        if m.group(0) == "var":
            for k in range(m.end(), len(flat)):
                c = flat[k]
                if c == "\n" or (c == "=" and flat[k + 1:k + 2] != "="):
                    break
                if c == "{":
                    stored = re.match(r"\s*(?:willSet|didSet)\b", body[k + 1:]) is not None
                    break
        count += 1 if stored else 0
    return count


def swift_types(code):
    """[(qualified name, kind or None for an extension, private, first line, lines, stored properties)] of every type
    declaration and extension in comment- and string-free Swift code. Nested declarations carry the enclosing type in
    their name
    (`Outer.Inner`); `private` is true for a top-level `private`/`fileprivate` declaration (its name is only unique
    within the file)."""
    result, stack = [], []
    for m in SWIFT_TYPE_RE.finditer(code):
        kind, name = m.group(1), m.group(2)
        if name in SWIFT_NOT_A_TYPE:
            continue
        open_at = code.find("{", m.end())
        if open_at < 0 or ";" in code[m.end():open_at] or "}" in code[m.end():open_at]:
            continue
        if kind == "class" and re.search(r"\bprotocol\b[^\n{]*$", code[:m.start()]):
            continue
        close_at = matching(code, open_at)
        while stack and stack[-1][0] < m.start():
            stack.pop()
        qualified = f"{stack[-1][1]}.{name}" if stack else name
        line_start = code.rfind("\n", 0, m.start()) + 1
        private = not stack and re.search(r"\b(?:private|fileprivate)\b", code[line_start:m.start()]) is not None
        stack.append((close_at, qualified))
        first = code.count("\n", 0, m.start()) + 1
        last = code.count("\n", 0, close_at) + 1
        result.append((qualified, None if kind == "extension" else kind, private, first, last - first + 1,
                       swift_stored_properties(code[open_at + 1:close_at])))
    return result


def collect_swift_types(files):
    """Rows (file of the declaration, type, line, lines, stored properties, number of files, kind) summed over the
    declaration and all extensions. `files`: [(relative path, comment- and string-free code)]. Types without a
    declaration in `files` (extensions of system types) are left out."""
    totals = {}
    for path, code in files:
        found = swift_types(code)
        local = {name for name, kind, private, _, _, _ in found if private and kind}
        for name, kind, _, first, lines, props in found:
            # A file-private type and its extensions (same file) stay apart from equally named types elsewhere
            key = (name, path) if name.split(".")[0] in local else (name, None)
            entry = totals.setdefault(key, {"lines": 0, "props": 0, "files": set(), "at": None})
            entry["lines"] += lines
            entry["props"] += props
            entry["files"].add(path)
            if kind and entry["at"] is None:
                entry["at"] = (path, first, kind)
    return sorted((entry["at"][0], name, entry["at"][1], entry["lines"], entry["props"], len(entry["files"]),
                   entry["at"][2]) for (name, _), entry in totals.items() if entry["at"] is not None)


def evaluate_types(rows, ceilings, top=TOP_TYPES):
    """Report lines and exit code for rows (file, type, line, lines, stored properties, files, kind). The ceiling for
    stored properties holds for classes and actors (objects with state of their own); structs and enums are plain
    values or SwiftUI views and only listed."""
    lines, failed = [], False
    if not rows:
        return lines, 0
    ordered = sorted(rows, key=lambda r: (-r[3], r[1], r[0]))
    objects = [r for r in ordered if r[6] in SWIFT_STATEFUL]
    for path, name, line, length, props, files, kind in ordered:
        if length > ceilings["lines"]:
            failed = True
            lines.append(annotation("error", f"{name}: {length} lines across {files} files, at most "
                                    f"{ceilings['lines']} - move a coherent part into a type of its own", path, line))
        if kind in SWIFT_STATEFUL and props > ceilings["stored properties"]:
            failed = True
            lines.append(annotation("error", f"{name}: {props} stored properties, at most "
                                    f"{ceilings['stored properties']} - move state into a helper or state holder",
                                    path, line))
    largest = ordered[0]
    summary = (f"Swift type size: {len(ordered)} types, max {largest[3]} lines ({largest[1]}, {largest[5]} files), "
               f"ceiling {ceilings['lines']}")
    measured = [("lines", largest[3])]
    if objects:
        most = max(objects, key=lambda r: (r[4], r[1]))
        summary += (f"; classes and actors: max {most[4]} stored properties ({most[1]}), "
                    f"ceiling {ceilings['stored properties']}")
        measured.append(("stored properties", most[4]))
    lines.append(summary)
    for path, name, line, length, props, files, kind in ordered[:top]:
        lines.append(f"  {length:4d} lines  {props:3d} stored  {files:2d} files  {kind} {name}  ({path}:{line})")
    if not failed:
        for label, value in measured:
            if value < ceilings[label]:
                lines.append(annotation("notice", f"largest Swift type: {value} {label} < ceiling {ceilings[label]} - "
                                        "lower SWIFT_TYPE_CEILINGS in .github/scripts/code_metrics.py"))
    return lines, (1 if failed else 0)


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


def evaluate_logic(platform, rows, ceiling, top=TOP_LOGIC_FUNCTIONS):
    """Report lines and exit code for the logic functions among rows (file, function, line, length, user interface)."""
    lines, failed = [], False
    ordered = sorted((r for r in rows if not r[4]), key=lambda r: (-r[3], r[0], r[2]))
    if not ordered:
        return lines, 0
    for path, name, line, length, _ in ordered:
        if length <= ceiling:
            break
        failed = True
        lines.append(annotation("error", f"{name}: {length} lines of logic, at most {ceiling} - split the function "
                                f"into named steps (target: {FUNCTION_TARGET} lines)", path, line))
    longest = ordered[0]
    lines.append(f"{platform} logic function length (not user interface): {len(ordered)} functions, max {longest[3]} "
                 f"({longest[1]}), ceiling {ceiling}")
    for path, name, line, length, _ in ordered[:top]:
        lines.append(f"  {length:4d}  {path}:{line}  {name}")
    if longest[3] < ceiling and not failed:
        lines.append(annotation("notice", f"longest {platform} logic function {longest[3]} < ceiling {ceiling} - lower "
                                "LOGIC_CEILINGS in .github/scripts/code_metrics.py"))
    return lines, (1 if failed else 0)


def evaluate_functions(platform, rows, ceiling, top=TOP_FUNCTIONS, target=FUNCTION_TARGET):
    """Report lines and exit code for rows (file, function, line, length, …) of one platform."""
    lines, failed = [], False
    rows = [r[:4] for r in rows]
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
        lines.append(f"Constructor dependencies: {len(deps_rows)} injected classes and providers, max {top[3]} "
                     f"({top[1]}), {warn}ceiling {deps_ceiling}")
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

class Wired(a: A, b: B, c: C)
'''

PROVIDERS = '''
package x.di

@Module
@InstallIn(SingletonComponent::class)
object SampleModule {
    @Provides
    @Singleton
    fun wired(a: A, map: Map<String, Int>): Wired = Wired(a, B(), C(map))

    @Provides
    @Named("io, default")
    fun dispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    // @Provides fun fake(a: A): Plain = Plain(1, 2)
    fun helper(a: A): Plain = Plain(1, 2)

    @Provides fun nullable(
        a: A,
        b: B,
        c: () -> Unit,
    ): Repo? = null
}
'''

SWIFT_TYPES = '''
final class Store: ObservableObject {
    @Published var items: [Int] = []
    @AppStorage("key, with comma") private var flag = false
    let id: UUID
    static let shared = Store()
    class var kind: String { "s" }
    var count: Int { items.count }
    var watched: Int = 0 {
        didSet { save() }
    }
    lazy var cache: [String: Int] = {
        var local = 1
        return [:]
    }()
    weak var delegate: Delegate?
    struct Row {
        let a: Int
        var b: Int { a }
    }
    func load() { let x = 1; var y = x }
    class func make() -> Store { Store() }
}
extension Store {
    var doubled: Int { count * 2 }
    enum Mode { case on, off
        var isOn: Bool { self == .on }
    }
}
extension Store.Row: Equatable {}
extension String {
    var trimmed: String { self }
}
private struct Helper { var a = 1 }
protocol Port: class { var x: Int { get } }
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


SWIFT_UI = '''
struct Row: View {
    var body: some View {
        Text("a")
    }
    @ViewBuilder
    private func row(_ a: Int) -> some View { Text("b") }
    func header() -> some View {
        Text("c")
    }
    func load() async -> [Int] { [] }
    @ToolbarContentBuilder
    func bar() -> some ToolbarContent { ToolbarItem { Text("d") } }
}
'''


def self_test_functions():
    kotlin = {name: (line, length) for name, line, length in function_lengths(code_only(KOTLIN_FUNCTIONS))}
    expected = {"withDefault": (4, 1), "Screen": (8, 8), "local": (13, 1), "total": (16, 3), "single": (19, 1),
                "chained": (21, 3)}
    assert kotlin == expected, kotlin
    swift = function_lengths(swift_code_only(SWIFT_FUNCTIONS), swift=True)
    assert swift == [("init", 7, 1), ("body", 8, 4), ("<", 12, 1), ("load", 13, 6)], swift

    kotlin_ui = {name: ui for name, _, _, ui in function_lengths(code_only(KOTLIN_FUNCTIONS), with_ui=True)}
    assert kotlin_ui == {"withDefault": False, "Screen": True, "local": False, "total": False, "single": False,
                         "chained": False}, kotlin_ui
    swift_ui = [(name, ui)
                for name, _, _, ui in function_lengths(swift_code_only(SWIFT_UI), swift=True, with_ui=True)]
    assert swift_ui == [("body", True), ("row", True), ("header", True), ("load", False), ("bar", True)], swift_ui
    assert is_ui_path("android/app/src/main/java/com/x/app/ui/features/A.kt")
    assert is_ui_path("android/app/src/main/java/com/x/app/widget/W.kt")
    assert not is_ui_path("android/domain/src/main/java/com/x/app/domain/guide/Ui.kt")
    assert is_ui_path("ios/App/Features/Watchlist/Row.swift", swift=True)
    assert is_ui_path("ios/Watch/A.swift", swift=True)
    assert not is_ui_path("ios/Shared/Services/Refresher.swift", swift=True)
    assert not is_ui_path("ios/WatchCore/Quotes.swift", swift=True)
    logic_rows = [("a.kt", "screen", 1, 300, True), ("b.kt", "refresh", 5, 120, False), ("c.kt", "sum", 2, 9, False)]
    lines, code = evaluate_logic("Kotlin", logic_rows, ceiling=120)
    assert code == 0 and lines[0].endswith("2 functions, max 120 (refresh), ceiling 120") and len(lines) == 3, lines
    lines, code = evaluate_logic("Kotlin", logic_rows, ceiling=100)
    assert code == 1 and lines[0].startswith("::error ") and "refresh: 120 lines of logic" in lines[0], lines
    lines, code = evaluate_logic("Kotlin", logic_rows, ceiling=150)
    assert code == 0 and lines[-1].startswith("::notice "), lines
    assert evaluate_logic("Swift", [("v.swift", "body", 1, 50, True)], ceiling=1) == ([], 0)
    lines, code = evaluate_functions("Kotlin", logic_rows, ceiling=300)
    assert code == 0 and "max 300 (screen)" in lines[0], lines

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


def self_test_providers():
    found = providers(PROVIDERS)
    assert found == [("SampleModule", "wired", 9, 2, "Wired"),
                     ("SampleModule", "dispatcher", 13, 0, "CoroutineDispatcher"),
                     ("SampleModule", "nullable", 18, 3, "Repo")], found
    provided = frozenset(row[4] for row in found)
    by_name = {name: deps for name, _, deps, _ in measure(SAMPLE, provided)}
    assert by_name.get("Wired") == 3 and by_name.get("Repo") == 2 and "Plain" not in by_name, by_name
    assert "Wired" not in {name for name, _, _, _ in measure(SAMPLE)}


def self_test_swift_types():
    found = {name: (kind, private, first, lines, props)
             for name, kind, private, first, lines, props in swift_types(swift_code_only(SWIFT_TYPES)) if kind}
    assert found == {"Store": ("class", False, 2, 22, 6), "Store.Row": ("struct", False, 17, 4, 1),
                     "Store.Mode": ("enum", False, 26, 3, 0), "Helper": ("struct", True, 34, 1, 1)}, found
    files = [("a/Store.swift", swift_code_only(SWIFT_TYPES)),
             ("b/Store+More.swift", swift_code_only("extension Store {\n    func more() {}\n}\nstruct Helper { }\n"))]
    rows = collect_swift_types(files)
    assert rows == [("a/Store.swift", "Helper", 34, 1, 1, 1, "struct"),
                    ("a/Store.swift", "Store", 2, 22 + 6 + 3, 6, 2, "class"),
                    ("a/Store.swift", "Store.Mode", 26, 3, 0, 1, "enum"),
                    ("a/Store.swift", "Store.Row", 17, 4 + 1, 1, 1, "struct"),
                    ("b/Store+More.swift", "Helper", 4, 1, 0, 1, "struct")], rows
    lines, code = evaluate_types(rows, {"lines": 31, "stored properties": 6})
    assert code == 0 and not any(x.startswith("::") for x in lines), lines
    assert lines[0] == ("Swift type size: 5 types, max 31 lines (Store, 2 files), ceiling 31; classes and actors: "
                        "max 6 stored properties (Store), ceiling 6"), lines
    lines, code = evaluate_types(rows, {"lines": 30, "stored properties": 5})
    assert code == 1 and sum(x.startswith("::error ") for x in lines) == 2, lines
    # A struct with many stored properties (plain value) is listed, not an error
    values = [("v.swift", "Settings", 1, 10, 70, 1, "struct"), ("o.swift", "Model", 1, 9, 3, 1, "actor")]
    lines, code = evaluate_types(values, {"lines": 10, "stored properties": 3})
    assert code == 0 and "max 3 stored properties (Model)" in lines[0], lines
    lines, code = evaluate_types(rows, {"lines": 40, "stored properties": 6})
    assert code == 0 and sum(x.startswith("::notice ") for x in lines) == 1, lines
    assert evaluate_types([], {"lines": 1, "stored properties": 1}) == ([], 0)


def self_test():
    compared = self_test_property_regex()
    self_test_providers()
    self_test_swift_types()
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
    provided = set()
    for path in provider_files(root):
        with open(path, encoding="utf-8") as f:
            for module, function, line, count, returned in providers(f.read()):
                rows.append((os.path.relpath(path, root), f"{module}.{function}()", line, count, None))
                provided.add(returned)
    for path in kotlin_files(root):
        files += 1
        with open(path, encoding="utf-8") as f:
            source = f.read()
        relative = os.path.relpath(path, root)
        for name, line, deps, props in measure(source, provided):
            rows.append((relative, name, line, deps, props))
        ui_file = is_ui_path(relative)
        for name, line, length, ui in function_lengths(code_only(source), with_ui=True):
            functions["Kotlin"].append((relative, name, line, length, ui or ui_file))
    if files == 0:
        print(annotation("error", f"no Kotlin sources found under {os.path.abspath(root)}/android"))
        return 1
    swift_sources = []
    for path in swift_files(root):  # an Android-only checkout has none: nothing to report
        with open(path, encoding="utf-8") as f:
            swift_sources.append((os.path.relpath(path, root), swift_code_only(f.read())))
    swift = len(swift_sources)
    for relative, swift_code in swift_sources:
        ui_file = is_ui_path(relative, swift=True)
        for name, line, length, ui in function_lengths(swift_code, swift=True, with_ui=True):
            functions["Swift"].append((relative, name, line, length, ui or ui_file))
    lines, code = evaluate(rows)
    print(f"Code metrics: {files} Kotlin files, {swift} Swift files")
    for line in lines:
        print(line)
    for platform in ("Kotlin", "Swift"):
        more, failed = evaluate_functions(platform, functions[platform], FUNCTION_CEILINGS[platform])
        logic, logic_failed = evaluate_logic(platform, functions[platform], LOGIC_CEILINGS[platform])
        code = code or failed or logic_failed
        for line in more + logic:
            print(line)
    more, failed = evaluate_types(collect_swift_types(swift_sources), SWIFT_TYPE_CEILINGS)
    for line in more:
        print(line)
    return code or failed


if __name__ == "__main__":
    sys.exit(main())
