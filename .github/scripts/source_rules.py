#!/usr/bin/env python3
"""Source rules: static checks of the Kotlin and Swift sources that are rules, not behaviour.

Usage: python3 -I source_rules.py [repository root, default: current directory]   (root with android/ and ios/)
       python3 -I source_rules.py --self-test   (the detectors against built-in samples)

Each rule reads the sources from disk, prints one error annotation per finding and the script exits 1 when any rule
finds something. The rules were unit tests that read source text (audit A241-A-01): they are rules of the code base
(forbidden patterns, ceilings that may only go down, layer boundaries, wiring between configuration and code), so they
live here next to line_limit.py and code_metrics.py; behaviour stays in the unit tests. A ceiling or allow list that is
no longer needed is reported too (so it cannot hide a regression later). Both android/ and ios/ are required.

Rules (ids in the annotation title):
  runcatching        no plain runCatching { } in coroutine code (it swallows the CancellationException)
  haptics            haptics only through the catalogue (Haptics.kt / Haptics.swift), none in background code
  typography         smallest text style (labelSmall / .caption2) only goes down; labelSmall at least 12 sp
  spacing            paddings and gaps on the 4/8/12/16/24/32 grid (Kotlin, Swift, widget layouts), same tokens
  label-value        «label: value» through the label_value string, never a hard-coded ": "
  dispatchers        Dispatchers.IO/Default only from DispatcherModule (remaining places may only go down)
  layers             package = module; :domain and :data import only their own layers; no module loses its tests;
                     :coreui without :data and app/ui references to data only in UI_DATA_REMAINING (C-07)
  dialog-scroll      dialog texts with more than one sentence scroll (DialogText)
  write-consent      every user write of WatchRepository outside data/ asks confirmUserWrite() first
  ios-write-consent  every iOS screen calling a user write of AppData asks withWatchlistConfirm first
  write-order        results of user writes are never discarded (follow-ups only after the write); consent before locks
  settings-write     settings written outside the settings screen go through settingsRepository.guarded
  compose-stability  gradle/compose-stability.conf lists exactly the immutable models
  test-inputs        every path the unit tests read is a declared input of the test tasks
  bounded-caches     per-pair memory caches use BoundedCache, not an unbounded ConcurrentHashMap
  ios-widget-offline every iOS widget timeline asks for the network (WidgetOffline.isOffline) like the Android renderers
  swipe-delete       every swipe-to-delete row asks before deleting (confirmsDelete = true); the watchlist offers undo
  ios-alarm-create   every iOS alarm sheet goes through AlarmCreateGuard (double tap = one alarm)
  bulk-undo          «Undo» after adding many pairs removes only from the confirmation (BulkUndoConfirm), both platforms
  app-start          process start (Application.onCreate) starts the price overlay; its setting says it comes back then
  settings-anchors   every settings search hit has its anchor on the page (both platforms); key entries are searchable
  permission-event   the price-pair settings ask for the notification permission through a screen event
  missing-logos      «coins without logo» sits under the loading hint; dismissed coins stay in the logo settings/app group
  lot-sheets         deleting a cost lot asks first on iOS; the open lot sheets survive recreation on Android
  wiring             restore writes under the address refresh lock; every exact-check entry reads the shared demand
  portfolio-settings-confirm  iOS asks before another cost method / system backup; the tax sheet names the file contents
  watch-deletion     Wear OS reads deleted phone entries and forgets what came from the phone
  ios-wiring         fixed wiring of iOS views, widgets, watch and data layer (table, former XCTest source checks)

The comments and messages are in German like the tests they replace.
"""
import os
import re
import sys

A = re.ASCII
APP = "com.cryptochecker.app"


class RuleError(Exception):
    """Broken checkout (missing folder or file): reported as an error of the rule, not as a crash."""


# --------------------------------------------------------------------------------------------------------------------
# Files


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def files_under(directory, extension):
    """All files with [extension] below [directory] (sorted, relative paths with «/»)."""
    if not os.path.isdir(directory):
        return []
    out = []
    for base, dirs, names in os.walk(directory):
        dirs.sort()
        for name in sorted(names):
            if name.endswith("." + extension):
                full = os.path.join(base, name)
                out.append((os.path.relpath(full, directory).replace(os.sep, "/"), full))
    return out


def need_dir(path):
    if not os.path.isdir(path):
        raise RuleError(f"Ordner fehlt: {path}")
    return path


def need_file(path):
    if not os.path.isfile(path):
        raise RuleError(f"Datei fehlt: {path}")
    return path


def squash(text):
    """Ohne Leerraum und ohne Komma vor einer schliessenden Klammer: Vergleich unabhängig vom Umbruch des Formatierers."""
    return re.sub(r",([)\]}])", r"\1", re.sub(r"\s+", "", text))


def is_comment_line(line):
    code = line.lstrip()
    return code.startswith("//") or code.startswith("*") or code.startswith("/*")


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


class Tree:
    """Paths of the checkout: [root] holds android/ and ios/."""

    def __init__(self, root):
        root = os.path.abspath(root)
        self.root = root
        self.android = need_dir(os.path.join(root, "android"))
        self.ios = need_dir(os.path.join(root, "ios"))

    def a(self, *parts):
        return os.path.join(self.android, *parts)

    def i(self, *parts):
        return os.path.join(self.ios, *parts)

    def java(self, module):
        """android/<module>/src/main/java/com/cryptochecker/app (the app package root of a module)."""
        return self.a(module, "src", "main", "java", "com", "cryptochecker", "app")


# --------------------------------------------------------------------------------------------------------------------
# Kotlin scanning helpers


def strip_comments_and_strings(src):
    """Kommentare, Strings und Zeichenliterale durch Leerzeichen ersetzen (Zeilenumbrüche bleiben)."""
    out = []
    i = 0
    n = len(src)

    def blank(start, to):
        out.append("".join("\n" if src[k] == "\n" else " " for k in range(start, to)))
        return to

    while i < n:
        if src.startswith("//", i):
            end = src.find("\n", i)
            i = blank(i, n if end < 0 else end)
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = blank(i, n if end < 0 else end + 2)
        elif src.startswith('"""', i):
            end = src.find('"""', i + 3)
            i = blank(i, n if end < 0 else end + 3)
        elif src[i] == '"':
            k = i + 1
            while k < n and src[k] != '"' and src[k] != "\n":
                if src[k] == "\\":
                    k += 1
                k += 1
            i = blank(i, min(k + 1, n))
        elif src[i] == "'" and i + 2 < n and (src[i + 1] == "\\" or src[i + 2] == "'"):
            end = src.find("'", i + 3 if src[i + 1] == "\\" else i + 2)
            i = blank(i, n if end < 0 else end + 1)
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


# --------------------------------------------------------------------------------------------------------------------
# runcatching: runCatching { } in coroutine code


RC_CALL = "runCatching"
RC_RETHROW_WINDOW = 240
RC_COROUTINE_BUILDERS = {
    "launch", "async", "withContext", "coroutineScope", "supervisorScope", "withTimeout",
    "withTimeoutOrNull", "runBlocking", "LaunchedEffect", "produceState", "flow", "channelFlow",
    "callbackFlow", "transformLatest", "collect", "collectLatest", "withPermit", "withTransaction",
}
RC_NON_SUSPEND = {"synchronized", "sequence", "iterator", "lazy", "get", "set"}
SUSPEND, PLAIN, INHERIT = "suspend", "plain", "inherit"


def _rc_has_expression_body(decl):
    """Steht nach der Parameterliste ein `=` (Ausdrucksrumpf statt `{`-Rumpf)?"""
    open_ = decl.find("(")
    if open_ < 0:
        return False
    depth = 0
    close = -1
    for k in range(open_, len(decl)):
        if decl[k] == "(":
            depth += 1
        if decl[k] == ")":
            depth -= 1
            if depth == 0:
                close = k
                break
    if close < 0:
        return False
    return re.search(r"(?<![=!<>])=(?![=>])", decl[close + 1:]) is not None


def _rc_is_suspend_modifier(head, fun_start):
    return re.search(r"\bsuspend\b", head[:fun_start].rsplit("\n", 1)[-1], A) is not None


def _rc_matching_open(t, close):
    depth = 0
    for k in range(close, -1, -1):
        if t[k] == ")":
            depth += 1
        if t[k] == "(":
            depth -= 1
            if depth == 0:
                return k
    return -1


def _rc_frame(s, pos):
    """Herkunft der Klammer an [pos]: Funktions-/Klassenrumpf, Coroutine-Lambda oder sonstige Lambda."""
    head = s[max(0, pos - 400):pos]
    funs = list(re.finditer(r"\bfun\b", head, A))
    if funs:
        fn = funs[-1]
        rest = head[fn.start():]
        if not any(c in "{};" for c in rest) and not _rc_has_expression_body(rest):
            return SUSPEND if _rc_is_suspend_modifier(head, fn.start()) else PLAIN
    t = head.rstrip()
    if re.search(r"\b(class|object|interface|init)\b[^{}()=]*$", t, A):
        return PLAIN
    if t.endswith(")"):
        open_ = _rc_matching_open(t, len(t) - 1)
        before = t[:max(open_, 0)].rstrip()
        if re.search(r"\b(class|object)\s+\w+\s*(<[^<>]*>)?$", before, A):
            return PLAIN
        m = re.search(r"(\w+)\s*(<[^<>]*>)?$", before, A)
    else:
        m = re.search(r"(\w+)\s*(<[^<>]*>)?$", t, A)
    name = m.group(1) if m else ""
    if name in RC_COROUTINE_BUILDERS:
        return SUSPEND
    if name in RC_NON_SUSPEND:
        return PLAIN
    return INHERIT


def _rc_suspend_expression_body(s, pos):
    """`suspend fun x(): T =` direkt vor [pos] (Ausdrucksrumpf ohne Klammer)."""
    head = s[max(0, pos - 400):pos]
    funs = list(re.finditer(r"\bfun\b", head, A))
    if not funs:
        return False
    fn = funs[-1]
    decl = head[fn.start():]
    if any(c in "{};" for c in decl):
        return False
    return decl.rstrip().endswith("=") and _rc_has_expression_body(decl) and _rc_is_suspend_modifier(head, fn.start())


def _rc_rethrows_after(s, brace):
    """Wirft die Kette direkt nach dem Block den Abbruch selbst weiter?"""
    depth = 0
    k = brace
    while k < len(s):
        if s[k] == "{":
            depth += 1
        if s[k] == "}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    tail = s[min(k + 1, len(s)):min(k + 1 + 4 * RC_RETHROW_WINDOW, len(s))]
    tail = re.sub(r"\s+", " ", tail)[:RC_RETHROW_WINDOW]
    return "CancellationException" in tail or "ensureActive" in tail


def runcatching_violations(source):
    """Zeilennummern (ab 1) mit `runCatching {` in Coroutine-Kontext ohne eigenes Weiterwerfen."""
    s = strip_comments_and_strings(source)
    frames = []
    result = []
    for i, c in enumerate(s):
        if c == "{":
            frames.append(_rc_frame(s, i))
        elif c == "}":
            if frames:
                frames.pop()
        elif c == "r" and s.startswith(RC_CALL, i) and (i == 0 or not (s[i - 1].isalnum() or s[i - 1] in "_.")):
            brace = i + len(RC_CALL)
            while brace < len(s) and s[brace].isspace():
                brace += 1
            if brace < len(s) and s[brace] == "{":
                ctx = next((f for f in reversed(frames) if f != INHERIT), PLAIN)
                in_coroutine = ctx == SUSPEND or _rc_suspend_expression_body(s, i)
                if in_coroutine and not _rc_rethrows_after(s, brace):
                    result.append(source.count("\n", 0, i) + 1)
    return result


