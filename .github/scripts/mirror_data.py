#!/usr/bin/env python3
"""Mirrors public market data that is the same for every user of the Crypto Checker apps.

Instead of every phone asking alternative.me and Coin Metrics, this script
fetches the data once an hour and the workflow uploads it as assets of the release «data»:
  https://github.com/r1adBE/cryptoChecker/releases/download/data/<file>

Besides plain copies it writes altseason.txt: the altcoin season already computed from daily
candles (data-api.binance.vision, else Binance.US) with the apps' rule, so a phone needs one
request instead of about 21.

coin_meta.json (plain JSON, not wrapped): the coin symbols currently traded on Binance Spot, built
ONLY from the documented public REST endpoint GET /api/v3/exchangeInfo (data-api.binance.vision,
else api.binance.com) — no images, no CoinGecko data, nothing from Binance's internal website API
(www.binance.com/bapi). That endpoint has no coin names, logo URLs or Alpha tokens, so those fields
are left out; the apps treat them as optional (CoinMeta.kt / CoinMeta.swift: missing fullName/logo/
tags/onlyFutures are skipped, an empty "alpha" array is ignored and the apps keep their own list).
The apps read the file only when the direct Binance lists fail.
  {"v":1,"generated":<ms>,"binance":[{"name"}…],"alpha":[]}
binance_entries / alpha_entries still parse the website lists for android/tools/gen_coin_assets.py
(--binance-file / --alpha-file, local files only).

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

Signed manifest (--manifest DIR, run in the publish job after artifact_check.py):
  manifest.json      {"created":<ms>,"files":{"<file>":"<sha-256 hex of the file bytes>"…},"v":1}
                     (compact, sorted keys, ASCII; the signature covers exactly these bytes)
  manifest.json.sig  Base64 of the DER ECDSA P-256/SHA-256 signature, made with the secret
                     MIRROR_SIGNING_KEY (PEM, PKCS#8) via the openssl command line tool
The apps (MirrorManifest.kt / MirrorManifest.swift) check the signature with the built-in public key
PUBLIC_KEY, the age of "created" (at most 7 days) and the SHA-256 of every mirror file; otherwise they
ignore the mirror and ask the sources directly. Files not renewed in this run keep their hash from the
previous manifest, but only if that one is validly signed (--previous DIR). Without the secret the
manifest is published unsigned with a warning (the apps then use the sources directly).
Python 3.9+ standard library only (plus the openssl command line tool for the manifest).
"""
import argparse
import base64
import contextlib
import datetime as dt
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

USER_AGENT = "cryptoChecker-data-mirror (+https://github.com/r1adBE/cryptoChecker)"
MAGIC = "CCDM1"
COIN_METRICS = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
# Documented public market-data endpoint (Binance Spot API). data-api.binance.vision first: it serves
# public market data only and answers from GitHub's US runners, where api.binance.com may be blocked.
EXCHANGE_INFO_URLS = ["https://data-api.binance.vision/api/v3/exchangeInfo",
                      "https://api.binance.com/api/v3/exchangeInfo"]
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


def exchange_info_entries(body):
    """GET /api/v3/exchangeInfo -> [{name}]: base assets of the TRADING spot symbols, first occurrence
    wins, upper case like the website list. No names or logos (the endpoint has none)."""
    symbols = json.loads(body).get("symbols")
    if not isinstance(symbols, list):
        raise RuntimeError("exchangeInfo: no symbols array")
    out, seen = [], set()
    for o in symbols:
        if not isinstance(o, dict) or o.get("status") != "TRADING":
            continue
        base = _text(o.get("baseAsset"))
        if not base or base.upper() in seen:
            continue
        seen.add(base.upper())
        out.append({"name": base.upper()})
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
    """Spot symbols from exchangeInfo (first URL that answers with enough entries). No Alpha list:
    there is no documented public endpoint for it, so "alpha" stays empty and the apps keep theirs."""
    fetcher = fetcher or (lambda url: fetch(url, {}))
    errors = []
    for url in EXCHANGE_INFO_URLS:
        try:
            binance = exchange_info_entries(fetcher(url))
            if len(binance) < 50:
                raise RuntimeError(f"only {len(binance)} symbols")
            return coin_meta(binance, [], int(time.time() * 1000)), []
        except Exception as e:
            errors.append(f"{url.split('/')[2]}: {e}")
    raise RuntimeError("; ".join(errors))


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
    info = json.dumps({"symbols": [
        {"symbol": "BTCUSDT", "baseAsset": "BTC", "status": "TRADING"},
        {"symbol": "BTCEUR", "baseAsset": "BTC", "status": "TRADING"},
        {"symbol": "OLDUSDT", "baseAsset": "OLD", "status": "BREAK"},
        {"symbol": "ethusdt", "baseAsset": "eth", "status": "TRADING"}, "broken", {"status": "TRADING"}]})
    assert exchange_info_entries(info) == [{"name": "BTC"}, {"name": "ETH"}]
    responses = {EXCHANGE_INFO_URLS[0]: info, EXCHANGE_INFO_URLS[1]: "nonsense"}

    def fake(url):
        if url not in responses:
            raise RuntimeError("unexpected " + url)
        return responses[url]
    try:
        build_coin_meta(fake)
        raise AssertionError("too few entries must fail")
    except RuntimeError:
        pass
    many = json.dumps({"symbols": [{"baseAsset": f"C{i}", "status": "TRADING"} for i in range(60)]})
    responses[EXCHANGE_INFO_URLS[1]] = many
    built, errors = build_coin_meta(fake)
    assert len(built["binance"]) == 60 and built["alpha"] == [] and errors == []
    assert set(built["binance"][0]) == {"name"}
    # Only the documented API, never the internal website API or CoinGecko
    assert all("/api/v3/exchangeInfo" in u and "bapi" not in u and "coingecko" not in u for u in EXCHANGE_INFO_URLS)


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


