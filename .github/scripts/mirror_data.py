#!/usr/bin/env python3
"""Mirrors public market data that is the same for every user of the Crypto Checker apps.

Instead of every phone asking CoinGecko, alternative.me and Coin Metrics, this script
fetches the data once an hour and the workflow uploads it as assets of the release «data»:
  https://github.com/r1adBE/cryptoChecker/releases/download/data/<file>

Besides plain copies it writes altseason.txt: the altcoin season already computed from daily
candles (data-api.binance.vision, else Binance.US) with the apps' rule, so a phone needs one
request instead of about 21.

File format (the apps read it with DataMirror.unwrap):
  first line  «CCDM1 <fetched, epoch milliseconds>»
  then        the original response body, unchanged

The apps use a mirror file only while it is fresh enough (max age per file in
DataMirror.kt / DataMirror.swift) and otherwise ask the original source as before.
The table below must match the apps (same URLs, same file names).

A source that fails or answers nonsense is skipped: its old asset stays and the apps
fall back once it is too old. Exit 1 only if nothing at all could be fetched.
Python 3.9+ standard library only. Optional CoinGecko demo key: env COINGECKO_API_KEY.
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
CG_MARKETS = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page={n}&page=1"
COIN_METRICS = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"


def onchain_url(today):
    start = today - dt.timedelta(days=430)
    return (COIN_METRICS + "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d"
            f"&start_time={start.isoformat()}&page_size=1000")


def sources(today):
    """(file, url, uses CoinGecko, check) — same files and URLs as DataMirror in the apps."""
    return [
        ("global.txt", "https://api.coingecko.com/api/v3/global", True,
         lambda j: isinstance(j.get("data", {}).get("market_cap_percentage", {}).get("btc"), (int, float))),
        ("fng.txt", "https://api.alternative.me/fng/?limit=31", False,
         lambda j: isinstance(j.get("data"), list) and len(j["data"]) > 0),
        ("coins30.txt", CG_MARKETS.format(n=30), True, lambda j: isinstance(j, list) and len(j) >= 20),
        ("coins40.txt", CG_MARKETS.format(n=40), True, lambda j: isinstance(j, list) and len(j) >= 30),
        ("onchain.txt", onchain_url(today), False,
         lambda j: isinstance(j.get("data"), list) and len(j["data"]) >= 300),
        ("btcprice.txt",
         COIN_METRICS + "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000", False,
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
        print("selftest ok")
        return 0
    os.makedirs(args.output, exist_ok=True)
    key = os.environ.get("COINGECKO_API_KEY", "").strip()
    today = dt.datetime.now(dt.timezone.utc).date()
    status, ok = {}, 0
    for name, url, cg, check in sources(today):
        headers = {"x-cg-demo-api-key": key} if cg and key else {}
        try:
            body = fetch(url, headers)
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
    with open(os.path.join(args.output, "status.json"), "w", encoding="utf-8") as f:
        json.dump({"lastRun": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "files": status}, f, indent=1)
        f.write("\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
