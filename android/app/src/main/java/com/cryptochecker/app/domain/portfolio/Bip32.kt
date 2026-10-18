package com.cryptochecker.app.domain.portfolio

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Erweiterte ÖFFENTLICHE Bitcoin-Schlüssel (xpub/ypub/zpub, BIP32) lesen und Adressen daraus ableiten —
 * nur öffentliche Ableitung (CKDpub, nicht gehärtet), nie private Schlüssel.
 *
 * Adresstyp nach Präfix (Versionsbytes BIP32/BIP49/BIP84):
 *  - xpub (0x0488B21E) → P2PKH «1…» (BIP44). Manche Wallets (Ledger Live) zeigen auch für Segwit-Konten eine xpub:
 *    findet die Suche als P2PKH nichts, prüft sie P2SH-P2WPKH und P2WPKH desselben Schlüssels ([XpubScan.scripts]) und
 *    merkt sich den Typ mit Verlauf ([XpubCache.script]).
 *  - ypub (0x049D7CB2) → P2SH-P2WPKH «3…» (BIP49)
 *  - zpub (0x04B24746) → P2WPKH «bc1q…» (BIP84)
 *  - Taproot-Konto (BIP86) → P2TR «bc1p…»: für BIP86 gibt es kein SLIP-132-Präfix, Wallets geben einen Deskriptor
 *    `tr([fp/86'/0'/0']xpub…)` mit den Ketten 0 und 1 aus ([AddressValidation] liest ihn). Gespeichert wird die
 *    Normalform ([taprootDescriptor]); [parse] liest auch diese ([ExtendedKey.taproot]). Adresse: x-only-Schlüssel
 *    mit BIP341-Tweak (TapTweak, Q = P + t·G), Bech32m Version 1 ([taprootOutputKey]).
 * Ketten: 0 = Empfang, 1 = Wechselgeld; Adresse i = Schlüssel/Kette/i.
 * Mathematik: secp256k1 mit BigInteger (affin), RIPEMD-160 selbst (nicht in jedem JDK/Android).
 * Geprüft mit den offiziellen Testvektoren (BIP32 Vektor 1/2, BIP84, BIP49) in `address_balance.json`, BIP86 und
 * BIP350 in `taproot.json`.
 * Reines Kotlin, gleich wie `Bip32.swift`.
 */
object Bip32 {

    /** Adresstyp; der Name ist die gespeicherte Form ([XpubCache.script]). */
    enum class Script { P2PKH, P2SH_P2WPKH, P2WPKH, P2TR }

    const val XPUB = 0x0488B21E
    const val YPUB = 0x049D7CB2
    const val ZPUB = 0x04B24746

    /** Multisig (Ypub/Zpub): nicht unterstützt. */
    private val MULTISIG = setOf(0x0295B43F, 0x02AA7ED3)
    /** Testnet tpub/upub/vpub/Upub/Vpub. */
    private val TESTNET = setOf(0x043587CF, 0x044A5262, 0x045F1CF6, 0x024289EF, 0x02575483)
    /** Private Versionen (xprv … Vprv) — nie annehmen. */
    private val PRIVATE = setOf(
        0x0488ADE4, 0x049D7878, 0x04B2430C, 0x0295B005, 0x02AA7A99,
        0x04358394, 0x044A4E28, 0x045F18BC, 0x024285B5, 0x02575048,
    )

    /** Grösster nicht gehärteter Index (2³¹ − 1). */
    const val MAX_INDEX = 0x7fffffffL

    /** Erweiterter öffentlicher Schlüssel (78 Byte ohne Prüfsumme). */
    class ExtendedKey(
        val version: Int,
        val depth: Int,
        val parentFingerprint: ByteArray,
        val childNumber: Long,
        val chainCode: ByteArray,
        /** Komprimiert, 33 Byte (0x02/0x03 + x). */
        val publicKey: ByteArray,
        /** Taproot-Konto (BIP86, aus `tr(…)`): Adressen P2TR; die Versionsbytes sind die eines xpub. */
        val taproot: Boolean = false,
    ) {
        val script: Script
            get() = when {
                taproot -> Script.P2TR
                version == YPUB -> Script.P2SH_P2WPKH
                version == ZPUB -> Script.P2WPKH
                else -> Script.P2PKH
            }

        /** Adresstyp unbekannt (xpub ohne Deskriptor): die Suche prüft mehrere Typen ([XpubScan.scripts]). */
        val detectsScript: Boolean get() = version == XPUB && !taproot
    }

