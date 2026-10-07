#!/usr/bin/env python3
"""Generate CryptoChecker.xcodeproj (project.pbxproj + shared scheme) for the iOS port.

Rerunnable: Swift files and resources are collected from Shared/, App/ and Widgets/ at run
time, object IDs are derived from hashes, so the output is deterministic (same tree -> same file).

  Shared/**  -> compiled into both targets (InfoPlist.xcstrings: app only)
  App/**     -> app target "CryptoChecker"
  Widgets/** -> widget extension "CryptoCheckerWidgetsExtension"
  App/PrivacyInfo.xcprivacy -> resource in both targets (privacy manifest per bundle)

Usage: python3 tools/gen_xcodeproj.py            (generate + validate)
       python3 tools/gen_xcodeproj.py --check    (only validate the existing project file)
"""
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PROJECT_NAME = "CryptoChecker"
PROJ_DIR = os.path.join(ROOT, PROJECT_NAME + ".xcodeproj")

MARKETING_VERSION = "16.2.2"
CURRENT_PROJECT_VERSION = "17"
DEPLOYMENT_TARGET = "17.0"
APP_ID = "com.cryptochecker.app"
WIDGET_ID = "com.cryptochecker.app.widgets"
APP_TARGET = "CryptoChecker"
WIDGET_TARGET = "CryptoCheckerWidgetsExtension"
ALTERNATE_ICONS = ["AppIconOrangeLight", "AppIconRed", "AppIconRedLight", "AppIconBlue",
                   "AppIconBlueLight", "AppIconGreen", "AppIconGreenLight",
                   "AppIconMarrsGreen", "AppIconMarrsGreenLight"]
TOP_FOLDERS = ["Shared", "App", "Widgets"]
APP_ONLY_RESOURCES = {"InfoPlist.xcstrings"}
# Ressourcen aus App/, die auch die Widget-Erweiterung braucht (eigenes Bundle,
# eigenes Datenschutz-Manifest: beide nutzen UserDefaults).
BOTH_TARGET_RESOURCES = {os.path.join("App", "PrivacyInfo.xcprivacy")}

FILE_TYPES = {
    ".swift": "sourcecode.swift",
    ".xcassets": "folder.assetcatalog",
    ".xcstrings": "text.json.xcstrings",
    ".plist": "text.plist.xml",
    ".entitlements": "text.plist.entitlements",
    ".strings": "text.plist.strings",
    ".json": "text.json",
    ".png": "image.png",
    ".storyboard": "file.storyboard",
    ".intentdefinition": "file.intentdefinition",
    ".xcprivacy": "text.xml",
    ".wav": "audio.wav",
    ".txt": "text",
}
# .txt: Lizenztexte (App/Resources/Licenses), nur im App-Ziel
RESOURCE_EXTS = {".xcassets", ".xcstrings", ".strings", ".json", ".png", ".storyboard", ".xcprivacy", ".wav", ".txt"}


def known_regions():
    path = os.path.join(ROOT, "Shared", "Resources", "Localizable.xcstrings")
    langs = set()
    with open(path, encoding="utf-8") as f:
        for entry in json.load(f)["strings"].values():
            langs.update(entry.get("localizations", {}))
    return sorted(langs) + ["Base"]


def uid(*parts):
    return hashlib.md5("|".join(parts).encode()).hexdigest()[:24].upper()


# ------------------------------------------------------------------ file tree

class Node:
    def __init__(self, name, rel, is_group):
        self.name, self.rel, self.is_group = name, rel, is_group
        self.children = []
        self.id = uid("group" if is_group else "file", rel)


def scan(rel):
    node = Node(os.path.basename(rel), rel, True)
    full = os.path.join(ROOT, rel)
    entries = sorted(os.listdir(full), key=lambda s: s.lower())
    dirs, files = [], []
    for e in entries:
        if e.startswith(".") or e == "__pycache__":
            continue
        p = os.path.join(full, e)
        ext = os.path.splitext(e)[1]
        if os.path.isdir(p) and ext not in FILE_TYPES:
            sub = scan(os.path.join(rel, e))
            if sub.children:
                dirs.append(sub)
        elif ext in FILE_TYPES:
            files.append(Node(e, os.path.join(rel, e), False))
        else:
            print(f"note: ignoring {os.path.join(rel, e)} (unknown type)")
    node.children = dirs + files
    return node


