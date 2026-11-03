#!/usr/bin/env python3
"""Builds the logo packs bundled with both apps — Python 3.9+ standard library only.

1. coin_logos.bin — current logos of the 100 largest coins by market cap (CoinGecko /coins/markets, one request,
   the same for everyone), Android assets + iOS Shared/Resources (app and widgets). Shown from the device until the
   downloaded logo of a coin is there (lookup CoinLogos.bundledIconName; downloaded logos keep precedence).
2. exchange_logos.bin — the logo of every exchange with a logo (ExchangeLogos.LOGOS on both platforms; futures use
   the parent's logo): CoinGecko's public exchange list (/exchanges pages, detail page /exchanges/<id> for
   exchanges missing from the list), for exchanges CoinGecko does not list the App Store icon (iTunes lookup by
   app id). Android assets + iOS App/Resources (app only).

Format (CoinLogos.parseIconPack on both platforms): "CCIP1\\n", one line "<name> <byte length>\\n" per logo, an
empty line, then the PNG bytes in the same order; names [a-z0-9] (coins: lower-case symbol, exchanges: the names
in EXCHANGES below). Every logo is scaled into a square of SIZE px (×2 of the largest displayed size, 40 pt/dp)
and written as the smallest PNG that stays sharp (logo_image.encode_png: palette PNG when lossless or nearly so).
android/tools/logo_packs_manifest.json lists per logo: pack, name, symbol/exchange keys, source URL, sha256 of
the source and of the stored PNG, fetch date.

Usage: python3 -I android/tools/gen_logo_packs.py [--coins N] [--only coins|exchanges] [--cache DIR]
Run it before a release (network needed: api.coingecko.com, coin-images.coingecko.com, itunes.apple.com,
is1-ssl.mzstatic.com); outputs are committed. Skipped coins (image format without a standard-library decoder,
e.g. WebP) are listed in the manifest; the next coin by market cap takes the place.
"""
import argparse
import datetime
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # sibling modules (also under python3 -I)
sys.dont_write_bytecode = True
import logo_image  # noqa: E402

ANDROID = os.path.dirname(HERE)
ROOT = os.path.dirname(ANDROID)
ANDROID_ASSETS = os.path.join(ANDROID, "app", "src", "main", "assets")
IOS_SHARED = os.path.join(ROOT, "ios", "Shared", "Resources")
IOS_APP = os.path.join(ROOT, "ios", "App", "Resources")
COIN_PACK = "coin_logos.bin"
EXCHANGE_PACK = "exchange_logos.bin"
MANIFEST = os.path.join(HERE, "logo_packs_manifest.json")
SIZE = 80
COINS = 100
USER_AGENT = "cryptoChecker-logo-packs/1 (+https://github.com/r1adBE/cryptoChecker)"
MARKETS_URL = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page=1"
EXCHANGES_URL = "https://api.coingecko.com/api/v3/exchanges?per_page=250&page={page}"
EXCHANGE_URL = "https://api.coingecko.com/api/v3/exchanges/{id}"
ITUNES_URL = "https://itunes.apple.com/lookup?id={id}&country={country}"
PAUSE_SECONDS = 2.5
MAX_PAGES = 6
# Pack name → source: ("coingecko", exchange id) or ("appstore", app id, store country). Same names as
# ExchangeLogos.LOGOS (Android) / ExchangeLogos.logos (iOS); the parity test checks that every name is in the pack.
EXCHANGES = {
    "gdax": ("coingecko", "gdax"), "kraken": ("coingecko", "kraken"), "gate": ("coingecko", "gate"),
    "binance": ("coingecko", "binance"), "binanceus": ("coingecko", "binance_us"), "kucoin": ("coingecko", "kucoin"),
    "okex": ("coingecko", "okex"), "bybit": ("coingecko", "bybit_spot"), "bitget": ("coingecko", "bitget"),
    "mexc": ("coingecko", "mxc"), "htx": ("coingecko", "huobi"), "bitfinex": ("coingecko", "bitfinex"),
    "bitstamp": ("coingecko", "bitstamp"), "bitvavo": ("coingecko", "bitvavo"), "cryptocom": ("coingecko", "crypto_com"),
    "gemini": ("coingecko", "gemini"), "hyperliquid": ("coingecko", "hyperliquid-spot"), "aster": ("coingecko", "aster-spot"),
    "bingx": ("coingecko", "bingx"), "lighter": ("coingecko", "lighter-spot"), "woo": ("coingecko", "wootrade"),
    "deribit": ("coingecko", "deribit_spot"), "phemex": ("coingecko", "phemex"), "bitunix": ("coingecko", "bitunix"),
    "poloniex": ("coingecko", "poloniex"), "onetrading": ("coingecko", "bitpanda"), "upbit": ("coingecko", "upbit"),
    "bithumb": ("coingecko", "bithumb"), "bitflyer": ("coingecko", "bitflyer"), "bitbank": ("coingecko", "bitbank"),
    "coincheck": ("coingecko", "coincheck"), "btcturk": ("coingecko", "btcturk"), "paribu": ("coingecko", "paribu"),
    "icrypex": ("coingecko", "icrypex"), "bitso": ("coingecko", "bitso"), "mercadobitcoin": ("coingecko", "mercado_bitcoin"),
    "indodax": ("coingecko", "indodax"), "zebpay": ("coingecko", "zebpay"), "coindcx": ("coingecko", "coindcx"),
    "hashkey": ("coingecko", "hashkey"), "independentreserve": ("coingecko", "independent_reserve"),
    "btcmarkets": ("coingecko", "btcmarkets"), "luno": ("coingecko", "luno"), "bitkub": ("coingecko", "bitkub"),
    "coinsph": ("coingecko", "coinspro"), "coinone": ("coingecko", "coinone"), "whitebit": ("coingecko", "whitebit"),
    "maxmaicoin": ("coingecko", "max_maicoin"), "bitopro": ("coingecko", "bitopro"), "foxbit": ("coingecko", "foxbit"),
    "bit2me": ("coingecko", "bit2me"), "valr": ("coingecko", "valr"), "wazirx": ("coingecko", "wazirx"),
    "buda": ("coingecko", "buda"), "tokocrypto": ("coingecko", "toko_crypto"), "upbitid": ("coingecko", "upbit_indonesia"),
    "coinjar": ("coingecko", "coinjar"), "gmocoin": ("coingecko", "gmo_japan"), "latoken": ("coingecko", "latoken"),
    "nonkyc": ("coingecko", "nonkyc_io"),
    # Not listed by CoinGecko («bitpanda» there is One Trading): official App Store icon
    "bitpanda": ("appstore", "1449018960", "at"), "coinspot": ("appstore", "1541949985", "au"),
    "quidax": ("appstore", "6742988930", "ng"), "swyftx": ("appstore", "1516986805", "au"),
    "ndax": ("appstore", "1412219882", "ca"), "bitoasis": ("appstore", "1521661794", "ae"),
}