def rule_runcatching(t):
    # Alle Module: app plus coreui, data, domain, marketdata, wear und widget
    roots = [need_dir(t.java("app"))] + [need_dir(t.a(m, "src", "main", "java"))
                                          for m in ("coreui", "data", "domain", "marketdata", "wear", "widget")]
    problems = []
    for root in roots:
        for _, path in files_under(root, "kt"):
            for line in runcatching_violations(read(path)):
                problems.append(f"{os.path.relpath(path, t.android)}:{line}: runCatching {{ }} in Coroutine-Code "
                                "(runCatchingCancellable nehmen)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# haptics


HAPTICS_ANDROID_DIRECT = re.compile(r"performHapticFeedback|LocalHapticFeedback|HapticFeedbackConstants|\bVibrator\b|VibratorManager")
HAPTICS_ANDROID_CATALOG = re.compile(r"rememberHaptics\(|Haptics\.play\(|HapticEvent\.")
HAPTICS_IOS_DIRECT = re.compile(r"UIImpactFeedbackGenerator|UINotificationFeedbackGenerator|UISelectionFeedbackGenerator|\.sensoryFeedback\(")
HAPTICS_IOS_CATALOG = re.compile(r"Haptics\.play\(|\.haptic\(")
# Abspielen (nicht der Typ): in Code ohne Bildschirm verboten, Zuordnungen auf `HapticEvent` bleiben erlaubt
HAPTICS_ANDROID_PLAYBACK = re.compile(r"rememberHaptics\(|Haptics\.play\(|\bHapticPlayer\b|performHapticFeedback")
HAPTICS_IOS_PLAYBACK = re.compile(r"Haptics\.play\(|\.haptic\(|FeedbackGenerator")


def find_uses(directory, extension, pattern, allowed=()):
    """«Datei:Zeile» aller Treffer von [pattern] in Codezeilen (Kommentare zählen nicht) unter [directory]."""
    hits = []
    for rel, path in files_under(directory, extension):
        if rel in allowed:
            continue
        for index, line in enumerate(read(path).split("\n")):
            if not is_comment_line(line) and pattern.search(line):
                hits.append(f"{rel}:{index + 1}")
    return hits


def rule_haptics(t):
    """Android spielt Haptik nur über ui/components/Haptics.kt, iOS nur über App/Components/Haptics.swift; Hintergrund,
    Mitteilungen und Widgets bekommen keine; Daten-, Domänen- und Hilfscode ordnet höchstens zu."""
    app = need_dir(t.java("app"))
    problems = [f"app/{h}: Haptik direkt statt über rememberHaptics()/Haptics.play"
                for h in find_uses(app, "kt", HAPTICS_ANDROID_DIRECT, {"ui/components/Haptics.kt"})]
    background = [os.path.join(app, d) for d in ("notification", "work", "tts")] + [t.a("widget", "src", "main", "java")]
    for d in background:
        need_dir(d)
        problems += [f"{os.path.relpath(d, t.android)}/{h}: Haptik im Hintergrund, in Mitteilungen oder Widgets"
                     for h in find_uses(d, "kt", HAPTICS_ANDROID_CATALOG)]
    non_ui = [os.path.join(app, d) for d in ("activity", "alarm", "diagnostics", "gas", "refresh", "lock", "setup", "di")]
    non_ui += [os.path.join(t.java("coreui"), d) for d in ("util", "settings")]
    non_ui += [t.a(m, "src", "main", "java") for m in ("data", "domain")]
    for d in non_ui:
        if os.path.isdir(d):
            problems += [f"{os.path.relpath(d, t.android)}/{h}: Haptik wird in Daten-/Domänen-/Hilfscode abgespielt"
                         for h in find_uses(d, "kt", HAPTICS_ANDROID_PLAYBACK)]
    for top in ("App", "Shared", "Widgets"):
        d = need_dir(t.i(top))
        problems += [f"ios/{top}/{h}: Haptik direkt statt über Haptics.play/.haptic(_:trigger:)"
                     for h in find_uses(d, "swift", HAPTICS_IOS_DIRECT, {"Components/Haptics.swift"})]
    for top in ("Shared", "Widgets"):
        problems += [f"ios/{top}/{h}: Haptik in Widgets oder geteiltem Code"
                     for h in find_uses(t.i(top), "swift", HAPTICS_IOS_CATALOG)]
    services = need_dir(t.i("App", "Services"))
    problems += [f"ios/App/Services/{h}: Haptik wird in App/Services abgespielt"
                 for h in find_uses(services, "swift", HAPTICS_IOS_PLAYBACK)]
    return problems


# --------------------------------------------------------------------------------------------------------------------
# typography


LABEL_SMALL = re.compile(r"typography\.labelSmall\b")
CAPTION2 = re.compile(r"\.caption2\b")
# Bereich (relativ zu `com/cryptochecker/app` in :app, leer = alles) → höchstens so viele Stellen; darf nur sinken
TYPOGRAPHY_ANDROID_CEILINGS = {"": 48, "ui/features/info": 8, "ui/features/portfolio": 14, "ui/features/watchlist": 23}
# Module ausserhalb von :app (seit dem Aufteilen) → höchstens so viele Stellen; darf nur sinken
TYPOGRAPHY_MODULE_CEILINGS = {"coreui": 0, "widget": 0}
TYPOGRAPHY_IOS_CAPTION2_CEILING = 44


def count_uses(directory, extension, pattern):
    """Treffer in Codezeilen (Kommentarzeilen zählen nicht) aller Dateien mit [extension] unter [directory]."""
    total = 0
    for _, path in files_under(directory, extension):
        for line in read(path).split("\n"):
            if not is_comment_line(line):
                total += len(pattern.findall(line))
    return total


def rule_typography(t):
    """`typography.labelSmall` (12 sp) bzw. iOS `.caption2` nur für Quellen, rechtliche Fusszeilen und Chart-Beschriftungen;
    Metadaten nehmen `label` bzw. `.caption`. Obergrenzen = erreichter Stand, dürfen nur sinken."""
    problems = []
    app = need_dir(t.java("app"))
    for area, ceiling in TYPOGRAPHY_ANDROID_CEILINGS.items():
        uses = count_uses(os.path.join(app, area) if area else app, "kt", LABEL_SMALL)
        if uses > ceiling:
            problems.append(f"typography.labelSmall in «{area or 'alle'}»: {uses} Stellen, erlaubt höchstens {ceiling} — "
                            "für Metadaten MaterialTheme.typography.label nehmen")
    for module, ceiling in TYPOGRAPHY_MODULE_CEILINGS.items():
        uses = count_uses(need_dir(t.a(module, "src", "main", "java")), "kt", LABEL_SMALL)
        if uses > ceiling:
            problems.append(f"typography.labelSmall in :{module}: {uses} Stellen, erlaubt höchstens {ceiling}")
    typography = read(need_file(os.path.join(t.java("coreui"), "ui", "theme", "Typography.kt")))
    m = re.search(r"labelSmall = base\.labelSmall\.copy\(fontSize = (\d+)\.sp", typography)
    if not m:
        problems.append("labelSmall-Grösse in coreui Typography.kt nicht gefunden")
    elif int(m.group(1)) < 12:
        problems.append(f"labelSmall {m.group(1)} sp, mindestens 12 sp")
    uses = count_uses(need_dir(t.i("App")), "swift", CAPTION2)
    if uses > TYPOGRAPHY_IOS_CAPTION2_CEILING:
        problems.append(f".caption2 in ios/App: {uses} Stellen, erlaubt höchstens {TYPOGRAPHY_IOS_CAPTION2_CEILING} — "
                        "für Metadaten .caption nehmen")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# spacing


OFF_GRID = "5|6|7|9|10|11|13|14|15|17|18|19|20|22|26|28|30"
# `padding(… 6.dp …)`, `spacedBy(6.dp)`, `PaddingValues(6.dp)`, `Spacer(Modifier.width(6.dp))` und benannte Abstände
# über mehrere Zeilen (`top = 6.dp`); nicht 1.5.dp oder 16.dp
SPACING_ANDROID = re.compile(
    r"(?:(?:padding|spacedBy|PaddingValues|Spacer\(Modifier\.(?:width|height))\([^)]*?|\b(?:start|end|top|bottom|horizontal"
    r"|vertical|all) = )(?<![\d.])(?:" + OFF_GRID + r")\.dp\b")
# `spacing: 6`, `Spacer(minLength: 6)`, `.padding(6)`, `.padding(.top, 6)`, `EdgeInsets(top: 6 …)`; nicht -6
SPACING_IOS = re.compile(
    r"(?:\b(?:spacing|minLength|top|bottom|leading|trailing):\s*|\.padding\((?:\.\w+,\s*|\[[^\]]*\],\s*)?)(?:" + OFF_GRID +
    r")(?:\.0)?\b(?![.\d])")
# `android:paddingStart="6dp"`, `android:layout_marginTop="10dp"`; nicht 1–3 dp (Feinabstimmung) oder 0dp
SPACING_XML = re.compile(r'android:(?:padding|layout_margin)\w*="(?:' + OFF_GRID + r')dp"')
SPACING_XML_SPACE = re.compile(r"<Space\b[^>]*>")
SPACING_XML_SPACE_OFF = re.compile(r'android:layout_(?:width|height)="(?:' + OFF_GRID + r')dp"')
# Datei → höchstens so viele Stellen, die bewusst bleiben (Kotlin relativ zu `<Modul>/…/com/cryptochecker/app`)
SPACING_ANDROID_CEILINGS = {
    # Chart-Inneres: Platz für die Beschriftung über der Kurve
    "app/ui/features/info/MarketCycleChart.kt": 1,
    # Suchfeld 48 dp hoch, Pille 44 dp: je 2 dp weniger Abstand (12 − 2 = 10), damit nichts wächst
    "app/ui/features/watchlist/WatchGroupEditSheet.kt": 1,
}
SPACING_ANDROID_MODULES = ("app", "coreui", "widget")
SPACING_WIDGET_XML_CEILINGS = {
    # Vorschau: 6 dp stehen für die zwei Leerzeichen vor dem Prozent (WidgetTextFit.PERCENT_GAP)
    "widget_preview_portfolio.xml": 1,
}
SPACING_IOS_AREAS = ("App", "Widgets")
SPACING_IOS_CEILINGS = {
    # Zeilen der Marktdaten: wie Android 2 × 3 dp Zeilen-Innenabstand (Feinabstimmung) = 6 (in `PulseFacts`)
    "App/Features/Cycle/CryptoPulseParts.swift": 1,
}


def offenders(files, pattern, ceilings):
    """Dateien mit mehr Treffern als erlaubt (nicht gelistete: 0), samt Zeilen; Kommentarzeilen zählen nicht.
    [files]: (Anzeigename, Pfad). Dazu Einträge in [ceilings], die nicht mehr nötig sind."""
    problems = []
    counts = {}
    for name, path in files:
        hits = [f"  {i + 1}: {line.strip()}" for i, line in enumerate(read(path).split("\n"))
                if not is_comment_line(line) for _ in pattern.findall(line)]
        counts[name] = len(hits)
        allowed = ceilings.get(name, 0)
        if len(hits) > allowed:
            problems.append(f"{name}: {len(hits)} Stellen, erlaubt {allowed}\n" + "\n".join(hits))
    for name, allowed in ceilings.items():
        if counts.get(name, 0) < allowed:
            problems.append(f"{name}: {counts.get(name, 0)} Stellen, Obergrenze {allowed} senken (bei 0 Eintrag entfernen)")
    return problems


def rule_spacing(t):
    """Abstandsraster 4/8/12/16/24/32 (dp bzw. pt): Innenabstände, spacedBy/spacing:, Spacer als Lücke, contentPadding und
    die Widget-Layouts; Ausnahmen mit Begründung in den Obergrenzen (dürfen nur sinken)."""
    files = []
    for module in SPACING_ANDROID_MODULES:
        root = need_dir(t.java(module))
        files += [(f"{module}/{rel}", path) for rel, path in files_under(root, "kt")]
    problems = offenders(files, SPACING_ANDROID, SPACING_ANDROID_CEILINGS)
    ios_files = []
    for area in SPACING_IOS_AREAS:
        ios_files += [(f"{area}/{rel}", path) for rel, path in files_under(need_dir(t.i(area)), "swift")]
    problems += offenders(ios_files, SPACING_IOS, SPACING_IOS_CEILINGS)
    layouts = need_dir(t.a("resources", "src", "main", "res", "layout"))
    counts = {}
    for name in sorted(os.listdir(layouts)):
        if not (name.startswith("widget_") and name.endswith(".xml")):
            continue
        text = read(os.path.join(layouts, name))
        hits = [(line_of(text, m.start()), m.group(0)) for m in SPACING_XML.finditer(text)]
        for space in SPACING_XML_SPACE.finditer(text):
            hits += [(line_of(text, space.start() + m.start()), m.group(0)) for m in SPACING_XML_SPACE_OFF.finditer(space.group(0))]
        hits.sort()
        counts[name] = len(hits)
        allowed = SPACING_WIDGET_XML_CEILINGS.get(name, 0)
        if len(hits) > allowed:
            problems.append(f"{name}: {len(hits)} Stellen, erlaubt {allowed}\n" + "\n".join(f"  {l}: {v}" for l, v in hits))
    for name, allowed in SPACING_WIDGET_XML_CEILINGS.items():
        if counts.get(name, 0) < allowed:
            problems.append(f"{name}: {counts.get(name, 0)} Stellen, Obergrenze {allowed} senken")
    # Beide Plattformen haben dieselben Stufen mit denselben Werten
    spacing_kt = read(need_file(os.path.join(t.java("coreui"), "ui", "theme", "Spacing.kt")))
    android = {m.group(1): int(m.group(2)) for m in re.finditer(r"val (\w+) = (\d+)\.dp", spacing_kt)}
    swift = read(need_file(t.i("Shared", "Util", "Spacing.swift")))
    block = swift.split("enum Spacing {", 1)[-1].split("}", 1)[0]
    ios = {m.group(1): int(m.group(2)) for m in re.finditer(r"static let (\w+): CGFloat = (\d+)", block)}
    expected = {"xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24, "xxl": 32}
    if android != expected:
        problems.append(f"Spacing.kt: {android}, erwartet {expected}")
    if ios != android:
        problems.append(f"Spacing.swift {ios} ≠ Spacing.kt {android}")
    # Freiraum unter Listen mit schwebendem «+»: Android nur über Spacing.fabClearance (3 × xxl), iOS per safeAreaInset
    if "val fabClearance = xxl * 3" not in spacing_kt:
        problems.append("Spacing.kt: val fabClearance = xxl * 3 fehlt")
    for module in SPACING_ANDROID_MODULES:
        for rel, path in files_under(t.java(module), "kt"):
            if re.search(r"bottom = 96\.dp", read(path)):
                problems.append(f"{module}/{rel}: bottom = 96.dp statt Spacing.fabClearance")
    overlay = re.compile(r"\.overlay\(alignment: \.bottomTrailing\) \{\s*PortfolioAddButton")
    for rel, path in files_under(t.i("App"), "swift"):
        text = read(path)
        if "Color.clear.frame(height: 96)" in text:
            problems.append(f"ios/App/{rel}: leerer Füllblock Color.clear.frame(height: 96)")
        if overlay.search(text):
            problems.append(f"ios/App/{rel}: «+»-Knopf als Overlay statt Einschub unten")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# label-value


# `a + ": " + b`, `.append(": ")`, `"$a: $b"` und `"$a: " + b`
LV_KOTLIN = [re.compile(r'\+ ": " \+'), re.compile(r'\.append\(": "\)'), re.compile(r'\$\{?[A-Za-z_][\w.]*\}?: (\$|" \+)', A)]
# `a + ": " + b`, `"\(a): \(b)"` und `"\(a): " + b`
LV_SWIFT = [re.compile(r'\+ ": " \+'), re.compile(r'\): \\\('), re.compile(r'\): " \+')]
# Protokoll und Programmierfehler: nie in der Sprache der Oberfläche
LV_IGNORED_CALLS = ("HttpLog.", "Log.", "Timber.", "fatalError(", 'error("', "require(", "check(")
# Pfadende → Zeilenstücke, die bewusst ein festes «: » behalten
LV_ALLOWED = {
    # Diagnosebericht zum Kopieren: feste Codes, gleich in jeder Sprache
    "diagnostics/DiagnosticReport.kt": ["${it.row.code}: "],
    "Services/DiagnosticReport.swift": ["\\(item.row.rawValue): "],
    # Prüfprotokoll des Datenbank-Exports (Diagnose, deutsch)
    "data/local/DatabaseFingerprint.kt": ["$table: "],
    # Protokoll und Fehlertext beim Öffnen der Datenbank (Diagnose, deutsch, nie in der Oberfläche)
    "data/local/DatabaseOpenPlanner.kt": ["$database: $reason"],
    # Orientierungsposten auch in der CSV-Ausgabe: Format der Vorlage, nicht der Oberfläche
    "domain/portfolio/TaxSummaryTexts.kt": ["$fallback: $label"],
    "Portfolio/Tax/TaxSummaryTexts.swift": ["\\(fallback): \\(label)"],
    # Technischer Fehlertext: HTTP-Status und Auszug der Antwort
    "Core/ExchangeHTTP.swift": ['status + ": " + excerpt'],
    "Exchanges/BulkTickerRows.swift": ["\\(formatError): "],
}
LV_EXPECTED = {
    "values-fr": "%1$s" + "\\" + "u00A0: %2$s",
    "values-zh": "%1$s：%2$s", "values-b+zh+Hant": "%1$s：%2$s", "values-b+yue": "%1$s：%2$s", "values-ja": "%1$s：%2$s",
    "values-am": "%1$s፦ %2$s",
    "values-hy": "%1$s՝ %2$s",
    "values-km": "%1$s៖ %2$s",
}


def label_value_offenders(directory, extension, patterns):
    """Treffer in Codezeilen (ohne Kommentare, Protokoll und LV_ALLOWED) als «Pfad:Zeile: Text»."""
    out = []
    for rel, path in files_under(directory, extension):
        full = path.replace(os.sep, "/")
        allowed = [piece for end, pieces in LV_ALLOWED.items() if full.endswith(end) for piece in pieces]
        for index, line in enumerate(read(path).split("\n")):
            if is_comment_line(line) or any(c in line for c in LV_IGNORED_CALLS):
                continue
            if any(p.search(line) for p in patterns) and not any(a in line for a in allowed):
                out.append(f"{os.path.basename(path)}:{index + 1}: {line.strip()}")
    return out


def rule_label_value(t):
    """«Bezeichnung: Wert» über R.string.label_value bzw. L("label_value", …), nie über ein festes «: » — sonst fehlt die
    Typografie der Sprache (Französisch « : », Chinesisch/Japanisch «：»). Alle 75 Sprachen haben label_value."""
    problems = []
    for module in ("app", "coreui", "widget", "domain", "data"):
        problems += [f"{p} — stringResource(R.string.label_value, a, b) nehmen"
                     for p in label_value_offenders(need_dir(t.java(module)), "kt", LV_KOTLIN)]
    for top in ("App", "Shared", "Widgets"):
        problems += [f"{p} — L(\"label_value\", a, b) nehmen"
                     for p in label_value_offenders(need_dir(t.i(top)), "swift", LV_SWIFT)]
    res = need_dir(t.a("resources", "src", "main", "res"))
    folders = sorted(d for d in os.listdir(res) if d.startswith("values") and os.path.isfile(os.path.join(res, d, "strings_common.xml")))
    if len(folders) != 75:
        problems.append(f"strings_common.xml in {len(folders)} statt 75 Sprachen")
    for folder in folders:
        m = re.search(r'<string name="label_value">([^<]*)</string>', read(os.path.join(res, folder, "strings_common.xml")))
        expected = LV_EXPECTED.get(folder, "%1$s: %2$s")
        if not m:
            problems.append(f"label_value fehlt in {folder}")
        elif m.group(1) != expected:
            problems.append(f"label_value in {folder}: «{m.group(1)}», erwartet «{expected}»")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# dispatchers


DISPATCHER_MODULES = ("app", "coreui", "data", "domain", "widget")
# Liefert die Dispatcher und nennt sie darum selbst (zweimal)
DISPATCHER_PROVIDER = "app/app/di/DispatcherModule.kt"
DISPATCHER_LITERAL = re.compile(r"\bDispatchers\.(?:IO|Default)\b")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
DISPATCHER_LINE_COMMENT = re.compile(r"(^|[ \t])//[^\n]*", re.M)
# Datei (`<Modul>/` + Pfad relativ zu `src/main/java/com/cryptochecker/`) → höchstens so viele Stellen; darf nur sinken.
# Was noch nicht umgestellt ist, nach Grund geordnet — bei 0 kommt der Eintrag weg.
DISPATCHER_REMAINING = {
    # Von Android erzeugt oder ohne Hilt-Konstruktor (Application vor der Injektion, Receiver, `object`,
    # Hilfsklassen und -funktionen, die von Hand gebaut werden)
    "app/app/CryptoCheckerApp.kt": 2,
    "coreui/app/util/ReceiverAsync.kt": 1,
    "app/app/work/ExactCheckReceiver.kt": 1,
    "domain/app/domain/widget/PulseBackgroundFetch.kt": 1,
    "widget/app/widget/PortfolioWidgetChartView.kt": 1,
    "app/app/ui/features/portfolio/PortfolioExportRun.kt": 2,
    "app/app/ui/features/portfolio/PortfolioCsvImportReader.kt": 3,
    "app/app/ui/features/watchlist/WatchlistContentFlows.kt": 1,
    "data/app/data/portfolio/PortfolioBackupMirror.kt": 1,
    "data/app/data/remote/util/InFlightRequests.kt": 1,
    # Composables (LaunchedEffect/produceState): kein Konstruktor, in den Hilt etwas reichen könnte
    "app/app/ui/features/about/LicensesSheet.kt": 1,
    "app/app/ui/features/alarms/AlarmPickerRows.kt": 1,
    "app/app/ui/features/explorer/ExplorerExchangeBadge.kt": 1,
    "app/app/ui/features/portfolio/PortfolioRestoreNoticeDialog.kt": 1,
    "app/app/ui/features/settings/SettingsDataPages.kt": 1,
    "app/app/ui/features/settings/SettingsScreen.kt": 1,
    "app/app/ui/features/settings/StorageRecoveryNoticeDialog.kt": 3,
    "app/app/ui/features/watchlist/WatchSheetChartCanvas.kt": 1,
    # ViewModels mit den meisten Konstruktor-Abhängigkeiten in :app (code_metrics.py) samt Erweiterungsdateien;
    # Explorer und Portfolio nehmen den Default-Dispatcher schon, IO erst nach dem Aufteilen
    "app/app/ui/features/alarms/AlarmsViewModel.kt": 1,
    "app/app/ui/features/explorer/ExplorerBulkActions.kt": 1,
    "app/app/ui/features/explorer/ExplorerSearchActions.kt": 2,
    "app/app/ui/features/info/MarketDataLoaders.kt": 1,
    "app/app/ui/features/info/MarketDataSync.kt": 1,
    "app/app/ui/features/info/MarketPreviewActions.kt": 1,
    "app/app/ui/features/portfolio/PortfolioLoadActions.kt": 1,
    # Dateien an der Zeilengrenze (line_limit.py; ein Konstruktor-Parameter mehr sprengt sie) samt Erweiterungsdateien
    "data/app/data/CoinListSync.kt": 5,
    "data/app/data/CoinListSyncMap.kt": 4,
    "data/app/data/logos/LogoImageStore.kt": 5,
    "data/app/data/portfolio/WatchedAddressSourceXpub.kt": 2,
    # Nahe der Zeilengrenze und von Hand gebaut in 6 Testdateien
    "data/app/data/remote/ExchangeRemoteDataSource.kt": 1,
    # Gebaut von LogoImageStore (verdrahtet IO selbst, siehe oben); dazu von Hand in den Paritätstests
    "domain/app/domain/logos/LogoLoadingHint.kt": 2,
}


def dispatcher_literals(text):
    code = BLOCK_COMMENT.sub("", text)
    code = DISPATCHER_LINE_COMMENT.sub(r"\1", code)
    return len(DISPATCHER_LITERAL.findall(code))


def rule_dispatchers(t):
    """Keine Kotlin-Datei von :app, :coreui, :data, :domain und :widget nennt Dispatchers.IO/Default selbst — ausser
    DispatcherModule. Per Hilt erzeugte Klassen nehmen den Dispatcher im Konstruktor (@param:IoDispatcher …)."""
    files = {}
    for module in DISPATCHER_MODULES:
        main = need_dir(t.a(module, "src", "main"))
        for rel, path in files_under(main, "kt"):
            name = f"{module}/" + (rel[len("java/com/cryptochecker/"):] if rel.startswith("java/com/cryptochecker/") else rel)
            files[name] = path
    if len(files) <= 900:
        return [f"nur {len(files)} Kotlin-Dateien gefunden"]
    problems = []
    if DISPATCHER_PROVIDER not in files:
        return [f"{DISPATCHER_PROVIDER} fehlt"]
    if dispatcher_literals(read(files[DISPATCHER_PROVIDER])) != 2:
        problems.append(f"{DISPATCHER_PROVIDER}: erwartet genau 2 Dispatcher")
    counts = {name: dispatcher_literals(read(path)) for name, path in files.items() if name != DISPATCHER_PROVIDER}
    for name, count in sorted(counts.items()):
        if count > DISPATCHER_REMAINING.get(name, 0):
            problems.append(f"{name}: {count}, erlaubt {DISPATCHER_REMAINING.get(name, 0)} — Dispatchers.IO/Default fest "
                            "verdrahtet; im Konstruktor nehmen (@param:IoDispatcher/@param:DefaultDispatcher)")
    for name, allowed in sorted(DISPATCHER_REMAINING.items()):
        if counts.get(name, 0) < allowed:
            problems.append(f"{name}: {counts.get(name, 0)} statt {allowed} — Zahl in DISPATCHER_REMAINING senken "
                            "(bei 0 Eintrag entfernen)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# layers


DOMAIN = APP + ".domain"
DATA = APP + ".data"
# Paket des gemeinsamen Test-Helfers (`test-shared/java`): Tests von :domain und :data dürfen es importieren
TEST_SHARED = APP + ".parity"
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M | A)
IMPORT = re.compile(r"^import\s+([\w.*]+)", re.M | A)
LINE_COMMENT = re.compile(r"//[^\n]*")
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.M | A)
TEST_MODULES = ("app", "domain", "data", "marketdata", "wear")
# Untergrenze der `@Test`-Methoden je Modul = heutiger Stand: weniger heisst verloren. Wer Tests hinzufügt, hebt die
# Untergrenze im selben Commit; wer Tests bewusst streicht (oder hierher verschiebt), senkt sie mit Begründung.
TEST_FLOOR = {"app": 1473, "domain": 1621, "data": 606, "marketdata": 32, "wear": 49}
# Datei (relativ zu `<Modul>/src/main`) → Grund, warum sie die Regel verletzen darf. Leer: keine Ausnahme.
DOMAIN_EXCEPTIONS = {}
DATA_EXCEPTIONS = {}


def in_package(name, base):
    return name == base or name.startswith(base + ".")


def parse_source(name, text):
    m = PACKAGE.search(text)
    return name, (m.group(1) if m else ""), [i.group(1) for i in IMPORT.finditer(text)]


def layer_violations(source, base, forbidden, app_packages):
    name, package, imports = source
    folder = name.rsplit("/", 1)[0] if "/" in name else ""
    folder = (folder[len("java/"):] if folder.startswith("java/") else folder).replace("/", ".")
    if not in_package(package, base):
        placement = [f"Paket {package} statt {base}"]
    elif package != folder:
        placement = [f"Paket {package} passt nicht zum Ordner"]
    else:
        placement = []
    bad = [f"import {i}" for i in imports
           if any(in_package(i, f) for f in forbidden) or (in_package(i, APP) and not any(in_package(i, p) for p in app_packages))]
    return placement + bad


def test_methods(text):
    """`@Test` am Zeilenanfang (nach Einrückung), ohne Kommentare."""
    return len(TEST_ANNOTATION.findall(LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))))


def _layer(t, module, base, forbidden, app_packages, exceptions, source_set="main"):
    directory = need_dir(t.a(module, "src", source_set))
    files = files_under(directory, "kt")
    least = 100 if source_set == "main" else 50
    if len(files) <= least:
        return [f"{module}/src/{source_set}: nur {len(files)} Kotlin-Dateien gefunden"]
    found = {}
    for rel, path in files:
        v = layer_violations(parse_source(rel, read(path)), base, forbidden, app_packages)
        if v:
            found[rel] = v
    problems = [f":{module} ({source_set}) {name}: {'; '.join(v)}" for name, v in sorted(found.items()) if name not in exceptions]
    problems += [f"Ausnahme ohne Verstoss entfernen: {name}" for name in sorted(exceptions) if name not in found]
    return problems


