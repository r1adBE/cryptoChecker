#!/usr/bin/env python3
"""Compares two independent builds of the same commit (reproducible-build check, reproducible.yml).

Each input directory holds what one build produced: the unsigned release APK and AAB and the SBOM files. Files are
compared byte for byte; for archives (.apk/.aab) the differing entries are named (entry list, CRC-32, size), so a
non-reproducible part (timestamp, ordering, build path) can be found without downloading anything.

Output: one finding per line in the format of annotate.py (level, file, line, message): an error per differing file
(with up to 20 differing entries), a notice with the SHA-256 of every identical file.

Usage: python3 -I repro_compare.py <build A dir> <build B dir> <findings file>
       python3 -I repro_compare.py --self-test
"""
import hashlib
import os
import sys
import tempfile
import zipfile

ARCHIVES = (".apk", ".aab")
MAX_ENTRIES = 20


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def files_of(directory):
    out = {}
    for base, _, files in os.walk(directory):
        for name in files:
            path = os.path.join(base, name)
            out[os.path.relpath(path, directory).replace(os.sep, "/")] = path
    return out


def archive_diff(a, b):
    """Differing entries of two zip archives: missing on one side, other CRC or size, other order."""
    with zipfile.ZipFile(a) as za, zipfile.ZipFile(b) as zb:
        ia = {i.filename: i for i in za.infolist()}
        ib = {i.filename: i for i in zb.infolist()}
        diffs = [f"only in A: {n}" for n in sorted(set(ia) - set(ib))]
        diffs += [f"only in B: {n}" for n in sorted(set(ib) - set(ia))]
        for name in sorted(set(ia) & set(ib)):
            x, y = ia[name], ib[name]
            if (x.CRC, x.file_size) != (y.CRC, y.file_size):
                diffs.append(f"content differs: {name}")
            elif x.date_time != y.date_time:
                diffs.append(f"timestamp differs: {name}")
        if not diffs and [i.filename for i in za.infolist()] != [i.filename for i in zb.infolist()]:
            diffs.append("same entries in a different order")
        if not diffs:
            diffs.append("entries identical, archive metadata differs (zip comment, alignment or signing block)")
        return diffs


def compare(dir_a, dir_b):
    """(differences, identical): lists of (name, details) and (name, sha256)."""
    fa, fb = files_of(dir_a), files_of(dir_b)
    differences, identical = [], []
    for name in sorted(set(fa) | set(fb)):
        if name not in fa or name not in fb:
            differences.append((name, ["produced by only one build"]))
            continue
        ha, hb = sha256(fa[name]), sha256(fb[name])
        if ha == hb:
            identical.append((name, ha))
        elif name.endswith(ARCHIVES):
            differences.append((name, archive_diff(fa[name], fb[name])))
        else:
            differences.append((name, ["bytes differ"]))
    return differences, identical


def main(argv):
    if argv == ["--self-test"]:
        self_test()
        print("repro_compare self-test ok")
        return 0
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[-2], file=sys.stderr)
        return 2
    dir_a, dir_b, findings = argv
    differences, identical = compare(dir_a, dir_b)
    with open(findings, "w", encoding="utf-8") as f:
        if not identical and not differences:
            f.write("error\t\t\tNo build outputs to compare\n")
        for name, details in differences:
            shown = details[:MAX_ENTRIES] + ([f"… {len(details) - MAX_ENTRIES} more"] if len(details) > MAX_ENTRIES else [])
            f.write(f"error\t\t\tNot reproducible: {name} – " + "; ".join(shown) + "\n")
        for name, digest in identical:
            f.write(f"notice\t\t\tReproducible: {name} sha256 {digest}\n")
    return 0


def self_test():
    with tempfile.TemporaryDirectory() as d:
        a, b = os.path.join(d, "a"), os.path.join(d, "b")
        os.makedirs(a)
        os.makedirs(b)

        def apk(path, entries, stamp=(1981, 1, 1, 1, 1, 2)):
            with zipfile.ZipFile(path, "w") as z:
                for name, data in entries:
                    z.writestr(zipfile.ZipInfo(name, stamp), data)

        apk(os.path.join(a, "app.apk"), [("classes.dex", b"x"), ("res/a.xml", b"1")])
        apk(os.path.join(b, "app.apk"), [("classes.dex", b"x"), ("res/a.xml", b"1")])
        for p in (a, b):
            with open(os.path.join(p, "sbom.json"), "w") as f:
                f.write("{}")
        differences, identical = compare(a, b)
        assert differences == [] and [n for n, _ in identical] == ["app.apk", "sbom.json"]
        apk(os.path.join(b, "app.apk"), [("classes.dex", b"y"), ("res/a.xml", b"1"), ("extra", b"")])
        differences, _ = compare(a, b)
        assert differences == [("app.apk", ["only in B: extra", "content differs: classes.dex"])]
        apk(os.path.join(b, "app.apk"), [("classes.dex", b"x"), ("res/a.xml", b"1")], stamp=(2026, 10, 24, 1, 2, 4))
        assert compare(a, b)[0] == [("app.apk", ["timestamp differs: classes.dex", "timestamp differs: res/a.xml"])]
        apk(os.path.join(b, "app.apk"), [("res/a.xml", b"1"), ("classes.dex", b"x")])
        assert compare(a, b)[0] == [("app.apk", ["same entries in a different order"])]
        os.remove(os.path.join(b, "sbom.json"))
        apk(os.path.join(b, "app.apk"), [("classes.dex", b"x"), ("res/a.xml", b"1")])
        assert compare(a, b)[0] == [("sbom.json", ["produced by only one build"])]
        out = os.path.join(d, "findings.tsv")
        assert main([a, b, out]) == 0
        text = open(out, encoding="utf-8").read()
        assert "error\t\t\tNot reproducible: sbom.json" in text and "notice\t\t\tReproducible: app.apk" in text


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
