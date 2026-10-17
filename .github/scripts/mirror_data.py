#!/usr/bin/env python3
"""Mirrors public market data that is the same for every user of the Crypto Checker apps.

Instead of every phone asking alternative.me and Coin Metrics, this script
fetches the data once an hour and the workflow uploads it as assets of the release «data»:
  https://github.com/r1adBE/cryptoChecker/releases/download/data/<file>

Besides plain copies it writes altseason.txt: the altcoin season already computed from daily
candles (data-api.binance.vision, else Binance.US) with the apps' rule, so a phone needs one
request instead of about 21.

coin_meta.json (plain JSON, not wrapped): coin names, logo URLs and Alpha flags built ONLY from
Binance's public lists (website symbol list + Binance Alpha token list) — no images, no CoinGecko
data. The apps read it only when the direct Binance lists fail (CoinMeta.kt / CoinMeta.swift);
android/tools/gen_coin_assets.py turns the same data into the snapshot bundled with the apps.
  {"v":1,"generated":<ms>,
   "binance":[{"name","fullName","logo","tags","onlyFutures"}…],
   "alpha":[{"symbol","name","iconUrl","chainId","contractAddress","alphaId","marketCap","offline","listingCex"}…]}

File format (the apps read it with DataMirror.unwrap):
  first line  «CCDM1 <fetched, epoch milliseconds>»
  then        the original response body, unchanged

The apps use a mirror file only while it is fresh enough (max age per file in
DataMirror.kt / DataMirror.swift) and otherwise ask the original source as before.
The table below must match the apps (same URLs, same file names).

A source that fails or answers nonsense is skipped: its old asset stays and the apps
fall back once it is too old. Exit 1 only if nothing at all could be fetched.
CoinGecko data is NOT mirrored: the CoinGecko API terms forbid re-distributing and storing
their data, so the apps ask CoinGecko directly.
Python 3.9+ standard library only.
"""
import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request

USER_AGENT = "cryptoChecker-data-mirror (+https://github.com/r1adBE/cryptoChecker)"
MAGIC = "CCDM1"
COIN_METRICS = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
# Same URLs as CoinLogos.BINANCE_LIST_URL / ALPHA_LIST_URL in the apps
BINANCE_LIST_URL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"
ALPHA_LIST_URL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"
COIN_META_VERSION = 1


def onchain_url(today):
    start = today - dt.timedelta(days=430)
    return (COIN_METRICS + "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d"
            f"&start_time={start.isoformat()}&page_size=1000")


def sources(today):
    """(file, url, check) — same files and URLs as DataMirror in the apps. Never CoinGecko (API terms)."""
    return [
        ("fng.txt", "https://api.alternative.me/fng/?limit=31",
         lambda j: isinstance(j.get("data"), list) and len(j["data"]) > 0),
        ("onchain.txt", onchain_url(today),
         lambda j: isinstance(j.get("data"), list) and len(j["data"]) >= 300),
        ("btcprice.txt",
         COIN_METRICS + "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000",
         lambda j: isinstance(j.get("data"), list) and len(j["data"]) >= 1000),
    ]


# Altcoin season (same rule and coins as the apps' InsightsDataSource.altSeason): how many of
# these 20 alts beat BTC over 90 days (daily closes, 91 candles incl. today).
ALTS = ["ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
        "TON", "SHIB", "LTC", "BCH", "UNI", "NEAR", "APT", "ICP", "ETC", "XLM"]
KLINE_SOURCES = [("Binance", "https://data-api.binance.vision/api/v3/klines?symbol={s}USDT&interval=1d&limit=91"),
                 ("Binance.US", "https://api.binance.us/api/v3/klines?symbol={s}USDT&interval=1d&limit=91")]


def change90(closes):
    if len(closes) < 91:
        return None
    first = closes[-91]
    return closes[-1] / first - 1.0 if first > 0 else None


def closes_of(base):
    """Daily closes (oldest first) and provider, first source that answers."""
    for provider, url in KLINE_SOURCES:
        try:
            rows = json.loads(fetch(url.format(s=base), {}, tries=2))
            closes = [float(r[4]) for r in rows]
            if len(closes) >= 91:
                return closes, provider
        except Exception:
            continue
    return None, None


def alt_season():
    btc, provider = closes_of("BTC")
    btc_change = change90(btc) if btc else None
    if btc_change is None:
        raise RuntimeError("no BTC history")
    changes = []
    for alt in ALTS:
        closes, _ = closes_of(alt)
        c = change90(closes) if closes else None
        if c is not None:
            changes.append(c)
    if len(changes) < 10:
        raise RuntimeError(f"only {len(changes)} alts")
    return {"outperformers": sum(1 for c in changes if c > btc_change), "total": len(changes), "provider": provider}