    /** Normalform eines Taproot-Kontos: beide Ketten (BIP389), ohne Herkunft und Prüfsumme. */
    fun taprootDescriptor(xpub: String): String = "tr($xpub$TAPROOT_SUFFIX)"

    /** xpub aus der Normalform [taprootDescriptor]; anderes = null. */
    fun taprootAccount(s: String): String? =
        if (s.startsWith("tr(") && s.endsWith("$TAPROOT_SUFFIX)")) s.substring(3, s.length - TAPROOT_SUFFIX.length - 1) else null

    private const val TAPROOT_SUFFIX = "/<0;1>/*"

    /** Prüfen: null = gültiger xpub/ypub/zpub (Mainnet); sonst das Problem für [AddressValidation]. */
    fun check(s: String): AddressValidation.Problem? {
        val raw = AddressValidation.base58Decode(s) ?: return AddressValidation.Problem.UNKNOWN_FORMAT
        if (raw.size != 82) return AddressValidation.Problem.UNKNOWN_FORMAT
        val payload = raw.copyOfRange(0, 78)
        val sum = sha256(sha256(payload))
        for (i in 0 until 4) if (sum[i] != raw[78 + i]) return AddressValidation.Problem.BAD_CHECKSUM
        val version = int32(payload, 0)
        return when {
            version in PRIVATE -> AddressValidation.Problem.SECRET
            version in TESTNET -> AddressValidation.Problem.TESTNET
            version in MULTISIG -> AddressValidation.Problem.EXTENDED_KEY
            version != XPUB && version != YPUB && version != ZPUB -> AddressValidation.Problem.UNKNOWN_FORMAT
            decodePoint(payload.copyOfRange(45, 78)) == null -> AddressValidation.Problem.UNKNOWN_FORMAT
            else -> null
        }
    }

    /** Lesen; ungültig (auch Testnet, Multisig, privat) = null. Auch die Normalform eines Taproot-Kontos (nur xpub). */
    fun parse(s: String): ExtendedKey? {
        taprootAccount(s)?.let { inner ->
            val key = parse(inner)?.takeIf { it.version == XPUB } ?: return null
            return ExtendedKey(key.version, key.depth, key.parentFingerprint, key.childNumber, key.chainCode, key.publicKey, taproot = true)
        }
        if (check(s) != null) return null
        val raw = AddressValidation.base58Decode(s) ?: return null
        return ExtendedKey(
            version = int32(raw, 0),
            depth = raw[4].toInt() and 0xff,
            parentFingerprint = raw.copyOfRange(5, 9),
            childNumber = int32(raw, 9).toLong() and 0xffffffffL,
            chainCode = raw.copyOfRange(13, 45),
            publicKey = raw.copyOfRange(45, 78),
        )
    }

    /** Zurück in die Textform (Base58Check). */
    fun serialize(key: ExtendedKey): String {
        val out = ByteArray(78)
        putInt32(out, 0, key.version)
        out[4] = key.depth.toByte()
        key.parentFingerprint.copyInto(out, 5)
        putInt32(out, 9, key.childNumber.toInt())
        key.chainCode.copyInto(out, 13)
        key.publicKey.copyInto(out, 45)
        return base58Check(out)
    }

