"""Room schema drift: compares the schema files Room exported during the build with the committed ones.

Usage: python3 -I room_schema_drift.py <schema dir> [<generated sources dir>...]   (paths relative to the current dir)
       python3 -I room_schema_drift.py --self-test

Fails (exit 1, error annotation) when a committed schema file now has another identity hash (an entity
changed without a new database version) or when the build produced a schema file that is not committed
(new version without its schema in the repository). Formatting differences are ignored.
Outside a git work tree (e.g. an unpacked source ZIP) there is nothing committed to compare against: the script says
so and skips this part instead of reporting every file as uncommitted.

With generated sources dirs (e.g. data/build/generated/ksp): the identityHash of each committed <version>.json must
equal the one compiled into the database's generated <Database>_Impl (RoomOpenDelegate). Room does not rewrite a
schema file that differs only in its identityHash, so the comparison above cannot see a wrong hash (e.g. a schema
edited by hand) - the device tests then fail with «Room cannot verify the data integrity». Every schema directory
needs its _Impl and the latest <version>.json; an _Impl whose hash cannot be read is an error, never a silent pass.
"""
import contextlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile

# Room 2.7+ (Kotlin code generation): RoomOpenDelegate(version, identityHash, legacyIdentityHash)
OPEN_DELEGATE = re.compile(r'RoomOpenDelegate\(\s*(?:version\s*=\s*)?(\d+)\s*,\s*(?:identityHash\s*=\s*)?"([0-9a-f]{32})"'
                           r'\s*,\s*(?:legacyIdentityHash\s*=\s*)?"([0-9a-f]{32})"\s*\)')


def identity(text):
    try:
        return json.loads(text)["database"]["identityHash"]
    except (ValueError, KeyError, TypeError):
        return None


def in_git_work_tree():
    try:
        done = subprocess.run(["git", "rev-parse", "--is-inside-work-tree"], capture_output=True, text=True)
    except OSError:
        return False
    return done.returncode == 0 and done.stdout.strip() == "true"


def drift_problems(root):
    problems = []
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as f:
                built = identity(f.read())
            shown = subprocess.run(["git", "show", "HEAD:./" + path], capture_output=True, text=True)
            if shown.returncode != 0:
                problems.append(f"{path}: produced by the build but not committed")
                continue
            committed = identity(shown.stdout)
            if committed != built:
                problems.append(f"{path}: identity hash {committed} committed, {built} built")
    return problems


def impl_files(generated_roots, database):
    """Generated <Database>_Impl.kt/.java of a database (fully qualified name) under the roots, one per variant."""
    suffixes = tuple(os.path.join(*database.split(".")) + "_Impl" + ext for ext in (".kt", ".java"))
    found = []
    for root in generated_roots:
        for dirpath, _, files in os.walk(root):
            for name in sorted(files):
                path = os.path.join(dirpath, name)
                if path.endswith(suffixes):
                    found.append(path)
    return sorted(found)


def generated_problems(schema_root, generated_roots):
    """Committed identityHash against the compiled one, for the version each _Impl opens."""
    problems = []
    databases = sorted(d for d in os.listdir(schema_root) if os.path.isdir(os.path.join(schema_root, d)))
    if not databases:
        return [f"{schema_root}: no schema directories"]
    for database in databases:
        impls = impl_files(generated_roots, database)
        if not impls:
            problems.append(f"{database}: no generated {database.rsplit('.', 1)[-1]}_Impl under "
                            + ", ".join(generated_roots))
            continue
        for impl in impls:
            with open(impl, encoding="utf-8") as f:
                match = OPEN_DELEGATE.search(f.read())
            if not match:
                problems.append(f"{impl}: identity hash not found (RoomOpenDelegate)")
                continue
            version, compiled = match.group(1), match.group(2)
            schema = os.path.join(schema_root, database, version + ".json")
            if not os.path.isfile(schema):
                problems.append(f"{schema}: missing (database version {version} in {impl})")
                continue
            with open(schema, encoding="utf-8") as f:
                committed = identity(f.read())
            if committed != compiled:
                problems.append(f"{schema}: identity hash {committed} committed, {compiled} compiled ({impl})")
    return problems