def walk_files(node):
    for c in node.children:
        if c.is_group:
            yield from walk_files(c)
        else:
            yield c


# ------------------------------------------------------------------ serialisation

SAFE = re.compile(r"^[A-Za-z0-9_./]+$")


def q(v):
    s = str(v)
    if SAFE.match(s) and not s.startswith("//"):
        return s
    s = s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    return f'"{s}"'


class Obj(dict):
    """Ordered object with a comment for the pbxproj writer."""
    def __init__(self, oid, comment, **kv):
        super().__init__(kv)
        self.oid, self.comment = oid, comment


def fmt_value(v, objs, indent, inline):
    if isinstance(v, dict):
        if inline:
            return "{" + "".join(f"{q(k)} = {fmt_value(x, objs, indent, True)}; " for k, x in v.items()) + "}"
        pad = "\t" * (indent + 1)
        body = "".join(f"{pad}{q(k)} = {fmt_value(x, objs, indent + 1, False)};\n" for k, x in v.items())
        return "{\n" + body + "\t" * indent + "}"
    if isinstance(v, list):
        if inline:
            return "(" + "".join(f"{fmt_value(x, objs, indent, True)}, " for x in v) + ")"
        pad = "\t" * (indent + 1)
        body = "".join(f"{pad}{fmt_value(x, objs, indent + 1, False)},\n" for x in v)
        return "(\n" + body + "\t" * indent + ")"
    s = str(v)
    if s in objs and objs[s].comment:
        return f"{s} /* {objs[s].comment} */"
    return q(s)


