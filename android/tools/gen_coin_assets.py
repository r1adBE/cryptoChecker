#!/usr/bin/env python3
"""Builds the coin name data bundled with both apps (Android assets and iOS Shared/Resources).
(The bundled logos — coin_logos.bin, exchange_logos.bin — come from android/tools/gen_logo_packs.py.)

coin_meta_snapshot.json — a small extract of coin_meta.json (the first Binance list entries and
   all tradable Binance Alpha tokens, no logo URLs), so a fresh install shows names and «α»
   immediately, even offline. Same format and same code as the GitHub Action «Market data»
   (.github/scripts/mirror_data.py, coin_meta_snapshot). Only Binance data, never CoinGecko.
   Usage (one of):
       python3 android/tools/gen_coin_assets.py --meta live             (fetch both Binance lists now)
       python3 android/tools/gen_coin_assets.py --meta release          (coin_meta.json of the release «data»)
       python3 android/tools/gen_coin_assets.py --meta-file coin_meta.json
       python3 android/tools/gen_coin_assets.py --binance-file list.json [--alpha-file alpha.json]

Run it before a release; the output is committed. Python 3.9+ standard library only.
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
SNAPSHOT = "coin_meta_snapshot.json"
RELEASE_URL = "https://github.com/r1adBE/cryptoChecker/releases/download/data/coin_meta.json"


def load_mirror_module():
    """mirror_data.py lives in .github/scripts (public repo) or public/.github/scripts (private tree)."""
    for base in (os.path.join(ROOT, ".github", "scripts"), os.path.join(ROOT, "public", ".github", "scripts")):
        if os.path.isfile(os.path.join(base, "mirror_data.py")):
            sys.path.insert(0, base)
            sys.dont_write_bytecode = True
            import mirror_data  # noqa: E402
            return mirror_data
    raise SystemExit("mirror_data.py not found (.github/scripts)")


def write_all(file, data):
    for target in TARGETS:
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, file), "wb") as f:
            f.write(data)
        print(f"wrote {os.path.relpath(os.path.join(target, file), ROOT)} ({len(data) / 1024:.0f} KB)")


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
    ap.add_argument("--meta", choices=["live", "release"])
    ap.add_argument("--meta-file")
    ap.add_argument("--binance-file")
    ap.add_argument("--alpha-file")
    ap.add_argument("--top", type=int, default=600, help="Binance list entries in the snapshot")
    args = ap.parse_args()
    if not (args.meta or args.meta_file or args.binance_file):
        ap.error("nothing to do")
    meta(args, load_mirror_module())
    return 0


if __name__ == "__main__":
    sys.exit(main())
