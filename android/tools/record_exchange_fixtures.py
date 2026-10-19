#!/usr/bin/env python3
"""Record real, dated exchange responses for the contract tests.

The hand-written fixtures in android/testdata/exchanges/exchange_*.json pin exact
expected values but were written from the API documentation. This tool adds the
other half: it fetches the live endpoints the adapters read (single ticker for
every registered market, plus the batch ticker and pair list where the adapter
has a single fixed address) and stores the raw bodies, with the UTC time of the
recording, in android/testdata/exchanges/recorded/recorded_<market>.json.

ExchangeContractTest.kt (Android) and ExchangeContractTests.swift (iOS) run these
files when they are present. Real prices change, so the recorded cases only check
plausibility: the parser must not throw, a single ticker must have a positive
last price, a batch must yield tickers, a pair list must contain the pair the
single ticker asked for. Nothing is committed automatically: review the files
(git diff) and commit them by hand.

The table below mirrors the adapters in android/marketdata/.../model/market/*.kt
and ios/Shared/Markets/*.swift (and .github/scripts/exchange_live_check.py) -
keep it in sync when an adapter changes an address.

Geo-blocked answers (HTTP 451, or 403 for exchanges that block US cloud IPs, as
on GitHub's runners) and oversized bodies are skipped and listed, not recorded.

Usage:
  python3 android/tools/record_exchange_fixtures.py               # all markets
  python3 android/tools/record_exchange_fixtures.py --only Kraken,Okex
  python3 android/tools/record_exchange_fixtures.py --out /tmp/rec --dry-run

Exit code: 0 = at least one market recorded (or --dry-run), 1 = nothing recorded,
2 = bad arguments. Python 3.9+ standard library only.
"""
import argparse
import ssl
import json
import os
import sys
import time
import urllib.error
import urllib.request

TIMEOUT_SECONDS = 30
# Gleicher User-Agent wie die Android-App (HttpUserAgent.android): Einige Börsen (z. B. Bitvavo hinter einer
# Bot-Abwehr) lehnen fremde Kennungen mit HTTP 403 ab – die Aufnahme soll sehen, was die App sieht.
# Mit --user-agent überschreibbar.
USER_AGENT = "cryptoChecker-Android/16.2.2"
# Wie die Apps: Kurse höchstens 2 MB; Paarlisten dürfen grösser sein, die Testdaten aber nicht ausufern
MAX_TICKER_BYTES = 2 * 1024 * 1024
MAX_PAIRS_BYTES = 4 * 1024 * 1024
DEFAULT_OUT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            "..", "testdata", "exchanges", "recorded"))


def pair(base, quote, pair_id, contract="NONE"):
    return {"base": base, "quote": quote, "id": pair_id, "contract": contract}


BTC_USDT = ("BTC", "USDT")