def fetch(url, accept="application/json"):
    """GET with one retry after «429» (Retry-After, at most 60 s)."""
    for attempt in range(2):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept})
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt:
                raise
            time.sleep(min(60, int(e.headers.get("Retry-After") or 30)))
    raise AssertionError("unreachable")


def large(url):
    """CoinGecko image in the largest variant (250 px); «small» is only 50 px."""
    return url.replace("/small/", "/large/").replace("/thumb/", "/large/")


CACHE = None


def logo_png(url):
    """Source bytes and the stored PNG; with --cache the source is read from / written to that folder."""
    path = os.path.join(CACHE, hashlib.sha256(url.encode()).hexdigest()) if CACHE else None
    if path and os.path.isfile(path):
        with open(path, "rb") as f:
            data = f.read()
    else:
        data = fetch(url, accept="image/png,image/jpeg")
        if path:
            with open(path, "wb") as f:
                f.write(data)
    width, height, rgba = logo_image.decode(data)
    png = logo_image.encode_png(SIZE, logo_image.fit_square(width, height, rgba, SIZE))
    return data, png


def plain(name):
    return bool(name) and all(c in "abcdefghijklmnopqrstuvwxyz0123456789" for c in name)


def coin_entries(count, today, skipped):
    rows = json.loads(fetch(MARKETS_URL))
    entries, seen = [], set()
    for row in rows:
        name = str(row.get("symbol", "")).strip().lower()
        url = str(row.get("image") or "")
        # Symbols outside [a-z0-9] can never be a watchlist symbol; the larger coin keeps a shared symbol
        if not plain(name) or name in seen or not url.startswith("https://"):
            continue
        seen.add(name)
        try:
            source, png = logo_png(large(url))
        except logo_image.ImageError as e:
            # e.g. WebP (no decoder in the standard library): the coin keeps its downloaded logo, the next one moves up
            print(f"skipped {name}: {e}")
            skipped.append({"symbol": name.upper(), "source": large(url), "reason": str(e)})
            continue
        entries.append((name, png, {"pack": COIN_PACK, "name": name, "symbol": name.upper(), "coingecko": row.get("id"),
                                    "rank": row.get("market_cap_rank"), "source": large(url),
                                    "sha256": hashlib.sha256(source).hexdigest(), "fetched": today}))
        if len(entries) == count:
            break
    if len(entries) < count:
        raise SystemExit(f"only {len(entries)} coins")
    return entries