    /**
     * Kind [index] (nicht gehärtet, 0…2³¹−1) — CKDpub aus BIP32. Ungültiges Ergebnis (IL ≥ n oder Unendlich,
     * Wahrscheinlichkeit < 2⁻¹²⁷) oder gehärteter Index = null.
     */
    fun child(key: ExtendedKey, index: Long): ExtendedKey? {
        if (index < 0 || index > MAX_INDEX) return null
        val parent = decodePoint(key.publicKey) ?: return null
        val data = ByteArray(37)
        key.publicKey.copyInto(data, 0)
        putInt32(data, 33, index.toInt())
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.chainCode, "HmacSHA512"))
        val i = mac.doFinal(data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        if (il >= N) return null
        val point = add(multiply(il, G), parent) ?: return null
        return ExtendedKey(
            version = key.version,
            depth = (key.depth + 1) and 0xff,
            parentFingerprint = hash160(key.publicKey).copyOfRange(0, 4),
            childNumber = index,
            chainCode = i.copyOfRange(32, 64),
            publicKey = encodePoint(point),
            taproot = key.taproot,
        )
    }

    /** Adresse eines komprimierten öffentlichen Schlüssels. */
    fun address(script: Script, publicKey: ByteArray): String {
        val h = hash160(publicKey)
        return when (script) {
            Script.P2PKH -> base58Check(byteArrayOf(0x00) + h)
            Script.P2SH_P2WPKH -> base58Check(byteArrayOf(0x05) + hash160(byteArrayOf(0x00, 0x14) + h))
            Script.P2WPKH -> bech32V0("bc", h)
            Script.P2TR -> segwitAddress("bc", 1, taprootOutputKey(publicKey) ?: throw IllegalStateException("Tweak ungültig"))
        }
    }

    /**
     * Ausgabeschlüssel (32 Byte, x-only) eines Taproot-Kontos ohne Skriptbaum (BIP86/BIP341): P = Punkt mit geradem y
     * zu x(Schlüssel), t = TaggedHash("TapTweak", x(P)), Q = P + t·G. t ≥ n oder Q unendlich (praktisch nie) = null.
     */
    fun taprootOutputKey(publicKey: ByteArray): ByteArray? {
        if (publicKey.size != 33) return null
        val x = publicKey.copyOfRange(1, 33)
        val p = decodePoint(byteArrayOf(2) + x) ?: return null
        val t = BigInteger(1, taggedHash("TapTweak", x))
        if (t >= N) return null
        val q = add(multiply(t, G), p) ?: return null
        return encodePoint(q).copyOfRange(1, 33)
    }

    /** BIP340: SHA-256(SHA-256(tag) ‖ SHA-256(tag) ‖ data). */
    fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.US_ASCII))
        return sha256(t + t + data)
    }

    /**
     * k·G + Q (k: 32 Byte big-endian, Q komprimiert); Unendlich oder ungültiges Q = null. Gleicher Weg wie in
     * [child] und [taprootOutputKey] — offen nur, damit `bip32_fuzz.json` die Kurvenrechnung an Randfällen
     * (k nahe n, Verdoppeln, Unendlich) prüfen kann, die über HMAC/Tweak nicht gezielt erreichbar sind.
     */
    internal fun multiplyAddG(k: ByteArray, q: ByteArray): ByteArray? {
        val point = decodePoint(q) ?: return null
        return add(multiply(BigInteger(1, k), G), point)?.let { encodePoint(it) }
    }

    // ---------------- secp256k1 (affin, BigInteger) ----------------

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val THREE = BigInteger.valueOf(3)

    private class Point(val x: BigInteger, val y: BigInteger)

    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )

    /** null = Punkt im Unendlichen. */
    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if ((a.y + b.y).mod(P).signum() == 0) return null
            // Verdoppeln: λ = 3x² / 2y
            val l = THREE * a.x * a.x % P * (a.y.shiftLeft(1)).modInverse(P) % P
            return line(a, a, l)
        }
        val l = (b.y - a.y).mod(P) * (b.x - a.x).mod(P).modInverse(P) % P
        return line(a, b, l)
    }

    private fun line(a: Point, b: Point, l: BigInteger): Point {
        val x = (l * l - a.x - b.x).mod(P)
        val y = (l * (a.x - x) - a.y).mod(P)
        return Point(x, y)
    }

    private fun multiply(k: BigInteger, p: Point): Point? {
        var result: Point? = null
        var addend: Point? = p
        for (bit in 0 until k.bitLength()) {
            if (k.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    /** Komprimierten Punkt lesen; nicht auf der Kurve = null. */
    private fun decodePoint(b: ByteArray): Point? {
        if (b.size != 33 || (b[0].toInt() != 2 && b[0].toInt() != 3)) return null
        val x = BigInteger(1, b.copyOfRange(1, 33))
        if (x >= P) return null
        val rhs = (x.modPow(THREE, P) + SEVEN).mod(P)
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != rhs) return null
        if (y.testBit(0) != (b[0].toInt() == 3)) y = P - y
        return Point(x, y)
    }

    private fun encodePoint(p: Point): ByteArray {
        val out = ByteArray(33)
        out[0] = if (p.y.testBit(0)) 3 else 2
        val x = p.x.toByteArray() // Vorzeichen-Byte möglich
        val xs = if (x.size > 32) x.copyOfRange(x.size - 32, x.size) else x
        xs.copyInto(out, 33 - xs.size)
        return out
    }

    // ---------------- Hilfen ----------------

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun putInt32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Bytes + 4 Byte Prüfsumme (doppeltes SHA-256) → Base58. */
    fun base58Check(payload: ByteArray): String {
        val sum = sha256(sha256(payload))
        val data = payload + sum.copyOfRange(0, 4)
        val digits = ArrayList<Int>() // Basis 58, niedrigste Stelle zuerst
        for (byte in data) {
            var carry = byte.toInt() and 0xff
            for (i in digits.indices) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        val sb = StringBuilder()
        for (byte in data) if (byte.toInt() == 0) sb.append('1') else break
        for (i in digits.indices.reversed()) sb.append(BASE58[digits[i]])
        return sb.toString()
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val BECH32M_CONST = 0x2bc830a3

    /** Segwit v0 (Bech32, BIP173). */
    fun bech32V0(hrp: String, program: ByteArray): String = segwitAddress(hrp, 0, program)

    /** Segwit-Adresse: Version 0 mit Bech32 (BIP173), ab Version 1 mit Bech32m (BIP350). */
    fun segwitAddress(hrp: String, version: Int, program: ByteArray): String {
        require(version in 0..16)
        val data = ArrayList<Int>()
        data.add(version)
        var acc = 0
        var bits = 0
        for (b in program) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                data.add((acc ushr bits) and 31)
            }
            acc = acc and 0xff
        }
        if (bits > 0) data.add((acc shl (5 - bits)) and 31)
        val values = ArrayList<Int>()
        for (c in hrp) values.add(c.code ushr 5)
        values.add(0)
        for (c in hrp) values.add(c.code and 31)
        values.addAll(data)
        repeat(6) { values.add(0) }
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        val mod = chk xor (if (version == 0) 1 else BECH32M_CONST)
        val sb = StringBuilder(hrp).append('1')
        for (d in data) sb.append(CHARSET[d])
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }
}

/**
 * Adressen eines xpub/ypub/zpub (oder Taproot-Kontos in Normalform) der Reihe nach (Kette 0 = Empfang, 1 = Wechselgeld); die Kettenschlüssel werden
 * einmal abgeleitet. Ungültiger Schlüssel: [Bip32.parse] liefert null → Konstruktor wirft. [script] = anderer
 * Adresstyp als nach Präfix (nur für xpub sinnvoll, [XpubScan.scripts]).
 */
class XpubDeriver(xpub: String, script: Bip32.Script? = null) {
    private val root: Bip32.ExtendedKey = Bip32.parse(xpub) ?: throw IllegalArgumentException("xpub ungültig")
    private val chains = arrayOfNulls<Bip32.ExtendedKey>(2)

    /** Adresstyp: [script] (xpub mit Segwit-Konto), sonst nach Präfix ([Bip32.ExtendedKey.script]). */
    val script: Bip32.Script = script ?: root.script

    fun address(chain: Int, index: Int): String {
        require((chain == 0 || chain == 1) && index >= 0)
        val chainKey = chains[chain] ?: (Bip32.child(root, chain.toLong()) ?: throw IllegalStateException("Ableitung ungültig"))
            .also { chains[chain] = it }
        val key = Bip32.child(chainKey, index.toLong()) ?: throw IllegalStateException("Ableitung ungültig")
        return Bip32.address(script, key.publicKey)
    }
}
