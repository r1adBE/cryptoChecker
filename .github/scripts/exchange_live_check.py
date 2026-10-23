#!/usr/bin/env python3
"""Weekly live check of the exchange APIs the apps read.

Queries the public single-ticker endpoint of every exchange adapter for one
major pair and checks that the fields the adapters parse are still there and
numeric (last price, plus bid/ask/volume/change where the adapter reads them).
The table below mirrors the adapters in
android/marketdata/.../model/market/*.kt and ios/Shared/Markets/*.swift and the
endpoints listed in android/testdata/exchanges/exchange_*.json - keep it in sync
when an adapter changes its URL or fields.

Result handling (GitHub Actions, gh CLI with GITHUB_TOKEN):
  - any failure: open ONE issue "Exchange API check failed" or update the open one
  - all pass:    close that issue (with a comment) if it is open
Geo-blocked answers (HTTP 451, or 403 for exchanges that block US cloud IPs)
count as "skipped", not as failure: GitHub's runners are in the US. Skipped
exchanges are listed in the job summary and in the issue.
Binance Spot is also checked via data-api.binance.vision (same schema as
api.binance.com, not geo-blocked; the apps read it for candles and portfolio
prices, see mirror_data.py), so the Spot schema is checked even when
api.binance.com answers 451 from the runner.

Usage:
  python3 exchange_live_check.py              # check and manage the issue (CI)
  python3 exchange_live_check.py --dry-run    # check only, print the issue text
  python3 exchange_live_check.py --only kraken,okx --dry-run
  python3 exchange_live_check.py --skip-issue --result r.json   # CI step 1: check, no token
  python3 exchange_live_check.py --issue-only --result r.json   # CI step 2: issue, no requests

The workflow runs two steps, so the token never shares a step with the
exchange requests: --skip-issue checks and writes the result (failed count, issue text) to
--result; --issue-only contacts no exchange, reads only that file and manages the issue.

Exit code: 0 = checks done (all passed, or failures reported in the issue, or --dry-run);
1 = the script itself failed (e.g. the gh CLI could not open/update the issue), 2 = usage error.
Failing exchanges are tracked in the issue, so the run stays green and a red run means the
check itself is broken.
Python 3.9+ standard library only.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ISSUE_TITLE = "Exchange API check failed"
TIMEOUT_SECONDS = 20
USER_AGENT = "CryptoChecker-ExchangeLiveCheck/1.0 (+https://github.com/r1adBE/cryptoChecker)"

# Pfad in die Antwort: Schlüssel (str) oder Index (int)
# key, Name, URL, Pfade der Pflichtfelder (zahlig), optional POST-Body, geo = 403 gilt als gesperrt
EXCHANGES = [
    {"key": "coinbase", "name": "Coinbase",
     "url": "https://api.exchange.coinbase.com/products/BTC-USD/stats",
     "fields": [["last"], ["open"], ["volume"]]},
    {"key": "kraken", "name": "Kraken",
     "url": "https://api.kraken.com/0/public/Ticker?pair=XXBTZUSD",
     "fields": [["result", "XXBTZUSD", "c", 0], ["result", "XXBTZUSD", "b", 0], ["result", "XXBTZUSD", "a", 0],
                ["result", "XXBTZUSD", "v", 1]]},
    {"key": "gateio", "name": "Gate.io",
     "url": "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=BTC_USDT",
     "fields": [[0, "last"], [0, "change_percentage"], [0, "quote_volume"]]},
    {"key": "binance", "name": "Binance",
     "url": "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT", "geo": True,
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    # Gleiche Antwort wie api.binance.com, aber nicht geosperrt: Schema auch vom US-Runner geprüft
    {"key": "binancevision", "name": "Binance (data-api)",
     "url": "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=BTCUSDT",
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    {"key": "binanceus", "name": "Binance.US",
     "url": "https://api.binance.us/api/v3/ticker/24hr?symbol=BTCUSDT", "geo": True,
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    {"key": "kucoin", "name": "KuCoin",
     "url": "https://api.kucoin.com/api/v1/market/stats?symbol=BTC-USDT",
     "fields": [["data", "last"], ["data", "changeRate"], ["data", "volValue"]]},
    {"key": "okx", "name": "OKX",
     "url": "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT",
     "fields": [["data", 0, "last"], ["data", 0, "open24h"], ["data", 0, "volCcy24h"]]},
    {"key": "okxfutures", "name": "OKX Futures",
     "url": "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT-SWAP",
     "fields": [["data", 0, "last"], ["data", 0, "open24h"]]},
    {"key": "binancefutures", "name": "Binance Futures",
     "url": "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT", "geo": True,
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    {"key": "bybit", "name": "Bybit",
     "url": "https://api.bybit.com/v5/market/tickers?category=spot&symbol=BTCUSDT", "geo": True,
     "fields": [["result", "list", 0, "lastPrice"], ["result", "list", 0, "price24hPcnt"]]},
    {"key": "bybitfutures", "name": "Bybit Futures",
     "url": "https://api.bybit.com/v5/market/tickers?category=linear&symbol=BTCUSDT", "geo": True,
     "fields": [["result", "list", 0, "lastPrice"], ["result", "list", 0, "price24hPcnt"]]},
    {"key": "bitget", "name": "Bitget",
     "url": "https://api.bitget.com/api/v2/spot/market/tickers?symbol=BTCUSDT",
     "fields": [["data", 0, "lastPr"], ["data", 0, "open"], ["data", 0, "quoteVolume"]]},
    {"key": "bitgetfutures", "name": "Bitget Futures",
     "url": "https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES",
     "fields": [["data", 0, "lastPr"], ["data", 0, "change24h"]]},
    {"key": "mexc", "name": "MEXC",
     "url": "https://api.mexc.com/api/v3/ticker/24hr?symbol=BTCUSDT",
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    {"key": "mexcfutures", "name": "MEXC Futures",
     "url": "https://contract.mexc.com/api/v1/contract/ticker?symbol=BTC_USDT",
     "fields": [["data", "lastPrice"], ["data", "riseFallRate"]]},  # riseFallRate: nur Feldform prüfen, die App nutzt es nicht (Tageswert UTC+8)
    {"key": "htx", "name": "HTX",
     "url": "https://api.huobi.pro/market/detail/merged?symbol=btcusdt",
     "fields": [["tick", "close"], ["tick", "open"], ["tick", "vol"]]},
    {"key": "htxfutures", "name": "HTX Futures",
     "url": "https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=BTC-USDT",
     "fields": [["tick", "close"], ["tick", "open"]]},
    {"key": "bitfinex", "name": "Bitfinex",
     "url": "https://api-pub.bitfinex.com/v2/ticker/tBTCUSD",
     "fields": [[6], [5], [7]]},
    {"key": "bitstamp", "name": "Bitstamp",
     "url": "https://www.bitstamp.net/api/v2/ticker/btcusd/",
     "fields": [["last"], ["open_24"], ["volume"]]},
    {"key": "bitvavo", "name": "Bitvavo",
     "url": "https://api.bitvavo.com/v2/ticker/24h?market=BTC-EUR",
     "fields": [["last"], ["open"], ["volumeQuote"]]},
    {"key": "cryptocom", "name": "Crypto.com",
     "url": "https://api.crypto.com/exchange/v1/public/get-tickers?instrument_name=BTC_USDT",
     "fields": [["result", "data", 0, "a"], ["result", "data", 0, "c"]]},
    {"key": "gemini", "name": "Gemini",
     "url": "https://api.gemini.com/v1/pubticker/btcusd",
     "fields": [["last"], ["bid"], ["ask"]]},
    {"key": "bingx", "name": "BingX",
     "url": "https://open-api.bingx.com/openApi/spot/v1/ticker/24hr?symbol=BTC-USDT", "stamp": True,
     "fields": [["data", 0, "lastPrice"], ["data", 0, "openPrice"]]},
    {"key": "bingxfutures", "name": "BingX Futures",
     "url": "https://open-api.bingx.com/openApi/swap/v2/quote/ticker?symbol=BTC-USDT", "stamp": True,
     "fields": [["data", "lastPrice"], ["data", "openPrice"]]},
    {"key": "aster", "name": "Aster",
     "url": "https://fapi.asterdex.com/fapi/v1/ticker/24hr?symbol=BTCUSDT",
     "fields": [["lastPrice"], ["priceChangePercent"], ["quoteVolume"]]},
    {"key": "lighter", "name": "Lighter",
     # Gesamtliste aller Märkte (wie der Adapter); geprüft wird die erste Zeile
     "url": "https://mainnet.zklighter.elliot.ai/api/v1/exchangeStats",
     "fields": [["order_book_stats", 0, "last_trade_price"], ["order_book_stats", 0, "daily_price_change"]]},
    {"key": "hyperliquid", "name": "Hyperliquid",
     "url": "https://api.hyperliquid.xyz/info", "post": {"type": "metaAndAssetCtxs"},
     "fields": [[1, 0, "midPx"], [1, 0, "prevDayPx"], [1, 0, "funding"]],
     "names": [0, "universe", 0, "name"]},
    {"key": "woo", "name": "WOO X",
     "url": "https://api.woox.io/v3/public/kline?symbol=SPOT_BTC_USDT&type=1h&limit=25",
     "fields": [["data", "rows", 0, "close"], ["data", "rows", 0, "open"]]},
    {"key": "woofutures", "name": "WOO X Futures",
     "url": "https://api.woox.io/v3/public/futures?symbol=PERP_BTC_USDT",
     "fields": [["data", "rows", 0, "24hClose"], ["data", "rows", 0, "24hOpen"]]},
    {"key": "deribit", "name": "Deribit",
     "url": "https://www.deribit.com/api/v2/public/ticker?instrument_name=BTC-PERPETUAL",
     "fields": [["result", "last_price"], ["result", "stats", "price_change"]]},
    {"key": "phemex", "name": "Phemex",
     "url": "https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT",
     "fields": [["result", "lastEp"], ["result", "openEp"]]},
    {"key": "phemexfutures", "name": "Phemex Futures",
     "url": "https://api.phemex.com/md/v2/ticker/24hr?symbol=BTCUSDT",
     "fields": [["result", "closeRp"], ["result", "openRp"]]},
    {"key": "poloniex", "name": "Poloniex",
     "url": "https://api.poloniex.com/markets/BTC_USDT/ticker24h",
     "fields": [["close"], ["dailyChange"], ["amount"]]},
    {"key": "onetrading", "name": "One Trading",
     "url": "https://api.onetrading.com/fast/v1/market-ticker/BTC_EUR",
     "fields": [["last_price"], ["quote_volume"]]},
    {"key": "onetradingbulk", "name": "One Trading (all tickers)",
     "url": "https://api.onetrading.com/fast/v1/market-ticker",
     "fields": [[0, "last_price"], [0, "quote_volume"]]},
    {"key": "upbit", "name": "Upbit",
     "url": "https://api.upbit.com/v1/ticker?markets=KRW-BTC",
     "fields": [[0, "trade_price"], [0, "change_rate"], [0, "acc_trade_price_24h"]]},
    {"key": "bithumb", "name": "Bithumb",
     "url": "https://api.bithumb.com/v1/ticker?markets=KRW-BTC",
     "fields": [[0, "trade_price"], [0, "change_rate"]]},
    {"key": "bitflyer", "name": "bitFlyer",
     "url": "https://api.bitflyer.com/v1/ticker?product_code=BTC_JPY",
     "fields": [["ltp"], ["best_bid"], ["best_ask"]]},
    {"key": "bitbank", "name": "bitbank",
     "url": "https://public.bitbank.cc/btc_jpy/ticker",
     "fields": [["data", "last"], ["data", "open"], ["data", "vol"]]},
    {"key": "coincheck", "name": "Coincheck",
     "url": "https://coincheck.com/api/ticker?pair=btc_jpy",
     "fields": [["last"], ["bid"], ["ask"], ["volume"]]},
    {"key": "btcturk", "name": "BtcTurk",
     "url": "https://api.btcturk.com/api/v2/ticker?pairSymbol=BTCTRY",
     "fields": [["data", 0, "last"], ["data", 0, "dailyPercent"]]},
    {"key": "paribu", "name": "Paribu",
     # Eine Gesamtliste aller Paare (wie der Adapter)
     "url": "https://www.paribu.com/ticker",
     "fields": [["BTC_TL", "last"], ["BTC_TL", "highestBid"], ["BTC_TL", "lowestAsk"]]},
    {"key": "icrypex", "name": "Icrypex",
     # Eine Gesamtliste aller Paare (wie der Adapter); geprüft wird die erste Zeile
     "url": "https://api.icrypex.com/v1/tickers",
     "fields": [[0, "last"], [0, "bid"], [0, "ask"]]},
    {"key": "bitso", "name": "Bitso",
     "url": "https://bitso.com/api/v3/ticker?book=btc_mxn",
     "fields": [["payload", "last"], ["payload", "volume"]]},
    {"key": "mercadobitcoin", "name": "Mercado Bitcoin",
     "url": "https://api.mercadobitcoin.net/api/v4/tickers?symbols=BTC-BRL",
     "fields": [[0, "last"], [0, "open"], [0, "vol"]]},
    {"key": "luno", "name": "Luno",
     "url": "https://api.luno.com/api/1/ticker?pair=XBTZAR",
     "fields": [["last_trade"], ["bid"], ["ask"]]},
    {"key": "indodax", "name": "Indodax",
     "url": "https://indodax.com/api/ticker/btcidr",
     "fields": [["ticker", "last"], ["ticker", "buy"], ["ticker", "sell"]]},
    {"key": "zebpay", "name": "ZebPay",
     "url": "https://sapi.zebpay.com/api/v2/market/ticker?symbol=BTC-INR",
     "fields": [["data", "last"], ["data", "quoteVolume"]]},
    {"key": "coindcx", "name": "CoinDCX",
     # Nur die Gesamtliste (wie der Adapter); geprüft wird die erste Zeile
     "url": "https://api.coindcx.com/exchange/ticker",
     "fields": [[0, "last_price"], [0, "change_24_hour"]]},
    {"key": "hashkey", "name": "HashKey Exchange",
     "url": "https://api-pro.hashkey.com/quote/v1/ticker/24hr?symbol=BTCUSD", "geo": True,
     "fields": [[0, "c"], [0, "o"], [0, "v"]]},
    {"key": "independentreserve", "name": "Independent Reserve",
     "url": "https://api.independentreserve.com/Public/GetMarketSummary?primaryCurrencyCode=Xbt&secondaryCurrencyCode=Aud",
     "fields": [["LastPrice"], ["CurrentHighestBidPrice"], ["CurrentLowestOfferPrice"]]},
    {"key": "btcmarkets", "name": "BTC Markets",
     "url": "https://api.btcmarkets.net/v3/markets/BTC-AUD/ticker",
     "fields": [["lastPrice"], ["price24h"], ["volume24h"]]},
    {"key": "latoken", "name": "LATOKEN",
     # BTC/USDT über die Währungs-IDs (wie der Adapter, siehe exchange_latoken.json)
     "url": "https://api.latoken.com/v2/ticker/92151d82-df98-4d88-9a4d-284fa9eca49f/0c3a106d-bde3-4c13-a26e-3fd2394529e5",
     "fields": [["lastPrice"], ["change24h"], ["volume24h"]]},
    {"key": "nonkyc", "name": "NonKYC",
     "url": "https://api.nonkyc.io/api/v2/ticker/BTC_USDT",
     "fields": [["last_price"], ["target_volume"]]},
    {"key": "dexscreener", "name": "DexScreener",
     "url": "https://api.dexscreener.com/latest/dex/pairs/ethereum/0x11950d141EcB863F01007AdD7D1A342041227b58",
     "fields": [["pairs", 0, "priceNative"], ["pairs", 0, "priceChange", "h24"]]},
]

OK, FAIL, SKIP = "ok", "fail", "skip"


def fetch(entry):
    """Antwort als (HTTP-Status, Text); Status 0 = keine Verbindung."""
    data = None
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if "post" in entry:
        data = json.dumps(entry["post"]).encode()
        headers["Content-Type"] = "application/json"
    url = entry["url"]
    if entry.get("stamp"):
        # BingX verlangt eine Zeitangabe in ms (wie der Adapter)
        url += ("&" if "?" in url else "?") + f"timestamp={int(time.time() * 1000)}"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            # Wie die Apps: höchstens 2 MB lesen
            return resp.status, resp.read(2 * 1024 * 1024 + 1).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:  # Zeitüberschreitung, DNS, TLS …
        return 0, type(e).__name__


def walk(obj, path):
    for step in path:
        if isinstance(step, int):
            if not isinstance(obj, list) or len(obj) <= step:
                return None
        elif not isinstance(obj, dict) or step not in obj:
            return None
        obj = obj[step]
    return obj


def numeric(value):
    if isinstance(value, bool) or value is None:
        return False
    try:
        float(value)
        return True
    except (TypeError, ValueError):
        return False


def path_text(path):
    return "".join(f"[{p}]" if isinstance(p, int) else f".{p}" for p in path).lstrip(".")


def check(entry):
    """(Status, Hinweis) für eine Börse; ein zweiter Versuch bei Netz-/Serverfehlern."""
    status, body = fetch(entry)
    if status == 0 or status >= 500 or status == 429:
        time.sleep(3)
        status, body = fetch(entry)
    if status == 451 or (status == 403 and entry.get("geo")):
        return SKIP, f"HTTP {status} (geo-blocked from this runner)"
    if status != 200:
        return FAIL, f"HTTP {status}" if status else f"no connection ({body})"
    if len(body) > 2 * 1024 * 1024:
        return FAIL, "response larger than 2 MB"
    try:
        data = json.loads(body)
    except ValueError:
        return FAIL, "response is not JSON"
    missing = [path_text(p) for p in entry["fields"] if not numeric(walk(data, p))]
    if "names" in entry and walk(data, entry["names"]) is None:
        missing.append(path_text(entry["names"]))
    if missing:
        return FAIL, "missing or non-numeric: " + ", ".join(missing)
    last = float(walk(data, entry["fields"][0]))
    if last <= 0:
        return FAIL, f"{path_text(entry['fields'][0])} is {last}"
    return OK, ""


def issue_body(results):
    lines = [
        "The weekly exchange API check (`.github/workflows/exchange-live-check.yml`) found "
        "endpoints whose response no longer contains the fields the apps parse.",
        "",
        "| Exchange | Result | Detail | Endpoint |",
        "|---|---|---|---|",
    ]
    for entry, (status, note) in results:
        if status != FAIL:
            continue
        lines.append(f"| {entry['name']} | {status} | {note} | `{entry['url']}` |")
    passed = sum(1 for _, (s, _) in results if s == OK)
    lines += ["", f"{passed} of {len(results)} passed. Updated {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}."]
    lines += skipped_lines(results)
    lines += ["", "This issue is closed automatically once all checks pass again."]
    return "\n".join(lines)


def skipped_lines(results):
    """Übersprungene Börsen (geosperrt vom Runner) als Markdown-Abschnitt; leer = keine."""
    skipped = [(e, note) for e, (s, note) in results if s == SKIP]
    if not skipped:
        return []
    lines = ["", f"Skipped ({len(skipped)}, not checked from this runner):", ""]
    lines += [f"- {e['name']}: {note}" for e, note in skipped]
    return lines


def write_summary(results):
    """Kurzbericht in die Job-Zusammenfassung (GitHub Actions), auch übersprungene Börsen."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    counts = {k: sum(1 for _, (s, _) in results if s == k) for k in (OK, FAIL, SKIP)}
    lines = ["## Exchange API check", "",
             f"{counts[OK]} passed, {counts[FAIL]} failed, {counts[SKIP]} skipped (of {len(results)})."]
    failed = [f"- {e['name']}: {note}" for e, (s, note) in results if s == FAIL]
    if failed:
        lines += ["", "Failed:", ""] + failed
    lines += skipped_lines(results)
    try:
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    except OSError as e:
        print(f"could not write the job summary: {e}", file=sys.stderr)


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def open_issue_number():
    out = gh("issue", "list", "--state", "open", "--search", f'"{ISSUE_TITLE}" in:title',
             "--json", "number,title", "--limit", "20")
    for item in json.loads(out or "[]"):
        if item.get("title") == ISSUE_TITLE:
            return item["number"]
    return None