# C-07: Oberfläche ohne :data. :coreui hängt nicht mehr an :data (auch nicht über `api`); in app/ui verweisen nur noch die
# Dateien aus UI_DATA_REMAINING auf das Paket data (Import oder voller Name) — die Liste darf nur kürzer werden.
UI_DATA_REMAINING = {
    "MainActivity.kt",
    "components/BulkAddProgress.kt",
    "components/BulkUndoConfirm.kt",
    "components/WatchlistLockedHint.kt",
    "components/WatchlistNotSavedGateHost.kt",
    "features/about/BatteryOptimizationDialog.kt",
    "features/about/LicensesSheet.kt",
    "features/alarms/AlarmCard.kt",
    "features/alarms/AlarmDeliveryScreen.kt",
    "features/alarms/AlarmDeliveryTexts.kt",
    "features/alarms/AlarmEditorDialogs.kt",
    "features/alarms/AlarmEditorSheet.kt",
    "features/alarms/AlarmLogScreen.kt",
    "features/alarms/AlarmReliabilityLine.kt",
    "features/alarms/AlarmUserChange.kt",
    "features/alarms/AlarmsOverviewCards.kt",
    "features/alarms/AlarmsOverviewViewModel.kt",
    "features/alarms/AlarmsViewModel.kt",
    "features/alarms/AlarmsViewModelTemplates.kt",
    "features/explorer/ExplorerBulkActions.kt",
    "features/explorer/ExplorerCollaborators.kt",
    "features/explorer/ExplorerFeedback.kt",
    "features/explorer/ExplorerSelectionActions.kt",
    "features/explorer/ExplorerViewModel.kt",
    "features/info/InfoViewModel.kt",
    "features/info/InfoWatchlist.kt",
    "features/info/MarketDataLoaders.kt",
    "features/info/MarketDataSync.kt",
    "features/info/MarketPhaseScreenParts.kt",
    "features/portfolio/CsvImportState.kt",
    "features/portfolio/PortfolioAddressActions.kt",
    "features/portfolio/PortfolioAddressRow.kt",
    "features/portfolio/PortfolioAddressSheet.kt",
    "features/portfolio/PortfolioCsvImportReader.kt",
    "features/portfolio/PortfolioCsvImportSave.kt",
    "features/portfolio/PortfolioCsvImportSaving.kt",
    "features/portfolio/PortfolioCsvImportViewModel.kt",
    "features/portfolio/PortfolioCutoffExport.kt",
    "features/portfolio/PortfolioEditActions.kt",
    "features/portfolio/PortfolioExportViewModel.kt",
    "features/portfolio/PortfolioHelpers.kt",
    "features/portfolio/PortfolioHistoryLoad.kt",
    "features/portfolio/PortfolioLoadActions.kt",
    "features/portfolio/PortfolioPriceSources.kt",
    "features/portfolio/PortfolioRestoreNoticeDialog.kt",
    "features/portfolio/PortfolioScreenDialogs.kt",
    "features/portfolio/PortfolioScreenEffects.kt",
    "features/portfolio/PortfolioTaxPrices.kt",
    "features/portfolio/PortfolioTxSheet.kt",
    "features/portfolio/PortfolioTxValueSections.kt",
    "features/portfolio/PortfolioViewModel.kt",
    "features/settings/AutoBackupViewModel.kt",
    "features/settings/BackupDialogs.kt",
    "features/settings/BackupResultPresentation.kt",
    "features/settings/SettingsAddressServer.kt",
    "features/settings/SettingsAlarmActions.kt",
    "features/settings/SettingsBackupActions.kt",
    "features/settings/SettingsDataPages.kt",
    "features/settings/SettingsDiagnostics.kt",
    "features/settings/SettingsGeneralPages.kt",
    "features/settings/SettingsPickers.kt",
    "features/settings/SettingsViewModel.kt",
    "features/settings/StorageRecoveryNoticeDialog.kt",
    "features/watchlist/WatchEditViewModel.kt",
    "features/watchlist/WatchSheetDexAddress.kt",
    "features/watchlist/WatchSparklines.kt",
    "features/watchlist/WatchlistBulkAdd.kt",
    "features/watchlist/WatchlistContentFlows.kt",
    "features/watchlist/WatchlistEditController.kt",
    "features/watchlist/WatchlistEditUndo.kt",
    "features/watchlist/WatchlistListItem.kt",
    "features/watchlist/WatchlistLiveFlows.kt",
    "features/watchlist/WatchlistRefreshController.kt",
    "features/watchlist/WatchlistRetiredNotice.kt",
    "features/watchlist/WatchlistRowParts.kt",
    "features/watchlist/WatchlistScreenEffects.kt",
    "features/watchlist/WatchlistSheetData.kt",
    "features/watchlist/WatchlistStarterController.kt",
    "features/watchlist/WatchlistUiState.kt",
    "features/watchlist/WatchlistViewModel.kt",
}


def ui_data_problems(t):
    problems = []
    coreui = read(need_file(t.a("coreui", "build.gradle.kts")))
    if 'project(":data")' in coreui:
        problems.append("coreui/build.gradle.kts hängt an :data (C-07: Oberflächen-Bausteine ohne :data)")
    for rel, path in files_under(t.a("coreui", "src", "main"), "kt"):
        if (DATA + ".") in read(path):
            problems.append(f"coreui/src/main/{rel} verweist auf {DATA}")
    ui = t.a("app", "src", "main", "java", "com", "cryptochecker", "app", "ui")
    found = {rel for rel, path in files_under(need_dir(ui), "kt") if (DATA + ".") in read(path)}
    problems += [f"app/ui/{rel} verweist neu auf {DATA} (C-07: über :domain-Schnittstellen gehen)"
                 for rel in sorted(found - UI_DATA_REMAINING)]
    problems += [f"app/ui/{rel} verweist nicht mehr auf {DATA} — aus UI_DATA_REMAINING streichen"
                 for rel in sorted(UI_DATA_REMAINING - found)]
    return problems


def rule_layers(t):
    """Schichtgrenzen am Quelltext (Paket = Modul): :domain ohne Room/OkHttp und ohne andere App-Pakete, :data nur mit
    :domain; :app ohne Pakete domain/data; kein Paket in zwei Modulen; Tests halten dieselben Regeln ein; der gemeinsame
    Test-Helfer liegt einmal in test-shared/java; kein Modul verliert still seine Tests (TEST_FLOOR)."""
    problems = _layer(t, "domain", DOMAIN, ["androidx.room", "okhttp3"], [DOMAIN], DOMAIN_EXCEPTIONS)
    problems += _layer(t, "data", DATA, [], [DATA, DOMAIN], DATA_EXCEPTIONS)
    problems += _layer(t, "domain", DOMAIN, ["androidx.room", "okhttp3"], [DOMAIN, TEST_SHARED], {}, "test")
    problems += _layer(t, "data", DATA, [], [DATA, DOMAIN, TEST_SHARED], {}, "test")
    app_main = need_dir(t.a("app", "src", "main"))
    for rel, path in files_under(app_main, "kt"):
        package = parse_source(rel, read(path))[1]
        if in_package(package, DOMAIN):
            problems.append(f"Paket {DOMAIN} gehört :domain, in :app liegt: {rel}")
        if in_package(package, DATA):
            problems.append(f"Paket {DATA} gehört :data, in :app liegt: {rel}")
    modules = sorted(d for d in os.listdir(t.android) if os.path.isdir(t.a(d, "src", "main")))
    if not {"app", "data", "domain", "marketdata", "wear"} <= set(modules):
        problems.append(f"Module: {modules}")
    by_package = {}
    for module in modules:
        for _, path in files_under(t.a(module, "src", "main"), "kt"):
            m = PACKAGE.search(read(path))
            if m:
                by_package.setdefault(m.group(1), set()).add(module)
    if len(by_package) <= 50:
        problems.append(f"nur {len(by_package)} Pakete gefunden")
    problems += [f"Paket in zwei Modulen (Paket = Modul): {name}: {sorted(owners)}"
                 for name, owners in sorted(by_package.items()) if len(owners) > 1]
    # Gemeinsamer Test-Helfer: genau eine Datei, Testquelle von :app, :domain und :data, keine Kopie
    shared = [f"test-shared/java/{rel}" for rel, _ in files_under(t.a("test-shared", "java"), "kt")]
    if shared != ["test-shared/java/com/cryptochecker/app/parity/ParityJson.kt"]:
        problems.append(f"test-shared/java enthält {shared}")
    elif parse_source("", read(t.a(shared[0])))[1] != TEST_SHARED:
        problems.append(f"ParityJson.kt nicht im Paket {TEST_SHARED}")
    for module in ("app", "domain", "data"):
        gradle = read(need_file(t.a(module, "build.gradle.kts")))
        if 'getByName("test") {\n            kotlin.directories.add("$rootDir/test-shared/java")' not in gradle:
            problems.append(f"{module}/build.gradle.kts bindet test-shared/java nicht als Testquelle ein")
        tests = files_under(t.a(module, "src", "test"), "kt")
        copies = [rel for rel, _ in tests if rel.endswith("/ParityJson.kt")]
        if copies:
            problems.append(f"eigene Kopie von ParityJson in :{module}: {copies}")
        if module != "app":
            in_shared = [rel for rel, path in tests if parse_source(rel, read(path))[1] == TEST_SHARED]
            if in_shared:
                problems.append(f"Tests im Paket {TEST_SHARED} in :{module}: {in_shared}")
    counts = {m: sum(test_methods(read(p)) for _, p in files_under(need_dir(t.a(m, "src", "test")), "kt")) for m in TEST_MODULES}
    lost = [f":{m} {counts[m]} < {floor}" for m, floor in TEST_FLOOR.items() if counts[m] < floor]
    if lost:
        problems.append(f"@Test-Methoden verloren (Ordner src/test des Moduls?): {lost} – gezählt: {counts}")
    return problems + ui_data_problems(t)


# --------------------------------------------------------------------------------------------------------------------
# dialog-scroll


DS_DIALOG = re.compile(r"(?<![\w])AlertDialog\(", A)
DS_TEXT_SLOT = re.compile(r"\btext\s*=\s*\{", A)
DS_TEXT_CALL = re.compile(r"(?<![\w])Text\(", A)
DS_COLUMN = re.compile(r"(?<![\w])Column\s*[({]", A)
DS_JOINED = re.compile(r'"\s*\+|\+\s*"|\+\s*(?:pluralS|s)tringResource\(')
DS_RESOURCE = re.compile(r"(?<![\w.])R\.(?:string|plurals)\.(\w+)", A)
DS_SENTENCE_BREAK = re.compile(r"[.!?][\"”»)]?\s+\S")
DS_SCROLLS = ("DialogText", "verticalScroll", "LazyColumn")


def ds_skip_char(code, start):
    """Ende (exklusiv) des Zeichen-Literals ab code[start]."""
    from_ = start + 3 if start + 1 < len(code) and code[start + 1] == "\\" else start + 2
    end = code.find("'", from_)
    return len(code) if end < 0 else end + 1


def ds_skip_string(code, start):
    """Ende (exklusiv) des String-Literals ab code[start] (`"` oder `\"\"\"`), samt `${…}`."""
    raw = code.startswith('"""', start)
    i = start + (3 if raw else 1)
    while i < len(code):
        if raw and code.startswith('"""', i):
            end = i + 3
            while end < len(code) and code[end] == '"':
                end += 1
            return end
        if not raw and code[i] == "\\":
            i += 2
        elif not raw and code[i] == '"':
            return i + 1
        elif code.startswith("${", i):
            c = ds_closing(code, i + 1)
            i = len(code) if c < 0 else c + 1
        else:
            i += 1
    return len(code)


def ds_closing(code, open_):
    """Index der schliessenden Klammer zu code[open_]; Strings und Zeichen werden übersprungen."""
    depth = 0
    i = open_
    while i < len(code):
        c = code[i]
        if c == '"':
            i = ds_skip_string(code, i)
            continue
        if c == "'":
            i = ds_skip_char(code, i)
            continue
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def ds_strip_comments(source):
    """Kommentare durch Leerzeichen ersetzen (Zeilenumbrüche bleiben, Strings unverändert)."""
    out = list(source)
    i = 0
    n = len(source)
    while i < n:
        if source[i] == '"':
            i = ds_skip_string(source, i)
            continue
        if source[i] == "'":
            i = ds_skip_char(source, i)
            continue
        if source.startswith("//", i):
            end = source.find("\n", i)
            end = n if end < 0 else end
            for k in range(i, end):
                out[k] = " "
            i = end
            continue
        if source.startswith("/*", i):
            depth = 0
            k = i
            while k < n:
                if source.startswith("/*", k):
                    depth += 1
                    k += 2
                    continue
                if source.startswith("*/", k):
                    k += 2
                    depth -= 1
                    if depth == 0:
                        break
                    continue
                k += 1
            for j in range(i, min(k, n)):
                if source[j] != "\n":
                    out[j] = " "
            i = k
            continue
        i += 1
    return "".join(out)


def ds_text_slot(code, open_, close):
    """Inhalt des Arguments `text = { … }` direkt in der Klammer open_..close, sonst None."""
    depth = 0
    i = open_ + 1
    while i < close:
        c = code[i]
        if c == '"':
            i = ds_skip_string(code, i)
            continue
        if c == "'":
            i = ds_skip_char(code, i)
            continue
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
        elif depth == 0 and c == "t" and not (code[i - 1].isalnum() or code[i - 1] == "_"):
            m = DS_TEXT_SLOT.match(code, i, close)
            if m:
                brace = m.end() - 1
                return code[brace + 1:ds_closing(code, brace)]
        i += 1
    return None


def ds_is_long(text):
    return "\\n" in text or DS_SENTENCE_BREAK.search(text) is not None


def ds_reason(slot, strings):
    """Warum der Slot scrollen muss, oder None."""
    if any(s in slot for s in DS_SCROLLS):
        return None
    names = [m.group(1) for m in DS_RESOURCE.finditer(slot)]
    texts = len(DS_TEXT_CALL.findall(slot))
    if texts > 1:
        return "mehrere Text("
    if DS_COLUMN.search(slot):
        return "Column ohne verticalScroll"
    if DS_JOINED.search(slot) or "\\n" in slot:
        return "zusammengesetzter Text (+ bzw. \\n)"
    if any(ds_is_long(text) for name in names for text in strings.get(name, [])):
        return "Ressource mit mehr als einem Satz"
    if texts == 1 and not names:
        return "Inhalt nicht prüfbar (keine Text-Ressource)"
    return None


def dialog_violations(source, strings):
    """Verstösse in [source] als «Zeile: Grund»; [strings]: Ressourcenname → Texte (Plural: alle Formen)."""
    code = ds_strip_comments(source)
    out = []
    for match in DS_DIALOG.finditer(code):
        open_ = match.end() - 1
        close = ds_closing(code, open_)
        if close < 0:
            continue
        slot = ds_text_slot(code, open_, close)
        if slot is None:
            continue
        reason = ds_reason(slot, strings)
        if reason:
            out.append(f"{line_of(code, match.start())}: {reason}")
    return out


def load_strings(values):
    """`<string>` und `<plurals>` (alle Formen) aus den XML-Dateien in `values/` (Englisch)."""
    result = {}
    single = re.compile(r'<string name="(\w+)"[^>]*>(.*?)</string>', re.S | A)
    plural = re.compile(r'<plurals name="(\w+)"[^>]*>(.*?)</plurals>', re.S | A)
    item = re.compile(r'<item quantity="\w+">(.*?)</item>', re.S | A)
    for name in sorted(os.listdir(values)):
        if name.endswith(".xml"):
            xml = read(os.path.join(values, name))
            for m in single.finditer(xml):
                result[m.group(1)] = [m.group(2)]
            for p in plural.finditer(xml):
                result[p.group(1)] = [i.group(1) for i in item.finditer(p.group(2))]
    return result


