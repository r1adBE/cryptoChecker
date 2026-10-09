#!/usr/bin/env python3
"""Builds docs/logos/: coin and stock logos and names for the Crypto Checker apps.

The apps load ONE small file (docs/logos/index.json, served by GitHub Pages at
https://r1adbe.github.io/cryptoChecker/logos/index.json), the first time ONE pack
with all logos (release asset «logos.pack»), later only the few new logos from
docs/logos/img/. No app asks CoinGecko, Binance, OKX or Nasdaq for anything about
single coins, so nobody learns what is on a watchlist.

Crypto (key = ticker, the same order the apps used before):
  1. CoinGecko /coins/markets top 1000 (best rank wins a ticker),
  2. Binance website symbol list (logo «logo», name «fullName»),
  3. Binance Alpha token list (tradable first, largest market cap first),
  4. the rest of CoinGecko (ALL pages; per ticker the highest 24 h volume, then market cap;
     only coins in the top 1500 or with at least MIN_VOLUME_USD volume),
  5. OKX public instrument lists (SPOT + SWAP) with static.okx.com icons.
  Logo and name are filled separately: a later source only fills what is missing.

TradFi (key = «TRADFI:<ticker>», never a crypto logo):
  - gold (XAU, GOLD, XAUT), silver (XAG, SILVER), platinum (XPT) and palladium (XPD): our own drawn
    icons (metal_icon) and names;
  - Binance symbol list: tokenised stocks («NVDAB», tag bStocks → NVDA), entries only on
    futures, entries CoinGecko does not know (same rules as the apps' pickTradFi);
  - Binance stock images bin.bnbstatic.com/static/stock/<TICKER>.png, probed for every US
    stock of the Nasdaq symbol directory (misses retried after MISS_RETRY_DAYS);
  - names from the Nasdaq symbol directory (nasdaqlisted.txt + otherlisted.txt), all of them
    in docs/logos/stocks.txt («TICKER<TAB>Name» lines) for stock pairs without a logo.

Sources other than CoinGecko are optional: if one is unreachable (Binance and OKX block
some regions), its previous entries stay. CoinGecko must deliver every page, else nothing
is written (exit 1; the apps keep the last list). Same if the crypto list shrinks by half.

Images: downloaded once per source URL, scaled to at most 128 px, stored as WebP named by
content hash (img/<sha1-16>.webp); a changed logo gets a new name. Unused files are deleted.
--pack writes all referenced images into one file:
  b"CCLP1\\n" then per image  b"img/<hash>.webp\\t<length>\\n" + <length> bytes.

index.json (only what the apps need):
  {"version": 1, "generated": "...Z", "source": "...", "pack": "<sha1 of the pack>",
   "coins": {"BTC": {"f": "img/0123456789abcdef.webp", "n": "Bitcoin"}, "TRADFI:NVDA": {...}, ...}}
state.json (for the next run): {"stockMiss": {"XYZ": "2026-10-09"}, "src": {"BTC": ["cg", "<source url>"]}}
status.json: time of the last successful run, counts and which sources were reachable.

Needs Python 3.9+ and Pillow. Optional free CoinGecko demo key: env COINGECKO_API_KEY.
"""
import argparse
import concurrent.futures
import datetime as dt
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

USER_AGENT = "cryptoChecker-logos (+https://github.com/r1adBE/cryptoChecker)"
CG_MARKETS = ("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd"
              "&order=market_cap_desc&per_page=250&page={page}&sparkline=false")
PER_PAGE = 250
MAX_PAGES = 120
BINANCE_LIST = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"
ALPHA_LIST = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"
OKX_INSTRUMENTS = "https://www.okx.com/api/v5/public/instruments?instType={kind}"
OKX_ICON = "https://static.okx.com/cdn/oksupport/asset/currency/icon/{ccy}.png"
STOCK_ICON = "https://bin.bnbstatic.com/static/stock/{ticker}.png"
NASDAQ_FILES = ["https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt",
                "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"]

TOP_RANK = 1000
KEEP_RANK = 1500
MIN_VOLUME_USD = 100_000
STORED_PX = 128
MAX_IMAGE_BYTES = 2 * 1024 * 1024
MISS_RETRY_DAYS = 60
PLACEHOLDER_LIMIT = 20       # the same image for more stock tickers = a placeholder
NAME_MAX = 40
TRADFI = "TRADFI:"
PACK_MAGIC = b"CCLP1\n"
PLAIN = re.compile(r"^[A-Z0-9]{1,20}$")