# Schlüssel = MarketsConfig-Schlüssel (Klassenname). single: Einzelkurs (Anfrage 0);
# bulk: Sammelabfrage (Anfrage 0), nur wo sie ohne Vorwissen lesbar ist; pairs: Paarliste, nur
# bei genau einer festen GET-Adresse (SimpleMarket). post = JSON-Körper (POST); geo = 403 gilt als gesperrt;
# stamp = «timestamp=<jetzt in ms>» beim Abruf anhängen (BingX).
MARKETS = [
    {"market": "Coinbase", "pair": pair("BTC", "USD", "BTC-USD"),
     "single": "https://api.exchange.coinbase.com/products/BTC-USD/stats",
     "pairs": "https://api.exchange.coinbase.com/products"},
    {"market": "Kraken", "pair": pair("BTC", "USD", "XXBTZUSD"),
     "single": "https://api.kraken.com/0/public/Ticker?pair=XXBTZUSD",
     "bulk": "https://api.kraken.com/0/public/Ticker",
     "pairs": "https://api.kraken.com/0/public/AssetPairs"},
    {"market": "GateIo", "pair": pair(*BTC_USDT, "BTC_USDT"),
     "single": "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=BTC_USDT",
     "bulk": "https://api.gateio.ws/api/v4/spot/tickers",
     "pairs": "https://api.gateio.ws/api/v4/spot/currency_pairs"},
    {"market": "Binance", "pair": pair(*BTC_USDT, "BTCUSDT"), "geo": True,
     "single": "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT",
     "bulk": "https://api.binance.com/api/v3/ticker/24hr",
     # Wie BinanceBase (Binance.kt / Binance.swift): binance.com filtert serverseitig auf handelbare Paare
     # ohne Berechtigungslisten (kleinere Antwort); Binance.US bleibt ohne Abfrage-Parameter
     "pairs": "https://api.binance.com/api/v3/exchangeInfo?symbolStatus=TRADING&showPermissionSets=false"},
    {"market": "BinanceUs", "pair": pair("BTC", "USD", "BTCUSD"), "geo": True,
     "single": "https://api.binance.us/api/v3/ticker/24hr?symbol=BTCUSD",
     "bulk": "https://api.binance.us/api/v3/ticker/24hr",
     "pairs": "https://api.binance.us/api/v3/exchangeInfo"},
    {"market": "Kucoin", "pair": pair(*BTC_USDT, "BTC-USDT"),
     "single": "https://api.kucoin.com/api/v1/market/stats?symbol=BTC-USDT",
     "bulk": "https://api.kucoin.com/api/v1/market/allTickers",
     "pairs": "https://api.kucoin.com/api/v2/symbols"},
    {"market": "Okex", "pair": pair(*BTC_USDT, "BTC-USDT"),
     "single": "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT",
     "bulk": "https://www.okx.com/api/v5/market/tickers?instType=SPOT",
     "pairs": "https://www.okx.com/api/v5/market/tickers?instType=SPOT"},
    {"market": "OkexFutures", "pair": pair(*BTC_USDT, "BTC-USDT-SWAP", "PERPETUAL"),
     "single": "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT-SWAP",
     "bulk": "https://www.okx.com/api/v5/market/tickers?instType=SWAP",
     "pairs": "https://www.okx.com/api/v5/public/instruments?instType=SWAP"},
    {"market": "BinanceFutures", "pair": pair(*BTC_USDT, "BTCUSDT", "PERPETUAL"), "geo": True,
     "single": "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT",
     "bulk": "https://fapi.binance.com/fapi/v1/ticker/24hr"},
    {"market": "Bybit", "pair": pair(*BTC_USDT, "BTCUSDT"), "geo": True,
     "single": "https://api.bybit.com/v5/market/tickers?category=spot&symbol=BTCUSDT",
     "bulk": "https://api.bybit.com/v5/market/tickers?category=spot",
     "pairs": "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000"},
    {"market": "BybitFutures", "pair": pair(*BTC_USDT, "BTCUSDT", "PERPETUAL"), "geo": True,
     "single": "https://api.bybit.com/v5/market/tickers?category=linear&symbol=BTCUSDT",
     "bulk": "https://api.bybit.com/v5/market/tickers?category=linear",
     "pairs": "https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000"},
    {"market": "Bitget", "pair": pair(*BTC_USDT, "BTCUSDT"),
     "single": "https://api.bitget.com/api/v2/spot/market/tickers?symbol=BTCUSDT",
     "bulk": "https://api.bitget.com/api/v2/spot/market/tickers",
     "pairs": "https://api.bitget.com/api/v2/spot/public/symbols"},
    {"market": "BitgetFutures", "pair": pair(*BTC_USDT, "BTCUSDT", "PERPETUAL"),
     "single": "https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES",
     "bulk": "https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES",
     "pairs": "https://api.bitget.com/api/v2/mix/market/contracts?productType=USDT-FUTURES"},
    {"market": "Mexc", "pair": pair(*BTC_USDT, "BTCUSDT"),
     "single": "https://api.mexc.com/api/v3/ticker/24hr?symbol=BTCUSDT",
     "bulk": "https://api.mexc.com/api/v3/ticker/24hr",
     "pairs": "https://api.mexc.com/api/v3/exchangeInfo"},
    {"market": "MexcFutures", "pair": pair(*BTC_USDT, "BTC_USDT", "PERPETUAL"),
     "single": "https://contract.mexc.com/api/v1/contract/ticker?symbol=BTC_USDT",
     "bulk": "https://contract.mexc.com/api/v1/contract/ticker",
     "pairs": "https://contract.mexc.com/api/v1/contract/detail"},
    {"market": "Htx", "pair": pair(*BTC_USDT, "btcusdt"),
     "single": "https://api.huobi.pro/market/detail/merged?symbol=btcusdt",
     "bulk": "https://api.huobi.pro/market/tickers",
     "pairs": "https://api.huobi.pro/v2/settings/common/symbols"},
    {"market": "HtxFutures", "pair": pair(*BTC_USDT, "BTC-USDT", "PERPETUAL"),
     "single": "https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=BTC-USDT",
     "bulk": "https://api.hbdm.com/linear-swap-ex/market/detail/batch_merged?business_type=swap",
     "pairs": "https://api.hbdm.com/linear-swap-api/v1/swap_contract_info?business_type=swap"},
    {"market": "Bitfinex", "pair": pair("BTC", "USD", "tBTCUSD"),
     "single": "https://api-pub.bitfinex.com/v2/ticker/tBTCUSD",
     "bulk": "https://api-pub.bitfinex.com/v2/tickers?symbols=ALL",
     "pairs": "https://api-pub.bitfinex.com/v2/conf/pub:list:pair:exchange"},
    {"market": "Bitstamp", "pair": pair("BTC", "USD", "btcusd"),
     "single": "https://www.bitstamp.net/api/v2/ticker/btcusd/",
     "bulk": "https://www.bitstamp.net/api/v2/ticker/",
     "pairs": "https://www.bitstamp.net/api/v2/trading-pairs-info/"},
    {"market": "Bitvavo", "pair": pair("BTC", "EUR", "BTC-EUR"),
     "single": "https://api.bitvavo.com/v2/ticker/24h?market=BTC-EUR",
     "bulk": "https://api.bitvavo.com/v2/ticker/24h",
     "pairs": "https://api.bitvavo.com/v2/markets"},
    {"market": "CryptoCom", "pair": pair(*BTC_USDT, "BTC_USDT"),
     "single": "https://api.crypto.com/exchange/v1/public/get-tickers?instrument_name=BTC_USDT",
     "bulk": "https://api.crypto.com/exchange/v1/public/get-tickers",
     "pairs": "https://api.crypto.com/exchange/v1/public/get-instruments"},
    {"market": "Gemini", "pair": pair("BTC", "USD", "btcusd"),
     "single": "https://api.gemini.com/v1/pubticker/btcusd",
     "bulk": "https://api.gemini.com/v1/pricefeed",
     "pairs": "https://api.gemini.com/v1/symbols"},
    {"market": "Hyperliquid", "pair": pair("BTC", "USDC", "BTC", "PERPETUAL"),
     "single": "https://api.hyperliquid.xyz/info", "post": {"type": "metaAndAssetCtxs"},
     "bulk": "https://api.hyperliquid.xyz/info"},
    # BingX: die Adapter hängen an jede Adresse «timestamp=<ms>» an (stamp), wie BingX.kt / BingX.swift
    {"market": "BingX", "pair": pair(*BTC_USDT, "BTC-USDT"), "stamp": True,
     "single": "https://open-api.bingx.com/openApi/spot/v1/ticker/24hr?symbol=BTC-USDT",
     "bulk": "https://open-api.bingx.com/openApi/spot/v1/ticker/24hr",
     "pairs": "https://open-api.bingx.com/openApi/spot/v1/common/symbols"},
    {"market": "BingXFutures", "pair": pair(*BTC_USDT, "BTC-USDT", "PERPETUAL"), "stamp": True,
     "single": "https://open-api.bingx.com/openApi/swap/v2/quote/ticker?symbol=BTC-USDT",
     "bulk": "https://open-api.bingx.com/openApi/swap/v2/quote/ticker",
     "pairs": "https://open-api.bingx.com/openApi/swap/v2/quote/contracts"},
    {"market": "Aster", "pair": pair(*BTC_USDT, "BTCUSDT", "PERPETUAL"),
     "single": "https://fapi.asterdex.com/fapi/v1/ticker/24hr?symbol=BTCUSDT",
     "bulk": "https://fapi.asterdex.com/fapi/v1/ticker/24hr",
     "pairs": "https://fapi.asterdex.com/fapi/v1/exchangeInfo"},
    # Lighter: Einzel- und Sammelkurs aus exchangeStats (Paar-ID = Symbol)
    {"market": "Lighter", "pair": pair("BTC", "USDC", "BTC", "PERPETUAL"),
     "single": "https://mainnet.zklighter.elliot.ai/api/v1/exchangeStats",
     "bulk": "https://mainnet.zklighter.elliot.ai/api/v1/exchangeStats",
     "pairs": "https://mainnet.zklighter.elliot.ai/api/v1/orderBooks"},
    {"market": "Woo", "pair": pair(*BTC_USDT, "SPOT_BTC_USDT"),
     "single": "https://api.woox.io/v3/public/kline?symbol=SPOT_BTC_USDT&type=1h&limit=25",
     "pairs": "https://api.woox.io/v3/public/instruments"},
    {"market": "WooFutures", "pair": pair(*BTC_USDT, "PERP_BTC_USDT", "PERPETUAL"),
     "single": "https://api.woox.io/v3/public/futures?symbol=PERP_BTC_USDT",
     "bulk": "https://api.woox.io/v3/public/futures",
     "pairs": "https://api.woox.io/v3/public/instruments"},
    {"market": "Deribit", "pair": pair("BTC", "USD", "BTC-PERPETUAL", "INVERSE_PERPETUAL"),
     "single": "https://www.deribit.com/api/v2/public/ticker?instrument_name=BTC-PERPETUAL",
     "bulk": "https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency=BTC&kind=future"},
    # Phemex: Sammelabfrage braucht zuerst die Skalen aus der Produktliste, darum nur Einzelkurs und Liste
    {"market": "Phemex", "pair": pair(*BTC_USDT, "sBTCUSDT"),
     "single": "https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT",
     "pairs": "https://api.phemex.com/public/products"},
    {"market": "PhemexFutures", "pair": pair(*BTC_USDT, "BTCUSDT", "PERPETUAL"),
     "single": "https://api.phemex.com/md/v2/ticker/24hr?symbol=BTCUSDT",
     "pairs": "https://api.phemex.com/public/products"},
    {"market": "Poloniex", "pair": pair(*BTC_USDT, "BTC_USDT"),
     "single": "https://api.poloniex.com/markets/BTC_USDT/ticker24h",
     "bulk": "https://api.poloniex.com/markets/ticker24h",
     "pairs": "https://api.poloniex.com/markets"},
    # BTC_EUR steht seit Okt. 2026 in der Instrumentenliste auf «CLOSED» – darum das aktive BTC_USDC
    {"market": "OneTrading", "pair": pair("BTC", "USDC", "BTC_USDC"),
     "single": "https://api.onetrading.com/fast/v1/market-ticker/BTC_USDC",
     "bulk": "https://api.onetrading.com/fast/v1/market-ticker",
     "pairs": "https://api.onetrading.com/fast/v1/instruments"},
    {"market": "Upbit", "pair": pair("BTC", "KRW", "KRW-BTC"),
     "single": "https://api.upbit.com/v1/ticker?markets=KRW-BTC",
     "bulk": "https://api.upbit.com/v1/ticker/all?quote_currencies=KRW,BTC,USDT",
     "pairs": "https://api.upbit.com/v1/market/all?is_details=false"},
    {"market": "Bithumb", "pair": pair("BTC", "KRW", "KRW-BTC"),
     "single": "https://api.bithumb.com/v1/ticker?markets=KRW-BTC",
     "pairs": "https://api.bithumb.com/v1/market/all?isDetails=false"},
    {"market": "BitFlyer", "pair": pair("BTC", "JPY", "BTC_JPY"),
     "single": "https://api.bitflyer.com/v1/ticker?product_code=BTC_JPY"},
    # bitbank: Paarliste ist dieselbe Antwort wie die Sammelabfrage (/tickers)
    {"market": "Bitbank", "pair": pair("BTC", "JPY", "btc_jpy"),
     "single": "https://public.bitbank.cc/btc_jpy/ticker",
     "bulk": "https://public.bitbank.cc/tickers",
     "pairs": "https://public.bitbank.cc/tickers"},
    # Coincheck: keine Sammelabfrage; exchange_status dient als Paarliste
    {"market": "Coincheck", "pair": pair("BTC", "JPY", "btc_jpy"),
     "single": "https://coincheck.com/api/ticker?pair=btc_jpy",
     "pairs": "https://coincheck.com/api/exchange_status"},
    {"market": "Btcturk", "pair": pair("BTC", "TRY", "BTCTRY"),
     "single": "https://api.btcturk.com/api/v2/ticker?pairSymbol=BTCTRY",
     "bulk": "https://api.btcturk.com/api/v2/ticker",
     "pairs": "https://api.btcturk.com/api/v2/server/exchangeinfo"},
    {"market": "Bitso", "pair": pair("BTC", "MXN", "btc_mxn"),
     "single": "https://bitso.com/api/v3/ticker?book=btc_mxn",
     "bulk": "https://bitso.com/api/v3/ticker",
     "pairs": "https://bitso.com/api/v3/available_books"},
    {"market": "Indodax", "pair": pair("BTC", "IDR", "btcidr"),
     "single": "https://indodax.com/api/ticker/btcidr",
     "bulk": "https://indodax.com/api/ticker_all",
     "pairs": "https://indodax.com/api/pairs"},
    {"market": "ZebPay", "pair": pair("BTC", "INR", "BTC-INR"),
     "single": "https://sapi.zebpay.com/api/v2/market/ticker?symbol=BTC-INR",
     "bulk": "https://sapi.zebpay.com/api/v2/market/allTickers",
     "pairs": "https://sapi.zebpay.com/api/v2/ex/exchangeInfo"},
    # Build 85: Formate von Hand nach der Doku geschrieben – erste Aufnahme prüft sie (exchange_<name>.json)
    # Paribu: eine Gesamtliste dient als Einzelkurs, Sammelabfrage und Paarliste
    {"market": "Paribu", "pair": pair("BTC", "TRY", "BTC_TL"),
     "single": "https://www.paribu.com/ticker",
     "bulk": "https://www.paribu.com/ticker",
     "pairs": "https://www.paribu.com/ticker"},
    # Icrypex: kein Einzelabruf (/v1/ticker antwortet 404), der Einzelkurs liest die Gesamtliste
    {"market": "Icrypex", "pair": pair("BTC", "USDT", "BTCUSDT"),
     "single": "https://api.icrypex.com/v1/tickers",
     "bulk": "https://api.icrypex.com/v1/tickers",
     "pairs": "https://api.icrypex.com/v1/exchange/info"},
    # Mercado Bitcoin: Sammelabfrage nur mit Auswahl
    {"market": "MercadoBitcoin", "pair": pair("BTC", "BRL", "BTC-BRL"),
     "single": "https://api.mercadobitcoin.net/api/v4/tickers?symbols=BTC-BRL",
     "bulk": "https://api.mercadobitcoin.net/api/v4/tickers?symbols=BTC-BRL,ETH-BRL",
     "pairs": "https://api.mercadobitcoin.net/api/v4/symbols"},
    {"market": "Luno", "pair": pair("BTC", "ZAR", "XBTZAR"),
     "single": "https://api.luno.com/api/1/ticker?pair=XBTZAR",
     "bulk": "https://api.luno.com/api/1/tickers",
     "pairs": "https://api.luno.com/api/exchange/1/markets"},
    # CoinDCX: kein Einzelabruf, der Einzelkurs liest die Gesamtliste
    {"market": "CoinDcx", "pair": pair("BTC", "INR", "BTCINR"),
     "single": "https://api.coindcx.com/exchange/ticker",
     "bulk": "https://api.coindcx.com/exchange/ticker",
     "pairs": "https://api.coindcx.com/exchange/v1/markets_details"},
    # HashKey Exchange (Hongkong) sperrt womöglich US-Adressen: 403 gilt als gesperrt
    {"market": "HashKey", "pair": pair("BTC", "USD", "BTCUSD"), "geo": True,
     "single": "https://api-pro.hashkey.com/quote/v1/ticker/24hr?symbol=BTCUSD",
     "bulk": "https://api-pro.hashkey.com/quote/v1/ticker/24hr",
     "pairs": "https://api-pro.hashkey.com/api/v1/exchangeInfo"},
    {"market": "IndependentReserve", "pair": pair("BTC", "AUD", "Xbt_Aud"),
     "single": "https://api.independentreserve.com/Public/GetMarketSummary?primaryCurrencyCode=Xbt&secondaryCurrencyCode=Aud"},
    # BTC Markets: Sammelabfrage nur als Auswahl (je Markt ein «marketId»), wie BtcMarkets.kt / BtcMarkets.swift
    {"market": "BtcMarkets", "pair": pair("BTC", "AUD", "BTC-AUD"),
     "single": "https://api.btcmarkets.net/v3/markets/BTC-AUD/ticker",
     "bulk": "https://api.btcmarkets.net/v3/markets/tickers?marketId=BTC-AUD&marketId=ETH-AUD",
     "pairs": "https://api.btcmarkets.net/v3/markets"},
    {"market": "Latoken",
     # BTC/USDT über die Währungs-IDs (wie der Adapter, siehe exchange_latoken.json)
     "pair": pair(*BTC_USDT, "92151d82-df98-4d88-9a4d-284fa9eca49f/0c3a106d-bde3-4c13-a26e-3fd2394529e5"),
     "single": "https://api.latoken.com/v2/ticker/92151d82-df98-4d88-9a4d-284fa9eca49f/0c3a106d-bde3-4c13-a26e-3fd2394529e5",
     "bulk": "https://api.latoken.com/v2/ticker"},
    {"market": "NonKyc", "pair": pair(*BTC_USDT, "BTC_USDT"),
     "single": "https://api.nonkyc.io/api/v2/ticker/BTC_USDT",
     "bulk": "https://api.nonkyc.io/api/v2/tickers",
     "pairs": "https://api.nonkyc.io/api/v2/market/getlist"},
    {"market": "DexScreener",
     "pair": pair("PEPE", "USD (ethereum)", "ethereum/0x11950d141EcB863F01007AdD7D1A342041227b58"),
     "single": "https://api.dexscreener.com/latest/dex/pairs/ethereum/0x11950d141EcB863F01007AdD7D1A342041227b58",
     # Sammelabfrage: mehrere Pools derselben Chain mit Komma (höchstens 30)
     "bulk": "https://api.dexscreener.com/latest/dex/pairs/ethereum/"
             "0x11950d141EcB863F01007AdD7D1A342041227b58,0xA43fe16908251ee70EF74718545e4FE6C5cCEc9f"},
]