def manage_issue(failed, body):
    number = open_issue_number()
    if failed:
        if number is None:
            gh("issue", "create", "--title", ISSUE_TITLE, "--body", body)
            print("Opened issue.")
        else:
            gh("issue", "edit", str(number), "--body", body)
            gh("issue", "comment", str(number), "--body", "Still failing - table above updated.")
            print(f"Updated issue #{number}.")
    elif number is not None:
        gh("issue", "close", str(number), "--comment", "All exchange API checks passed again.")
        print(f"Closed issue #{number}.")


def save_result(path, failed_count, body):
    """Ergebnis für den Issue-Schritt: nur Zahl und fertiger Issue-Text."""
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"failed": failed_count, "body": body}, f)


def load_result(path):
    """(Anzahl Fehler, Issue-Text) aus --result; wirft ValueError bei falschem Inhalt."""
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    if not isinstance(doc, dict):
        raise ValueError("unexpected result file content")
    failed, body = doc.get("failed"), doc.get("body")
    if not isinstance(failed, int) or isinstance(failed, bool) or failed < 0 or not isinstance(body, str):
        raise ValueError("unexpected result file content")
    return failed, body[:60000]


def report_issue(failed_count, body):
    """Issue öffnen/aktualisieren/schliessen; Exit-Code."""
    if os.environ.get("GITHUB_ACTIONS") != "true" and not os.environ.get("GH_TOKEN"):
        print("Not in GitHub Actions and no GH_TOKEN: use --dry-run.", file=sys.stderr)
        return 2
    try:
        manage_issue(failed_count > 0, body)
    except (subprocess.CalledProcessError, OSError) as e:
        detail = getattr(e, "stderr", "") or str(e)
        print(f"::error::could not manage the issue: {detail.strip()[:300]}")
        return 1
    if failed_count:
        # Reported in the issue: annotation only, the job stays green
        print(f"::warning::{failed_count} exchange API check(s) failed - see issue \"{ISSUE_TITLE}\"")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="only check and print, no GitHub issue")
    parser.add_argument("--only", help="comma-separated exchange keys")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--skip-issue", action="store_true", help="check and write --result, no issue (no token)")
    mode.add_argument("--issue-only", action="store_true", help="no requests: manage the issue from --result")
    parser.add_argument("--result", help="result file between --skip-issue and --issue-only")
    args = parser.parse_args()
    if (args.skip_issue or args.issue_only) and not args.result:
        print("--skip-issue and --issue-only need --result", file=sys.stderr)
        return 2
    if args.issue_only:
        try:
            failed_count, body = load_result(args.result)
        except (OSError, ValueError) as e:
            print(f"::error::result file unreadable: {e}")
            return 1
        return report_issue(failed_count, body)

    entries = EXCHANGES
    if args.only:
        wanted = {k.strip().lower() for k in args.only.split(",")}
        entries = [e for e in EXCHANGES if e["key"] in wanted]
        unknown = wanted - {e["key"] for e in entries}
        if unknown:
            print("Unknown keys: " + ", ".join(sorted(unknown)), file=sys.stderr)
            return 2

    results = []
    for entry in entries:
        status, note = check(entry)
        results.append((entry, (status, note)))
        print(f"{status.upper():4}  {entry['name']:<20} {note}")

    failed = [e for e, (s, _) in results if s == FAIL]
    skipped = [e["name"] for e, (s, _) in results if s == SKIP]
    if skipped:
        print(f"\nSkipped ({len(skipped)}): " + ", ".join(skipped))
    write_summary(results)
    body = issue_body(results)
    if args.skip_issue:
        save_result(args.result, len(failed), body)
        print(f"result written: {args.result}")
        return 0
    if args.dry_run:
        if failed:
            print("\n--- issue text (dry run) ---\n" + body)
        return 0
    return report_issue(len(failed), body)


if __name__ == "__main__":
    sys.exit(main())