# ---------------- Signed manifest (same rules as MirrorManifest.kt / MirrorManifest.swift)

MANIFEST = "manifest.json"
SIGNATURE = MANIFEST + ".sig"
MANIFEST_VERSION = 1
# Public key of MIRROR_SIGNING_KEY (ECDSA P-256, SubjectPublicKeyInfo DER, Base64), built into the apps
PUBLIC_KEY = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnyWpdnpqDJUM1yHZQxQKPAcaXyGpbk8HvfNVmnR1"
              "MAuShndvEGub711SBOXLJ0aottnrndttNuskZV2BdiG/Pw==")
# Reserve key (F-29): its private part is kept offline by the owner; the apps accept both keys. If the signing key is
# lost, the secret is switched to the reserve key without an app update; if it leaked, the leaked key must also be
# removed from the apps with the next update (until then it stays valid) - DEVELOPMENT.md, "Reserve key"
RESERVE_PUBLIC_KEY = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2QmggFHrIqdCNnz8a03ZyaQoet4YwwLdBwy9MP4k"
                      "lEv3ChD2aFAhLUyH2gs3vk7c4TUuihar2eMy/wx6He+Ltw==")
PUBLIC_KEYS = (PUBLIC_KEY, RESERVE_PUBLIC_KEY)
MANIFEST_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000
MANIFEST_MAX_BYTES = 64 * 1024
# Files whose hash may be carried over from the previous signed manifest (old asset stays on the release)
CARRIED = ("fng.txt", "onchain.txt", "btcprice.txt", "altseason.txt", "coin_meta.json")
FILE_NAME = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
SHA256_HEX = re.compile(r"[0-9a-f]{64}\Z")


def manifest_bytes(files, created_millis):
    doc = {"v": MANIFEST_VERSION, "created": created_millis, "files": dict(sorted(files.items()))}
    return json.dumps(doc, separators=(",", ":"), sort_keys=True).encode("ascii")


def parse_manifest(data):
    """{"created", "files"} or None - same checks as the apps (after the signature)."""
    try:
        doc = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(doc, dict) or doc.get("v") != MANIFEST_VERSION or isinstance(doc.get("v"), bool):
        return None
    created, files = doc.get("created"), doc.get("files")
    if not isinstance(created, int) or isinstance(created, bool) or created <= 0 or not isinstance(files, dict):
        return None
    if not all(isinstance(k, str) and FILE_NAME.match(k) and isinstance(v, str) and SHA256_HEX.match(v)
               for k, v in files.items()):
        return None
    return {"created": created, "files": files}