def write_pbxproj(objs, root_id, path):
    out = ["// !$*UTF8*$!", "{",
           "\tarchiveVersion = 1;", "\tclasses = {", "\t};", "\tobjectVersion = 56;", "\tobjects = {"]
    by_isa = {}
    for o in objs.values():
        by_isa.setdefault(o["isa"], []).append(o)
    for isa in sorted(by_isa):
        out.append("")
        out.append(f"/* Begin {isa} section */")
        inline = isa in ("PBXBuildFile", "PBXFileReference")
        for o in sorted(by_isa[isa], key=lambda x: x.oid):
            head = f"\t\t{o.oid} /* {o.comment} */ = " if o.comment else f"\t\t{o.oid} = "
            out.append(head + fmt_value(dict(o), objs, 2, inline) + ";")
        out.append(f"/* End {isa} section */")
    out += ["\t};", f"\trootObject = {root_id} /* Project object */;", "}", ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


# ------------------------------------------------------------------ build settings

def project_settings(debug):
    s = {
        "ALWAYS_SEARCH_USER_PATHS": "NO",
        "ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOLS": "NO",
        "CLANG_ANALYZER_NONNULL": "YES",
        "CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION": "YES_AGGRESSIVE",
        "CLANG_CXX_LANGUAGE_STANDARD": "gnu++20",
        "CLANG_ENABLE_MODULES": "YES",
        "CLANG_ENABLE_OBJC_ARC": "YES",
        "CLANG_ENABLE_OBJC_WEAK": "YES",
        "CLANG_WARN_BLOCK_CAPTURE_AUTORELEASING": "YES",
        "CLANG_WARN_BOOL_CONVERSION": "YES",
        "CLANG_WARN_COMMA": "YES",
        "CLANG_WARN_CONSTANT_CONVERSION": "YES",
        "CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS": "YES",
        "CLANG_WARN_DIRECT_OBJC_ISA_USAGE": "YES_ERROR",
        "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES",
        "CLANG_WARN_EMPTY_BODY": "YES",
        "CLANG_WARN_ENUM_CONVERSION": "YES",
        "CLANG_WARN_INFINITE_RECURSION": "YES",
        "CLANG_WARN_INT_CONVERSION": "YES",
        "CLANG_WARN_NON_LITERAL_NULL_CONVERSION": "YES",
        "CLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF": "YES",
        "CLANG_WARN_OBJC_LITERAL_CONVERSION": "YES",
        "CLANG_WARN_OBJC_ROOT_CLASS": "YES_ERROR",
        "CLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER": "YES",
        "CLANG_WARN_RANGE_LOOP_ANALYSIS": "YES",
        "CLANG_WARN_STRICT_PROTOTYPES": "YES",
        "CLANG_WARN_SUSPICIOUS_MOVE": "YES",
        "CLANG_WARN_UNGUARDED_AVAILABILITY": "YES_AGGRESSIVE",
        "CLANG_WARN_UNREACHABLE_CODE": "YES",
        "CLANG_WARN__DUPLICATE_METHOD": "YES",
        "COPY_PHASE_STRIP": "NO",
        "DEBUG_INFORMATION_FORMAT": "dwarf" if debug else "dwarf-with-dsym",
        "ENABLE_STRICT_OBJC_MSGSEND": "YES",
        "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
        "GCC_C_LANGUAGE_STANDARD": "gnu17",
        "GCC_NO_COMMON_BLOCKS": "YES",
        "GCC_WARN_64_TO_32_BIT_CONVERSION": "YES",
        "GCC_WARN_ABOUT_RETURN_TYPE": "YES_ERROR",
        "GCC_WARN_UNDECLARED_SELECTOR": "YES",
        "GCC_WARN_UNINITIALIZED_AUTOS": "YES_AGGRESSIVE",
        "GCC_WARN_UNUSED_FUNCTION": "YES",
        "GCC_WARN_UNUSED_VARIABLE": "YES",
        "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
        "LOCALIZATION_PREFERS_STRING_CATALOGS": "YES",
        "MTL_FAST_MATH": "YES",
        "SDKROOT": "iphoneos",
        "SWIFT_VERSION": "5.0",
    }
    if debug:
        s.update({
            "ENABLE_TESTABILITY": "YES",
            "GCC_DYNAMIC_NO_PIC": "NO",
            "GCC_OPTIMIZATION_LEVEL": "0",
            "GCC_PREPROCESSOR_DEFINITIONS": ["DEBUG=1", "$(inherited)"],
            "MTL_ENABLE_DEBUG_INFO": "INCLUDE_SOURCE",
            "ONLY_ACTIVE_ARCH": "YES",
            "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG $(inherited)",
            "SWIFT_OPTIMIZATION_LEVEL": "-Onone",
        })
    else:
        s.update({
            "ENABLE_NS_ASSERTIONS": "NO",
            "MTL_ENABLE_DEBUG_INFO": "NO",
            "SWIFT_COMPILATION_MODE": "wholemodule",
            "VALIDATE_PRODUCT": "YES",
        })
    return dict(sorted(s.items()))


def common_target_settings():
    return {
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": CURRENT_PROJECT_VERSION,
        "DEVELOPMENT_TEAM": "",
        "GENERATE_INFOPLIST_FILE": "NO",
        "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
        "MARKETING_VERSION": MARKETING_VERSION,
        "PRODUCT_NAME": "$(TARGET_NAME)",
        "SWIFT_EMIT_LOC_STRINGS": "NO",
        "SWIFT_VERSION": "5.0",
        "TARGETED_DEVICE_FAMILY": "1,2",
    }


def app_settings():
    s = common_target_settings()
    s.update({
        "ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES": " ".join(ALTERNATE_ICONS),
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
        "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
        "ASSETCATALOG_COMPILER_INCLUDE_ALL_APPICON_ASSETS": "YES",
        "CODE_SIGN_ENTITLEMENTS": "App/CryptoChecker.entitlements",
        "ENABLE_PREVIEWS": "YES",
        "INFOPLIST_FILE": "App/Info.plist",
        "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
        "PRODUCT_BUNDLE_IDENTIFIER": APP_ID,
    })
    return dict(sorted(s.items()))


def widget_settings():
    s = common_target_settings()
    s.update({
        "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
        "ASSETCATALOG_COMPILER_WIDGET_BACKGROUND_COLOR_NAME": "WidgetBackground",
        "CODE_SIGN_ENTITLEMENTS": "Widgets/CryptoCheckerWidgets.entitlements",
        "ENABLE_PREVIEWS": "YES",
        "INFOPLIST_FILE": "Widgets/Info.plist",
        "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks",
                                   "@executable_path/../../Frameworks"],
        "PRODUCT_BUNDLE_IDENTIFIER": WIDGET_ID,
        "SKIP_INSTALL": "YES",
    })
    return dict(sorted(s.items()))


# ------------------------------------------------------------------ project

