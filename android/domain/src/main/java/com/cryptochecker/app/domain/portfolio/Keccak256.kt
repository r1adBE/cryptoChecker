package com.cryptochecker.app.domain.portfolio

/**
 * Keccak-256 (die Ethereum-Variante, Polsterung 0x01 — NICHT SHA3-256 mit 0x06) für die
 * EIP-55-Prüfsumme von Ethereum-Adressen ([AddressValidation]). Das JDK kennt nur SHA3-256.
 * Reines Kotlin, gleich wie `Keccak256.swift`.
 */
object Keccak256 {

    /** Keccak-256 (Ethereum). */
    fun hash(input: ByteArray): ByteArray = sponge(input, 0x01)

    /** SHA3-256 (FIPS 202) — nur für Tests der Permutation gegen bekannte Werte. */
    fun sha3(input: ByteArray): ByteArray = sponge(input, 0x06)

    fun hex(input: ByteArray): String = hash(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val RATE = 136

    private val RC = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(),
        0x8000000080008000uL.toLong(), 0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(), 0x000000000000008AuL.toLong(),
        0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
        0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(),
        0x8000000000008003uL.toLong(), 0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
        0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(), 0x8000000080008081uL.toLong(),
        0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
    )

    /** Rotation je Lane (Index x + 5y). */
    private val ROT = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    private fun sponge(input: ByteArray, pad: Int): ByteArray {
        // Nachricht + Polsterung auf ein Vielfaches der Rate
        val blocks = input.size / RATE + 1
        val padded = ByteArray(blocks * RATE)
        input.copyInto(padded)
        padded[input.size] = (padded[input.size].toInt() xor pad).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()

        val state = LongArray(25)
        for (b in 0 until blocks) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (k in 0 until 8) {
                    lane = lane or ((padded[b * RATE + i * 8 + k].toLong() and 0xff) shl (8 * k))
                }
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            // θ
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            // ρ und π
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
            }
            // χ
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            // ι
            a[0] = a[0] xor RC[round]
        }
    }
}