def rule_dialog_scroll(t):
    """Der text-Slot eines AlertDialog mit mehr als einem Satz scrollt (DialogText { … } bzw. verticalScroll), sonst schneidet
    Android ihn bei grosser Schrift ab und die Schaltflächen rutschen weg. Kurze einzeilige Ressourcen bleiben ohne."""
    strings = load_strings(need_dir(t.a("resources", "src", "main", "res", "values")))
    if len(strings) <= 100:
        return ["keine Text-Ressourcen gelesen"]
    problems = []
    dialogs = 0
    for module in ("app", "coreui"):
        for rel, path in files_under(need_dir(t.java(module)), "kt"):
            source = read(path)
            dialogs += len(DS_DIALOG.findall(ds_strip_comments(source)))
            problems += [f"{module}/{rel}:{v} — text = {{ DialogText {{ … }} }} nehmen" for v in dialog_violations(source, strings)]
    if dialogs <= 20:
        problems.append(f"nur {dialogs} AlertDialog-Aufrufe gefunden (liest der Wächter nichts?)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# write-consent: Rückfrage «nicht gespeichert» je Aufruf eines Schreibwegs von WatchRepository


WC_FUN = re.compile(
    r"(?m)^[ \t]*(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec)\s+)*"
    r"fun\s+(?:<[^>\n]*>\s*)?((?:[\w<>?]+\.)?)(\w+)\s*\(", A)
WC_CALL = re.compile(r"(?:\b(\w+)\s*\??\.\s*|(?<![\w.]))(\w+)\(", A)
WC_REFUSED = re.compile(r"userWriteRefused\(([^)]*)\)")
WC_TYPED = re.compile(r"\b(\w+)\s*:\s*([\w.]+)", A)
WC_CONFIRM = re.compile(r"confirmUserWrite\(\)")
WC_IF_HEAD = re.compile(r"\bif\s*\(\s*(!\s*)?(?:\w+\s*\??\.\s*)?$", A)
WC_NEGATED = re.compile(r"!\s*(?:\w+\s*\??\.\s*)?$", A)
WC_BLOCK_CALL = re.compile(r"(?<![\w.])block\(", A)
# Neue Coroutine mit Empfänger (`appScope.launch(…) {`, `GlobalScope.async {`, `CoroutineScope(…).launch {`)
WC_ESCAPE = re.compile(r"(?:\b\w+|\))\s*\??\.\s*(?:launch|async)\s*([({])", A)
WC_LAUNCH_WRITE = re.compile(r"(?<!fun )\blaunchWrite\s*([({])", A)
# Erlaubte Aufrufe ohne Rückfrage (Datei relativ zu `com/cryptochecker/app`, Schreibweg), nur mit `fromUser = false`:
# «1 h später» aus der Alarm-Mitteilung stellt die Kursmarke wieder scharf — Systemweg ohne Bildschirm.
WC_SYSTEM_CALLS = {("notification/AlarmActionReceiver.kt", "setAlarmEnabled")}
# Folge-Schreibwege: fragen nie und lehnen nie ab (`userWriteRefused(notify = false)`)
WC_SILENT = {"setNotifiedPrice", "setMissingPercentReferences"}


class Fn:
    def __init__(self, name, receiver, start, body_start, end):
        self.name, self.receiver, self.start, self.body_start, self.end = name, receiver, start, body_start, end


def wc_strip(text):
    """Kommentare durch Leerraum ersetzen (Positionen bleiben); Zeichenketten bleiben stehen, Kommentarzeichen darin
    öffnen keinen Kommentar; Blockkommentare verschachtelt wie in Kotlin."""
    out = list(text)
    n = len(text)
    i = 0

    def blank(start, to):
        for k in range(start, to):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
        elif text.startswith("/*", i):
            depth = 0
            k = i
            while k < n:
                if text.startswith("/*", k):
                    depth += 1
                    k += 2
                elif text.startswith("*/", k):
                    depth -= 1
                    k += 2
                    if depth == 0:
                        break
                else:
                    k += 1
            blank(i, min(k, n))
            i = k
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif text[i] in "\"'":
            quote = text[i]
            k = i + 1
            while k < n and text[k] != quote and text[k] != "\n":
                k += 2 if text[k] == "\\" else 1
            i = k + 1
        else:
            i += 1
    return "".join(out)


def wc_functions(text):
    found = list(WC_FUN.finditer(text))
    return [Fn(m.group(2), (m.group(1)[:-1] if m.group(1).endswith(".") else m.group(1)) or None, m.start(), m.end(),
               found[i + 1].start() if i + 1 < len(found) else len(text)) for i, m in enumerate(found)]


def wc_write_paths(repo):
    """(Schreibwege, stille Folge-Schreibwege) aus den Repository-Dateien."""
    writes, silent = set(), set()
    pairs = [(fn, text) for text in repo for fn in wc_functions(text)]
    for fn, text in pairs:
        if fn.name in ("userWriteRefused", "confirmUserWrite"):
            continue
        calls = [m.group(1) for m in WC_REFUSED.finditer(text[fn.body_start:fn.end])]
        if not calls:
            continue
        if all("notify = false" in c for c in calls):
            silent.add(fn.name)
        else:
            writes.add(fn.name)
    # Weiterleitungen (saveAlarm → trySaveAlarm, addWatch → tryAddWatch) zählen mit
    while True:
        added = {fn.name for fn, text in pairs if fn.name not in writes and fn.name not in silent and
                 any(re.search(r"(?<![\w])" + re.escape(w) + r"\(", text[fn.body_start:fn.end], A) for w in writes)}
        if not added:
            break
        writes |= added
    return writes, silent


def wc_matching(text, open_, a, b):
    depth = 0
    for i in range(open_, len(text)):
        if text[i] == a:
            depth += 1
        elif text[i] == b:
            depth -= 1
        if depth == 0:
            return i
    return len(text) - 1


def wc_skip_ws(text, from_):
    i = from_
    while i < len(text) and text[i].isspace():
        i += 1
    return i


def wc_is_word(text, at, word):
    def ident(c):
        return c.isalnum() or c == "_"
    return (text.startswith(word, at) and (at == 0 or not ident(text[at - 1])) and
            (at + len(word) >= len(text) or not ident(text[at + len(word)])))


def wc_statement_end(text, from_, stop_at_else):
    """Ende einer Anweisung: Zeilenende bzw. (mit stop_at_else) `else` auf oberster Ebene, oder eine Klammer zu."""
    depth = 0
    for i in range(from_, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif depth == 0 and (c == "\n" or (stop_at_else and wc_is_word(text, i, "else"))):
            return i
    return len(text)


def wc_never_below(text, from_, to, minimum, start=0):
    """Klammertiefe von from_ bis to fällt nie unter minimum (Start bei start)."""
    depth = start
    for i in range(from_, to):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        if depth < minimum:
            return False
    return True


def wc_has_top_level_or(text, from_, to):
    depth = 0
    for i in range(from_, min(to, len(text) - 1)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "|" and depth == 0 and text[i + 1] == "|":
            return True
    return False


def wc_escapes(text, from_, pos):
    """Beginnt zwischen der Zustimmung und dem Schreibweg ein Block `x.launch { … }`/`x.async { … }` mit Empfänger, der pos
    noch umschliesst? Dann läuft der Schreibweg losgelöst von der Coroutine der Aktion (ihre Zustimmung gilt dort nicht)."""
    if from_ >= pos:
        return False
    for m in WC_ESCAPE.finditer(text[from_:pos]):
        at = from_ + m.start(1)
        if text[at] == "(":
            at = wc_matching(text, at, "(", ")") + 1
            while at < len(text) and text[at].isspace():
                at += 1
        if at < pos and text[at] == "{" and wc_never_below(text, at + 1, pos, 1, start=1):
            return True
    return False


def wc_consent_covers(text, start, end, pos):
    """Liegt pos im Zweig NACH der Zustimmung des Aufrufs confirmUserWrite() (start bis end)? Nur die im Code benutzten
    Formen: `if (!c) return…` (danach, auch im else), `if (!c || write()) return…`, `if (!c) 0 else write()`,
    `if (c) write() else …`, `f(c && write())`; der Rest der Bedingung muss passen."""
    if pos < end:
        return False
    window = max(0, start - 200)
    head = WC_IF_HEAD.search(text[window:start])
    if head is None:
        if WC_NEGATED.search(text[window:start]):
            return False
        rest = wc_skip_ws(text, end)
        return (text.startswith("&&", rest) and pos < wc_statement_end(text, rest, False)
                and not wc_has_top_level_or(text, rest, pos))
    negated = head.group(1) is not None
    cond_open = text.find("(", window + head.start())
    cond_close = wc_matching(text, cond_open, "(", ")")
    rest_at = wc_skip_ws(text, end)
    if pos < cond_close:
        if negated:
            return text.startswith("||", rest_at)
        return text.startswith("&&", rest_at) and not wc_has_top_level_or(text, rest_at, pos)
    if rest_at >= cond_close:
        rest_ok = True
    elif negated:
        rest_ok = text.startswith("||", rest_at)
    else:
        rest_ok = text.startswith("&&", rest_at) and not wc_has_top_level_or(text, rest_at, cond_close)
    if not rest_ok:
        return False
    then_start = wc_skip_ws(text, cond_close + 1)
    if then_start < len(text) and text[then_start] == "{":
        then_end = wc_matching(text, then_start, "{", "}") + 1
    else:
        then_end = wc_statement_end(text, then_start, True)
    if pos < then_end:
        return not negated and pos >= then_start
    if not negated:
        return False
    body = text[then_start:then_end].strip()
    body = body[1:] if body.startswith("{") else body
    body = body[:-1] if body.endswith("}") else body
    exits = body.strip().replace("\r\n", "\n").replace("\r", "\n").split("\n")[-1].strip().startswith("return")
    stmt_end = then_end
    else_at = wc_skip_ws(text, then_end)
    if wc_is_word(text, else_at, "else"):
        else_start = wc_skip_ws(text, else_at + 4)
        if else_start < len(text) and text[else_start] == "{":
            stmt_end = wc_matching(text, else_start, "{", "}") + 1
        else:
            stmt_end = wc_statement_end(text, else_start, False)
        if pos < stmt_end:
            return True
    return exits and wc_never_below(text, stmt_end, pos, 0)


def wc_asks_before(text, from_, pos):
    part = text[from_:pos]
    for m in WC_CONFIRM.finditer(part):
        end = from_ + m.end()
        if wc_consent_covers(text, from_ + m.start(), end, pos) and not wc_escapes(text, end, pos):
            return True
    # launchWrite(…) { … }: Aufruf im Block
    for m in WC_LAUNCH_WRITE.finditer(part):
        at = from_ + m.start(1)
        if text[at] == "(":
            at = wc_matching(text, at, "(", ")") + 1
            while at < len(text) and text[at].isspace():
                at += 1
        # Aufruf in den Klammern (z. B. `cancelled = { … }`) liegt vor dem Block, gilt nicht
        if (at < len(text) and text[at] == "{" and at < pos and wc_never_below(text, at + 1, pos, 1, start=1)
                and not wc_escapes(text, at + 1, pos)):
            return True
    return False


def wc_launch_write_problems(text):
    """`launchWrite`-Funktionen, die ihren Block nicht nur nach der Zustimmung ausführen (Befund je Funktion)."""
    out = []
    for fn in wc_functions(text):
        if fn.name != "launchWrite":
            continue
        blocks = [b for b in WC_BLOCK_CALL.finditer(text[:fn.end]) if b.start() >= fn.body_start]

        def covered(b):
            for c in WC_CONFIRM.finditer(text[fn.body_start:b.start()]):
                end = fn.body_start + c.end()
                if wc_consent_covers(text, fn.body_start + c.start(), end, b.start()) and not wc_escapes(text, end, b.start()):
                    return True
            return False
        if not blocks or not all(covered(b) for b in blocks):
            out.append(f"launchWrite@{fn.start}")
    return out


WC_REPO_TYPES = ("WatchRepository", "WatchStore")


def wc_receivers(text):
    """Namen der Felder der Datei vom Typ WatchRepository bzw. WatchStore (Schnittstelle der Oberfläche, reicht an
    WatchRepository weiter) — sonst «watchRepository», wenn nicht anders getypt."""
    typed = [(m.group(1), m.group(2).rsplit(".", 1)[-1]) for m in WC_TYPED.finditer(text)]
    own = {n for n, ty in typed if ty in WC_REPO_TYPES}
    other = {n for n, ty in typed if ty not in WC_REPO_TYPES}
    return ({"watchRepository"} - other) | own


def wc_confirmed(sources, functions, file, pos, seen):
    """Liegt pos hinter einer noch offenen Rückfrage — oder fragen alle Aufrufer des umschliessenden Hilfswegs?"""
    text = sources[file]
    fns = [f for f in functions[file] if f.start <= pos]
    if not fns:
        return False
    fn = fns[-1]
    if pos >= fn.end:
        return False
    if wc_asks_before(text, fn.body_start, pos):
        return True
    if fn.name in seen or len(seen) > 4:
        return False
    call = re.compile(r"(?<![\w])" + re.escape(fn.name) + r"\(", A)
    sites = []
    for other, t in sources.items():
        decls = [(f.start, f.body_start) for f in functions[other]]
        sites += [(other, m.start()) for m in call.finditer(t) if not any(a <= m.start() < b for a, b in decls)]
    return bool(sites) and all(wc_confirmed(sources, functions, other, at, seen | {fn.name}) for other, at in sites)


def write_consent_check(sources, writes):
    """(Zahl der Aufrufe, Befunde «Datei:Zeile fun Name: Aufruf», ausgenommene Systemaufrufe)."""
    problems, exempted = [], set()
    calls = 0
    functions = {f: wc_functions(t) for f, t in sources.items()}
    for file, text in sources.items():
        receivers = wc_receivers(text)
        fns = functions[file]
        for m in WC_CALL.finditer(text):
            receiver = m.group(1) or ""
            method = m.group(2)
            if method not in writes:
                continue
            in_extension = not receiver and any(f.receiver == "WatchRepository" and f.body_start <= m.start() < f.end for f in fns)
            if not (receiver in receivers or in_extension):
                continue
            if any(f.start <= m.start() < f.body_start for f in fns):
                continue  # Deklaration
            calls += 1
            paren = m.end() - 1
            args = text[paren:wc_matching(text, paren, "(", ")") + 1]
            if "fromUser = false" in args and (file, method) in WC_SYSTEM_CALLS:
                exempted.add((file, method))
                continue
            if not wc_confirmed(sources, functions, file, m.start(), frozenset()):
                owner = [f.name for f in fns if f.start <= m.start()]
                problems.append(f"{file}:{line_of(text, m.start())} fun {owner[-1] if owner else None}: {m.group(0)}")
    return calls, problems, exempted


def rule_write_consent(t):
    """Jeder Aufruf eines Schreibwegs des Nutzers (Funktionen von WatchRepository mit userWriteRefused( samt Weiterleitungen)
    ausserhalb von data/ liegt in einer Funktion, die vorher confirmUserWrite() fragt (Block noch offen, richtige Richtung
    der Bedingung, nicht losgelöst in einem fremden Scope) bzw. im Block von launchWrite { … }; ein Hilfsweg ohne eigene
    Frage gilt, wenn jeder seiner Aufrufer fragt. Das Verhalten der Zustimmung selbst prüfen WatchlistWriteConsentTest und
    BulkAddRunnerTest; hier: dass JEDER Aufruf in Oberfläche, Mitteilungen und Widgets vorher fragt."""
    data = need_dir(os.path.join(t.java("data"), "data"))
    repo = [wc_strip(read(os.path.join(data, n))) for n in sorted(os.listdir(data))
            if n.startswith("WatchRepository") and n.endswith(".kt")]
    if not repo:
        return ["WatchRepository*.kt nicht gefunden"]
    writes, silent = wc_write_paths(repo)
    problems = []
    if silent != WC_SILENT:
        problems.append(f"Folge-Schreibwege {sorted(silent)}, erwartet {sorted(WC_SILENT)}")
    if not {"setFavorite", "tryAddWatch", "addWatch", "saveAlarm", "deleteAlarm"} <= writes:
        problems.append(f"Ableitung der Schreibwege greift nicht: {sorted(writes)}")
    sources = {}
    for module in ("app", "coreui", "widget"):
        for rel, path in files_under(need_dir(t.java(module)), "kt"):
            if not rel.startswith("data/"):
                sources[rel] = wc_strip(read(path))
    calls, found, exempted = write_consent_check(sources, writes)
    if calls < 20:
        problems.append(f"zu wenige Aufrufe gefunden ({calls}) — Scan defekt?")
    if exempted != WC_SYSTEM_CALLS:
        problems.append(f"Ausnahmeliste veraltet: genutzt {sorted(exempted)}, gelistet {sorted(WC_SYSTEM_CALLS)}")
    problems += [f"{p} — Schreibweg ohne Rückfrage «nicht gespeichert» davor" for p in found]
    launch_writes = 0
    for file, text in sources.items():
        launch_writes += sum(1 for f in wc_functions(text) if f.name == "launchWrite")
        problems += [f"{file}: {p} führt den Block nicht nur nach der Zustimmung aus" for p in wc_launch_write_problems(text)]
    if launch_writes == 0:
        problems.append("launchWrite nicht gefunden — Scan defekt?")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# write-order: Folgen eines Schreibwegs erst nach dem Schreiben, Rückfrage vor Sperren


# Schreibwege des Nutzers mit eindeutigem Ergebnis (false = abgelehnt, nichts geschrieben)
WO_BOOLEAN_WRITES = ("deleteWatch", "deleteWatches", "deleteAllWatches", "deleteWatchesWithoutAlarms", "setDisabled",
                     "deleteAlarm", "setNotificationEnabled", "setAlarmEnabled", "setTtsEnabled")
WO_DISCARDED = re.compile(r"(?:^|[{;])[ \t]*(\w+)\s*\.\s*(" + "|".join(WO_BOOLEAN_WRITES) + r")\(", re.M | A)
# Sperren und sichtbare Folgen, vor denen die Rückfrage «nicht gespeichert» kommt (ein offener Dialog hielte sie sonst)
WO_LOCK = re.compile(r"undoMutex\.withLock|withRefreshLock\s*\{|addMoments\.post")
WO_ASK = re.compile(r"launchWrite\s*[({]|confirmUserWrite\(\)")


def write_order_violations(file, text):
    """Befunde in einer (Kommentar-bereinigten) Datei: verworfenes Ergebnis eines Schreibwegs mit Ergebnis (Folgen liefen
    sonst auch nach «abgelehnt»), Sperre bzw. Folge vor der Rückfrage."""
    out = []
    receivers = wc_receivers(text)
    for m in WO_DISCARDED.finditer(text):
        if m.group(1) in receivers and (file, m.group(2)) not in WC_SYSTEM_CALLS:
            out.append(f"{file}:{line_of(text, m.start(2))}: Ergebnis von {m.group(2)}( verworfen — Folgen nur nach "
                       "dem Schreiben (if (!…) return)")
    for fn in wc_functions(text):
        body = text[fn.body_start:fn.end]
        lock = WO_LOCK.search(body)
        if lock:
            ask = WO_ASK.search(body)
            if ask is None or ask.start() > lock.start():
                out.append(f"{file}:{line_of(text, fn.body_start + lock.start())} fun {fn.name}: {lock.group(0)} vor der "
                           "Rückfrage (launchWrite bzw. confirmUserWrite() zuerst)")
    return out


def rule_write_order(t):
    """Schreibwege des Nutzers mit Ergebnis: Rückgängig, Mitteilungen und Protokoll laufen erst nach dem Schreiben (das
    Ergebnis wird nie verworfen); die Rückfrage «nicht gespeichert» kommt vor Sperren und sichtbaren Folgen. Das
    Verhalten (gesperrt: keine Folgen) prüfen WatchlistViewModelTest und WatchlistLockDeviceTest auf dem Gerät."""
    problems = []
    for module in ("app", "coreui", "widget"):
        for rel, path in files_under(need_dir(t.java(module)), "kt"):
            if not rel.startswith("data/"):
                problems += write_order_violations(rel, wc_strip(read(path)))
    return problems


# --------------------------------------------------------------------------------------------------------------------
# ios-write-consent: iOS-Gegenstück zu write-consent


IOS_FUNC = re.compile(r"(?m)^[ \t]*(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:private|fileprivate|internal|public|static|nonisolated|"
                      r"mutating|final)\s+)*func\s+(\w+)\s*[(<]")
IOS_WRITE_SERVICES = "App/Services"
# Hintergrund (Kurse, Live-Kurse, Laden): schreibt weiter, auch gesperrt — nie über die Sicherung
IOS_BACKGROUND_FILES = ("AppData+Refresh.swift", "AppData+Live.swift", "AppData.swift")


def _ios_arity(text, open_):
    """Zahl der Argumente bzw. Parameter in der Klammer bei text[open_] (Kommas auf oberster Ebene)."""
    close = ds_closing(text, open_)
    inner = text[open_ + 1:close if close >= 0 else len(text)]
    if not inner.strip():
        return 0
    depth, count = 0, 1
    for c in inner:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            count += 1
    return count


def ios_write_paths(texts):
    """{Schreibweg des Nutzers: Parameterzahlen}: Funktionen von AppData, deren Rumpf watchlistWriteRefused( enthält; bei
    gleichem Namen ohne Sicherung (z. B. Favoriten im Explorer mit zwei Parametern) zählt nur die Parameterzahl der
    gesicherten Fassung, die es ungesichert nicht gibt."""
    locked, unlocked = {}, {}
    for text in texts:
        found = list(IOS_FUNC.finditer(text))
        for i, f in enumerate(found):
            if f.group(1) == "watchlistWriteRefused":
                continue
            body = text[f.end():found[i + 1].start() if i + 1 < len(found) else len(text)]
            open_ = text.find("(", f.end() - 1)
            arity = _ios_arity(text, open_)
            (locked if "watchlistWriteRefused(" in body else unlocked).setdefault(f.group(1), set()).add(arity)
    return {n: arities - unlocked.get(n, set()) for n, arities in locked.items() if arities - unlocked.get(n, set())}


def ios_unconfirmed_calls(file, text, writes):
    """Aufrufe `data.<Schreibweg>(` (mit der Argumentzahl einer gesicherten Fassung) in einer Datei ohne
    withWatchlistConfirm."""
    if "withWatchlistConfirm" in text or not writes:
        return []
    call = re.compile(r"\bdata\s*\.\s*(" + "|".join(sorted(writes)) + r")\s*\(", A)
    return [f"{file}:{line_of(text, m.start())}: data.{m.group(1)}( ohne withWatchlistConfirm"
            for m in call.finditer(text) if _ios_arity(text, m.end() - 1) in writes[m.group(1)]]


def rule_ios_write_consent(t):
    """iOS: Jeder Bildschirm, der einen Schreibweg des Nutzers von AppData aufruft (Funktionen mit watchlistWriteRefused(),
    hergeleitet), fragt vorher über withWatchlistConfirm; der Schreibweg selbst fragt nicht und führt nichts erneut aus
    (kein retry); Hintergrund-Dateien nutzen die Sicherung nie; die Rückfrage erscheint über allem (kein .alert im
    Hinweis). Das Verhalten prüfen WatchlistLockBehaviorTests und WatchlistConsentBehaviorTests."""
    services = need_dir(t.i("App", "Services"))
    names = sorted(n for n in os.listdir(services) if n.startswith("AppData") and n.endswith(".swift"))
    texts = {n: read(os.path.join(services, n)) for n in names}
    writes = ios_write_paths(texts.values())
    problems = []
    if not {"changePair", "deleteAlarm", "setNotificationEnabled", "tryAddWatch"} <= set(writes):
        problems.append(f"Ableitung der Schreibwege greift nicht: {sorted(writes)}")
    for rel, path in files_under(need_dir(t.i("App", "Features")), "swift"):
        problems += ios_unconfirmed_calls(f"App/Features/{rel}", read(path), writes)
    for name in IOS_BACKGROUND_FILES:
        if "watchlistWriteRefused(" in texts.get(name, ""):
            problems.append(f"App/Services/{name}: Hintergrund darf die Sicherung nicht nutzen")
    lock = texts.get("AppData+WatchlistLock.swift", "")
    if "private func confirmWatchlistWrite() async -> Bool" not in lock:
        problems.append("AppData+WatchlistLock.swift: confirmWatchlistWrite ist nicht privat (nur über withWatchlistConfirm)")
    for name, text in texts.items():
        if "retry:" in text or (name == "AppData+WatchlistLock.swift" and "retry" in text):
            problems.append(f"App/Services/{name}: Schreibweg mit retry (führt nach «Trotzdem» erneut aus)")
    hint = read(need_file(t.i("App", "Components", "WatchlistLockedHint.swift")))
    if ".alert(" in hint:
        problems.append("WatchlistLockedHint.swift: Rückfrage als .alert (erscheint unter einem Blatt nicht)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# settings-write, compose-stability, bounded-caches


SW_WRITE = re.compile(r"settingsRepository\.(set|record|update|confirm)[A-Za-z]*\(")


def rule_settings_write(t):
    """Einstellungen aus anderen Bildschirmen als «Einstellungen» schreiben über settingsRepository.guarded { … } — bei vollem
    Speicher ein Hinweis statt Absturz (DataStore wirft eine IOException). «Einstellungen» schreibt über
    SettingsViewModel.update, ausser dem eigenen ViewModel des Bitcoin-Servers."""
    ui = need_dir(os.path.join(t.java("app"), "ui"))
    problems = []
    guarded = 0
    for rel, path in files_under(ui, "kt"):
        text = read(path)
        if "settingsRepository.guarded {" in text:
            guarded += 1
        if rel.startswith("features/settings/") and os.path.basename(rel) != "SettingsAddressServer.kt":
            continue
        for i, line in enumerate(text.split("\n")):
            if SW_WRITE.search(line) and "runCatchingCancellable" not in line and "safe {" not in line:
                problems.append(f"{os.path.basename(rel)}:{i + 1}: {line.strip()} — über settingsRepository.guarded {{ … }} schreiben")
    if guarded < 11:
        problems.append(f"nur {guarded} Dateien mit settingsRepository.guarded {{ — Scan defekt?")
    return problems


CS_EXPECTED = [
    "com.cryptochecker.app.domain.model.AlarmEntity",
    "com.cryptochecker.app.domain.model.ExchangeInfo",
    "com.cryptochecker.app.domain.model.ExchangePairsInfo",
    "com.cryptochecker.app.domain.model.WatchEntity",
    "com.cryptochecker.marketdata.model.ExchangePair",
    "com.cryptochecker.marketdata.model.PairQuery",
    "com.cryptochecker.marketdata.model.PostRequest",
]
CS_VAR = re.compile(r"\bvar\s")
CS_MUTABLE = re.compile(r"\b(?:Mutable\w+|Array(?:List)?|\w*HashMap|\w*HashSet|StringBuilder|IntArray|DoubleArray|LongArray)\b", A)


def cs_constructor(code, open_):
    depth = 0
    for index in range(open_, len(code)):
        if code[index] == "(":
            depth += 1
        elif code[index] == ")":
            depth -= 1
            if depth == 0:
                return code[open_ + 1:index]
    return code[open_ + 1:]


def rule_compose_stability(t):
    """gradle/compose-stability.conf: Modelle aus :domain und :marketdata, die Compose als stabil behandeln darf — nur
    solange sie unveränderlich sind (kein var, keine veränderliche Sammlung im Konstruktor); eingebunden in :app."""
    problems = []
    gradle = read(need_file(t.a("app", "build.gradle.kts")))
    if 'stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("gradle/compose-stability.conf"))' not in gradle:
        problems.append("app/build.gradle.kts bindet gradle/compose-stability.conf nicht ein")
    entries = [line.strip() for line in read(need_file(t.a("gradle", "compose-stability.conf"))).split("\n")
               if line.strip() and not line.strip().startswith("//")]
    if entries != CS_EXPECTED:
        problems.append(f"Einträge {entries}, erwartet {CS_EXPECTED}")
    for entry in entries:
        package, name = entry.rsplit(".", 1)
        relative = package.replace(".", "/") + f"/{name}.kt"
        file = next((p for p in (t.a(m, "src", "main", "java", relative) for m in ("domain", "marketdata")) if os.path.isfile(p)), None)
        if file is None:
            problems.append(f"{entry}: Quelldatei fehlt (umbenannt? Eintrag in gradle/compose-stability.conf anpassen)")
            continue
        code = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", read(file)))
        if CS_VAR.search(code):
            problems.append(f"{entry}: `var` in {os.path.basename(file)} — nicht mehr unveränderlich")
        head = re.search(r"\bclass\s+" + re.escape(name) + r"\s*\(", code, A)
        if not head:
            problems.append(f"{entry}: Klasse mit Konstruktor nicht gefunden")
        elif CS_MUTABLE.search(cs_constructor(code, head.end() - 1)):
            problems.append(f"{entry}: veränderlicher Typ im Konstruktor")
    # Gegenprobe an den bewusst nicht gelisteten Klassen: der Prüfer erkennt `var` und veränderliche Typen
    model = t.a("marketdata", "src", "main", "java", "com", "cryptochecker", "marketdata", "model")
    if not CS_VAR.search(read(need_file(os.path.join(model, "SimpleTicker.kt")))):
        problems.append("Gegenprobe: SimpleTicker.kt ohne var — Prüfer prüfen")
    alias = [l for l in read(need_file(os.path.join(model, "Exchange.kt"))).split("\n") if l.startswith("typealias CurrencyPairsMap")]
    if not alias or not CS_MUTABLE.search(alias[0]):
        problems.append("Gegenprobe: typealias CurrencyPairsMap ohne veränderlichen Typ — Prüfer prüfen")
    return problems


# Datei (Modul, Pfad relativ zu `com/cryptochecker/app`) → nimmt BoundedCache statt einer unbegrenzten ConcurrentHashMap
BC_FILES = [
    ("data", "data/remote/VolumeDataSource.kt"), ("data", "data/remote/NearExtremeDataSource.kt"),
    ("data", "data/portfolio/PortfolioHistorySource.kt"), ("data", "data/alarm/DerivativesAlarmData.kt"),
    ("domain", "domain/watch/SheetChartCache.kt"), ("widget", "widget/SingleWidgetRenderer.kt"),
]


def rule_bounded_caches(t):
    """Zwischenspeicher je Paar bzw. Coin im Arbeitsspeicher bleiben begrenzt (BoundedCache, MEMORY_CACHE_ENTRIES)."""
    problems = []
    for module, rel in BC_FILES:
        text = read(need_file(os.path.join(t.java(module), rel)))
        if "ConcurrentHashMap" in text:
            problems.append(f"{module}/{rel}: ConcurrentHashMap statt BoundedCache")
        if "BoundedCache" not in text:
            problems.append(f"{module}/{rel}: BoundedCache fehlt")
    historic = read(need_file(os.path.join(t.java("data"), "data", "portfolio", "HistoricPriceSource.kt")))
    if "routeMemo = BoundedCache" not in historic:
        problems.append("HistoricPriceSource.kt: routeMemo = BoundedCache fehlt")
    # Eigene Kerzen (die Pausen je Host bleiben eine kleine Map, begrenzt durch die Zahl der Börsen)
    own = read(need_file(os.path.join(t.java("data"), "data", "remote", "candles", "OwnCandleDataSource.kt")))
    if "cache = BoundedCache<String, Entry>(MEMORY_CACHE_ENTRIES)" not in own or "cache = ConcurrentHashMap" in own:
        problems.append("OwnCandleDataSource.kt: cache = BoundedCache<String, Entry>(MEMORY_CACHE_ENTRIES) erwartet")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# ios-widget-offline: jede Timeline eines iOS-Widgets fragt das Netz ab («Keine Verbindung · Stand …», WidgetOffline).
# Android prüft dasselbe als Verhalten auf dem Gerät (WidgetDrawDeviceTest); die Erweiterung hat keinen Test-Host.


WO_PROVIDER = re.compile(r"\b(?:struct|final class|class)\s+\w+\s*:\s*[^{]*\b(?:AppIntent)?TimelineProvider\b")
WO_ASKS = "WidgetOffline.isOffline()"
WO_MIN_PROVIDERS = 4


def widget_offline_missing(sources):
    """Dateien mit einer Timeline (TimelineProvider), die das Netz nicht abfragen; dazu die Zahl der Timelines."""
    missing, providers = [], 0
    for name, text in sources:
        code = ds_strip_comments(text)
        found = len(WO_PROVIDER.findall(code))
        providers += found
        if found and WO_ASKS not in code:
            missing.append(name)
    return missing, providers


def rule_ios_widget_offline(t):
    widgets = need_dir(t.i("Widgets"))
    sources = [(rel, read(path)) for rel, path in files_under(widgets, "swift")]
    missing, providers = widget_offline_missing(sources)
    problems = [f"ios/Widgets/{name}: Timeline ohne {WO_ASKS} (ohne Netz «Keine Verbindung · Stand …»)" for name in missing]
    if providers < WO_MIN_PROVIDERS:
        problems.append(f"nur {providers} Timelines erkannt (mindestens {WO_MIN_PROVIDERS}: Merkliste, Einzel, Portfolio, Puls)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# swipe-delete: Wischen zum Löschen wie iOS — Positionen, Transaktionen, Alarme, Adressen immer mit Rückfrage.
# Die Merkliste löscht ohne Frage, bietet aber «Rückgängig» (WatchlistViewModel.deleteWithUndo).


SD_CALL = "SwipeActionsRow("
SD_CONFIRM = re.compile(r"\bconfirmsDelete\s*=\s*true\b")
SD_UNDO_FILES = {"ui/features/watchlist/WatchlistListItem.kt"}
SD_MIN_CONFIRMED = 4


def swipe_calls(text):
    """Argumente jedes Aufrufs SwipeActionsRow(...) (ohne die Deklaration), mit Zeile."""
    code = ds_strip_comments(text)
    out = []
    start = 0
    while True:
        i = code.find(SD_CALL, start)
        if i < 0:
            return out
        start = i + len(SD_CALL)
        if re.search(r"fun\s+$", code[max(0, i - 8):i]):
            continue
        close = ds_closing(code, i + len(SD_CALL) - 1)
        out.append((line_of(code, i), code[i:close if close >= 0 else len(code)]))


def rule_swipe_delete(t):
    problems, confirmed, undo = [], 0, 0
    base = t.java("app")
    for rel, path in files_under(base, "kt"):
        rel = rel.replace(os.sep, "/")
        for line, args in swipe_calls(read(path)):
            if rel in SD_UNDO_FILES:
                undo += 1
            elif SD_CONFIRM.search(args):
                confirmed += 1
            else:
                problems.append(f"app/{rel}:{line}: Wischen zum Löschen ohne confirmsDelete = true (Rückfrage wie iOS)")
    if confirmed < SD_MIN_CONFIRMED:
        problems.append(f"nur {confirmed} Listen mit Rückfrage erkannt (mindestens {SD_MIN_CONFIRMED}: Positionen, Transaktionen, Alarme, Adressen)")
    if undo != len(SD_UNDO_FILES):
        problems.append(f"Merkliste: {undo} Aufrufe in {sorted(SD_UNDO_FILES)} (erwartet einer, mit «Rückgängig»)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# ios-alarm-create: Doppeltipp auf «Speichern» legt einen Alarm an — jedes Blatt, das Alarme anlegt (struct …Alarm…Sheet),
# geht über AlarmCreateGuard (begin vor dem Anlegen). Android prüft dasselbe als Verhalten (PortfolioAlarmDoubleTapTest).


AC_SHEET = re.compile(r"\bstruct\s+\w*Alarm\w*Sheet\s*:\s*View\b")
AC_GUARD = "createGuard.begin("
AC_MIN_SHEETS = 2


def alarm_create_unguarded(sources):
    """Dateien mit einem Alarm-Blatt ohne AlarmCreateGuard; dazu die Zahl der Alarm-Blätter."""
    missing, sheets = [], 0
    for name, text in sources:
        code = ds_strip_comments(text)
        found = len(AC_SHEET.findall(code))
        sheets += found
        if found and AC_GUARD not in code:
            missing.append(name)
    return missing, sheets


def rule_ios_alarm_create(t):
    features = need_dir(t.i("App", "Features"))
    sources = [(rel, read(path)) for rel, path in files_under(features, "swift")]
    missing, sheets = alarm_create_unguarded(sources)
    problems = [f"ios/App/Features/{name}: Alarm-Blatt ohne {AC_GUARD}…) (Doppeltipp = zwei Alarme)" for name in missing]
    if sheets < AC_MIN_SHEETS:
        problems.append(f"nur {sheets} Alarm-Blätter erkannt (mindestens {AC_MIN_SHEETS}: Paar- und Portfolio-Alarm)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# bulk-undo: «Rückgängig» nach «Mehrere Paare auf einmal» entfernt erst nach der Rückfrage — der Undo-Weg wird nur aus
# «Entfernen» der Rückfrage gerufen (Android onRemove von BulkUndoConfirmDialog, iOS bulkUndo.remove). Die Rückfrage
# selbst ist Verhalten (BulkUndoConfirmTest, BulkUndoConfirmTests).


BU_KOTLIN = re.compile(r"\brunner\.undo\(|\bonUndoBulk\b(?!\s*[:=])")
BU_KOTLIN_OK = re.compile(r"onRemove\s*=\s*(?:\{[^{}]*)?$")
BU_SWIFT = re.compile(r"(?<!func )\bundoBulkAdd\(")
BU_SWIFT_SCOPE = 600


def bulk_undo_kotlin(name, text):
    code = ds_strip_comments(text)
    found, bad = 0, []
    for m in BU_KOTLIN.finditer(code):
        found += 1
        line_start = code.rfind("\n", 0, m.start()) + 1
        if not BU_KOTLIN_OK.search(code[line_start:m.start()]):
            bad.append(f"{name}:{line_of(code, m.start())}")
    return found, bad


def bulk_undo_swift(name, text):
    code = ds_strip_comments(text)
    found, bad = 0, []
    for m in BU_SWIFT.finditer(code):
        found += 1
        if "bulkUndo.remove(" not in code[max(0, m.start() - BU_SWIFT_SCOPE):m.start()]:
            bad.append(f"{name}:{line_of(code, m.start())}")
    return found, bad


def rule_bulk_undo(t):
    problems, kotlin, swift = [], 0, 0
    for rel, path in files_under(os.path.join(t.java("app"), "ui"), "kt"):
        found, bad = bulk_undo_kotlin("app/ui/" + rel.replace(os.sep, "/"), read(path))
        kotlin += found
        problems += [f"{b}: Undo-Weg ausserhalb von onRemove der Rückfrage (BulkUndoConfirmDialog)" for b in bad]
    for rel, path in files_under(t.i("App", "Features"), "swift"):
        found, bad = bulk_undo_swift("ios/App/Features/" + rel.replace(os.sep, "/"), read(path))
        swift += found
        problems += [f"{b}: undoBulkAdd ausserhalb von bulkUndo.remove (Rückfrage)" for b in bad]
    if kotlin < 2:
        problems.append(f"Android: nur {kotlin} Undo-Wege erkannt (mindestens 2: Coin-Suche, Merkliste)")
    if swift < 1:
        problems.append("iOS: kein Undo-Weg erkannt (Coin-Suche)")
    return problems


# --------------------------------------------------------------------------------------------------------------------
# app-start: das Kurs-Overlay startet mit jedem Prozessstart (auch dem nächsten Hintergrund-Lauf) — darum sagt seine
# Einstellung, dass es nach dem Ende der App mit der nächsten Aktualisierung zurückkommt. Gerätetests laufen unter
# HiltTestApplication (ohne CryptoCheckerApp.onCreate): darum eine Regel.


AS_START = "priceOverlay.get().start()"
AS_HINT = "R.string.settings_price_overlay_restart_hint"


def app_start_problems(app_text, row_text):
    problems = []
    code = ds_strip_comments(app_text)
    body = code[code.find("override fun onCreate()"):] if "override fun onCreate()" in code else ""
    if AS_START not in body:
        problems.append(f"CryptoCheckerApp.onCreate: {AS_START} fehlt (Overlay käme nach dem Ende der App nicht zurück)")
    if AS_HINT not in ds_strip_comments(row_text):
        problems.append(f"SettingsPriceOverlay.kt: Hinweis {AS_HINT} fehlt")
    return problems


def rule_app_start(t):
    app = read(need_file(os.path.join(t.java("app"), "CryptoCheckerApp.kt")))
    row = read(need_file(os.path.join(t.java("app"), "ui", "features", "settings", "SettingsPriceOverlay.kt")))
    return app_start_problems(app, row)


# --------------------------------------------------------------------------------------------------------------------
# settings-anchors: jeder Treffer der Suche in den Einstellungen hat seinen Anker auf der Seite (sonst öffnete ein Treffer
# die Seite, ohne zum Punkt zu scrollen), auf beiden Plattformen; «Damit Alarme ankommen», «Diagnose teilen»,
# «Alarm-Protokoll», «Pünktlich prüfen» und «Letzte Prüfungen» sind auffindbar. Die Suche selbst ist Composable bzw.
# SwiftUI und ohne Gerät nicht aufrufbar; die Zuordnung Seite ↔ Startseite prüft SettingsPlacementTest als Verhalten.


SA_KOTLIN_TARGET = re.compile(r'SettingsSearchTarget\.\w+\((?:SettingsPage\.\w+,\s*)?"([^"]+)"\)')
SA_SWIFT_TARGET = re.compile(r'anchor:\s*"([^"]+)"|\.main\("([^"]+)"\)')
SA_MIN_TARGETS = 30
# Katalog der Suche (Android), nach Bereichen auf Dateien verteilt
SA_CATALOG_FILES = ("SettingsSearchCatalog.kt", "SettingsSearchAppearance.kt", "SettingsSearchAlarms.kt", "SettingsSearchMore.kt")
# Pflicht-Einträge: Kennung → Anker, zu dem der Treffer führt (Android: Seite und Anker; iOS: Kennung als Ziel)
SA_REQUIRED_ANDROID = {
    "alarms.delivery": ("ALARMS", "alarms.delivery"),
    "about.diagnostics": ("ABOUT", "about.diagnostics"),
    "updates.alarm_log": ("UPDATES", "updates.check_runs"),
    "updates.exact": ("UPDATES", "updates.exact"),
    "updates.check_runs": ("UPDATES", "updates.check_runs"),
}
SA_REQUIRED_IOS_TARGETS = ("alarms.delivery", "about.diagnostics", "updates.check_runs")
SA_REQUIRED_IOS_ENTRIES = ("updates.alarm_log", "updates.check_runs")


def anchors_missing(targets, sources, marker):
    """Ziele ohne Aufruf [marker]…) mit ihrer Kennung in den Argumenten (Kommentare zählen nicht, Umbrüche egal)."""
    args = []
    for text in sources:
        code = squash(ds_strip_comments(text))
        start = code.find(marker)
        while start >= 0:
            i, depth = start + len(marker), 1
            while i < len(code) and depth:
                depth += {"(": 1, ")": -1}.get(code[i], 0)
                i += 1
            args.append(code[start:i])
            start = code.find(marker, i)
    return sorted(t for t in targets if not any(f'"{t}"' in a for a in args))


def settings_anchor_problems_android(catalog, sources):
    code = ds_strip_comments(catalog)
    targets = set(SA_KOTLIN_TARGET.findall(code))
    problems = []
    if len(targets) <= SA_MIN_TARGETS:
        problems.append(f"Android: nur {len(targets)} Suchziele erkannt (mehr als {SA_MIN_TARGETS} erwartet)")
    problems += [f"Android: Suchziel {t} ohne SettingsAnchor" for t in anchors_missing(targets, sources, "SettingsAnchor(")]
    flat = squash(code)
    for entry, (page, anchor) in SA_REQUIRED_ANDROID.items():
        pattern = re.compile(r'add\("' + re.escape(entry) + r'",(?:(?!add\().)*?SettingsSearchTarget\.Page\(SettingsPage\.'
                             + page + r',"' + re.escape(anchor) + r'"\)')
        if not pattern.search(flat):
            problems.append(f"Android: Sucheintrag {entry} führt nicht zu {page}/{anchor}")
    return problems


def settings_anchor_problems_ios(search, sources):
    code = ds_strip_comments(search)
    targets = {a or b for a, b in SA_SWIFT_TARGET.findall(code)}
    problems = [f"iOS: Suchziel {t} fehlt" for t in SA_REQUIRED_IOS_TARGETS if t not in targets]
    problems += [f"iOS: Sucheintrag {e} fehlt" for e in SA_REQUIRED_IOS_ENTRIES if f'add("{e}"' not in code]
    problems += [f"iOS: Suchziel {t} ohne .settingsAnchor" for t in anchors_missing(targets, sources, ".settingsAnchor(")]
    return problems


def rule_settings_anchors(t):
    app = t.java("app")
    catalog = "\n".join(read(need_file(os.path.join(app, "ui", "features", "settings", name))) for name in SA_CATALOG_FILES)
    problems = settings_anchor_problems_android(catalog, [read(f) for _, f in files_under(app, "kt")])
    ios_app = need_dir(t.i("App"))
    search = "\n".join(read(need_file(t.i("App", "Features", "Settings", n)))
                       for n in ("SettingsSearchUi.swift", "SettingsSearchCatalog.swift"))
    return problems + settings_anchor_problems_ios(search, [read(f) for _, f in files_under(ios_app, "swift")])


# --------------------------------------------------------------------------------------------------------------------
# permission-event: Einstellungen › Alarme › Preis-Paare fragt nach der Mitteilungs-Erlaubnis über ein Ereignis für den
# aktuellen Bildschirm (askPermissionAfterWrite + CollectScreenEvents), nie aus einem Rückruf in die alte Komposition.
# Das Ereignis selbst ist Verhalten (SettingsUiEventsTest).


PE_DIRECT = re.compile(r"written\s*->[^}]*requestNotifications\(\)")
PE_ASK = re.compile(r"askPermissionAfterWrite\(uiEvents\.requestNotifications")
PE_COLLECT = re.compile(r"CollectScreenEvents\(uiEvents\.requestNotifications\)")


def permission_event_problems(page):
    code = ds_strip_comments(page)
    problems = []
    if PE_DIRECT.search(code):
        problems.append("SettingsAlarmPages.kt: Erlaubnis-Abfrage direkt im Rückruf nach dem Schreiben")
    if len(PE_ASK.findall(code)) != 2:
        problems.append(f"SettingsAlarmPages.kt: {len(PE_ASK.findall(code))}× askPermissionAfterWrite (2 erwartet)")
    if len(PE_COLLECT.findall(code)) != 1:
        problems.append("SettingsAlarmPages.kt: CollectScreenEvents(uiEvents.requestNotifications) nicht genau einmal")
    return problems


def rule_permission_event(t):
    return permission_event_problems(read(need_file(os.path.join(t.java("app"), "ui", "features", "settings", "SettingsAlarmPages.kt"))))


# --------------------------------------------------------------------------------------------------------------------
# missing-logos: die Zeile «2 Coins ohne Logo» steht unter dem Lade-Hinweis der Merkliste (an seiner Stelle, nicht
# daneben), die Prüfung läuft auf iOS im Hintergrund des Kopfs mit; gemerkte Coins liegen in der Einstellungsdatei der
# Logos (Android, nicht in der Systemsicherung) bzw. in der App-Gruppe (iOS). Zeile, ✕ und Zustände sind Verhalten
# (MissingLogosHintTest, MissingLogosModelTests).


ML_ANDROID = (("WatchlistLogoHint(reduceMotion = reduceMotion)", "WatchlistMissingLogos(watches, reduceMotion = reduceMotion)"),)
ML_IOS = (("WatchlistLogoHint()", "WatchlistMissingLogos()"),)
ML_IOS_CHECK = ".background { WatchlistMissingLogosCheck(watches: data.watches) }"
ML_ANDROID_STORE = "private val prefs by lazy { CoinLogoStorage.prefs(context) }"
ML_IOS_STORE = "SharedStorage.defaults.set(MissingLogoNotice.encode(keys), forKey: Self.key)"


def missing_logo_problems(body, store, header, ios_store):
    problems = []
    for name, text, pairs in (("WatchlistScreenBody.kt", body, ML_ANDROID), ("WatchlistHeader.swift", header, ML_IOS)):
        code = ds_strip_comments(text)
        for first, second in pairs:
            a, b = code.find(first), code.find(second)
            if a < 0 or b < 0 or b < a:
                problems.append(f"{name}: {second} fehlt oder steht nicht unter {first}")
    if ML_IOS_CHECK not in ds_strip_comments(header):
        problems.append(f"WatchlistHeader.swift: {ML_IOS_CHECK} fehlt (Prüfung läuft nicht mit)")
    if ML_ANDROID_STORE not in ds_strip_comments(store):
        problems.append("MissingLogoDismissals.kt: gemerkte Coins nicht in CoinLogoStorage.prefs")
    if ML_IOS_STORE not in ds_strip_comments(ios_store):
        problems.append("WatchlistMissingLogos.swift: gemerkte Coins nicht in SharedStorage.defaults")
    return problems


def rule_missing_logos(t):
    watch = os.path.join(t.java("app"), "ui", "features", "watchlist")
    body = read(need_file(os.path.join(watch, "WatchlistScreenBody.kt")))
    store = read(need_file(os.path.join(t.java("data"), "data", "logos", "MissingLogoDismissals.kt")))
    header = read(need_file(t.i("App", "Features", "Watchlist", "WatchlistHeader.swift")))
    ios_store = read(need_file(t.i("App", "Features", "Watchlist", "WatchlistMissingLogos.swift")))
    return missing_logo_problems(body, store, header, ios_store)


# --------------------------------------------------------------------------------------------------------------------
# ios-lot-delete: «Löschen» eines Postens einer Adresse fragt auf iOS erst nach («Posten löschen?»). Android prüft dasselbe
# als Verhalten (AddressLotEditorDeviceTest); das iOS-Blatt ist ohne Netz (Adresse mit Guthaben) nicht erreichbar.


LD_BUTTON = 'Button(L("action_delete"), role: .destructive) { askDelete = true }'
LD_ALERT = '.alert(L("address_lots_delete_title"), isPresented: $askDelete)'


def lot_delete_problems(sheet):
    code = ds_strip_comments(sheet)
    return [f"PortfolioAddressLotsSheet.swift: {part} fehlt (Löschen ohne Rückfrage?)" for part in (LD_BUTTON, LD_ALERT)
            if part not in code]


# Android: welches Blatt offen ist (Erfassen der Coin-Seite, Posten-Liste, Posten im Editor), übersteht das Neu-Erzeugen;
# die Eingaben im Editor selbst prüft AddressLotEditorDeviceTest.
LD_SAVEABLE = {
    "PortfolioDetailScreen.kt": ("var sheet by rememberSaveable(stateSaver = TxDraftSaver) { mutableStateOf<TxDraft?>(null) }",
                                 "var lotsFor by rememberSaveable { mutableStateOf<Long?>(null) }"),
    "PortfolioAddressSheet.kt": ("var lotsFor by rememberSaveable { mutableStateOf<Long?>(null) }",),
    "PortfolioAddressLotsSheet.kt": ("var editingId by rememberSaveable { mutableStateOf<Long?>(null) }",),
}


def lot_sheet_problems(sources):
    return [f"{name}: {line} fehlt (Blatt ginge beim Drehen zu)" for name, lines in LD_SAVEABLE.items()
            for line in lines if line not in ds_strip_comments(sources.get(name, ""))]


def rule_ios_lot_delete(t):
    portfolio = os.path.join(t.java("app"), "ui", "features", "portfolio")
    sources = {name: read(need_file(os.path.join(portfolio, name))) for name in LD_SAVEABLE}
    return (lot_delete_problems(read(need_file(t.i("App", "Features", "Portfolio", "PortfolioAddressLotsSheet.swift"))))
            + lot_sheet_problems(sources))


# --------------------------------------------------------------------------------------------------------------------
# wiring: Anbindungen, die nur der zusammengesetzte Ablauf zeigt (Hilt, Wecker, Sicherung) — je eine feste Stelle:
#  - Wiederherstellen schreibt unter der Sperre der Adress-Aktualisierung, Fehler mit eigener Meldung (RestoreRun);
#    Zurückrollen ebenso (Adressen und eigener Server in getrennten Schritten). Meldung selbst: RestoreStorageMessageTest.
#  - Wecker, Abgleich nach dem Lauf, Schalter und Text der Einstellungen lesen denselben Bedarf (ExactCheckDemand);
#    nirgends «nur aktive Paare» als Bedarf. Bedarf selbst: BackgroundWorkPolicyTest.
#  - Schalter eines Alarms (Karte, Übersicht) nutzen AlarmJson.toggleAllowed («fällt um ≥ 100 %» nie einschalten);
#    Schreibweg: WatchlistLockDeviceTest.


WI_REQUIRED = {
    ("data", "data/backup/BackupManager.kt"): (
        "val settingsNotes = try { addressRefresher.withRefreshLock { writeRestored(input, previous, encrypted) } } "
        "catch (e: Exception) { throw RestoreRun.forMessage(e) }",),
    ("data", "data/backup/BackupManagerRecovery.kt"): ("addressRefresher.withRefreshLock {\n            rollBack(previous, Step.entries.toSet()",),
    ("data", "data/portfolio/WatchedAddressRefresher.kt"): (
        "suspend fun <T> withRefreshLock(block: suspend () -> T): T = gate.withLock { block() }",),
    ("app", "work/BackgroundCheck.kt"): ("suspend fun hasWork(): Boolean = exactDemand.hasWork()",),
    ("app", "ui/features/settings/ExactCheckControl.kt"): (
        "exactScheduler.sync(enabled, hasWork = demand.hasWork())", "val hasWork: Flow<Boolean> get() = demand.observe()"),
    ("app", "ui/features/settings/SettingsViewModel.kt"): (
        "val hasBackgroundWork: StateFlow<Boolean> = exactChecks.hasWork",),
    ("app", "ui/features/alarms/AlarmCard.kt"): ("enabled = AlarmJson.toggleAllowed(",),
    ("app", "ui/features/alarms/AlarmsOverviewCards.kt"): (
        "val canToggle = AlarmJson.toggleAllowed(alarm.condition, alarm.threshold, enable = !alarm.enabled)",
        "enabled = canToggle"),
    ("app", "work/ExactCheckDemand.kt"): (
        "BackgroundWorkPolicy.exactNeeded(",
        "BackgroundWorkPolicy.marketAlarms(settings.gasAlertEthTenths, settings.gasAlertBtc, settings.depegAlarm)"),
}
WI_FORBIDDEN = {("app", p): ("any { !it.disabled }",) for p in
                ("work/BackgroundCheck.kt", "ui/features/settings/ExactCheckControl.kt", "ui/features/settings/SettingsViewModel.kt")}


def wiring_problems(sources):
    problems = []
    for key, parts in WI_REQUIRED.items():
        code = squash(ds_strip_comments(sources.get(key, "")))
        problems += [f"{key[1]}: {part.splitlines()[0]} fehlt" for part in parts if squash(part) not in code]
    for key, parts in WI_FORBIDDEN.items():
        code = squash(ds_strip_comments(sources.get(key, "")))
        problems += [f"{key[1]}: {part} (nur aktive Paare als Bedarf)" for part in parts if squash(part) in code]
    return problems


def rule_wiring(t):
    keys = set(WI_REQUIRED) | set(WI_FORBIDDEN)
    return wiring_problems({(m, rel): read(need_file(os.path.join(t.java(m), *rel.split("/")))) for m, rel in keys})


# --------------------------------------------------------------------------------------------------------------------
# portfolio-settings-confirm: Einstellungen › Portfolio fragt auf iOS vor einer anderen Einstandsmethode und vor
# «Portfolio in Systemsicherung» (SettingsConfirm); die Arbeitsunterlage nennt auf beiden Plattformen vor dem Export, was
# in den Dateien steht. Android prüft die Rückfragen als Verhalten (PortfolioSettingsConfirmDeviceTest).


PS_IOS = ("SettingsConfirm.costMethod(current: data.settings.costMethod, chosen: method)",
          "SettingsConfirm.systemBackup(enabling: on)", 'L("settings_portfolio_system_backup_confirm_ios")')
PS_IOS_FORBIDDEN = ") { data.settings.costMethod = method }"
PS_SHEET_ANDROID = "R.string.tax_export_file_contents"
PS_SHEET_IOS = 'L("tax_export_file_contents")'


def portfolio_settings_problems(page, sheet_android, sheet_ios):
    code = ds_strip_comments(page)
    problems = [f"SettingsPortfolioPages.swift: {part} fehlt" for part in PS_IOS if part not in code]
    if PS_IOS_FORBIDDEN in code:
        problems.append("SettingsPortfolioPages.swift: Einstandsmethode ohne Rückfrage geschrieben")
    if PS_SHEET_ANDROID not in ds_strip_comments(sheet_android):
        problems.append("TaxWorksheetContent.kt: Inhalt der Dateien vor dem Export fehlt")
    if PS_SHEET_IOS not in ds_strip_comments(sheet_ios):
        problems.append("TaxWorksheetPage.swift: Inhalt der Dateien vor dem Export fehlt")
    return problems


def rule_portfolio_settings(t):
    page = read(need_file(t.i("App", "Features", "Settings", "SettingsPortfolioPages.swift")))
    sheet_android = read(need_file(os.path.join(t.java("app"), "ui", "features", "portfolio", "TaxWorksheetContent.kt")))
    sheet_ios = read(need_file(t.i("App", "Features", "Portfolio", "TaxWorksheetPage.swift")))
    return portfolio_settings_problems(page, sheet_android, sheet_ios)


# --------------------------------------------------------------------------------------------------------------------
# watch-deletion: Wear OS liest gelöschte Einträge des Handys mit (App zurückgesetzt oder entfernt) und vergisst, was
# davon stammt — Portfolio-Wert weg, Kurse nicht mehr vom Handy, Wahl wie ohne Handy (WatchSettings.forgetPhone, Testfälle
# watch_link.json); fehlt ein Eintrag beim Öffnen ganz, gilt er als gelöscht. Die Daten-Schicht (DataEvent) gibt es nur
# mit Google Play-Diensten auf dem Gerät.


WD_PARTS = {
    "WatchDataLayer.kt": (
        "it.type == DataEvent.TYPE_CHANGED || it.type == DataEvent.TYPE_DELETED",
        "Item(uri.host, null, uri.path, deleted = true)",
        "quotesItem.deleted -> store.forgetPhoneQuotes()",
        "if (it.deleted) WatchPortfolio.Message.Clear else WatchPortfolio.fromJson(it.json)",
        "if (gone) store.updateSettings { WatchSettings.forgetPhone(it) }",
        "if (found.none { it.node != local }) listOf(Item(null, null, path, deleted = true)) else found",
    ),
    "WatchStore.kt": ("remove(KEY_PHONE_COINS)", "remove(KEY_PHONE_TIME)"),
    "WatchDataListener.kt": ("WatchDataLayer.changed(dataEvents)",),
}


def watch_deletion_problems(sources):
    return [f"{name}: {part} fehlt" for name, parts in WD_PARTS.items() for part in parts
            if part not in ds_strip_comments(sources.get(name, ""))]


def rule_watch_deletion(t):
    base = t.a("wear", "src", "main", "java", "com", "cryptochecker", "wear")
    return watch_deletion_problems({name: read(need_file(os.path.join(base, name))) for name in WD_PARTS})


# --------------------------------------------------------------------------------------------------------------------
# ios-wiring: Anbindungen in SwiftUI-Ansichten, Widgets, der Uhr und der Daten-Schicht, die ohne Gerät nicht als Verhalten
# prüfbar sind — je Datei Teile, die stehen («has») bzw. nicht stehen («not») müssen, und wie oft («count»); Kommentare
# zählen nicht. Früher Quelltext-Wächter in den XCTest-Dateien.


IW_TABLE = {'App/Components/WatchlistLockedHint.swift': {'has': ('.onReceive(NotificationCenter.default.publisher(for: '
                                                      'SharedStorage.watchlistWriteFailedNotice))',
                                                      'show(L("portfolio_write_failed"))'),
                                              'not': ()},
 'App/CryptoCheckerApp.swift': {'has': ('.environment(\\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)',),
                                'not': ()},
 'App/Features/Portfolio/PortfolioCoinDetail.swift': {'has': (), 'not': ('.environment(\\.hidePortfolioAmounts',)},
 'App/Features/Portfolio/PortfolioCsvImport.swift': {'has': ('|| !',
                                                             'guard ',
                                                             'CsvImportRateGate.mark(CsvTransferMatch.withFallbacks(preview.transactions, '
                                                             'plan: plan), ',
                                                             'preview = computed.preview\n'
                                                             '            // Neue Vorschau: Zeilen ohne Tageskurs ',
                                                             '"portfolio_csv_import_locked"'),
                                                     'not': ()},
 'App/Features/Portfolio/PortfolioCsvImportSheet+Choices.swift': {'has': ('Toggle(L("portfolio_csv_import_anyway"), '
                                                                          'isOn: $ratesAccepted)',
                                                                          'ratesStale = true\n        remap()'),
                                                                  'not': ()},
 'App/Features/Portfolio/PortfolioLoadActions.swift': {'has': ('data.watchedAddresses.filter { '
                                                               'AddressServer.queryAllowed($0.chain, serverUnknown: '
                                                               'unknown) }',),
                                                       'not': ()},
 'App/Features/Portfolio/PortfolioPositionCard.swift': {'has': (), 'not': ('.environment(\\.hidePortfolioAmounts',)},
 'App/Features/Portfolio/PortfolioScreen.swift': {'has': ('WatchlistStorageNotice(notice: '
                                                          'WatchlistStorageLock.notice(locked: storageLocked,',
                                                          'keptKey: "portfolio_storage_kept_in_memory", notSavedKey: '
                                                          '"portfolio_save_pending"',
                                                          'L(PortfolioCsvImportSheet.failureKey(data))'),
                                                  'not': ('.environment(\\.hidePortfolioAmounts',)},
 'App/Features/Settings/SettingsAddressServerCard.swift': {'has': ('if url != nil { '
                                                                   'StorageRecoveryNotice.confirmAddressServer() }',
                                                                   'if serverUnknown && '
                                                                   'data.settings.addressServerBtc == nil { '
                                                                   'confirmPublic() } else { save(nil) }'),
                                                           'not': ()},
 'App/Features/Settings/SettingsDataPages.swift': {'count': {'if let key = BackupManager.backupRefusalKey(BackupManager.storageAccess(data)) { return fail(key) }': 2,
                                                             'if let key = BackupManager.restoreRefusalKey(BackupManager.storageAccess(data)) { return fail(key) }': 1},
                                                   'has': ('fail(BackupManager.restoreFailureKey(error))',),
                                                   'not': ()},
 'App/Features/Watchlist/WatchlistList.swift': {'has': ('lastFinishedAt: SharedStorage.lastFullRefreshAt',),
                                                'not': ('lastFinishedAt: SharedStorage.lastRefreshAt',
                                                        'lastFinishedAt: entry.lastRefreshAt')},
 'App/Features/Watchlist/WatchlistRefreshCountdown.swift': {'has': ('lastFinishedAt: SharedStorage.lastFullRefreshAt',
                                                                    'let last = SharedStorage.lastFullRefreshAt'),
                                                            'not': ('lastFinishedAt: SharedStorage.lastRefreshAt',
                                                                    'lastFinishedAt: entry.lastRefreshAt')},
 'App/Services/AppData+Live.swift': {'has': (), 'not': ('markFullRefresh', 'lastFullRefreshAt =')},
 'App/Services/AppData+Refresh.swift': {'has': ('lastFinishedAt: SharedStorage.lastFullRefreshAt',),
                                        'not': ('lastFinishedAt: SharedStorage.lastRefreshAt',
                                                'lastFinishedAt: entry.lastRefreshAt')},
 'App/Services/AppData+WatchedAddresses.swift': {'has': ('guard '
                                                         'ProtectedFile.unreadableIsDamaged(WatchedAddressStore.url), '
                                                         'WatchedAddressStore.setAsideUnreadable() else { return '
                                                         'false }',),
                                                 'not': ()},
 'App/Services/AppData.swift': {'has': ('if watchlistSavePending || SharedStorage.watchlistWriteFailed {',
                                        'if !waiting, !SharedStorage.watchlistWriteFailed, let fresh = read.snapshot '
                                        '{'),
                                'not': ()},
 'App/Services/BackgroundRefresh+Planning.swift': {'has': ('MacroNotifications.clockChanged()',), 'not': ()},
 'App/Services/BackupManager+PortfolioFile.swift': {'has': ('guard '
                                                            'ProtectedFile.unreadableIsDamaged(PortfolioStore.url), '
                                                            'PortfolioStore.setAsideUnreadable() else { return false '
                                                            '}',),
                                                    'not': ()},
 'App/Services/BackupManager+Restore.swift': {'has': ('try requireJournal(try? journalData(data)',
                                                      'undo: { _ = rollBackPortfolio(previous.portfolio, wasIntact: '
                                                      'previous.portfolioIntact, into: data) })',
                                                      'guard WatchedAddressStore.setAsideUnreadable(at: '
                                                      'SharedStorage.portfolioAlarmsURL) else {'),
                                              'not': ()},
 'App/Services/BackupManager.swift': {'has': ('if journal && input.newSettings.addressServerBtc != nil { '
                                              'StorageRecoveryNotice.confirmAddressServer() }',
                                              'removeReplacedAlarmNotifications(previous.snapshot)',
                                              'old.alarms.forEach { Notifier.cancelAlarm($0.id) }',
                                              '(old.portfolioAlarms ?? []).forEach { '
                                              'Notifier.cancelPortfolioAlarm($0.id) }',
                                              'AlarmSnooze.clearAll()',
                                              'if journal && storageAccess(data) == .locked { throw '
                                              'Failure.storageKept }',
                                              'if !journal { putBackJournalDamaged(root, into: data) }'),
                                      'not': ()},
 'App/Services/LiveActivityController.swift': {'has': ('watches.first(where: { activity.attributes.matches($0) })',
                                                       'if watches.contains(where: { $0.id == id }) { '
                                                       'replaced.append(id) }',
                                                       'if let id = replaced.first, let watch = watches.first(where: '
                                                       '{ $0.id == id }) { _ = await start(watch) }'),
                                               'not': ()},
 'App/Services/MacroNotifications.swift': {'has': ('let notifyAt = MacroCalendar.notifyAt(day: day, calendar: '
                                                   'calendar),',
                                                   'refresh(settings: SharedStorage.loadSettings())'),
                                           'not': ()},
 'App/Services/PhoneWatchLink.swift': {'has': ('if WatchSettings.phoneSideDiffers(settings, watch: remote) { send() '
                                               '}',
                                               'try session.updateApplicationContext(context)',
                                               'if failedSends <= Self.maxRetries { sendSoon() }',
                                               'nonisolated func sessionWatchStateDidChange(_ session: WCSession) {\n'
                                               '        Task { @MainActor in self.send() }'),
                                       'not': ('try? session.updateApplicationContext(context)',)},
 'App/Services/StorageRecoveryNotice.swift': {'has': ('L("settings_address_server_unknown")',), 'not': ()},
 'Shared/Portfolio/WatchedAddressSource+Chains.swift': {'has': (),
                                                        'not': ('var btcEntries',
                                                                '= btcEntries\n',
                                                                'btcEntries: [String] =',
                                                                'btcServer: String? = nil')},
 'Shared/Portfolio/WatchedAddressSource.swift': {'has': (),
                                                 'not': ('var btcEntries',
                                                         '= btcEntries\n',
                                                         'btcEntries: [String] =',
                                                         'btcServer: String? = nil')},
 'Shared/Services/Notifier.swift': {'has': ('content.categoryIdentifier = Notifier.categoryPortfolioLocked\n',),
                                    'not': ('notification_portfolio_alarm_text',
                                            'PortfolioAlarmTexts.',
                                            'settings.appLock ? categoryPortfolioLocked : categoryAlarm')},
 'Shared/Storage/SharedStorage+Snapshot.swift': {'has': ('NotificationCenter.default.post(name: '
                                                         'watchlistWriteFailedNotice, object: nil)',),
                                                 'not': ()},
 'Watch/Device/WatchStore.swift': {'has': ('try? FileManager.default.removeItem(at: '
                                           'directory.appendingPathComponent(phoneFileName))',
                                           'values.isExcludedFromBackup = true',
                                           'set { writeExcluded(Data(newValue.toJson().utf8), settingsFileName) }',
                                           'if let data = try? JSONEncoder().encode(delivery) { writeExcluded(data, '
                                           'phoneFileName) }',
                                           'if let data = try? JSONEncoder().encode(p) { writeExcluded(data, '
                                           'portfolioFileName) }'),
                                   'not': ('UserDefaults',)},
 'Watch/WatchLink.swift': {'count': {'WatchModel.shared.forgetPhone()': 2},
                           'has': ('func sessionCompanionAppInstalledDidChange(_ session: WCSession)',
                                   'guard !session.isCompanionAppInstalled else { return }',
                                   'let companion = session.isCompanionAppInstalled'),
                           'not': ()},
 'Watch/WatchModel.swift': {'has': ('let next = WatchSettings.forgetPhone(settings)',
                                    'WatchStore.forgetPhoneQuotes()',
                                    'WatchStore.takePortfolio(.clear)'),
                            'not': ()},
 'WatchCore/WatchQuoteFile.swift': {'has': ('isExcludedFromBackup',), 'not': ()},
 'WatchWidgets/WatchPortfolioWidget.swift': {'has': ('WatchText.t("ios_watch_portfolio_turn_on_iphone")',
                                                     'WatchText.t("watch_portfolio_off")',
                                                     'WatchPortfolioOff.line(title: title, hint: hint)',
                                                     '@Environment(\\.redactionReasons) private var redactionReasons',
                                                     'if redactionReasons.contains(.privacy) { '
                                                     'spokenPortfolio.hidden = true }',
                                                     'WatchText.portfolioSpoken(spokenPortfolio)'),
                                             'not': ('WatchText.portfolioSpoken(p)',)},
 'WatchWidgets/WatchPriceWidget.swift': {'has': ('compact: q.map { WatchText.compactPrice($0.price) } ?? "—"',
                                                 'Text(entry.compact).foregroundStyle(valueStyle).widgetLabel(title)',
                                                 'quote: WatchPair.parse(coin)?.quote ?? WatchPair.usdt',
                                                 'empty: WatchText.t(phone ? "ios_watch_iphone_empty" : '
                                                 '"watch_choose")',
                                                 'let missing = q == nil ? WatchText.missingShort(coin) : nil'),
                                         'not': ('Text(entry.change).foregroundStyle(valueStyle)',)},
 'Widgets/WatchlistWidget.swift': {'has': ('lastFinishedAt: SharedStorage.lastFullRefreshAt',),
                                   'not': ('lastFinishedAt: SharedStorage.lastRefreshAt',
                                           'lastFinishedAt: entry.lastRefreshAt')},
 'Widgets/WidgetIntents.swift': {'has': ('lastFinishedAt: SharedStorage.lastFullRefreshAt',),
                                 'not': ('lastFinishedAt: SharedStorage.lastRefreshAt',
                                         'lastFinishedAt: entry.lastRefreshAt')}}


def ios_wiring_problems(sources, table=None):
    problems = []
    for path, parts in (table or IW_TABLE).items():
        raw = sources.get(path, "")
        code = ds_strip_comments(raw)
        # Ein Teil, der selbst einen Kommentar enthält, wird am Quelltext samt Kommentaren gesucht
        problems += [f"ios/{path}: {part.splitlines()[0]} fehlt" for part in parts["has"]
                     if part not in (raw if "//" in part else code)]
        problems += [f"ios/{path}: {part.splitlines()[0] or part!r} darf nicht stehen" for part in parts["not"] if part in code]
        problems += [f"ios/{path}: {part} {code.count(part)}× statt {n}×" for part, n in parts.get("count", {}).items()
                     if code.count(part) != n]
    return problems


def rule_ios_wiring(t):
    return ios_wiring_problems({path: read(need_file(t.i(*path.split("/")))) for path in IW_TABLE})


# --------------------------------------------------------------------------------------------------------------------
# test-inputs


TI_MODULES = ("app", "domain", "data", "marketdata", "wear")
# Pfade, die ein Test nur nennt (Meldung, Vergleichstext), aber nicht liest
TI_NOT_READ = set()
TI_RAW_STRING = re.compile(r'"""[\s\S]*?"""')
TI_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
TI_LEADING_TEMPLATE = re.compile(r"^(\$\{[^}]*\}|\$[A-Za-z_]+)")
TI_MIN_PATHS = 100


def path_part(literal):
    """Fester Anfang einer Zeichenkette als Pfad: eine Vorlage am Anfang fällt weg, ab der nächsten Vorlage wird am letzten
    «/» davor abgeschnitten; None für alles, was kein relativer Pfad sein kann."""
    v = TI_LEADING_TEMPLATE.sub("", literal, count=1)
    template = v.find("$")
    if template >= 0:
        v = v[:template]
        v = v[:v.rfind("/")] if "/" in v else ""
    v = v.strip()
    if not v or v.startswith("/") or "://" in v or "\\" in v or any(c.isspace() for c in v):
        return None
    while v.startswith("./"):
        v = v[2:]
    v = v.rstrip("/")
    if not v or all(c in "./" for c in v):
        return None
    return v


def _under(path, base):
    """Liegt [path] in [base] (Pfad-Bestandteile, wie File.startsWith)?"""
    p = os.path.normpath(path).split(os.sep)
    b = os.path.normpath(base).split(os.sep)
    return p[:len(b)] == b


def rule_test_inputs(t):
    """Die Test-Aufgaben aller Module kennen die Dateien, die die Tests ausserhalb ihres Klassenpfads lesen
    (gradle/test-inputs.gradle.kts) — sonst meldet Gradle «UP-TO-DATE» bzw. nimmt das Ergebnis aus dem Build-Cache,
    obwohl sich eine Fixture, eine Swift-Datei oder ein Nachbarmodul geändert hat. Jede Zeichenkette der Unit-Tests, die
    einen vorhandenen Ort im Baum bezeichnet, liegt unter einem angemeldeten Ordner (testInputRoots)."""
    problems = []
    android = os.path.normpath(t.android)
    repo = os.path.dirname(android)
    script = read(need_file(t.a("gradle", "test-inputs.gradle.kts")))
    block = script.split("// testInputRoots:begin", 1)[-1].split("// testInputRoots:end", 1)[0]
    declared = [os.path.normpath(os.path.join(android, m.group(1))) for m in re.finditer(r'(?m)^\s*"([^"]+)",\s*$', block)]
    root = read(need_file(t.a("build.gradle.kts")))
    if 'subprojects {\n    apply(from = rootProject.file("gradle/test-inputs.gradle.kts"))\n}' not in root:
        problems.append("build.gradle.kts wendet gradle/test-inputs.gradle.kts nicht auf alle Module an")
    for piece in ("tasks.withType<Test>().configureEach {", ".withPathSensitivity(PathSensitivity.RELATIVE)",
                  'providers.environmentVariable("REQUIRE_IOS_SOURCES")', 'inputs.property("requireIosSources", requireIosSources)'):
        if piece not in script:
            problems.append(f"gradle/test-inputs.gradle.kts: «{piece}» fehlt")
    if len(declared) < 20:
        problems.append(f"nur {len(declared)} Einträge in testInputRoots")
    if len(declared) != len(set(declared)):
        problems.append("doppelte Einträge in testInputRoots")
    with_tests = sorted(d for d in os.listdir(android) if os.path.isdir(os.path.join(android, d, "src", "test")))
    if with_tests != sorted(TI_MODULES):
        problems.append(f"Module mit Tests {with_tests}, erwartet {sorted(TI_MODULES)} (neues Modul in TI_MODULES und testInputRoots)")
    for module in TI_MODULES:
        for source_set in ("main", "test"):
            if os.path.normpath(os.path.join(android, module, "src", source_set)) not in declared:
                problems.append(f"{module}/src/{source_set} fehlt in testInputRoots")
    bases = [android] + [os.path.join(android, m) for m in TI_MODULES] + [repo, os.path.join(repo, "public")]
    # Einzelne Namen ohne «/» zählen nur, wenn sie oben im Baum liegen und kein Modul-/Paketname sind
    single = (set(os.listdir(android)) | set(os.listdir(repo))) - set(TI_MODULES)
    uncovered = {}
    paths = 0
    for module in TI_MODULES:
        for rel, path in files_under(os.path.join(android, module, "src", "test"), "kt"):
            text = TI_RAW_STRING.sub('""', read(path))
            for m in TI_LITERAL.finditer(text):
                part = path_part(m.group(1))
                if part is None or ("/" not in part and part not in single):
                    continue
                found = [os.path.normpath(os.path.join(b, part)) for b in bases]
                found = [f for f in found if os.path.exists(f) and _under(f, repo)]
                if not found or part in TI_NOT_READ:
                    continue
                paths += 1
                if not any(_under(f, d) or _under(d, f) for f in found for d in declared):
                    uncovered.setdefault(part, f"{module}/src/test/{rel}")
    # Der Wächter sieht die Pfade wirklich (sonst wäre er still grün)
    if paths < TI_MIN_PATHS:
        problems.append(f"nur {paths} Pfade erkannt (mindestens {TI_MIN_PATHS})")
    problems += [f"{p} (gelesen in {f}): Leseort ohne Eingabe in gradle/test-inputs.gradle.kts (testInputRoots)"
                 for p, f in sorted(uncovered.items())]
    return problems


# --------------------------------------------------------------------------------------------------------------------
# Run


RULES = [
    ("runcatching", rule_runcatching),
    ("haptics", rule_haptics),
    ("typography", rule_typography),
    ("spacing", rule_spacing),
    ("label-value", rule_label_value),
    ("dispatchers", rule_dispatchers),
    ("layers", rule_layers),
    ("dialog-scroll", rule_dialog_scroll),
    ("write-consent", rule_write_consent),
    ("write-order", rule_write_order),
    ("ios-write-consent", rule_ios_write_consent),
    ("settings-write", rule_settings_write),
    ("compose-stability", rule_compose_stability),
    ("test-inputs", rule_test_inputs),
    ("bounded-caches", rule_bounded_caches),
    ("ios-widget-offline", rule_ios_widget_offline),
    ("swipe-delete", rule_swipe_delete),
    ("ios-alarm-create", rule_ios_alarm_create),
    ("bulk-undo", rule_bulk_undo),
    ("app-start", rule_app_start),
    ("settings-anchors", rule_settings_anchors),
    ("permission-event", rule_permission_event),
    ("missing-logos", rule_missing_logos),
    ("lot-sheets", rule_ios_lot_delete),
    ("wiring", rule_wiring),
    ("portfolio-settings-confirm", rule_portfolio_settings),
    ("watch-deletion", rule_watch_deletion),
    ("ios-wiring", rule_ios_wiring),
]


def escape_data(text):
    """Message part of a workflow command - same as annotate.py (run with -I, so no import)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def run(root):
    tree = Tree(root)
    failed = 0
    for rule_id, rule in RULES:
        try:
            problems = rule(tree)
        except RuleError as e:
            problems = [str(e)]
        for problem in problems:
            print(f"::error title={escape_property('Source rules (' + rule_id + ')')}::{escape_data(problem)}")
        print(f"{rule_id}: {'ok' if not problems else str(len(problems)) + ' finding(s)'}")
        failed += len(problems)
    return 1 if failed else 0


def self_test():
    def eq(actual, expected, what):
        if actual != expected:
            raise AssertionError(f"{what}: {actual!r} != {expected!r}")

    # app-start: Start im onCreate und Hinweis der Einstellung; auskommentiert zählt nicht
    eq(app_start_problems("override fun onCreate() {\n priceOverlay.get().start()\n}", "Text(stringResource(R.string.settings_price_overlay_restart_hint))"), [], "app-start: ok")
    eq(len(app_start_problems("override fun onCreate() {\n // priceOverlay.get().start()\n}", "")), 2, "app-start: fehlt")

    # bulk-undo: Undo nur aus onRemove bzw. bulkUndo.remove
    eq(bulk_undo_kotlin("A.kt", "f(onRemove = { viewModel.runner.undo(it) })\nonUndoBulk: (List<Long>) -> Unit = {},\n"
                                "Dialog(onRemove = onUndoBulk)\nButton(onClick = { onUndoBulk(ids) })"), (3, ["A.kt:4"]),
       "bulk-undo: Kotlin")
    eq(bulk_undo_swift("B.swift", "func undoBulkAdd(_ ids: [Int64]) {}\nbulkUndo.remove(ids) { vm.undoBulkAdd($0) }\n"
                                  + "let a = 1\n" * 80 + "Button { vm.undoBulkAdd(ids) }"), (2, ["B.swift:83"]), "bulk-undo: Swift")

    # ios-wiring: vorhanden ok; fehlend, auskommentiert und verboten gefunden
    table = {"A.swift": {"has": ("good()",), "not": ("bad()",)}}
    eq(ios_wiring_problems({"A.swift": "good()"}, table), [], "ios-wiring: ok")
    eq(len(ios_wiring_problems({"A.swift": "// good()\nbad()"}, table)), 2, "ios-wiring: Fehler")

    # watch-deletion: vollständig ok, Lücke gefunden
    eq(watch_deletion_problems({k: "\n".join(v) for k, v in WD_PARTS.items()}), [], "watch-deletion: ok")
    eq(len(watch_deletion_problems({"WatchStore.kt": "remove(KEY_PHONE_COINS)"})), 8, "watch-deletion: Lücke")

    # portfolio-settings-confirm: ok, direkte Zuweisung und fehlender Hinweis gefunden
    eq(portfolio_settings_problems("\n".join(PS_IOS), PS_SHEET_ANDROID, PS_SHEET_IOS), [], "portfolio-settings-confirm: ok")
    eq(len(portfolio_settings_problems("\n".join(PS_IOS) + PS_IOS_FORBIDDEN, "", PS_SHEET_IOS)), 2,
       "portfolio-settings-confirm: Fehler")

    # wiring: vollständige Quellen ok, Lücke und verbotener Bedarf gefunden
    full = {k: "\n".join(v) for k, v in WI_REQUIRED.items()}
    eq(wiring_problems(full), [], "wiring: ok")
    broken = dict(full)
    broken[("app", "work/BackgroundCheck.kt")] = "watches.any { !it.disabled }"
    eq(len(wiring_problems(broken)), 2, "wiring: Lücke")

    # ios-lot-delete: mit Rückfrage ok, auskommentiert fehlt
    eq(lot_delete_problems(LD_BUTTON + "\n" + LD_ALERT), [], "ios-lot-delete: ok")
    eq(len(lot_delete_problems("// " + LD_BUTTON + "\n" + LD_ALERT)), 1, "ios-lot-delete: fehlt")
    eq(len(lot_sheet_problems({"PortfolioAddressSheet.kt": LD_SAVEABLE["PortfolioAddressSheet.kt"][0]})), 3, "lot-sheets: fehlt")

    # missing-logos: richtige Reihenfolge ok, vertauscht und ohne Ablage gefunden
    ok_body = "WatchlistLogoHint(reduceMotion = reduceMotion)\nWatchlistMissingLogos(watches, reduceMotion = reduceMotion)"
    ok_header = "WatchlistLogoHint()\nWatchlistMissingLogos()\n" + ML_IOS_CHECK
    eq(missing_logo_problems(ok_body, ML_ANDROID_STORE, ok_header, ML_IOS_STORE), [], "missing-logos: ok")
    swapped = "WatchlistMissingLogos(watches, reduceMotion = reduceMotion)\nWatchlistLogoHint(reduceMotion = reduceMotion)"
    eq(len(missing_logo_problems(swapped, "", "// " + ok_header, ML_IOS_STORE)), 3, "missing-logos: Fehler")

    # settings-anchors: Ziel ohne Anker gefunden, auskommentierter Anker zählt nicht
    eq(anchors_missing({"a.x", "b.y"}, ['SettingsAnchor("a.x")', '// SettingsAnchor("b.y")'], "SettingsAnchor("), ["b.y"],
       "settings-anchors: Anker")
    eq(settings_anchor_problems_ios('anchor: "alarms.delivery" .main("about.diagnostics")', ['.settingsAnchor("alarms.delivery")']),
       ["iOS: Suchziel updates.check_runs fehlt", "iOS: Sucheintrag updates.alarm_log fehlt",
        "iOS: Sucheintrag updates.check_runs fehlt", "iOS: Suchziel about.diagnostics ohne .settingsAnchor"],
       "settings-anchors: iOS")
    # permission-event: direkter Aufruf und falsche Zahl gefunden
    eq(permission_event_problems("askPermissionAfterWrite(uiEvents.requestNotifications, a)\n"
                                 "askPermissionAfterWrite(uiEvents.requestNotifications, b)\nCollectScreenEvents(uiEvents.requestNotifications)"),
       [], "permission-event: ok")
    eq(len(permission_event_problems("{ written -> requestNotifications() }")), 3, "permission-event: Rückruf")

    # ios-alarm-create: Anlegen ohne Wächter gefunden, mit Wächter bzw. ohne Anlegen nicht
    eq(alarm_create_unguarded([("A.swift", "struct XAlarmSheet: View {\n Button { onSave() }\n}"),
                               ("B.swift", "struct AlarmEditorSheet: View {\n guard createGuard.begin(creating: true) else { return }\n}"),
                               ("C.swift", "// struct OldAlarmSheet: View")]),
       (["A.swift"], 2), "ios-alarm-create: Erkennung")

    # swipe-delete: Aufrufe mit Argumenten erkannt, die Deklaration nicht
    calls = swipe_calls("internal fun SwipeActionsRow(\n a: Int,\n) {}\nSwipeActionsRow(\n onDelete = { x() },\n"
                        "    confirmsDelete = true,\n) { Row() }\nSwipeActionsRow(onDelete = { y() }) { }")
    eq([(line, bool(SD_CONFIRM.search(a))) for line, a in calls], [(4, True), (8, False)], "swipe-delete: Erkennung")

    # ios-widget-offline: Timeline ohne Netzabfrage gefunden, mit Abfrage bzw. ohne Timeline nicht
    eq(widget_offline_missing([("A.swift", "struct P: AppIntentTimelineProvider {\n func timeline() { }\n}"),
                               ("B.swift", "struct Q: TimelineProvider {\n let o = await WidgetOffline.isOffline()\n}"),
                               ("C.swift", "struct V: View { }\n// TimelineProvider")]),
       (["A.swift"], 2), "ios-widget-offline: Erkennung")
    eq(widget_offline_missing([("D.swift", "struct P: TimelineProvider {\n // WidgetOffline.isOffline()\n}")]),
       (["D.swift"], 1), "ios-widget-offline: Kommentar zählt nicht")

    # runcatching: Coroutine-Kontexte werden erkannt; ausserhalb und mit eigenem Weiterwerfen erlaubt
    eq(runcatching_violations(
        "class A {\n    suspend fun a() {\n        if (x) { runCatching { load() } }\n    }\n    fun b() {\n"
        "        scope.launch { list.forEach { runCatching { load() } } }\n    }\n    suspend fun c(): Int? =\n"
        "        runCatching { load() }.getOrNull()\n    fun d() = scope.launch(Dispatchers.IO) { runCatching { load() } }\n}"),
        [3, 6, 9, 10], "runcatching: Coroutine-Kontexte")
    eq(runcatching_violations(
        "class A {\n    private val x = runCatching { parse() }.getOrNull()\n    fun a() { runCatching { open() } }\n"
        "    suspend fun b() {\n        val o = object : Callback { override fun done() { runCatching { close() } } }\n"
        "        runCatching { load() }\n            .onFailure { if (it is CancellationException) throw it }\n"
        "        runCatchingCancellable { load() }\n        // runCatching { im Kommentar }\n"
        "        val s = \"runCatching { im Text }\"\n        val c = '{'\n    }\n"
        "    @Composable fun C() { Button(onClick = { runCatching { open() } }) }\n}"),
        [], "runcatching: erlaubte Formen")
    # dispatchers: Code zählt, Kommentare und Import nicht
    eq(dispatcher_literals("withContext(Dispatchers.IO) { }\nflow.flowOn(kotlinx.coroutines.Dispatchers.Default)"), 2, "dispatchers")
    eq(dispatcher_literals("import kotlinx.coroutines.Dispatchers\n// Dispatchers.IO\n/** [Dispatchers.Default] */\nval x = 1"), 0,
       "dispatchers: Kommentare")
    eq(dispatcher_literals("withContext(Dispatchers.Main) { }\nwithContext(ioDispatcher) { }"), 0, "dispatchers: Main")
    eq(len(DISPATCHER_REMAINING), 31, "dispatchers: Dateien")
    eq(sum(DISPATCHER_REMAINING.values()), 51, "dispatchers: Stellen")
    # layers: @Test am Zeilenanfang ohne Kommentare; Paket und Importe
    at = "@" + "Test"
    eq(test_methods(f"class A {{\n    {at}\n    fun a() = Unit\n    // {at}\n    /*\n{at} */\n    {at} fun b() = Unit\n"
                    f"    val text = \"{at}\"\n}}\n"), 2, "layers: @Test")
    eq(test_methods(f"{at}Only fun c() = Unit\n"), 0, "layers: @TestOnly")
    text = f"package {DOMAIN}.util\n\nimport okhttp3.OkHttpClient\n// import x.y\nimport {APP}.data.Foo\nimport {DOMAIN}.model.Bar\n"
    source = parse_source("java/com/cryptochecker/app/domain/util/A.kt", text)
    eq(source[1], f"{DOMAIN}.util", "layers: Paket")
    eq(source[2], ["okhttp3.OkHttpClient", f"{APP}.data.Foo", f"{DOMAIN}.model.Bar"], "layers: Importe")
    eq(layer_violations(source, DOMAIN, ["androidx.room", "okhttp3"], [DOMAIN]),
       ["import okhttp3.OkHttpClient", f"import {APP}.data.Foo"], "layers: Importe verboten")
    eq(layer_violations(source, DOMAIN, [], [DATA, DOMAIN]), [], "layers: erlaubt")
    eq(layer_violations(parse_source("java/com/cryptochecker/app/util/A.kt", f"package {APP}.util\n"), DOMAIN, [], [DOMAIN]),
       [f"Paket {APP}.util statt {DOMAIN}"], "layers: Paket falsch")
    eq(layer_violations(parse_source("java/com/cryptochecker/app/domain/A.kt", f"package {DOMAIN}.util\n"), DOMAIN, [], [DOMAIN]),
       [f"Paket {DOMAIN}.util passt nicht zum Ordner"], "layers: Ordner")
    eq((in_package(DOMAIN, DOMAIN), in_package(DOMAIN + "x.A", DOMAIN)), (True, False), "layers: in_package")
    _self_test_dialog(eq)
    _self_test_write_consent(eq)
    eq(len(write_order_violations("ui/A.kt", "fun a() = scope.launch {\n    watchRepository.deleteAlarm(id)\n    notifier.cancel()\n}")), 1,
       "write-order: verworfenes Ergebnis")
    eq(write_order_violations("ui/A.kt", "fun a() = scope.launch {\n    if (!watchRepository.deleteAlarm(id)) return@launch\n}"), [],
       "write-order: Ergebnis benutzt")
    eq(write_order_violations("ui/A.kt", "fun a() = scope.launch { settingsRepository.setTtsEnabled(true) }"), [],
       "write-order: anderes Repository")
    eq(len(write_order_violations("ui/A.kt", "fun a() {\n    scope.launch { undoMutex.withLock { x() }; watchRepository.confirmUserWrite() }\n}")), 1,
       "write-order: Sperre vor der Rückfrage")
    eq(write_order_violations("ui/A.kt", "fun a() {\n    launchWrite { undoMutex.withLock { x() } }\n}"), [],
       "write-order: Rückfrage zuerst")
    writes = ios_write_paths(["    func setNote(_ w: Watch) {\n        if watchlistWriteRefused() { return }\n    }\n"
                                         "    func toggleFavorite(_ w: Watch) {\n        guard !watchlistWriteRefused() else { return }\n    }\n"
                                         "    func toggleFavorite(_ kind: Kind, _ n: String) {\n        favorites.toggle(n)\n    }\n"])
    eq(writes, {"setNote": {1}, "toggleFavorite": {1}}, "ios-write-consent: Ableitung")
    eq(len(ios_unconfirmed_calls("A.swift", "Button { data.setNote(w) }", writes)), 1, "ios-write-consent: ohne Frage")
    eq(len(ios_unconfirmed_calls("A.swift", "Button { data.toggleFavorite(w) }", writes)), 1, "ios-write-consent: Favorit")
    eq(ios_unconfirmed_calls("A.swift", "data.withWatchlistConfirm { data.setNote(w) }", writes), [],
       "ios-write-consent: mit Frage")
    eq(ios_unconfirmed_calls("A.swift", "data.toggleFavorite(kind, s)", writes), [], "ios-write-consent: Explorer-Favorit")
    # test-inputs: Vorlagen, Präfixe und Nicht-Pfade
    for literal, expected in (("ios/$rel", "ios"), ("${prefix}src/main/$rel", "src/main"), ("../ios/App/", "../ios/App"),
                              ("./testdata/parity", "testdata/parity"), ("LICENSE", "LICENSE"), ("$dir/strings_common.xml", None),
                              ("https://example.org/a", None), ("zwei Wörter/x", None), ("/tmp/x", None), ("../", None), ("", None)):
        eq(path_part(literal), expected, f"test-inputs: {literal}")
    # Annotationen: kein Einschleusen weiterer Befehle
    eq(escape_data("a\nb%"), "a%0Ab%25", "escape")
    print("source_rules self-test ok")
    return 0


def _self_test_dialog(eq):
    strings = {"kurz": ["This alert will be removed."], "lang": ["Deletes all data. This cannot be undone."],
               "mehrzahl": ["One entry.", "%d entries were skipped. Check the file."]}

    def dialog(text):
        return ("fun f() {\n    AlertDialog(\n        onDismissRequest = {},\n        text = " + text +
                ",\n        confirmButton = { TextButton(onClick = {}) { Text(\"OK\") } }\n    )\n}\n")
    bad = [
        "{ Column { Text(stringResource(R.string.kurz)); Text(stringResource(R.string.kurz)) } }",
        '{ Text(stringResource(R.string.kurz) + "\\n\\n" + stringResource(R.string.kurz)) }',
        '{ Text("$a\\n$b") }',
        "{ Text(stringResource(R.string.lang)) }",
        "{ Text(pluralStringResource(R.plurals.mehrzahl, n, n)) }",
        "{ Text(message) }",
        "{ Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.kurz)) } }",
    ]
    for slot in bad:
        eq(len(dialog_violations(dialog(slot), strings)), 1, f"dialog-scroll nicht erkannt: {slot}")
    good = [
        "{ Text(stringResource(R.string.kurz)) }",
        "{ DialogText { Text(stringResource(R.string.lang)) } }",
        "{ DialogText { Text(a); Text(b) } }",
        '{ Column(Modifier.verticalScroll(rememberScrollState())) { Text(x + "\\n" + y) } }',
        '{\n            // Text(message) — nur Kommentar\n            Text(stringResource(R.string.kurz, "(}"))\n        }',
    ]
    for slot in good:
        eq(dialog_violations(dialog(slot), strings), [], f"dialog-scroll fälschlich gemeldet: {slot}")
    inner = "AlertDialog(\n    onDismissRequest = {},\n    confirmButton = { Foo(text = { Text(a); Text(b) }) }\n)"
    eq(dialog_violations(inner, strings), [], "dialog-scroll: innerer text =")


def _wc_names(problems):
    return [p.split("fun ", 1)[1].split(":", 1)[0] for p in problems]


def _self_test_write_consent(eq):
    r = "watchRepository"
    sample = f"""class C(private val {r}: WatchRepository) {{
    fun okReturn(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        {r}.setFavorite(id, true)
    }}
    fun okBlock(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) {{
            state = null
            return@launch
        }}
        {r}.setFavorite(id, true)
    }}
    fun okElse(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) return@launch else {r}.setFavorite(id, true)
    }}
    fun okOr(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite() || !{r}.deleteAlarm(id)) return@launch
        {r}.setFavorite(id, false)
    }}
    fun okValueElse(ids: List<Long>) = scope.launch {{
        val n = if (!{r}.confirmUserWrite()) 0 else lock.withLock {{
            {r}.reorder(ids)
        }}
        onDone(n)
    }}
    fun okThen(id: Long) = scope.launch {{ if ({r}.confirmUserWrite()) {r}.setFavorite(id, true) else other() }}
    fun okAnd(id: Long) = scope.launch {{ onDone({r}.confirmUserWrite() && {r}.setAlarmEnabled(id, true)) }}
    fun negatedBlock(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) {{ {r}.setFavorite(id, true) }}
    }}
    fun negatedStatement(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) {r}.setFavorite(id, true)
    }}
    fun positiveElse(ids: List<Long>) = scope.launch {{
        val n = if ({r}.confirmUserWrite()) 0 else {r}.reorder(ids)
    }}
    fun afterPositive(id: Long) = scope.launch {{
        if ({r}.confirmUserWrite()) other()
        {r}.setFavorite(id, true)
    }}
    fun noReturn(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite()) {{ state = null }}
        {r}.setFavorite(id, true)
    }}
    fun ignoredResult(id: Long) = scope.launch {{
        {r}.confirmUserWrite()
        {r}.setFavorite(id, true)
    }}
    fun positiveOr(id: Long) = scope.launch {{
        if ({r}.confirmUserWrite() || {r}.deleteAlarm(id)) other()
    }}
    fun negatedAnd(id: Long) = scope.launch {{ onDone(!{r}.confirmUserWrite() && {r}.setAlarmEnabled(id, true)) }}
    fun okAndThen(id: Long) = scope.launch {{ if ({r}.confirmUserWrite() && flag) {r}.setFavorite(id, true) }}
    fun negatedAndRest(id: Long) = scope.launch {{
        if (!{r}.confirmUserWrite() && flag) return@launch
        {r}.setFavorite(id, true)
    }}
    fun positiveOrRest(id: Long) = scope.launch {{ if ({r}.confirmUserWrite() || flag) {r}.setFavorite(id, true) }}
    fun positiveAndOr(id: Long) = scope.launch {{ if ({r}.confirmUserWrite() && flag || other) {r}.setFavorite(id, true) }}
    fun andOrInCondition(id: Long) = scope.launch {{ if ({r}.confirmUserWrite() && flag || {r}.deleteAlarm(id)) other() }}
}}"""
    calls, problems, _ = write_consent_check({"ui/Sample.kt": wc_strip(sample)}, {"setFavorite", "reorder", "deleteAlarm", "setAlarmEnabled"})
    eq(_wc_names(problems), ["negatedBlock", "negatedStatement", "positiveElse", "afterPositive", "noReturn", "ignoredResult",
                             "positiveOr", "negatedAnd", "negatedAndRest", "positiveOrRest", "positiveAndOr", "andOrInCondition"],
       "write-consent: Richtung der Bedingung")
    eq(calls, 21, "write-consent: Aufrufe")
    store = """class S(private val store: WatchStore) {
    fun bad(id: Long) = scope.launch { store.setFavorite(id, true) }
    fun ok(id: Long) = scope.launch {
        if (!store.confirmUserWrite()) return@launch
        store.setFavorite(id, true)
    }
}"""
    calls, problems, _ = write_consent_check({"ui/Store.kt": wc_strip(store)}, {"setFavorite"})
    eq((_wc_names(problems), calls), (["bad"], 2), "write-consent: Feld vom Typ WatchStore")
    launch_writes = f"""class D {{
    private fun launchWrite(cancelled: () -> Unit = {{}}, block: suspend () -> Unit) =
        scope.launch {{ if (!{r}.confirmUserWrite()) block() else cancelled() }}
}}
class E {{
    private fun launchWrite(block: suspend () -> Unit) = scope.launch {{ if ({r}.confirmUserWrite()) block() }}
}}"""
    eq(len(wc_launch_write_problems(wc_strip(launch_writes))), 1, "write-consent: launchWrite im Ablehnungszweig")
    foreign = f"""class C(private val {r}: WatchRepository) {{
    private fun launchWrite(block: suspend () -> Unit) = scope.launch {{ if ({r}.confirmUserWrite()) block() }}
    fun okChild(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        launch {{ {r}.setFavorite(id, true) }}
        withContext(Dispatchers.IO) {{ {r}.reorder(listOf(id)) }}
    }}
    fun okClosedLaunch(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        appScope.launch {{ log() }}
        {r}.setFavorite(id, true)
    }}
    fun foreign(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        appScope.launch {{ {r}.setFavorite(id, true) }}
    }}
    fun sameScopeAgain(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        viewModelScope.launch {{ {r}.setFavorite(id, true) }}
    }}
    fun global(id: Long) = viewModelScope.launch {{
        if ({r}.confirmUserWrite()) GlobalScope.launch(Dispatchers.IO) {{ {r}.setFavorite(id, true) }}
    }}
    fun newScope(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        CoroutineScope(Dispatchers.IO).async {{ {r}.reorder(listOf(id)) }}
    }}
    fun inLaunchWrite(id: Long) {{ launchWrite {{ appScope.launch {{ {r}.setFavorite(id, false) }} }} }}
    fun viaHelper(id: Long) = viewModelScope.launch {{
        if (!{r}.confirmUserWrite()) return@launch
        appScope.launch {{ helper(id) }}
    }}
    private suspend fun helper(id: Long) {{ {r}.deleteWatches(listOf(id)) }}
}}"""
    calls, problems, _ = write_consent_check({"ui/Sample.kt": wc_strip(foreign)}, {"setFavorite", "reorder", "deleteWatches"})
    eq(_wc_names(problems), ["foreign", "sameScopeAgain", "global", "newScope", "inLaunchWrite", "helper"], "write-consent: fremder Scope")
    eq(calls, 9, "write-consent: Aufrufe (fremder Scope)")
    eq(wc_launch_write_problems(wc_strip(foreign)), [], "write-consent: launchWrite ok")
    detached = f"""class D {{
    private fun launchWrite(block: suspend () -> Unit) =
        scope.launch {{ if ({r}.confirmUserWrite()) appScope.launch {{ block() }} }}
}}"""
    eq(len(wc_launch_write_problems(wc_strip(detached))), 1, "write-consent: launchWrite losgelöst")
    counter = f"""class C(private val {r}: WatchRepository, private val other: Store) {{
    private fun launchWrite(block: suspend () -> Unit) = scope.launch {{ if ({r}.confirmUserWrite()) block() }}
    fun ok(id: Long) {{
        launchWrite {{ {r}.setFavorite(id, true) }}
    }}
    fun okGuard(id: Long) {{
        scope.launch {{
            if (!{r}.confirmUserWrite()) return@launch
            helper(id)
        }}
    }}
    private suspend fun helper(id: Long) {{ {r}.deleteWatches(listOf(id)) }}
    fun bad(id: Long) {{
        scope.launch {{ {r}.setFavorite(id, false) }}
    }}
    fun afterBlock(ids: List<Long>) {{
        launchWrite {{ }}
        scope.launch {{ {r}.reorder(ids) }}
    }}
    fun closedGuard(id: Long) {{
        scope.launch {{ {r}.confirmUserWrite() }}
        scope.launch {{ {r}.setAlarmEnabled(id, true) }}
    }}
    fun systemNotListed(id: Long) {{ scope.launch {{ {r}.setAlarmEnabled(id, true, fromUser = false) }} }}
    fun cancelledPath(id: Long) {{ launchWrite(cancelled = {{ {r}.setFavorite(id, false) }}) {{ }} }}
    // {r}.setFavorite(1, true) — Kommentar zählt nicht
    fun notRepo() {{ scope.launch {{ other.reorder(emptyList()) }} }}
}}"""
    calls, problems, _ = write_consent_check({"ui/Sample.kt": wc_strip(counter)}, {"setFavorite", "reorder", "deleteWatches", "setAlarmEnabled"})
    eq(_wc_names(problems), ["bad", "afterBlock", "closedGuard", "systemNotListed", "cancelledPath"], "write-consent: Gegenprobe")
    eq(calls, 7, "write-consent: Aufrufe (Gegenprobe)")


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) > 1:
        print("Usage: source_rules.py [repository root] | --self-test", file=sys.stderr)
        return 2
    return run(argv[0] if argv else ".")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