def exchange_images():
    """CoinGecko exchange id → image URL for every id in EXCHANGES (list pages, then detail pages)."""
    wanted = {src[1] for src in EXCHANGES.values() if src[0] == "coingecko"}
    found = {}
    for page in range(1, MAX_PAGES + 1):
        if page > 1:
            time.sleep(PAUSE_SECONDS)
        rows = json.loads(fetch(EXCHANGES_URL.format(page=page)))
        for row in rows:
            if row.get("id") in wanted and row.get("image"):
                found.setdefault(row["id"], row["image"])
        if wanted <= set(found) or len(rows) < 250:
            break
    for missing in sorted(wanted - set(found)):
        time.sleep(PAUSE_SECONDS)
        image = json.loads(fetch(EXCHANGE_URL.format(id=missing))).get("image")
        if not image:
            raise SystemExit(f"no logo for exchange {missing}")
        found[missing] = image
    return found


def app_store_icon(app_id, country):
    results = json.loads(fetch(ITUNES_URL.format(id=app_id, country=country))).get("results") or []
    if not results:
        raise SystemExit(f"App Store app {app_id} ({country}) not found")
    art = results[0]["artworkUrl512"]
    # Same icon as PNG in the size needed (mzstatic renders the requested size and format)
    return art.rsplit("/", 1)[0] + "/256x256bb.png"


def exchange_entries(today):
    images = exchange_images()
    entries = []
    for name, src in EXCHANGES.items():
        if not plain(name):
            raise SystemExit(f"bad pack name {name}")
        if src[0] == "coingecko":
            url = large(images[src[1]])
        else:
            time.sleep(PAUSE_SECONDS)
            url = app_store_icon(src[1], src[2])
        source, png = logo_png(url)
        info = {"pack": EXCHANGE_PACK, "name": name, "source": url, "sha256": hashlib.sha256(source).hexdigest(),
                "fetched": today}
        info["coingecko" if src[0] == "coingecko" else "appstore"] = src[1]
        entries.append((name, png, info))
    return entries


def build_pack(entries):
    for _, png, info in entries:
        info["png_sha256"] = hashlib.sha256(png).hexdigest()
    header = ["CCIP1"] + [f"{name} {len(png)}" for name, png, _ in entries]
    pack = ("\n".join(header) + "\n\n").encode("ascii") + b"".join(png for _, png, _ in entries)
    # Self-check: same rules as CoinLogos.parseIconPack
    end = pack.find(b"\n\n")
    offset = end + 2
    for line in pack[:end].decode("ascii").split("\n")[1:]:
        name, length = line.split(" ")
        assert plain(name) and pack[offset:offset + 8] == logo_image.PNG_SIGNATURE, name
        offset += int(length)
    assert offset == len(pack)
    return pack


def write(pack, file, folders):
    for folder in folders:
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, file), "wb") as f:
            f.write(pack)
        print(f"wrote {os.path.relpath(os.path.join(folder, file), ROOT)} ({len(pack)} bytes)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--coins", type=int, default=COINS)
    ap.add_argument("--only", choices=["coins", "exchanges"])
    ap.add_argument("--cache", help="folder for the source images (re-runs without downloading them again)")
    args = ap.parse_args()
    global CACHE
    CACHE = args.cache
    if CACHE:
        os.makedirs(CACHE, exist_ok=True)
    today = datetime.date.today().isoformat()
    manifest = {}
    if os.path.isfile(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as f:
            manifest = json.load(f)
    if args.only != "exchanges":
        skipped = []
        entries = coin_entries(args.coins, today, skipped)
        write(build_pack(entries), COIN_PACK, [ANDROID_ASSETS, IOS_SHARED])
        manifest[COIN_PACK] = [info for _, _, info in entries]
        manifest[COIN_PACK + " skipped"] = skipped
    if args.only != "coins":
        time.sleep(PAUSE_SECONDS)
        entries = exchange_entries(today)
        write(build_pack(entries), EXCHANGE_PACK, [ANDROID_ASSETS, IOS_APP])
        manifest[EXCHANGE_PACK] = [info for _, _, info in entries]
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    print(f"wrote {os.path.relpath(MANIFEST, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
