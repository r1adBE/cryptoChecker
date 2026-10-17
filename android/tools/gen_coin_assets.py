#!/usr/bin/env python3
"""Builds the coin data bundled with both apps (Android assets and iOS Shared/Resources).

1. coin_icons.bin — the CC0 icon set «Cryptocurrency Icons» 0.18.1
   (https://github.com/spothq/cryptocurrency-icons, licence CC0-1.0), the 64 px colour PNGs
   (32@2x/color) packed into one file, so the majors have a logo offline from the first frame.
   Format (read by CoinLogos.parseIconPack on both platforms):
       "CCIP1\\n", one line "<name> <byte length>\\n" per icon, an empty line, then the PNG bytes
       in the same order. Names are lower-case [a-z0-9] (e.g. "btc"); "generic" and
       names with other characters ("$pac") are left out.
   Usage: python3 android/tools/gen_coin_assets.py --icons <checkout of the icon repo at tag v0.18.1>

2. coin_meta_snapshot.json — a small extract of coin_meta.json (the first Binance list entries and
   all tradable Binance Alpha tokens, no logo URLs), so a fresh install shows names and «α»
   immediately, even offline. Same format and same code as the GitHub Action «Market data»
   (.github/scripts/mirror_data.py, coin_meta_snapshot). Only Binance data, never CoinGecko.
   Usage (one of):
       python3 android/tools/gen_coin_assets.py --meta live             (fetch both Binance lists now)
       python3 android/tools/gen_coin_assets.py --meta release          (coin_meta.json of the release «data»)
       python3 android/tools/gen_coin_assets.py --meta-file coin_meta.json
       python3 android/tools/gen_coin_assets.py --binance-file list.json [--alpha-file alpha.json]

Run it before a release; both outputs are committed. Python 3.9+ standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
ROOT = os.path.dirname(ANDROID)
TARGETS = [os.path.join(ANDROID, "app", "src", "main", "assets"),
           os.path.join(ROOT, "ios", "Shared", "Resources")]
ICON_PACK = "coin_icons.bin"
SNAPSHOT = "coin_meta_snapshot.json"
RELEASE_URL = "https://github.com/r1adBE/cryptoChecker/releases/download/data/coin_meta.json"
# Upper bound for the icon pack (bundle size)
ICON_PACK_MAX_BYTES = 2 * 1024 * 1024


def load_mirror_module():
    """mirror_data.py lives in .github/scripts (public repo) or public/.github/scripts (private tree)."""
    for base in (os.path.join(ROOT, ".github", "scripts"), os.path.join(ROOT, "public", ".github", "scripts")):
        if os.path.isfile(os.path.join(base, "mirror_data.py")):
            sys.path.insert(0, base)
            sys.dont_write_bytecode = True
            import mirror_data  # noqa: E402
            return mirror_data
    raise SystemExit("mirror_data.py not found (.github/scripts)")


def icon_names(folder):
    """(name, path) of the colour PNGs, sorted; '<name>@2x.png' -> name."""
    out = []
    for file in sorted(os.listdir(folder)):
        if not file.endswith(".png"):
            continue
        name = file[:-4]
        if name.endswith("@2x"):
            name = name[:-3]
        name = name.lower()
        if name == "generic" or not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789" for c in name):
            continue
        out.append((name, os.path.join(folder, file)))
    return out


def build_icon_pack(entries):
    """entries: [(name, png bytes)] -> pack bytes."""
    header = ["CCIP1"]
    blobs = []
    for name, data in entries:
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise SystemExit(f"{name}: not a PNG")
        header.append(f"{name} {len(data)}")
        blobs.append(data)
    return ("\n".join(header) + "\n\n").encode("ascii") + b"".join(blobs)


def parse_icon_pack(pack):
    """Same rules as CoinLogos.parseIconPack (used for the self-check)."""
    end = pack.find(b"\n\n")
    lines = pack[:end].decode("ascii").split("\n")
    assert lines[0] == "CCIP1"
    out, offset = {}, end + 2
    for line in lines[1:]:
        name, length = line.split(" ")
        out.setdefault(name, (offset, int(length)))
        offset += int(length)
    assert offset == len(pack), "pack length mismatch"
    return out


def write_all(file, data):
    for target in TARGETS:
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, file), "wb") as f:
            f.write(data)
        print(f"wrote {os.path.relpath(os.path.join(target, file), ROOT)} ({len(data) / 1024:.0f} KB)")


def icons(repo):
    folder = os.path.join(repo, "32@2x", "color")
    package = json.load(open(os.path.join(repo, "package.json"), encoding="utf-8"))
    if package.get("license") != "CC0-1.0":
        raise SystemExit(f"unexpected licence {package.get('license')}")
    names = icon_names(folder)
    entries = [(name, open(path, "rb").read()) for name, path in names]
    pack = build_icon_pack(entries)
    index = parse_icon_pack(pack)
    assert len(index) == len(entries)
    if len(pack) > ICON_PACK_MAX_BYTES:
        raise SystemExit(f"icon pack too large: {len(pack)} bytes")
    print(f"cryptocurrency-icons {package.get('version')}: {len(entries)} icons")
    write_all(ICON_PACK, pack)


def meta(args, mirror):
    if args.meta_file:
        data = json.load(open(args.meta_file, encoding="utf-8"))
    elif args.meta == "release":
        req = urllib.request.Request(RELEASE_URL, headers={"User-Agent": mirror.USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    elif args.meta == "live":
        data, errors = mirror.build_coin_meta()
        for e in errors:
            print("warning:", e)
    else:
        binance = mirror.binance_entries(open(args.binance_file, encoding="utf-8").read())
        alpha = mirror.alpha_entries(open(args.alpha_file, encoding="utf-8").read()) if args.alpha_file else []
        data = mirror.coin_meta(binance, alpha, int(time.time() * 1000))
    if data.get("v") != mirror.COIN_META_VERSION:
        raise SystemExit("unexpected coin_meta version")
    snapshot = mirror.coin_meta_snapshot(data, top_binance=args.top)
    body = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    print(f"snapshot: {len(snapshot['binance'])} binance, {len(snapshot['alpha'])} alpha")
    write_all(SNAPSHOT, body)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--icons", help="checkout of github.com/spothq/cryptocurrency-icons at tag v0.18.1")
    ap.add_argument("--meta", choices=["live", "release"])
    ap.add_argument("--meta-file")
    ap.add_argument("--binance-file")
    ap.add_argument("--alpha-file")
    ap.add_argument("--top", type=int, default=600, help="Binance list entries in the snapshot")
    args = ap.parse_args()
    if not (args.icons or args.meta or args.meta_file or args.binance_file):
        ap.error("nothing to do")
    if args.icons:
        icons(args.icons)
    if args.meta or args.meta_file or args.binance_file:
        meta(args, load_mirror_module())
    return 0


if __name__ == "__main__":
    sys.exit(main())