def build():
    objs = {}

    def add(o):
        if o.oid in objs:
            raise SystemExit(f"duplicate object id {o.oid} ({o.comment})")
        objs[o.oid] = o
        return o.oid

    trees = [scan(f) for f in TOP_FOLDERS if os.path.isdir(os.path.join(ROOT, f))]

    # file references + groups
    def emit_group(node):
        children = []
        for c in node.children:
            if c.is_group:
                emit_group(c)
            else:
                ext = os.path.splitext(c.name)[1]
                add(Obj(c.id, c.name, isa="PBXFileReference", lastKnownFileType=FILE_TYPES[ext],
                        path=c.name, sourceTree="<group>"))
            children.append(c.id)
        add(Obj(node.id, node.name, isa="PBXGroup", children=children, path=node.name, sourceTree="<group>"))

    for t in trees:
        emit_group(t)

    app_product = uid("product", APP_TARGET)
    widget_product = uid("product", WIDGET_TARGET)
    add(Obj(app_product, f"{APP_TARGET}.app", isa="PBXFileReference", explicitFileType="wrapper.application",
            includeInIndex="0", path=f"{APP_TARGET}.app", sourceTree="BUILT_PRODUCTS_DIR"))
    add(Obj(widget_product, f"{WIDGET_TARGET}.appex", isa="PBXFileReference",
            explicitFileType="wrapper.app-extension", includeInIndex="0", path=f"{WIDGET_TARGET}.appex",
            sourceTree="BUILT_PRODUCTS_DIR"))
    products_group = uid("group", "Products")
    add(Obj(products_group, "Products", isa="PBXGroup", children=[app_product, widget_product],
            name="Products", sourceTree="<group>"))
    main_group = uid("group", "<main>")
    add(Obj(main_group, "", isa="PBXGroup", children=[t.id for t in trees] + [products_group],
            sourceTree="<group>"))

    # assign files to targets
    phases = {APP_TARGET: {"src": [], "res": []}, WIDGET_TARGET: {"src": [], "res": []}}
    names_per_target = {APP_TARGET: {}, WIDGET_TARGET: {}}
    for t in trees:
        for f in walk_files(t):
            ext = os.path.splitext(f.name)[1]
            top = f.rel.split(os.sep)[0]
            targets = {"Shared": [APP_TARGET, WIDGET_TARGET], "App": [APP_TARGET],
                       "Widgets": [WIDGET_TARGET]}[top]
            if f.name in APP_ONLY_RESOURCES:
                targets = [APP_TARGET]
            if f.rel in BOTH_TARGET_RESOURCES:
                targets = [APP_TARGET, WIDGET_TARGET]
            if ext == ".swift":
                kind = "src"
            elif ext in RESOURCE_EXTS:
                kind = "res"
            else:
                continue  # Info.plist, entitlements: referenced via build settings only
            for tgt in targets:
                if kind == "src":
                    other = names_per_target[tgt].get(f.name)
                    if other:
                        print(f"warning: {f.rel} and {other} have the same file name in target {tgt}")
                    names_per_target[tgt][f.name] = f.rel
                bf = uid("buildfile", tgt, f.rel)
                add(Obj(bf, f"{f.name} in {'Sources' if kind == 'src' else 'Resources'}",
                        isa="PBXBuildFile", fileRef=f.id))
                phases[tgt][kind].append(bf)

    # embed extension
    embed_bf = uid("buildfile", "embed", WIDGET_TARGET)
    add(Obj(embed_bf, f"{WIDGET_TARGET}.appex in Embed Foundation Extensions", isa="PBXBuildFile",
            fileRef=widget_product, settings={"ATTRIBUTES": ["RemoveHeadersOnCopy"]}))

    project_id = uid("project")
    targets = {}
    for tgt, settings_fn, product, ptype in (
            (APP_TARGET, app_settings, app_product, "com.apple.product-type.application"),
            (WIDGET_TARGET, widget_settings, widget_product, "com.apple.product-type.app-extension")):
        src = add(Obj(uid("sources", tgt), "Sources", isa="PBXSourcesBuildPhase", buildActionMask="2147483647",
                      files=phases[tgt]["src"], runOnlyForDeploymentPostprocessing="0"))
        fw = add(Obj(uid("frameworks", tgt), "Frameworks", isa="PBXFrameworksBuildPhase",
                     buildActionMask="2147483647", files=[], runOnlyForDeploymentPostprocessing="0"))
        res = add(Obj(uid("resources", tgt), "Resources", isa="PBXResourcesBuildPhase",
                      buildActionMask="2147483647", files=phases[tgt]["res"],
                      runOnlyForDeploymentPostprocessing="0"))
        cfgs = []
        for cfg in ("Debug", "Release"):
            cfgs.append(add(Obj(uid("cfg", tgt, cfg), cfg, isa="XCBuildConfiguration",
                                buildSettings=settings_fn(), name=cfg)))
        cl = add(Obj(uid("cfglist", tgt), f'Build configuration list for PBXNativeTarget "{tgt}"',
                     isa="XCConfigurationList", buildConfigurations=cfgs, defaultConfigurationIsVisible="0",
                     defaultConfigurationName="Release"))
        build_phases = [src, fw, res]
        deps = []
        if tgt == APP_TARGET:
            embed = add(Obj(uid("embedphase", tgt), "Embed Foundation Extensions",
                            isa="PBXCopyFilesBuildPhase", buildActionMask="2147483647", dstPath="",
                            dstSubfolderSpec="13", files=[embed_bf], name="Embed Foundation Extensions",
                            runOnlyForDeploymentPostprocessing="0"))
            build_phases.append(embed)
            proxy = add(Obj(uid("proxy", WIDGET_TARGET), "PBXContainerItemProxy", isa="PBXContainerItemProxy",
                            containerPortal=project_id, proxyType="1",
                            remoteGlobalIDString=uid("target", WIDGET_TARGET), remoteInfo=WIDGET_TARGET))
            deps.append(add(Obj(uid("dep", WIDGET_TARGET), "PBXTargetDependency", isa="PBXTargetDependency",
                                target=uid("target", WIDGET_TARGET), targetProxy=proxy)))
        targets[tgt] = add(Obj(uid("target", tgt), tgt, isa="PBXNativeTarget", buildConfigurationList=cl,
                               buildPhases=build_phases, buildRules=[], dependencies=deps, name=tgt,
                               productName=tgt, productReference=product, productType=ptype))

    pcfgs = [add(Obj(uid("cfg", "project", c), c, isa="XCBuildConfiguration",
                     buildSettings=project_settings(c == "Debug"), name=c)) for c in ("Debug", "Release")]
    pcl = add(Obj(uid("cfglist", "project"), f'Build configuration list for PBXProject "{PROJECT_NAME}"',
                  isa="XCConfigurationList", buildConfigurations=pcfgs, defaultConfigurationIsVisible="0",
                  defaultConfigurationName="Release"))
    add(Obj(project_id, "Project object", isa="PBXProject",
            attributes={"BuildIndependentTargetsInParallel": "1", "LastSwiftUpdateCheck": "1530",
                        "LastUpgradeCheck": "1530",
                        "TargetAttributes": {targets[APP_TARGET]: {"CreatedOnToolsVersion": "15.3"},
                                             targets[WIDGET_TARGET]: {"CreatedOnToolsVersion": "15.3"}}},
            buildConfigurationList=pcl, compatibilityVersion="Xcode 14.0", developmentRegion="en",
            hasScannedForEncodings="0", knownRegions=known_regions(), mainGroup=main_group,
            productRefGroup=products_group, projectDirPath="", projectRoot="",
            targets=[targets[APP_TARGET], targets[WIDGET_TARGET]]))
    return objs, project_id, targets, phases