def sha256_file(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def public_pem(public_b64):
    body = "".join(public_b64.split())
    return "-----BEGIN PUBLIC KEY-----\n" + "\n".join(body[i:i + 64] for i in range(0, len(body), 64)) + \
        "\n-----END PUBLIC KEY-----\n"


def _private_file(directory, name, data):
    path = os.path.join(directory, name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    return path


def openssl_sign(data, key_pem):
    """DER ECDSA signature (SHA-256) of data; raises on a bad key. The key only lives in a private temp dir."""
    with tempfile.TemporaryDirectory() as d:
        key = _private_file(d, "key.pem", key_pem.strip().replace("\r\n", "\n").encode("ascii") + b"\n")
        msg = _private_file(d, "msg.bin", data)
        out = os.path.join(d, "sig.der")
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", key, "-out", out, msg],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(out, "rb") as f:
            return f.read()


def openssl_verify(data, signature_der, public_b64):
    """True if the signature matches public_b64 (one Base64 key, or a tuple/list of keys: any of them)."""
    if isinstance(public_b64, (tuple, list)):
        return any(openssl_verify(data, signature_der, key) for key in public_b64)
    with tempfile.TemporaryDirectory() as d:
        pub = _private_file(d, "pub.pem", public_pem(public_b64).encode("ascii"))
        msg = _private_file(d, "msg.bin", data)
        sig = _private_file(d, "sig.der", signature_der)
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", sig, msg],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return r.returncode == 0


def previous_hashes(previous_dir, public_b64, now_millis):
    """Hashes of the previous manifest, only if it is validly signed and not too old; else {}."""
    if not previous_dir:
        return {}
    try:
        with open(os.path.join(previous_dir, MANIFEST), "rb") as f:
            data = f.read(MANIFEST_MAX_BYTES + 1)
        with open(os.path.join(previous_dir, SIGNATURE), "rb") as f:
            sig = base64.b64decode(f.read(4096).strip(), validate=True)
    except (OSError, ValueError):
        return {}
    if len(data) > MANIFEST_MAX_BYTES or not openssl_verify(data, sig, public_b64):
        return {}
    doc = parse_manifest(data)
    if doc is None or not 0 <= now_millis - doc["created"] < MANIFEST_MAX_AGE_MILLIS:
        return {}
    return doc["files"]


def build_manifest(data_dir, out_dir, key_pem, public_b64=PUBLIC_KEYS, previous_dir=None, now_millis=None):
    """Writes manifest.json (and manifest.json.sig if the key works) to out_dir; returns "signed"/"unsigned"."""
    now_millis = now_millis if now_millis is not None else int(time.time() * 1000)
    files = {name: sha256_file(os.path.join(data_dir, name)) for name in sorted(os.listdir(data_dir))
             if name not in (MANIFEST, SIGNATURE) and os.path.isfile(os.path.join(data_dir, name))}
    previous = previous_hashes(previous_dir, public_b64, now_millis)
    for name in CARRIED:
        if name not in files and name in previous:
            files[name] = previous[name]
            print(f"{name}: not renewed, hash carried over from the previous manifest")
    data = manifest_bytes(files, now_millis)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, MANIFEST), "wb") as f:
        f.write(data)
    if not key_pem or not key_pem.strip():
        print("::warning title=Mirror signature::MIRROR_SIGNING_KEY is not set: manifest.json published without "
              "signature, the apps ignore the mirror and ask the sources directly")
        return "unsigned"
    try:
        sig = openssl_sign(data, key_pem)
    except (OSError, subprocess.CalledProcessError, UnicodeEncodeError):
        print("::error title=Mirror signature::MIRROR_SIGNING_KEY could not be used (PEM, PKCS#8, EC P-256?): "
              "manifest.json published without signature")
        return "unsigned"
    if not openssl_verify(data, sig, public_b64):
        print("::error title=Mirror signature::MIRROR_SIGNING_KEY does not match the public key in the apps: "
              "manifest.json published without signature")
        return "unsigned"
    with open(os.path.join(out_dir, SIGNATURE), "wb") as f:
        f.write(base64.b64encode(sig))
    print(f"{MANIFEST}: {len(files)} files, signed")
    return "signed"


def selftest_manifest():
    """Manifest, signature and carry-over with a throw-away key (needs the openssl command line tool). Output
    (including the annotations of the expected failures) is swallowed, so the run shows no false warnings."""
    with contextlib.redirect_stdout(io.StringIO()):
        _selftest_manifest()


