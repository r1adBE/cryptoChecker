#!/usr/bin/env python3
"""Referenz-Fuzz für die xpub-Ableitung (Bip32.kt / Bip32.swift) und Zufallsfälle für Argon2id.

Erzeugt deterministisch (fester Seed) zwei gemeinsame Testdateien und legt sie byte-gleich in beide Projekte:

  android/testdata/parity/bip32_fuzz.json   ->  ios/Tests/Parity/bip32_fuzz.json
  android/testdata/parity/argon2_random.json ->  ios/Tests/Parity/argon2_random.json

bip32_fuzz.json
  - "cases": zufällige erweiterte Schlüssel (xpub/ypub/zpub, Tiefe, Fingerabdruck, Kettencode zufällig), je ein
    Blatt Kette/Index (Kette abwechselnd 0/1, Index klein, mittel, beliebig bis 2^31 - 1) mit komprimiertem
    Schlüssel, serialisiertem Blattschlüssel und den Adressen P2PKH, P2SH-P2WPKH, P2WPKH, P2TR (BIP86-Tweak).
  - "pointOps": k·G + Q mit Randfällen (k = 0, 1, n - 1, n - 2, n, 2^256 - 1, Ergebnis unendlich, Verdoppeln,
    beide y-Paritäten, x nahe 0 und nahe p).
  - "keyChecks": xpubs mit gebastelten Schlüsseln (x >= p, nicht auf der Kurve, falsches Präfix, beide Paritäten).
  - "indexLimits": grösster nicht gehärteter Index gültig, gehärtete Indizes ungültig.

Unabhängige Referenz: eigene affine Kurvenrechnung in reinem Python, dazu für jeden Schlüssel mit bekanntem
privaten Skalar ein Gegencheck mit `cryptography` (OpenSSL): Kindschlüssel = (IL + d)·G, Taproot-Ausgabe =
(d_gerade + t)·G. Vor dem Erzeugen prüft das Skript sich gegen die offiziellen Vektoren (BIP32 Vektor 1/2,
BIP84, BIP49, BIP86) und bricht bei einer Abweichung ab.

argon2_random.json: 20 Fälle mit zufälligen Parametern (m <= 1 MiB, damit die Tests schnell bleiben), gerechnet mit
`cryptography` (Argon2id, RFC 9106).

Aufruf (aus beliebigem Ordner): python3 android/tools/gen_bip32_vectors.py
Benötigt: Python 3.9+, cryptography >= 44 (Argon2id).
"""
import hashlib
import hmac
import json
import os
import random
import sys

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
REPO = os.path.dirname(ANDROID)
TARGETS = [os.path.join(ANDROID, "testdata", "parity"), os.path.join(REPO, "ios", "Tests", "Parity")]

SEED = 0xB1932
CASES = 1000
ARGON2_CASES = 20

# ---------------- secp256k1 (affin, reines Python) ----------------

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0]:
        if (a[1] + b[1]) % P == 0:
            return None
        lam = 3 * a[0] * a[0] * pow(2 * a[1], -1, P) % P
    else:
        lam = (b[1] - a[1]) * pow(b[0] - a[0], -1, P) % P
    x = (lam * lam - a[0] - b[0]) % P
    return x, (lam * (a[0] - x) - a[1]) % P


def point_mul(k, pt):
    result, addend = None, pt
    while k:
        if k & 1:
            result = point_add(result, addend)
        addend = point_add(addend, addend)
        k >>= 1
    return result


def lift_x(x, odd):
    """Punkt zu x mit gewünschter y-Parität; nicht auf der Kurve = None."""
    if x >= P:
        return None
    rhs = (pow(x, 3, P) + 7) % P
    y = pow(rhs, (P + 1) // 4, P)
    if y * y % P != rhs:
        return None
    if (y & 1) != odd:
        y = P - y
    return x, y


def encode(pt):
    return bytes([3 if pt[1] & 1 else 2]) + pt[0].to_bytes(32, "big")


def decode(b):
    if len(b) != 33 or b[0] not in (2, 3):
        return None
    return lift_x(int.from_bytes(b[1:], "big"), b[0] == 3)


def ossl_pub(d):
    """Komprimierter öffentlicher Schlüssel d·G mit OpenSSL (unabhängiger Gegencheck)."""
    key = ec.derive_private_key(d % N, ec.SECP256K1())
    return key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.CompressedPoint)