def main(argv):
    root, generated_roots = argv[0], argv[1:]
    drift = []
    if in_git_work_tree():
        drift = drift_problems(root)
    else:
        print("Room schema drift: no git work tree here, nothing committed to compare (skipped)")
    hashes = generated_problems(root, generated_roots) if generated_roots else []
    if drift:
        print("::error title=Room schema drift::" + " | ".join(drift)
              + " – raise the database version, add a migration and commit the exported schema")
    if hashes:
        print("::error title=Room identity hash::" + " | ".join(hashes)
              + " – the schema file must carry the hash Room compiles from the entities (regenerate it)")
    if drift or hashes:
        return 1
    print("Room schemas unchanged" + (", identity hashes match the generated code" if generated_roots else ""))
    return 0


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def _schema(h):
    return json.dumps({"formatVersion": 1, "database": {"version": 1, "identityHash": h, "entities": []}})


def _impl(version, h, legacy):
    return f'public override fun createOpenDelegate(): RoomOpenDelegate {{\n  val _openDelegate: RoomOpenDelegate = ' \
           f'object : RoomOpenDelegate({version}, "{h}", \n "{legacy}") {{\n'


def self_test():
    a, b, c = "a" * 32, "b" * 32, "c" * 32
    with tempfile.TemporaryDirectory() as tmp:
        schemas, gen = os.path.join(tmp, "schemas"), os.path.join(tmp, "ksp")
        _write(os.path.join(schemas, "com.x.MainDb", "1.json"), _schema(a))
        _write(os.path.join(schemas, "com.x.MainDb", "2.json"), _schema(b))
        _write(os.path.join(schemas, "com.y.OtherDb", "1.json"), _schema(c))
        main_impl = os.path.join(gen, "debug", "kotlin", "com", "x", "MainDb_Impl.kt")
        _write(main_impl, _impl(2, b, c))
        _write(os.path.join(gen, "release", "kotlin", "com", "x", "MainDb_Impl.kt"), _impl(2, b, c))
        _write(os.path.join(gen, "debug", "kotlin", "com", "y", "OtherDb_Impl.kt"), _impl(1, c, a))
        # Not a match: other package, other name
        _write(os.path.join(gen, "debug", "kotlin", "com", "z", "MainDb_Impl.kt"), _impl(1, a, a))
        assert len(impl_files([gen], "com.x.MainDb")) == 2, impl_files([gen], "com.x.MainDb")
        named = OPEN_DELEGATE.search(f'RoomOpenDelegate(version = 4, identityHash = "{a}", legacyIdentityHash = "{b}")')
        assert named and named.groups() == ("4", a, b), named
        assert generated_problems(schemas, [gen]) == [], generated_problems(schemas, [gen])
        # Hash edited by hand (the build-163 case): only the latest version counts, older files keep their hash
        _write(os.path.join(schemas, "com.x.MainDb", "2.json"), _schema(a))
        found = generated_problems(schemas, [gen])
        assert len(found) == 2 and all(f"identity hash {a} committed, {b} compiled" in p for p in found), found
        # The legacy hash is not enough: Room exports identityHash
        _write(os.path.join(schemas, "com.x.MainDb", "2.json"), _schema(c))
        assert len(generated_problems(schemas, [gen])) == 2
        _write(os.path.join(schemas, "com.x.MainDb", "2.json"), _schema(b))
        # Version compiled without its schema file
        _write(main_impl, _impl(3, b, c))
        assert any("3.json: missing" in p for p in generated_problems(schemas, [gen]))
        # Unreadable _Impl and missing _Impl are errors, never a silent pass
        _write(main_impl, "class MainDb_Impl")
        assert any("identity hash not found" in p for p in generated_problems(schemas, [gen]))
        assert any("no generated OtherDb_Impl" in p for p in generated_problems(schemas, [os.path.join(gen, "x")]))
        assert generated_problems(os.path.join(tmp, "ksp", "debug", "kotlin", "com", "x"), [gen]) == [
            os.path.join(tmp, "ksp", "debug", "kotlin", "com", "x") + ": no schema directories"]
        _write(main_impl, _impl(2, b, c))
        # Through main (outside a git work tree: drift part skipped, hash part still runs)
        out = io.StringIO()
        cwd = os.getcwd()
        try:
            os.chdir(tmp)
            with contextlib.redirect_stdout(out):
                assert main(["schemas", "ksp"]) == 0
                _write(os.path.join(tmp, "schemas", "com.y.OtherDb", "1.json"), _schema(a))
                assert main(["schemas", "ksp"]) == 1
                assert main(["schemas"]) == 0
        finally:
            os.chdir(cwd)
        text = out.getvalue()
        assert "identity hashes match the generated code" in text, text
        assert text.count("::error title=Room identity hash::") == 1, text
        assert "::error title=Room schema drift::" not in text, text
    print("room_schema_drift self-test ok")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(main(sys.argv[1:]))