def scheme_xml(targets):
    def ref(tgt, product):
        return (f'<BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "{targets[tgt]}" '
                f'BuildableName = "{product}" BlueprintName = "{tgt}" '
                f'ReferencedContainer = "container:{PROJECT_NAME}.xcodeproj">\n')

    def br(tgt, product, indent):
        pad = " " * indent
        return (pad + ref(tgt, product).replace("> \n", ">\n").replace("\" Blue", "\"\n" + pad + "   Blue")
                .replace("\" Build", "\"\n" + pad + "   Build").replace("\" Refer", "\"\n" + pad + "   Refer")
                + pad + "</BuildableReference>\n")

    app = (APP_TARGET, f"{APP_TARGET}.app")
    wid = (WIDGET_TARGET, f"{WIDGET_TARGET}.appex")

    def entry(t):
        return ('         <BuildActionEntry\n            buildForTesting = "YES"\n            buildForRunning = "YES"\n'
                '            buildForProfiling = "YES"\n            buildForArchiving = "YES"\n'
                '            buildForAnalyzing = "YES">\n' + br(*t, 12) + '         </BuildActionEntry>\n')

    runnable = lambda: ('      <BuildableProductRunnable\n         runnableDebuggingMode = "0">\n'
                        + br(*app, 9) + '      </BuildableProductRunnable>\n')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<Scheme\n   LastUpgradeVersion = "1530"\n   version = "1.7">\n'
            '   <BuildAction\n      parallelizeBuildables = "YES"\n      buildImplicitDependencies = "YES">\n'
            '      <BuildActionEntries>\n' + entry(app) + entry(wid) + '      </BuildActionEntries>\n'
            '   </BuildAction>\n'
            '   <TestAction\n      buildConfiguration = "Debug"\n'
            '      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"\n'
            '      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"\n'
            '      shouldUseLaunchSchemeArgsEnv = "YES"\n      shouldAutocreateTestPlan = "YES">\n'
            '   </TestAction>\n'
            '   <LaunchAction\n      buildConfiguration = "Debug"\n'
            '      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"\n'
            '      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"\n'
            '      launchStyle = "0"\n      useCustomWorkingDirectory = "NO"\n      ignoresPersistentStateOnLaunch = "NO"\n'
            '      debugDocumentVersioning = "YES"\n      debugServiceExtension = "internal"\n'
            '      allowLocationSimulation = "YES">\n' + runnable() + '   </LaunchAction>\n'
            '   <ProfileAction\n      buildConfiguration = "Release"\n      shouldUseLaunchSchemeArgsEnv = "YES"\n'
            '      savedToolIdentifier = ""\n      useCustomWorkingDirectory = "NO"\n'
            '      debugDocumentVersioning = "YES">\n' + runnable() + '   </ProfileAction>\n'
            '   <AnalyzeAction\n      buildConfiguration = "Debug">\n   </AnalyzeAction>\n'
            '   <ArchiveAction\n      buildConfiguration = "Release"\n      revealArchiveInOrganizer = "YES">\n'
            '   </ArchiveAction>\n</Scheme>\n')