MULTIPLIER_PREFIXES = ["1000000", "100000", "10000", "1000", "1M"]
ALIASES = {"XBT": "BTC", "XDG": "DOGE", "LUNA2": "LUNA"}
STOCK_TAG = "bstocks"
SPACES = re.compile(r"\s+")
STOCK_SUFFIX = re.compile(r"[\s,(]*(bStocks?|xStocks?|Tokeni[sz]ed Stock)\)?\s*$", re.IGNORECASE)
STOCK_NAME_TAIL = re.compile(
    r"[\s,]*(?:New\s+)?(?:(?:Class|Series)\s+[A-Z0-9]+\s+)?(?:Common Stock|Common Shares|Ordinary Shares|"
    r"American Deposit[ao]ry Shares|Depositary Shares|Shares of Beneficial Interest)\b.*$", re.IGNORECASE)


def log(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------------------
# Network

def http_get(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def get_json(url, headers=None, tries=5, wait=60.0):
    """GET with retries; honours Retry-After on 429/5xx; other 4xx fail at once."""
    last = None
    for attempt in range(tries):
        try:
            return json.loads(http_get(url, headers).decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 429 or e.code >= 500:
                retry = e.headers.get("Retry-After") if e.headers else None
                pause = float(retry) if retry and retry.isdigit() else wait * (attempt + 1)
                log(f"  HTTP {e.code}, waiting {min(pause, 180):.0f} s")
                time.sleep(min(pause, 180))
                continue
            raise
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            last = e
            time.sleep(min(10 * (attempt + 1), wait))
    raise RuntimeError(f"{url}: {last}")


# ---------------------------------------------------------------------------
# Rules shared with the apps (CoinLogos.kt / CoinLogos.swift)

def normalize(symbol):
    s = (symbol or "").strip().upper()
    for p in MULTIPLIER_PREFIXES:
        if s.startswith(p) and len(s) - len(p) >= 2 and s[len(p)].isalpha():
            s = s[len(p):]
            break
    return ALIASES.get(s, s)


def clean_name(raw):
    if raw is None:
        return None
    s = SPACES.sub(" ", raw).strip()
    s = STOCK_SUFFIX.sub("", s).strip()
    if not s:
        return None
    return s[:NAME_MAX - 1].rstrip() + "…" if len(s) > NAME_MAX else s


def clean_stock_name(raw):
    if raw is None:
        return None
    s = SPACES.sub(" ", raw).strip()
    dash = s.find(" - ")
    if dash > 0:
        s = s[:dash]
    s = STOCK_NAME_TAIL.sub("", s).strip().rstrip(", ")
    return clean_name(s)


def parse_symbol_directory(text, into=None):
    into = {} if into is None else into
    lines = (text or "").splitlines()
    if not lines:
        return into
    header = [h.strip() for h in lines[0].split("|")]
    sym = next((i for i, h in enumerate(header) if h in ("Symbol", "ACT Symbol")), -1)
    name = header.index("Security Name") if "Security Name" in header else -1
    test = header.index("Test Issue") if "Test Issue" in header else -1
    if sym < 0 or name < 0:
        return into
    for line in lines[1:]:
        parts = line.split("|")
        if len(parts) <= max(sym, name):
            continue
        if test >= 0 and len(parts) > test and parts[test].strip() == "Y":
            continue
        symbol = parts[sym].strip().upper()
        if not symbol or symbol in into:
            continue
        n = clean_stock_name(parts[name])
        if n:
            into[symbol] = n
    return into


def allowed_image(url):
    if not url or not url.startswith("https://") or "/missing_" in url:
        return False
    host = url[8:].split("/")[0].split("?")[0].lower()
    if not host or "@" in host or ":" in host:
        return False
    return host == "coingecko.com" or host.endswith(".coingecko.com") or host.endswith(".bnbstatic.com") \
        or host == "static.okx.com"


def pick_tradfi(entries, crypto):
    """Binance list → {ticker: entry} like the apps' pickTradFi (bStocks, only futures, unknown to CoinGecko)."""
    out = {}

    def put(symbol, e):
        s = (symbol or "").strip().upper()
        if PLAIN.match(s) and s not in out:
            out[s] = e
    for e in entries:
        n = e["name"].strip().upper()
        if len(n) > 1 and n.endswith("B") and any(t.lower() == STOCK_TAG for t in e["tags"]):
            put(n[:-1], e)
    for e in entries:
        if e["onlyFutures"]:
            put(e["name"], e)
    for e in entries:
        n = e["name"].strip().upper()
        if n not in crypto and normalize(n) == n:
            put(n, e)
    return out


def rank_alpha(entries):
    def cap(e):
        c = e.get("marketCap")
        try:
            c = float(c)
        except (TypeError, ValueError):
            return -1.0
        return c if c >= 0 and c == c and c != float("inf") else -1.0
    indexed = list(enumerate(entries))
    indexed.sort(key=lambda p: (1 if p[1].get("offline") else 0, -cap(p[1]), p[0]))
    return [e for _, e in indexed]


# ---------------------------------------------------------------------------
# CoinGecko choice

def keep_row(row):
    rank = row.get("market_cap_rank")
    return (rank is not None and rank <= KEEP_RANK) or (row.get("total_volume") or 0) >= MIN_VOLUME_USD


def _key(row):
    rank = row.get("market_cap_rank")
    if rank is not None and rank <= TOP_RANK:
        return (0, rank, 0, 0)
    return (1, 0, -(row.get("total_volume") or 0), -(row.get("market_cap") or 0))


def choose(markets):
    """CoinGecko rows → {TICKER: best row} (top-1000 rank first, else highest volume)."""
    best = {}
    for row in markets:
        sym = (row.get("symbol") or "").strip().upper()
        if not PLAIN.match(sym) or not keep_row(row):
            continue
        if sym not in best or _key(row) < _key(best[sym]):
            best[sym] = row
    return best


def is_top(row):
    rank = row.get("market_cap_rank")
    return rank is not None and rank <= TOP_RANK


def okx_bases(spot, swap):
    out = set()
    for row in spot or []:
        b = (row.get("baseCcy") or "").strip().upper()
        if PLAIN.match(b):
            out.add(b)
    for row in swap or []:
        b = (row.get("uly") or row.get("instFamily") or "").split("-")[0].strip().upper()
        if PLAIN.match(b):
            out.add(b)
    return out


class Coins:
    """Ordered result; logo and name are filled independently (first source wins each)."""

    def __init__(self):
        self.items = {}

    def add(self, key, source, url=None, name=None):
        if not key:
            return
        e = self.items.get(key)
        if e is None:
            e = self.items[key] = {"s": source}
        if url and "u" not in e and allowed_image(url):
            e["u"] = url.strip()
            e["s"] = source
        if name and "n" not in e:
            n = clean_name(name)
            if n:
                e["n"] = n
        if "u" not in e and "n" not in e:
            del self.items[key]

    def carry(self, old, source):
        """Previous entries of an unreachable source."""
        for k, e in old.items():
            if e.get("s") == source:
                self.add(k, source, e.get("u"), e.get("n"))


# ---------------------------------------------------------------------------
# Images and pack

def scale_webp(data):
    from PIL import Image
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception:
        return None
    if img.width <= 0 or img.height <= 0:
        return None
    img = img.convert("RGBA")
    longest = max(img.width, img.height)
    if longest > STORED_PX:
        f = STORED_PX / longest
        img = img.resize((max(1, round(img.width * f)), max(1, round(img.height * f))), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, "WEBP", quality=80, method=6)
    return out.getvalue()


# Gold and silver: our own drawn icons (no exchange shows a logo for them)
METALS = {"XAU": ("gold", "Gold"), "GOLD": ("gold", "Gold"), "XAUT": ("gold", "Gold"),
          "XAG": ("silver", "Silver"), "SILVER": ("silver", "Silver"),
          "XPT": ("platinum", "Platinum"), "PLATINUM": ("platinum", "Platinum"),
          "XPD": ("palladium", "Palladium"), "PALLADIUM": ("palladium", "Palladium")}
OWN_ICON_VERSION = 1

METAL_PALETTES = {
    # background top/bottom, ingot top face, front face light/dark, side, edge, highlight
    "gold":   dict(bg1=(255, 236, 170), bg2=(214, 158, 46), top=(255, 226, 120), front1=(245, 190, 60),
                   front2=(196, 136, 22), side=(170, 112, 14), edge=(122, 78, 6), hi=(255, 248, 210)),
    "silver": dict(bg1=(248, 249, 251), bg2=(160, 166, 174), top=(238, 241, 245), front1=(206, 211, 218),
                   front2=(150, 157, 166), side=(126, 133, 142), edge=(88, 94, 102), hi=(255, 255, 255)),
    "platinum": dict(bg1=(236, 243, 248), bg2=(132, 150, 166), top=(226, 236, 244), front1=(190, 205, 218),
                     front2=(124, 142, 158), side=(104, 122, 138), edge=(64, 80, 96), hi=(250, 253, 255)),
    "palladium": dict(bg1=(246, 241, 236), bg2=(150, 140, 132), top=(236, 230, 224), front1=(204, 195, 186),
                      front2=(146, 136, 128), side=(124, 116, 108), edge=(84, 76, 70), hi=(255, 252, 248)),
}

def _lerp(a, b, t): return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def metal_icon(kind, px=STORED_PX):
    """Own drawn icon (a bar on a round coin) for gold and silver; no third-party logo."""
    from PIL import Image, ImageDraw, ImageFilter
    p = METAL_PALETTES[kind]; S = 512
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # round background, vertical gradient
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        gd.line([(0, y), (S, y)], fill=_lerp(p["bg1"], p["bg2"], y / (S - 1)) + (255,))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    # soft shadow under the bar
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([86, 366, 426, 412], fill=(0, 0, 0, 90))
    sh = sh.filter(ImageFilter.GaussianBlur(14))
    img.alpha_composite(sh)
    d = ImageDraw.Draw(img)
    # ingot: trapezoid prism
    top = [(166, 146), (346, 146), (392, 202), (120, 202)]           # top face (perspective)
    front = [(120, 202), (392, 202), (430, 372), (82, 372)]         # front face
    # front gradient
    fr = Image.new("RGBA", (S, S))
    fd = ImageDraw.Draw(fr)
    for y in range(202, 373):
        fd.line([(0, y), (S, y)], fill=_lerp(p["front1"], p["front2"], (y - 202) / 170) + (255,))
    fm = Image.new("L", (S, S), 0); ImageDraw.Draw(fm).polygon(front, fill=255)
    img.paste(fr, (0, 0), fm)
    d = ImageDraw.Draw(img)
    d.polygon(top, fill=p["top"] + (255,))
    # bevel highlight lines
    d.line([(125, 205), (387, 205)], fill=p["hi"] + (255,), width=5)
    d.line([(171, 151), (341, 151)], fill=p["hi"] + (230,), width=4)
    # diagonal sheen on front
    sheen = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).polygon([(176, 202), (232, 202), (196, 372), (140, 372)], fill=p["hi"] + (70,))
    sheen_m = Image.new("L", (S, S), 0); ImageDraw.Draw(sheen_m).polygon(front, fill=255)
    img.paste(Image.alpha_composite(img.copy(), sheen), (0, 0), sheen_m)
    d = ImageDraw.Draw(img)
    # outlines
    d.line(top + [top[0]], fill=p["edge"] + (255,), width=6, joint="curve")
    d.line(front + [front[0]], fill=p["edge"] + (255,), width=6, joint="curve")
    # thin rim around the circle
    d.ellipse([3, 3, S - 4, S - 4], outline=p["edge"] + (140,), width=8)
    return img.resize((px, px), Image.LANCZOS)


def own_icon_webp(url):
    """«own:gold-v1» → WebP bytes of the drawn icon."""
    kind = url[len("own:"):].rsplit("-v", 1)[0]
    img = metal_icon(kind)
    out = io.BytesIO()
    img.save(out, "WEBP", quality=90, method=6)
    return out.getvalue()


def download(url):
    try:
        data = http_get(url, timeout=30)
    except Exception:
        return None
    if not data or len(data) > MAX_IMAGE_BYTES:
        return None
    return scale_webp(data)


def write_pack(path, out_dir, files):
    """All referenced images in one file (format in the module doc); returns its sha1."""
    h = hashlib.sha1()
    with open(path, "wb") as f:
        def w(b):
            f.write(b)
            h.update(b)
        w(PACK_MAGIC)
        for name in sorted(files):
            with open(os.path.join(out_dir, name), "rb") as img:
                data = img.read()
            w(f"{name}\t{len(data)}\n".encode("ascii"))
            w(data)
    return h.hexdigest()


def read_pack(data):
    """Inverse of write_pack (for the self-test)."""
    if not data.startswith(PACK_MAGIC):
        return None
    out, i = {}, len(PACK_MAGIC)
    while i < len(data):
        nl = data.index(b"\n", i)
        name, size = data[i:nl].decode("ascii").split("\t")
        start = nl + 1
        out[name] = data[start:start + int(size)]
        i = start + int(size)
    return out


# ---------------------------------------------------------------------------
# Sources

def fetch_coingecko(key):
    headers = {"x-cg-demo-api-key": key} if key else {}
    pause = 2.5 if key else 15.0
    rows = []
    for page in range(1, MAX_PAGES + 1):
        if page > 1:
            time.sleep(pause)
        data = get_json(CG_MARKETS.format(page=page), headers)
        if not isinstance(data, list):
            raise RuntimeError(f"CoinGecko page {page}: unexpected answer")
        rows.extend(data)
        log(f"  CoinGecko page {page}: {len(data)}")
        if len(data) < PER_PAGE:
            return rows
    raise RuntimeError("CoinGecko: more pages than expected")


def optional(label, fn):
    try:
        return fn()
    except Exception as e:
        log(f"  {label} not reachable ({e}); its previous entries stay")
        return None


def fetch_binance():
    data = get_json(BINANCE_LIST, tries=2, wait=10).get("data")
    if not isinstance(data, list) or not data:
        raise RuntimeError("empty")
    out = []
    for o in data:
        if not isinstance(o, dict):
            continue
        out.append({"name": (o.get("name") or o.get("baseAsset") or "").strip(),
                    "logo": o.get("logo"), "tags": [str(t) for t in (o.get("tags") or [])],
                    "onlyFutures": bool(o.get("onlyFutures")), "fullName": o.get("fullName")})
    return out


def fetch_alpha():
    data = get_json(ALPHA_LIST, tries=2, wait=10).get("data")
    if not isinstance(data, list) or not data:
        raise RuntimeError("empty")
    return rank_alpha([o for o in data if isinstance(o, dict)])


def fetch_okx():
    spot = get_json(OKX_INSTRUMENTS.format(kind="SPOT"), tries=2, wait=10).get("data")
    swap = get_json(OKX_INSTRUMENTS.format(kind="SWAP"), tries=2, wait=10).get("data")
    bases = okx_bases(spot, swap)
    if not bases:
        raise RuntimeError("empty")
    return bases


def fetch_nasdaq():
    out = {}
    for url in NASDAQ_FILES:
        parse_symbol_directory(http_get(url, timeout=60).decode("utf-8", "replace"), out)
    if len(out) < 1000:
        raise RuntimeError(f"only {len(out)} stocks")
    return out


# ---------------------------------------------------------------------------

def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def build(out_dir, key, pack_path=None, today=None):
    today = today or dt.date.today()
    os.makedirs(out_dir, exist_ok=True)
    index_path = os.path.join(out_dir, "index.json")
    state_path = os.path.join(out_dir, "state.json")
    stocks_path = os.path.join(out_dir, "stocks.txt")
    img_dir = os.path.join(out_dir, "img")
    prev = load_json(index_path, {})
    state = load_json(state_path, {})
    misses = dict(state.get("stockMiss", {}))
    # index.json holds only what the apps need (file, name); source and URL live in state.json
    src = state.get("src", {})
    old = {}
    for k, e in prev.get("coins", {}).items():
        o = dict(e)
        if isinstance(src.get(k), list) and len(src[k]) == 2:
            o["s"], o["u"] = src[k][0], src[k][1]
        elif not o.get("s"):
            o["s"] = "cg"
        old[k] = o
    status = {"coingecko": "ok"}

    log("CoinGecko …")
    cg = choose(fetch_coingecko(key))
    crypto = set(cg)
    coins = Coins()
    for sym, row in sorted(cg.items(), key=lambda kv: _key(kv[1])):
        if is_top(row):
            coins.add(sym, "cg", row.get("image"), row.get("name"))

    log("Binance symbol list …")
    binance = optional("Binance symbol list", fetch_binance)
    status["binance"] = "ok" if binance is not None else "unreachable"
    if binance is None:
        coins.carry({k: v for k, v in old.items() if not k.startswith(TRADFI)}, "bn")
    else:
        for e in binance:
            sym = normalize(e["name"])
            if PLAIN.match(sym):
                coins.add(sym, "bn", e["logo"], e["fullName"])

    log("Binance Alpha …")
    alpha = optional("Binance Alpha", fetch_alpha)
    status["binanceAlpha"] = "ok" if alpha is not None else "unreachable"
    if alpha is None:
        coins.carry(old, "alpha")
    else:
        for e in alpha:
            sym = normalize(e.get("symbol"))
            if PLAIN.match(sym):
                coins.add(sym, "alpha", e.get("iconUrl"), e.get("name"))

    for sym, row in sorted(cg.items(), key=lambda kv: _key(kv[1])):
        coins.add(sym, "cg", row.get("image"), row.get("name"))

    log("OKX …")
    okx = optional("OKX", fetch_okx)
    status["okx"] = "ok" if okx is not None else "unreachable"
    if okx is None:
        coins.carry(old, "okx")
    else:
        for sym in sorted(okx):
            coins.add(sym, "okx", OKX_ICON.format(ccy=sym.lower()))

    crypto_count = len(coins.items)
    old_crypto = sum(1 for k in old if not k.startswith(TRADFI))
    if old_crypto and crypto_count < old_crypto // 2:
        raise RuntimeError(f"only {crypto_count} coins (before {old_crypto}) – not published")

    # TradFi: own gold/silver icons first, then the Binance list, then Binance stock images; names from Nasdaq
    for sym, (kind, name) in METALS.items():
        coins.items[TRADFI + sym] = {"s": "own", "u": f"own:{kind}-v{OWN_ICON_VERSION}", "n": name}
    if binance is None:
        coins.carry({k: v for k, v in old.items() if k.startswith(TRADFI)}, "bnt")
    else:
        for sym, e in pick_tradfi(binance, crypto).items():
            coins.add(TRADFI + sym, "bnt", e["logo"], e["fullName"])

    log("Nasdaq symbol directory …")
    stocks = optional("Nasdaq", fetch_nasdaq)
    status["nasdaq"] = "ok" if stocks is not None else "unreachable"
    if stocks is None:
        stocks = {}
        try:
            with open(stocks_path, encoding="utf-8") as f:
                for line in f:
                    k, _, v = line.rstrip("\n").partition("\t")
                    if k and v:
                        stocks[k] = v
        except OSError:
            pass
    else:
        with open(stocks_path, "w", encoding="utf-8") as f:
            f.write("\n".join(f"{k}\t{v}" for k, v in sorted(stocks.items())) + "\n")

    # previous stock hits stay (no new request); new tickers and old misses get probed
    hits = {k[len(TRADFI):] for k, e in old.items() if k.startswith(TRADFI) and e.get("s") == "stk"}
    cutoff = (today - dt.timedelta(days=MISS_RETRY_DAYS)).isoformat()
    probe = sorted(s for s in stocks if PLAIN.match(s) and s not in hits
                   and TRADFI + s not in coins.items and misses.get(s, "") < cutoff)
    log(f"Stock images: {len(hits)} known, probing {len(probe)}")
    prefetched = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        found = dict(zip(probe, pool.map(lambda s: download(STOCK_ICON.format(ticker=s)), probe)))
    by_hash = {}
    for s, webp in found.items():
        if webp:
            by_hash.setdefault(hashlib.sha1(webp).hexdigest(), []).append(s)
    placeholders = {h for h, syms in by_hash.items() if len(syms) > PLACEHOLDER_LIMIT}
    for s, webp in found.items():
        if webp and hashlib.sha1(webp).hexdigest() not in placeholders:
            hits.add(s)
            misses.pop(s, None)
            prefetched[STOCK_ICON.format(ticker=s)] = webp
        else:
            misses[s] = today.isoformat()
    for s in sorted(hits):
        coins.add(TRADFI + s, "stk", STOCK_ICON.format(ticker=s), stocks.get(s) or old.get(TRADFI + s, {}).get("n"))
    for k in list(coins.items):
        if k.startswith(TRADFI) and k[len(TRADFI):] in stocks:
            coins.add(k, coins.items[k]["s"], None, stocks[k[len(TRADFI):]])

    # Images: reuse when the source URL is unchanged and the file exists
    items = coins.items
    os.makedirs(img_dir, exist_ok=True)
    todo = []
    for k, e in items.items():
        p = old.get(k, {})
        if "u" not in e:
            continue
        if p.get("u") == e["u"] and p.get("f") and os.path.exists(os.path.join(out_dir, p["f"])):
            e["f"] = p["f"]
        else:
            todo.append(k)
    log(f"Images: {len(todo)} to download, {sum(1 for e in items.values() if 'f' in e)} reused")

    def get(k):
        u = items[k]["u"]
        if u.startswith("own:"):
            return own_icon_webp(u)
        return prefetched[u] if u in prefetched else download(u)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results = dict(zip(todo, pool.map(get, todo)))
    failed = 0
    for k, webp in results.items():
        if webp is None:
            failed += 1
            p = old.get(k, {})
            if p.get("f") and os.path.exists(os.path.join(out_dir, p["f"])):
                items[k]["f"] = p["f"]          # keep an older logo rather than none
            elif "n" not in items[k]:
                del items[k]                     # nothing to offer (e.g. OKX ticker without icon)
            continue
        name = "img/" + hashlib.sha1(webp).hexdigest()[:16] + ".webp"
        path = os.path.join(out_dir, name)
        if not os.path.exists(path):
            with open(path, "wb") as f:
                f.write(webp)
        items[k]["f"] = name
    log(f"  {failed} images not available")

    used = {e["f"] for e in items.values() if "f" in e}
    removed = 0
    for fn in os.listdir(img_dir):
        if "img/" + fn not in used:
            os.remove(os.path.join(img_dir, fn))
            removed += 1
    log(f"  {removed} unused images removed")

    items = dict(sorted(items.items()))
    result = {k: {f: e[f] for f in ("f", "n") if f in e} for k, e in items.items()}
    sources = {k: [e["s"], e["u"]] for k, e in items.items() if "u" in e}
    pack_sha = prev.get("pack")
    if pack_path:
        pack_sha = write_pack(pack_path, out_dir, used)
        log(f"Pack: {os.path.getsize(pack_path) / 1e6:.1f} MB, {len(used)} images")
    changed = result != prev.get("coins") or pack_sha != prev.get("pack")
    doc = {
        "version": 1,
        "generated": (dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                      if changed or not prev.get("generated") else prev["generated"]),
        "source": ("CoinGecko /coins/markets; Binance symbol and Alpha token lists; OKX instruments; "
                   "Binance stock images; Nasdaq symbol directory"),
        "pack": pack_sha,
        "coins": result,
    }
    if changed or not os.path.exists(index_path):
        with open(index_path, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump({"stockMiss": dict(sorted(misses.items())), "src": sources}, f, separators=(",", ":"))
        f.write("\n")
    # Shown on https://r1adbe.github.io/cryptoChecker/logos/status.json: last successful run
    with open(os.path.join(out_dir, "status.json"), "w", encoding="utf-8") as f:
        json.dump({"lastSuccess": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   "listGenerated": doc["generated"], "entries": len(result), "crypto": crypto_count,
                   "logos": len(used), "sources": status}, f, indent=1)
        f.write("\n")
    log(f"{len(result)} entries ({crypto_count} crypto), {len(used)} logos, {'changed' if changed else 'unchanged'}")
    return changed


# ---------------------------------------------------------------------------

def selftest():
    rows = [
        {"symbol": "btc", "name": "Bitcoin", "market_cap_rank": 1, "total_volume": 9e10, "market_cap": 2e12},
        {"symbol": "aia", "name": "Clone", "market_cap_rank": 2400, "total_volume": 150_000, "market_cap": 9e6},
        {"symbol": "aia", "name": "DeAgentAI", "market_cap_rank": 1531, "total_volume": 2e7, "market_cap": 5e7},
        {"symbol": "agt", "name": "Alaya AI", "market_cap_rank": None, "total_volume": 3e6, "market_cap": 0},
        {"symbol": "dead", "name": "Dead", "market_cap_rank": None, "total_volume": 10, "market_cap": 0},
        {"symbol": "uni", "name": "Uniswap", "market_cap_rank": 30, "total_volume": 1e8, "market_cap": 5e9},
        {"symbol": "uni", "name": "Busy clone", "market_cap_rank": None, "total_volume": 9e9, "market_cap": 0},
        {"symbol": "a-b", "name": "Bad", "market_cap_rank": 5, "total_volume": 1e9},
    ]
    c = choose(rows)
    assert sorted(c) == ["AGT", "AIA", "BTC", "UNI"], sorted(c)
    assert c["AIA"]["name"] == "DeAgentAI" and c["UNI"]["name"] == "Uniswap"
    assert okx_bases([{"baseCcy": "btc"}, {"baseCcy": "x y"}], [{"uly": "AIO-USDT"}, {"instFamily": "ETH-USD"}]) \
        == {"BTC", "AIO", "ETH"}
    assert normalize("1000PEPE") == "PEPE" and normalize("LUNA2") == "LUNA" and normalize("1INCH") == "1INCH"
    assert clean_name("NVIDIA (bStocks)") == "NVIDIA" and clean_name("  a   b ") == "a b"
    assert clean_name("x" * 50) == "x" * 39 + "…"
    assert clean_stock_name("Caterpillar, Inc. Common Stock") == "Caterpillar, Inc."
    assert clean_stock_name("Alphabet Inc. - Class A Common Stock") == "Alphabet Inc."
    d = parse_symbol_directory("Symbol|Security Name|Test Issue\nCAT|Caterpillar, Inc. Common Stock|N\n"
                               "ZZZT|Test|Y\nFile Creation Time: 1|\n")
    assert d == {"CAT": "Caterpillar, Inc."}, d
    bn = [{"name": "NVDAB", "logo": "https://bin.bnbstatic.com/n.png", "tags": ["bStocks"], "onlyFutures": False,
           "fullName": "NVIDIA (bStocks)"},
          {"name": "BTC", "logo": "https://bin.bnbstatic.com/b.png", "tags": [], "onlyFutures": False, "fullName": "Bitcoin"},
          {"name": "XAU", "logo": "https://bin.bnbstatic.com/x.png", "tags": [], "onlyFutures": True, "fullName": "Gold"},
          {"name": "1000CAT", "logo": "https://bin.bnbstatic.com/c.png", "tags": [], "onlyFutures": False, "fullName": "Cat"}]
    assert sorted(pick_tradfi(bn, {"BTC"})) == ["NVDA", "NVDAB", "XAU"]  # like the apps
    assert [e["symbol"] for e in rank_alpha([{"symbol": "A", "marketCap": "5", "offline": True},
                                             {"symbol": "B", "marketCap": None},
                                             {"symbol": "C", "marketCap": "9"}])] == ["C", "B", "A"]
    co = Coins()
    co.add("X", "cg", "https://x/missing_large.png", "Name")
    co.add("X", "bn", "https://bin.bnbstatic.com/x.png", "Other")
    assert co.items["X"] == {"s": "bn", "n": "Name", "u": "https://bin.bnbstatic.com/x.png"}, co.items
    co.add("Y", "cg", "http://insecure/y.png", None)
    assert "Y" not in co.items
    try:
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGBA", (250, 200), (255, 0, 0, 128)).save(buf, "PNG")
        w = scale_webp(buf.getvalue())
        im = Image.open(io.BytesIO(w))
        assert im.format == "WEBP" and max(im.size) == STORED_PX and im.mode == "RGBA", (im.format, im.size, im.mode)
        assert scale_webp(b"not an image") is None
        for kind in METAL_PALETTES:
            icon = Image.open(io.BytesIO(own_icon_webp(f"own:{kind}-v1")))
            assert icon.size == (STORED_PX, STORED_PX) and icon.mode == "RGBA", (kind, icon.size, icon.mode)
    except ImportError:
        log("Pillow missing: image test skipped")
    log("selftest ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", default="docs/logos")
    ap.add_argument("--pack", help="write all logos into this file (release asset logos.pack)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        selftest()
        return 0
    os.makedirs(args.output, exist_ok=True)
    try:
        build(args.output, os.environ.get("COINGECKO_API_KEY", "").strip(), args.pack)
    except Exception as e:
        log(f"::error::Coin logos not updated: {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