def _selftest_manifest():
    with tempfile.TemporaryDirectory() as d:
        def run(*args):
            return subprocess.run(["openssl", *args], check=True, capture_output=True).stdout

        run("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", os.path.join(d, "k.pem"))
        run("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", os.path.join(d, "o.pem"))
        key = open(os.path.join(d, "k.pem"), encoding="ascii").read()
        other = open(os.path.join(d, "o.pem"), encoding="ascii").read()
        assert "BEGIN PRIVATE KEY" in key  # PKCS#8 like the secret
        pub = base64.b64encode(run("pkey", "-in", os.path.join(d, "k.pem"), "-pubout", "-outform", "DER")).decode()
        data, out, out2 = (os.path.join(d, n) for n in ("data", "out", "out2"))
        os.makedirs(data)
        for name, text in (("fng.txt", 'CCDM1 5\n{"data":[1]}'), ("coin_meta.json", '{"binance":[{"name":"Ä"}]}'),
                           ("status.json", "{}\n")):
            with open(os.path.join(data, name), "w", encoding="utf-8") as f:
                f.write(text)
        now = 1_800_000_000_000
        assert build_manifest(data, out, key, pub, None, now) == "signed"
        raw = open(os.path.join(out, MANIFEST), "rb").read()
        doc = parse_manifest(raw)
        assert raw == manifest_bytes(doc["files"], now) and raw.startswith(b'{"created":1800000000000,"files":{')
        assert doc["files"]["coin_meta.json"] == hashlib.sha256('{"binance":[{"name":"Ä"}]}'.encode()).hexdigest()
        assert sorted(doc["files"]) == ["coin_meta.json", "fng.txt", "status.json"]
        sig = base64.b64decode(open(os.path.join(out, SIGNATURE), "rb").read(), validate=True)
        assert openssl_verify(raw, sig, pub) and not openssl_verify(raw + b" ", sig, pub)
        # Not renewed: hash from the previous signed manifest; tampered or too old: nothing carried
        os.remove(os.path.join(data, "fng.txt"))
        assert build_manifest(data, out2, key, pub, out, now + 3_600_000) == "signed"
        assert parse_manifest(open(os.path.join(out2, MANIFEST), "rb").read())["files"]["fng.txt"] == doc["files"]["fng.txt"]
        assert previous_hashes(out, pub, now + MANIFEST_MAX_AGE_MILLIS) == {}
        with open(os.path.join(out, MANIFEST), "ab") as f:
            f.write(b" ")
        assert previous_hashes(out, pub, now) == {}
        # Without key or with a key that does not match the public key: manifest unsigned, no .sig
        for k in ("", other, "not a key"):
            out3 = os.path.join(d, "out3")
            assert build_manifest(data, out3, k, pub, None, now) == "unsigned"
            assert os.path.exists(os.path.join(out3, MANIFEST)) and not os.path.exists(os.path.join(out3, SIGNATURE))
            os.remove(os.path.join(out3, MANIFEST))
        # Key list (active + reserve, F-29): signed if the secret matches any key, unsigned if it matches none
        other_der = run("pkey", "-in", os.path.join(d, "o.pem"), "-pubout", "-outform", "DER")
        other_pub = base64.b64encode(other_der).decode()
        out4 = os.path.join(d, "out4")
        assert build_manifest(data, out4, other, (pub, other_pub), None, now) == "signed"
        sig4 = base64.b64decode(open(os.path.join(out4, SIGNATURE), "rb").read(), validate=True)
        raw4 = open(os.path.join(out4, MANIFEST), "rb").read()
        assert openssl_verify(raw4, sig4, (pub, other_pub)) and not openssl_verify(raw4, sig4, (pub,))
        out5 = os.path.join(d, "out5")
        assert build_manifest(data, out5, other, (pub,), None, now) == "unsigned"
    assert parse_manifest(b'{"v":true,"created":1,"files":{}}') is None
    assert parse_manifest(b'{"v":1,"created":1,"files":{"a/b":"' + b"0" * 64 + b'"}}') is None
    assert parse_manifest(b'{"v":1,"created":1.5,"files":{}}') is None
    # The apps' keys (parity fixture productionPublicKey / productionReservePublicKey) are exactly these: a typo in
    # the reserve key would otherwise only show in an emergency
    fixture = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "testdata", "parity",
                           "mirror_signature.json")
    if os.path.isfile(fixture):
        with open(fixture, encoding="utf-8") as f:
            keys = json.load(f)
        assert (keys["productionPublicKey"], keys["productionReservePublicKey"]) == PUBLIC_KEYS, "keys differ from the apps"
    # SubjectPublicKeyInfo of a P-256 key (91 bytes); active and reserve key differ
    assert all(base64.b64decode(k)[:2] == b"\x30\x59" for k in PUBLIC_KEYS) and len(set(PUBLIC_KEYS)) == 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--manifest", metavar="DIR", help="write manifest.json(.sig) for the files in --output to DIR")
    ap.add_argument("--previous", metavar="DIR", help="previous manifest.json(.sig) for not renewed files")
    args = ap.parse_args()
    if args.manifest:
        build_manifest(args.output, args.manifest, os.environ.get("MIRROR_SIGNING_KEY", ""), previous_dir=args.previous)
        return 0
    if args.selftest:
        assert wrap('{"a":1}', 5) == 'CCDM1 5\n{"a":1}'
        assert onchain_url(dt.date(2026, 10, 9)).endswith("&start_time=2025-08-05&page_size=1000")
        assert change90([1.0] * 90 + [2.0]) == 1.0 and change90([1.0] * 90) is None
        assert not any("coingecko" in url for _, url, _ in sources(dt.date(2026, 10, 9)))
        selftest_coin_meta()
        selftest_manifest()
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
