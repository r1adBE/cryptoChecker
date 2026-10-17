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
 * Ketten: 0 = Empfang, 1 = Wechselgeld; Adresse i = Schlüssel/Kette/i.
 * Mathematik: secp256k1 mit BigInteger (affin), RIPEMD-160 selbst (nicht in jedem JDK/Android).
 * Geprüft mit den offiziellen Testvektoren (BIP32 Vektor 1/2, BIP84, BIP49) in `address_balance.json`.
 * Reines Kotlin, gleich wie `Bip32.swift`.
 */
object Bip32 {

    /** Adresstyp; der Name ist die gespeicherte Form ([XpubCache.script]). */
    enum class Script { P2PKH, P2SH_P2WPKH, P2WPKH }

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
    ) {
        val script: Script
            get() = when (version) {
                YPUB -> Script.P2SH_P2WPKH
                ZPUB -> Script.P2WPKH
                else -> Script.P2PKH
            }
    }

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

    /** Lesen; ungültig (auch Testnet, Multisig, privat) = null. */
    fun parse(s: String): ExtendedKey? {
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
        )
    }

    /** Adresse eines komprimierten öffentlichen Schlüssels. */
    fun address(script: Script, publicKey: ByteArray): String {
        val h = hash160(publicKey)
        return when (script) {
            Script.P2PKH -> base58Check(byteArrayOf(0x00) + h)
            Script.P2SH_P2WPKH -> base58Check(byteArrayOf(0x05) + hash160(byteArrayOf(0x00, 0x14) + h))
            Script.P2WPKH -> bech32V0("bc", h)
        }
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

    /** Segwit v0 (Bech32, BIP173). */
    fun bech32V0(hrp: String, program: ByteArray): String {
        val data = ArrayList<Int>()
        data.add(0)
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
        val mod = chk xor 1
        val sb = StringBuilder(hrp).append('1')
        for (d in data) sb.append(CHARSET[d])
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    // ---------------- RIPEMD-160 ----------------

    private val RL = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
    )
    private val RR = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
    )
    private val SL = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
    )
    private val SR = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
    )
    private val KL = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
    private val KR = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

    private fun f(j: Int, x: Int, y: Int, z: Int): Int = when (j / 16) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        3 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }

    fun ripemd160(message: ByteArray): ByteArray {
        // Auffüllen: 0x80, Nullen, Länge in Bit (64 Bit, little-endian)
        val bitLen = message.size.toLong() * 8
        val padLen = ((56 - (message.size + 1) % 64) + 64) % 64
        val data = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(data)
        data[message.size] = 0x80.toByte()
        for (i in 0 until 8) data[data.size - 8 + i] = (bitLen ushr (8 * i)).toByte()

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val x = IntArray(16)
        var block = 0
        while (block < data.size) {
            for (i in 0 until 16) {
                val o = block + i * 4
                x[i] = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
                    ((data[o + 2].toInt() and 0xff) shl 16) or ((data[o + 3].toInt() and 0xff) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4
            for (j in 0 until 80) {
                var t = Integer.rotateLeft(al + f(j, bl, cl, dl) + x[RL[j]] + KL[j / 16], SL[j]) + el
                al = el; el = dl; dl = Integer.rotateLeft(cl, 10); cl = bl; bl = t
                t = Integer.rotateLeft(ar + f(79 - j, br, cr, dr) + x[RR[j]] + KR[j / 16], SR[j]) + er
                ar = er; er = dr; dr = Integer.rotateLeft(cr, 10); cr = br; br = t
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            block += 64
        }
        val out = ByteArray(20)
        for ((k, h) in intArrayOf(h0, h1, h2, h3, h4).withIndex()) {
            for (i in 0 until 4) out[k * 4 + i] = (h ushr (8 * i)).toByte()
        }
        return out
    }
}

/**
 * Adressen eines xpub/ypub/zpub der Reihe nach (Kette 0 = Empfang, 1 = Wechselgeld); die Kettenschlüssel werden
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