def _text(value):
    """String field or None (numbers kept as text, e.g. chainId 56)."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return str(value)
    return value.strip() if isinstance(value, str) and value.strip() else None


def _flag(value):
    return value is True or (isinstance(value, str) and value.lower() == "true")


def binance_entries(body):
    """Binance website symbol list -> [{name, fullName, logo, tags, onlyFutures}] (same fields the apps read)."""
    data = json.loads(body).get("data")
    if not isinstance(data, list):
        raise RuntimeError("binance list: no data array")
    out = []
    for o in data:
        if not isinstance(o, dict):
            continue
        name = _text(o.get("name")) or _text(o.get("baseAsset"))
        if not name:
            continue
        e = {"name": name}
        for key in ("fullName", "logo"):
            v = _text(o.get(key))
            if v:
                e[key] = v
        tags = [t for t in (o.get("tags") or []) if isinstance(t, str)]
        if tags:
            e["tags"] = tags
        if _flag(o.get("onlyFutures")):
            e["onlyFutures"] = True
        out.append(e)
    return out


def alpha_entries(body):
    """Binance Alpha token list -> [{symbol, name, iconUrl, chainId, contractAddress, alphaId, marketCap, offline, listingCex}]."""
    data = json.loads(body).get("data")
    if not isinstance(data, list):
        raise RuntimeError("alpha list: no data array")
    out = []
    for o in data:
        if not isinstance(o, dict):
            continue
        symbol = _text(o.get("symbol"))
        if not symbol:
            continue
        e = {"symbol": symbol}
        for key in ("name", "iconUrl", "chainId", "contractAddress", "alphaId", "marketCap"):
            v = _text(o.get(key))
            if v:
                e[key] = v
        for key in ("offline", "listingCex"):
            if _flag(o.get(key)):
                e[key] = True
        out.append(e)
    return out


def coin_meta(binance, alpha, generated_millis):
    return {"v": COIN_META_VERSION, "generated": generated_millis, "binance": binance, "alpha": alpha}


def coin_meta_snapshot(meta, top_binance=600):
    """Small extract bundled with the apps (names and Alpha flags offline): the first `top_binance`
    Binance entries and all tradable Alpha tokens, without logo URLs (images need the network anyway)."""
    binance = [{k: v for k, v in e.items() if k != "logo"} for e in meta.get("binance", [])[:top_binance]]
    alpha = [{k: v for k, v in e.items() if k in ("symbol", "name", "marketCap", "listingCex")}
             for e in meta.get("alpha", []) if not e.get("offline")]
    return coin_meta(binance, alpha, meta.get("generated", 0))


def build_coin_meta(fetcher=None):
    """Both Binance lists; at least one must work (the other is then empty and the apps keep theirs)."""
    fetcher = fetcher or (lambda url: fetch(url, {}))
    binance, alpha, errors = [], [], []
    try:
        binance = binance_entries(fetcher(BINANCE_LIST_URL))
        if len(binance) < 50:
            raise RuntimeError(f"only {len(binance)} binance entries")
    except Exception as e:
        binance = []
        errors.append(f"binance: {e}")
    try:
        alpha = alpha_entries(fetcher(ALPHA_LIST_URL))
        if len(alpha) < 50:
            raise RuntimeError(f"only {len(alpha)} alpha entries")
    except Exception as e:
        alpha = []
        errors.append(f"alpha: {e}")
    if not binance and not alpha:
        raise RuntimeError("; ".join(errors))
    return coin_meta(binance, alpha, int(time.time() * 1000)), errors


def selftest_coin_meta():
    binance_body = json.dumps({"code": "000000", "data": [
        {"name": "BTC", "fullName": "Bitcoin", "logo": "https://bin.bnbstatic.com/b.png", "tags": ["Layer1_Layer2"]},
        {"name": "", "baseAsset": "PEPE", "fullName": "Pepe", "logo": None},
        {"name": "XAU", "fullName": "Gold", "onlyFutures": True}, "broken", {"fullName": "no name"}]})
    alpha_body = json.dumps({"code": "000000", "data": [
        {"symbol": "FROGGIE\u200b", "name": "Froggie", "iconUrl": "/images/f.png", "chainId": 56,
         "contractAddress": "0x1", "alphaId": "ALPHA_1", "marketCap": "1000", "offline": False, "listingCex": False},
        {"symbol": "OLD", "name": "Old", "offline": True}, {"name": "no symbol"}]})
    b = binance_entries(binance_body)
    assert [e["name"] for e in b] == ["BTC", "PEPE", "XAU"], b
    assert "logo" not in b[1] and b[2]["onlyFutures"] is True and b[0]["tags"] == ["Layer1_Layer2"]
    a = alpha_entries(alpha_body)
    assert [e["symbol"] for e in a] == ["FROGGIE\u200b", "OLD"] and a[0]["chainId"] == "56", a
    assert a[0]["marketCap"] == "1000" and "offline" not in a[0] and a[1]["offline"] is True
    meta = coin_meta(b, a, 5)
    assert meta["v"] == 1 and meta["generated"] == 5
    snap = coin_meta_snapshot(meta, top_binance=2)
    assert [e["name"] for e in snap["binance"]] == ["BTC", "PEPE"] and "logo" not in snap["binance"][0]
    assert [e["symbol"] for e in snap["alpha"]] == ["FROGGIE\u200b"] and "iconUrl" not in snap["alpha"][0]
    responses = {BINANCE_LIST_URL: binance_body, ALPHA_LIST_URL: "nonsense"}

    def fake(url):
        if url not in responses:
            raise RuntimeError("unexpected " + url)
        return responses[url]
    try:
        build_coin_meta(fake)
        raise AssertionError("too few entries must fail")
    except RuntimeError:
        pass
    many = json.dumps({"data": [{"name": f"C{i}", "fullName": f"Coin {i}"} for i in range(60)]})
    responses[BINANCE_LIST_URL] = many
    built, errors = build_coin_meta(fake)
    assert len(built["binance"]) == 60 and built["alpha"] == [] and len(errors) == 1
    assert "coingecko" not in BINANCE_LIST_URL + ALPHA_LIST_URL


def fetch(url, headers, tries=3):
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json", **headers})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 429 or e.code >= 500:
                time.sleep(20 * (attempt + 1))
                continue
            break
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(str(last))


def wrap(body, fetched_millis):
    return f"{MAGIC} {fetched_millis}\n{body}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        assert wrap('{"a":1}', 5) == 'CCDM1 5\n{"a":1}'
        assert onchain_url(dt.date(2026, 10, 9)).endswith("&start_time=2025-08-05&page_size=1000")
        assert change90([1.0] * 90 + [2.0]) == 1.0 and change90([1.0] * 90) is None
        assert not any("coingecko" in url for _, url, _ in sources(dt.date(2026, 10, 9)))
        selftest_coin_meta()
        print("selftest ok")
        return 0
    os.makedirs(args.output, exist_ok=True)
    today = dt.datetime.now(dt.timezone.utc).date()
    status, ok = {}, 0
    for name, url, check in sources(today):
        try:
            body = fetch(url, {})
            if not check(json.loads(body)):
                raise RuntimeError("unexpected content")
            millis = int(time.time() * 1000)
            with open(os.path.join(args.output, name), "w", encoding="utf-8") as f:
                f.write(wrap(body, millis))
            status[name] = "ok"
            ok += 1
            print(f"{name}: {len(body) / 1000:.0f} kB")
        except Exception as e:
            status[name] = f"failed: {e}"[:200]
            print(f"::warning::{name} not updated: {e}")
    try:
        season = alt_season()
        with open(os.path.join(args.output, "altseason.txt"), "w", encoding="utf-8") as f:
            f.write(wrap(json.dumps(season, separators=(",", ":")), int(time.time() * 1000)))
        status["altseason.txt"] = "ok"
        ok += 1
        print(f"altseason.txt: {season}")
    except Exception as e:
        status["altseason.txt"] = f"failed: {e}"[:200]
        print(f"::warning::altseason.txt not updated: {e}")
    try:
        meta, errors = build_coin_meta()
        body = json.dumps(meta, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(args.output, "coin_meta.json"), "w", encoding="utf-8") as f:
            f.write(body)
        status["coin_meta.json"] = "ok" if not errors else ("partial: " + "; ".join(errors))[:200]
        ok += 1
        print(f"coin_meta.json: {len(meta['binance'])} binance, {len(meta['alpha'])} alpha, {len(body) / 1000:.0f} kB")
        for err in errors:
            print(f"::warning::coin_meta.json partial: {err}")
    except Exception as e:
        status["coin_meta.json"] = f"failed: {e}"[:200]
        print(f"::warning::coin_meta.json not updated: {e}")
    with open(os.path.join(args.output, "status.json"), "w", encoding="utf-8") as f:
        json.dump({"lastRun": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "files": status}, f, indent=1)
        f.write("\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