def _ssl_context():
    """Zertifikate von «certifi», wenn installiert: Der Windows-Zertifikatsspeicher enthält manchmal abgelaufene
    Zwischenzertifikate, dann scheitern einzelne Börsen mit «certificate has expired», obwohl der Browser sie
    öffnet. Ohne certifi gilt der Standard des Systems."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


SSL_CONTEXT = _ssl_context()


def _error_hint(error):
    """Kurzer Hinweis zu einer HTTP-Fehlerantwort: Server-Kopf und Anfang des Textes. Damit lässt sich eine
    Bot-Abwehr (z. B. «server: cloudflare», HTML-Seite) von einer Fehlermeldung der Börse unterscheiden."""
    server = error.headers.get("Server", "") if error.headers else ""
    try:
        text = error.read(300).decode("utf-8", "replace")
    except Exception:
        text = ""
    text = " ".join(text.split())[:120]
    parts = [p for p in (f"server={server}" if server else "", text) if p]
    return "; ".join(parts)


def fetch(url, post, limit):
    """(HTTP-Status, Text oder Fehlername); Status 0 = keine Verbindung, Text None = zu gross."""
    data = None
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if post is not None:
        data = json.dumps(post).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers)
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS, context=SSL_CONTEXT) as resp:
                raw = resp.read(limit + 1)
                return resp.status, (None if len(raw) > limit else raw.decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            # Ein zweiter Versuch nur bei Überlast/Serverfehler
            if attempt == 1 and (e.code == 429 or e.code >= 500):
                time.sleep(3)
                continue
            return e.code, _error_hint(e)
        except Exception as e:  # Zeitüberschreitung, DNS, TLS …
            if attempt == 1:
                time.sleep(3)
                continue
            # Grund mit ausgeben (z. B. «CERTIFICATE_VERIFY_FAILED», «Name or service not known»), sonst ist
            # «URLError» nicht zu deuten
            reason = getattr(e, "reason", None) or e
            return 0, f"{type(e).__name__}: {reason}"
    return 0, "unreachable"


def record_one(entry, kind, url, recorded_at):
    """Ein Fall oder (None, Grund)."""
    post = entry.get("post")
    if entry.get("stamp"):
        url += ("&" if "?" in url else "?") + f"timestamp={int(time.time() * 1000)}"
    limit = MAX_PAIRS_BYTES if kind == "pairs" else MAX_TICKER_BYTES
    status, body = fetch(url, post, limit)
    if status == 451 or (status == 403 and entry.get("geo")):
        return None, f"HTTP {status} (geo-blocked)"
    if status != 200:
        if not status:
            return None, f"no connection ({body})"
        return None, f"HTTP {status} ({body})" if body else f"HTTP {status}"
    if body is None:
        return None, f"larger than {limit // (1024 * 1024)} MB"
    try:
        json.loads(body)
    except ValueError:
        return None, "not JSON"
    case = {
        "kind": kind,
        "name": f"recorded {kind} {recorded_at}",
        "market": entry["market"],
        "requestId": 0,
        "request": ("POST " if post is not None else "GET ") + url,
        "body": body,
    }
    if kind == "single":
        case["pair"] = entry["pair"]
    if kind == "pairs":
        # Die Liste muss das Paar des Einzelkurses enthalten (gleiche Kennung wie in der App)
        case["contains"] = entry["pair"]["id"]
    return case, ""


def record_market(entry, recorded_at):
    cases, notes = [], []
    for kind in ("single", "bulk", "pairs"):
        url = entry.get(kind)
        if not url:
            continue
        case, note = record_one(entry, kind, url, recorded_at)
        if case is None:
            notes.append(f"{kind}: {note}")
        else:
            cases.append(case)
    return cases, notes


def main():
    global USER_AGENT
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="comma-separated market keys (MarketsConfig), e.g. Kraken,Okex")
    parser.add_argument("--out", default=DEFAULT_OUT, help="output folder (default: android/testdata/exchanges/recorded)")
    parser.add_argument("--dry-run", action="store_true", help="fetch and report, write nothing")
    parser.add_argument("--user-agent", help=f"User-Agent header (default: {USER_AGENT}, like the Android app)")
    args = parser.parse_args()

    entries = MARKETS
    if args.user_agent:
        USER_AGENT = args.user_agent
    if args.only:
        wanted = {k.strip() for k in args.only.split(",") if k.strip()}
        entries = [e for e in MARKETS if e["market"] in wanted]
        unknown = wanted - {e["market"] for e in entries}
        if unknown:
            print("Unknown market keys: " + ", ".join(sorted(unknown)), file=sys.stderr)
            return 2

    recorded_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    if not args.dry_run:
        os.makedirs(args.out, exist_ok=True)
    written = 0
    for entry in entries:
        cases, notes = record_market(entry, recorded_at)
        summary = ", ".join(c["kind"] for c in cases) or "-"
        print(f"{entry['market']:<20} recorded: {summary:<20} {'; '.join(notes)}")
        if not cases or args.dry_run:
            continue
        data = {
            "market": entry["market"],
            "recordedAt": recorded_at,
            "description": "Recorded live by android/tools/record_exchange_fixtures.py. Real responses: "
                           "the contract tests only check plausibility (parser does not throw, last > 0, "
                           "batch has tickers, pair list contains the pair). Skipped: "
                           + ("; ".join(notes) or "nothing") + ".",
            "cases": cases,
        }
        path = os.path.join(args.out, f"recorded_{entry['market'].lower()}.json")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        written += 1
    print(f"\n{written} file(s) written to {args.out}" if not args.dry_run else "\nDry run: nothing written.")
    return 0 if written or args.dry_run else 1


if __name__ == "__main__":
    sys.exit(main())