# ---------------- Hashes, Kodierungen ----------------

def sha256(b):
    return hashlib.sha256(b).digest()


def hash160(b):
    return hashlib.new("ripemd160", sha256(b)).digest()


def tagged_hash(tag, data):
    t = sha256(tag.encode())
    return sha256(t + t + data)


B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58check(payload):
    data = payload + sha256(sha256(payload))[:4]
    n = int.from_bytes(data, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = B58[r] + out
    return "1" * (len(data) - len(data.lstrip(b"\0"))) + out


def b58decode_check(s):
    n = 0
    for c in s:
        n = n * 58 + B58.index(c)
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    raw = b"\0" * (len(s) - len(s.lstrip("1"))) + raw
    assert sha256(sha256(raw[:-4]))[:4] == raw[-4:]
    return raw[:-4]


# Bech32/Bech32m nach der Referenz von BIP173/BIP350
CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32_polymod(values):
    gen = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if ((top >> i) & 1) else 0
    return chk


def convertbits(data, frombits, tobits):
    acc, bits, ret, maxv = 0, 0, [], (1 << tobits) - 1
    for value in data:
        acc = (acc << frombits) | value
        bits += frombits
        while bits >= tobits:
            bits -= tobits
            ret.append((acc >> bits) & maxv)
    if bits:
        ret.append((acc << (tobits - bits)) & maxv)
    return ret


def segwit_address(hrp, version, program):
    data = [version] + convertbits(program, 8, 5)
    const = 1 if version == 0 else 0x2BC830A3
    values = [ord(x) >> 5 for x in hrp] + [0] + [ord(x) & 31 for x in hrp] + data
    polymod = bech32_polymod(values + [0] * 6) ^ const
    checksum = [(polymod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(CHARSET[d] for d in data + checksum)


# ---------------- BIP32 (nur öffentliche Ableitung) ----------------

XPUB, YPUB, ZPUB = 0x0488B21E, 0x049D7CB2, 0x04B24746


def serialize(version, depth, fp, child, chain_code, pub):
    return b58check(version.to_bytes(4, "big") + bytes([depth]) + fp + child.to_bytes(4, "big") + chain_code + pub)


def parse(xpub):
    raw = b58decode_check(xpub)
    return dict(version=int.from_bytes(raw[0:4], "big"), depth=raw[4], fp=raw[5:9],
                child=int.from_bytes(raw[9:13], "big"), chain=raw[13:45], pub=raw[45:78])


def ckd_pub(key, index):
    """Liefert (Kindschlüssel, IL) oder None (IL >= n oder unendlich)."""
    assert 0 <= index < 2 ** 31
    i = hmac.new(key["chain"], key["pub"] + index.to_bytes(4, "big"), hashlib.sha512).digest()
    il = int.from_bytes(i[:32], "big")
    if il >= N:
        return None
    pt = point_add(point_mul(il, G), decode(key["pub"]))
    if pt is None:
        return None
    child = dict(version=key["version"], depth=(key["depth"] + 1) & 0xFF, fp=hash160(key["pub"])[:4],
                 child=index, chain=i[32:], pub=encode(pt))
    return child, il


def ser(key):
    return serialize(key["version"], key["depth"], key["fp"], key["child"], key["chain"], key["pub"])


def taproot_output(pub):
    x = pub[1:]
    p = lift_x(int.from_bytes(x, "big"), False)
    t = int.from_bytes(tagged_hash("TapTweak", x), "big")
    assert t < N
    return point_add(point_mul(t, G), p)[0].to_bytes(32, "big"), t


def addresses(pub):
    h = hash160(pub)
    return [
        b58check(b"\x00" + h),
        b58check(b"\x05" + hash160(b"\x00\x14" + h)),
        segwit_address("bc", 0, h),
        segwit_address("bc", 1, taproot_output(pub)[0]),
    ]


# ---------------- Selbstprüfung gegen offizielle Vektoren ----------------

def self_check():
    # BIP32 Vektor 1: m/0H -> /1, m/0H/1/2H -> /2 -> /1000000000; Vektor 2: m -> /0, m/0/2147483647H -> /1
    chains = [
        ("xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5",
         2, "xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV"),
        ("xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV",
         1000000000,
         "xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy"),
        ("xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB",
         0, "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH"),
        ("xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw",
         1, "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ"),
        ("xpub6ASAVgeehLbnwdqV6UKMHVzgqAG8Gr6riv3Fxxpj8ksbH9ebxaEyBLZ85ySDhKiLDBrQSARLq1uNRts8RuJiHjaDMBU4Zn9h8LZNnBC5y4a",
         1, "xpub6DF8uhdarytz3FWdA8TvFSvvAh8dP3283MY7p2V4SeE2wyWmG5mg5EwVvmdMVCQcoNJxGoWaU9DCWh89LojfZ537wTfunKau47EL2dhHKon"),
    ]
    for parent, index, child in chains:
        assert ser(ckd_pub(parse(parent), index)[0]) == child, (parent, index)
    # BIP84 m/84'/0'/0'/0/0, BIP49 (Mainnet umkodiert) 0/0, BIP44-xpub 0/0
    for xpub, idx, want in [
        ("zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
         2, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"),
        ("ypub6XaXJjyZ5oda7d4Etzuc5q3fs9YvEocqUa1pBdK67x4v3Cknh6oJHqEfbZtF6QR1HEdTWnec5mdrF1EYmhg4iTxm1nGVww7dzszLLVUuXzr",
         1, "36NvZTcMsMowbt78wPzJaHHWaNiyR73Y4g"),
        ("xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
         0, "12CL4K2eVqj7hQTix7dM7CVHCkpP17Pry3"),
    ]:
        leaf = ckd_pub(ckd_pub(parse(xpub), 0)[0], 0)[0]
        assert addresses(leaf["pub"])[idx] == want, (xpub, want)
    # BIP86 m/86'/0'/0'/0/0 und 0/1, 1/0
    bip86 = parse("xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ")
    for chain, index, want in [(0, 0, "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"),
                               (0, 1, "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"),
                               (1, 0, "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7")]:
        leaf = ckd_pub(ckd_pub(bip86, chain)[0], index)[0]
        assert addresses(leaf["pub"])[3] == want, want
    # BIP173/BIP350
    assert segwit_address("bc", 0, bytes.fromhex("751e76e8199196d454941c45d1b3a323f1433bd6")) == \
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    assert segwit_address("bc", 1, bytes.fromhex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")) == \
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
    # Kurve gegen OpenSSL
    rng = random.Random(1)
    for _ in range(50):
        d = rng.randrange(1, N)
        assert encode(point_mul(d, G)) == ossl_pub(d)


# ---------------- Fälle ----------------

def rand_index(rng, i):
    r = i % 10
    if r < 4:
        return rng.randrange(0, 20)
    if r < 7:
        return rng.randrange(20, 10000)
    if r < 9:
        return rng.randrange(0, 2 ** 31)
    return 2 ** 31 - 1 - rng.randrange(0, 4)


def make_cases(rng):
    cases = []
    versions = [XPUB, YPUB, ZPUB]
    names = {XPUB: "P2PKH", YPUB: "P2SH_P2WPKH", ZPUB: "P2WPKH"}
    for i in range(CASES):
        d = rng.randrange(1, N)
        version = versions[i % 3]
        root = dict(version=version, depth=rng.randrange(0, 250), fp=rng.randbytes(4),
                    child=rng.randrange(0, 2 ** 32), chain=rng.randbytes(32), pub=encode(point_mul(d, G)))
        assert root["pub"] == ossl_pub(d)
        chain = i % 2
        index = rand_index(rng, i)
        step = ckd_pub(root, chain)
        assert step is not None
        chain_key, il1 = step
        assert chain_key["pub"] == ossl_pub(d + il1)
        step = ckd_pub(chain_key, index)
        assert step is not None
        leaf, il2 = step
        d_leaf = (d + il1 + il2) % N
        assert leaf["pub"] == ossl_pub(d_leaf)
        out, t = taproot_output(leaf["pub"])
        d_even = d_leaf if leaf["pub"][0] == 2 else N - d_leaf
        assert ossl_pub(d_even + t)[1:] == out
        cases.append({
            "xpub": ser(root),
            "script": names[version],
            "chain": chain,
            "index": index,
            "pubkey": leaf["pub"].hex(),
            "leaf": ser(leaf),
            "addresses": addresses(leaf["pub"]),
        })
    return cases


def hx(k):
    return k.to_bytes(32, "big").hex()


def make_point_ops(rng):
    ops = []

    def add(note, k, q, q_scalar=None):
        res = point_add(point_mul(k, G), q)
        if q_scalar is not None:
            s = (k + q_scalar) % N
            assert (res is None) == (s == 0)
            if res is not None:
                assert encode(res) == ossl_pub(s), note
        ops.append({"note": note, "k": hx(k), "q": encode(q).hex(), "result": encode(res).hex() if res else None})

    g2 = point_mul(2, G)
    neg = lambda pt: (pt[0], P - pt[1])  # noqa: E731
    add("k = 0: Ergebnis Q", 0, g2, 2)
    add("k = 1, Q = G: Verdoppeln", 1, G, 1)
    add("k = 2, Q = G", 2, G, 1)
    add("k = n - 1, Q = G: unendlich", N - 1, G, 1)
    add("k = n - 1, Q = 2G: G", N - 1, g2, 2)
    add("k = n - 2, Q = G: -G (andere Paritaet)", N - 2, G, 1)
    add("k = 1, Q = -G: unendlich", 1, neg(G), N - 1)
    add("k = n: wie k = 0", N, g2, 2)
    add("k = n + 1, Q = G", N + 1, G, 1)
    add("k = 2^256 - 1", 2 ** 256 - 1, G, 1)
    add("k = 2^255", 2 ** 255, g2, 2)
    add("k = 2^128", 2 ** 128, G, 1)
    for j in range(8):
        q = rng.randrange(1, N)
        qp = point_mul(q, G)
        add(f"zufaellig {j}: k = q (Verdoppeln)", q, qp, q)
        add(f"zufaellig {j}: k = n - q (unendlich)", N - q, qp, q)
        add(f"zufaellig {j}: Q und -Q", rng.randrange(1, N), neg(qp), N - q)
        add(f"zufaellig {j}: k nahe n", N - 1 - rng.randrange(0, 2 ** 32), qp, q)
        add(f"zufaellig {j}: k = n - 1 (Q - G)", N - 1, qp, q)
        add(f"zufaellig {j}: k klein", rng.randrange(1, 256), qp, q)
    # Punkte ohne bekannten Skalar: kleinstes und grösstes gültiges x, beide Paritäten
    small = next(x for x in range(1, 1000) if lift_x(x, False))
    large = next(x for x in range(P - 1, P - 1000, -1) if lift_x(x, False))
    for x in (small, large):
        for odd in (False, True):
            add(f"x = {'klein' if x == small else 'gross'}, y {'ungerade' if odd else 'gerade'}",
                rng.randrange(1, N), lift_x(x, odd))
            add(f"x = {'klein' if x == small else 'gross'}, y {'ungerade' if odd else 'gerade'}, k = 0",
                0, lift_x(x, odd))
    return ops


def make_key_checks(rng):
    """xpubs mit gebastelten Schlüsseln: Bip32.check -> null oder UNKNOWN_FORMAT."""
    chain = rng.randbytes(32)
    out = []

    def add(note, pub, valid):
        x = serialize(XPUB, 3, bytes(4), 7, chain, pub)
        entry = {"note": note, "xpub": x, "problem": None if valid else "UNKNOWN_FORMAT"}
        if valid:
            child = ckd_pub(parse(x), 0)
            entry["child0"] = child[0]["pub"].hex()
        out.append(entry)

    off_curve = next(x for x in range(1, 1000) if lift_x(x, False) is None)
    small = next(x for x in range(1, 1000) if lift_x(x, False))
    large = next(x for x in range(P - 1, P - 1000, -1) if lift_x(x, False))
    for x in (small, large):
        for prefix in (2, 3):
            add(f"x {'klein' if x == small else 'gross'}, Praefix {prefix:02x}", bytes([prefix]) + x.to_bytes(32, "big"), True)
    add("nicht auf der Kurve", b"\x02" + off_curve.to_bytes(32, "big"), False)
    add("x = p", b"\x02" + P.to_bytes(32, "big"), False)
    add("x = p + 1 (wie x = 1 mod p)", b"\x02" + (P + 1).to_bytes(32, "big"), False)
    add("x = 2^256 - 1", b"\x03" + (2 ** 256 - 1).to_bytes(32, "big"), False)
    gx = G[0].to_bytes(32, "big")
    add("Praefix 04", b"\x04" + gx, False)
    add("Praefix 00", b"\x00" + gx, False)
    add("G, gerade", b"\x02" + gx, True)
    add("-G, ungerade", b"\x03" + gx, True)
    return out


def make_argon2(rng):
    cases = []
    for i in range(ARGON2_CASES):
        p = rng.randrange(1, 5)
        t = rng.randrange(1, 4)
        m = rng.randrange(8 * p, 1025)
        if m * t > 2048:  # Tests schnell halten
            t = max(1, 2048 // m)
        password = rng.randbytes(rng.randrange(0, 65))
        salt = rng.randbytes(rng.randrange(8, 33))
        tag = rng.choice([4, 16, 32, 32, 64, 65, 100, 128])
        secret = rng.randbytes(rng.randrange(1, 33)) if i % 4 == 1 else b""
        ad = rng.randbytes(rng.randrange(1, 33)) if i % 4 == 2 else b""
        kdf = Argon2id(salt=salt, length=tag, iterations=t, lanes=p, memory_cost=m,
                       ad=ad or None, secret=secret or None)
        cases.append({"password": password.hex(), "salt": salt.hex(), "secret": secret.hex(), "ad": ad.hex(),
                      "m": m, "t": t, "p": p, "tagLength": tag, "tag": kdf.derive(password).hex()})
    # RFC 9106 5.3 als Kontrolle der Referenz
    rfc = Argon2id(salt=b"\x02" * 16, length=32, iterations=3, lanes=4, memory_cost=32,
                   ad=b"\x04" * 12, secret=b"\x03" * 8).derive(b"\x01" * 32)
    assert rfc.hex() == "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
    return cases


def write(name, obj, cases_key):
    """Ein Fall je Zeile: kompakt, aber noch lesbar und diff-freundlich."""
    lines = ["{"]
    keys = list(obj.keys())
    for n, key in enumerate(keys):
        sep = "," if n < len(keys) - 1 else ""
        value = obj[key]
        if key in cases_key and isinstance(value, list):
            body = ",\n".join("    " + json.dumps(v, ensure_ascii=False, separators=(", ", ": ")) for v in value)
            lines.append(f'  {json.dumps(key)}: [\n{body}\n  ]{sep}')
        else:
            lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}{sep}")
    lines.append("}")
    text = "\n".join(lines) + "\n"
    json.loads(text)
    for target in TARGETS:
        if os.path.isdir(target):
            with open(os.path.join(target, name), "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
    return len(text.encode())


def main():
    self_check()
    rng = random.Random(SEED)
    fuzz = {
        "_comment": "Referenz-Fuzz BIP32/secp256k1 (android/tools/gen_bip32_vectors.py, Seed fest; nicht von Hand aendern). "
                    "Adressen: [P2PKH, P2SH-P2WPKH, P2WPKH, P2TR]. Gegenstuecke: Bip32FuzzParityTest.kt / Bip32FuzzTests.swift.",
        "cases": make_cases(rng),
        "pointOps": make_point_ops(rng),
        "keyChecks": make_key_checks(rng),
        "indexLimits": [{"index": 2 ** 31 - 1, "valid": True}, {"index": 2 ** 31, "valid": False},
                        {"index": 2 ** 32 - 1, "valid": False}, {"index": -1, "valid": False}],
    }
    size = write("bip32_fuzz.json", fuzz, {"cases", "pointOps", "keyChecks", "indexLimits"})
    argon = {
        "_comment": "Argon2id (RFC 9106) mit zufaelligen Parametern, m <= 1 MiB (android/tools/gen_bip32_vectors.py, "
                    "Python cryptography). Gegenstuecke: Argon2RandomParityTest.kt / Argon2RandomTests.swift.",
        "cases": make_argon2(random.Random(SEED + 1)),
    }
    write("argon2_random.json", argon, {"cases"})
    print(f"bip32_fuzz.json: {size} Bytes, {CASES} Faelle; argon2_random.json: {ARGON2_CASES} Faelle")


if __name__ == "__main__":
    sys.exit(main())