# ------------------------------------------------------------------ validation (independent parser)

def parse_openstep(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"^//.*$", "", text, flags=re.M)
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|[{}();=,]|[^\s{}();=,"]+', text)
    pos = 0

    def val():
        nonlocal pos
        t = tokens[pos]
        pos += 1
        if t == "{":
            d = {}
            while tokens[pos] != "}":
                k = val()
                assert tokens[pos] == "=", f"expected = after {k!r}, got {tokens[pos]!r}"
                pos += 1
                d[k] = val()
                assert tokens[pos] == ";", f"expected ; after value of {k!r}, got {tokens[pos]!r}"
                pos += 1
            pos += 1
            return d
        if t == "(":
            arr = []
            while tokens[pos] != ")":
                arr.append(val())
                if tokens[pos] == ",":
                    pos += 1
                else:
                    assert tokens[pos] == ")", f"expected , or ) got {tokens[pos]!r}"
            pos += 1
            return arr
        if t.startswith('"'):
            return bytes(t[1:-1], "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")
        assert t not in "{}();=,", f"unexpected token {t!r}"
        return t

    result = val()
    assert pos == len(tokens), "trailing tokens"
    return result


def validate(path, expect_sources=None):
    errors = []
    with open(path, encoding="utf-8") as f:
        data = parse_openstep(f.read())
    objs = data["objects"]
    root = objs[data["rootObject"]]
    idre = re.compile(r"^[0-9A-F]{24}$")

    # every ID-looking value must exist
    def refs(v, where):
        if isinstance(v, dict):
            for k, x in v.items():
                if k == "remoteGlobalIDString":
                    if x not in objs:
                        errors.append(f"{where}.{k}: missing {x}")
                    continue
                if idre.match(k) and k not in objs:
                    errors.append(f"{where}: key {k} not an object")
                refs(x, f"{where}.{k}")
        elif isinstance(v, list):
            for x in v:
                refs(x, where)
        elif isinstance(v, str) and idre.match(v) and v not in objs:
            errors.append(f"{where}: dangling reference {v}")

    for oid, o in objs.items():
        refs(o, oid)
        if "isa" not in o:
            errors.append(f"{oid}: no isa")

    # every file ref in exactly one group
    parent_count = {}
    for oid, o in objs.items():
        if o["isa"] in ("PBXGroup", "PBXVariantGroup"):
            for c in o["children"]:
                parent_count[c] = parent_count.get(c, 0) + 1
    for oid, o in objs.items():
        if o["isa"] in ("PBXFileReference", "PBXGroup") and oid != root["mainGroup"]:
            if parent_count.get(oid, 0) != 1:
                errors.append(f"{oid} ({o.get('path')}) is in {parent_count.get(oid, 0)} groups")
    for oid, o in objs.items():
        if o["isa"] == "PBXBuildFile" and objs.get(o.get("fileRef"), {}).get("isa") != "PBXFileReference":
            errors.append(f"build file {oid} does not reference a PBXFileReference")

    # build files used exactly once
    used = {}
    for oid, o in objs.items():
        if o["isa"].endswith("BuildPhase"):
            for bf in o["files"]:
                used[bf] = used.get(bf, 0) + 1
    for oid, o in objs.items():
        if o["isa"] == "PBXBuildFile" and used.get(oid) != 1:
            errors.append(f"build file {oid} used {used.get(oid, 0)} times")

    # files exist on disk (resolve group paths)
    def resolve(gid, base):
        g = objs[gid]
        for c in g["children"]:
            if c not in objs:
                continue  # already reported as dangling
            o = objs[c]
            p = os.path.join(base, o.get("path", "")) if o.get("sourceTree") == "<group>" else None
            if o["isa"] == "PBXGroup":
                resolve(c, p if p is not None else base)
            elif p is not None and not os.path.exists(p):
                errors.append(f"file missing on disk: {p}")
    resolve(root["mainGroup"], ROOT)

    # file types
    for oid, o in objs.items():
        if o["isa"] == "PBXFileReference" and "lastKnownFileType" in o:
            ext = os.path.splitext(o["path"])[1]
            if FILE_TYPES.get(ext) != o["lastKnownFileType"]:
                errors.append(f"{o['path']}: wrong file type {o['lastKnownFileType']}")

    # per-target summary
    summary = {}
    for tid in root["targets"]:
        t = objs[tid]
        s = {}
        for ph in t["buildPhases"]:
            p = objs[ph]
            s[p["isa"]] = [objs.get(objs.get(b, {}).get("fileRef"), {}).get("path", "?") for b in p["files"]]
        summary[t["name"]] = s
        for cfg in objs[t["buildConfigurationList"]]["buildConfigurations"]:
            bs = objs[cfg]["buildSettings"]
            for key in ("INFOPLIST_FILE", "CODE_SIGN_ENTITLEMENTS"):
                if not os.path.exists(os.path.join(ROOT, bs[key])):
                    errors.append(f"{t['name']}/{objs[cfg]['name']}: {key} {bs[key]} missing")
    if expect_sources:
        for tgt, files in expect_sources.items():
            got = sorted(summary[tgt]["PBXSourcesBuildPhase"])
            if got != sorted(files):
                errors.append(f"{tgt}: source list mismatch")
    return errors, summary


def main():
    if "--check" not in sys.argv:
        objs, root_id, targets, phases = build()
        os.makedirs(os.path.join(PROJ_DIR, "xcshareddata", "xcschemes"), exist_ok=True)
        os.makedirs(os.path.join(PROJ_DIR, "project.xcworkspace"), exist_ok=True)
        write_pbxproj(objs, root_id, os.path.join(PROJ_DIR, "project.pbxproj"))
        with open(os.path.join(PROJ_DIR, "xcshareddata", "xcschemes", f"{PROJECT_NAME}.xcscheme"), "w") as f:
            f.write(scheme_xml(targets))
        with open(os.path.join(PROJ_DIR, "project.xcworkspace", "contents.xcworkspacedata"), "w") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n<Workspace\n   version = "1.0">\n'
                    '   <FileRef\n      location = "self:">\n   </FileRef>\n</Workspace>\n')
    errors, summary = validate(os.path.join(PROJ_DIR, "project.pbxproj"))
    for tgt, phases in summary.items():
        print(f"{tgt}: " + ", ".join(f"{k.replace('PBX', '').replace('BuildPhase', '')}={len(v)}"
                                     for k, v in phases.items()))
        print(f"  resources: {', '.join(phases.get('PBXResourcesBuildPhase', []))}")
    if errors:
        print(f"{len(errors)} problem(s):")
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("project.pbxproj OK:", os.path.join(PROJ_DIR, "project.pbxproj"))


if __name__ == "__main__":
    main()
