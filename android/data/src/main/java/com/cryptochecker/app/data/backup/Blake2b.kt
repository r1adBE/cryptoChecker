// BLAKE2b (RFC 7693) für Argon2id.
package com.cryptochecker.app.data.backup

/** BLAKE2b ohne Schlüssel (RFC 7693), Ausgabe 1–64 Bytes — nur für [Argon2id]. */
class Blake2b(private val outLength: Int) {
    private val h = LongArray(8)
    private val buffer = ByteArray(128)
    private var bufferLength = 0
    private var counter = 0L // Bytes bisher (Eingaben hier weit unter 2^63)
    private val m = LongArray(16)
    private val v = LongArray(16)

    init {
        require(outLength in 1..64)
        IV.copyInto(h)
        h[0] = h[0] xor (0x01010000L or outLength.toLong())
    }

    fun updateInt(value: Int) {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (value ushr (8 * i)).toByte()
        update(b)
    }

    fun update(input: ByteArray) {
        var offset = 0
        var remaining = input.size
        while (remaining > 0) {
            // Volle Pufferblöcke erst verarbeiten, wenn weitere Eingabe folgt (der letzte Block braucht die Endmarke)
            if (bufferLength == 128) {
                counter += 128
                compress(last = false)
                bufferLength = 0
            }
            val take = minOf(128 - bufferLength, remaining)
            input.copyInto(buffer, bufferLength, offset, offset + take)
            bufferLength += take
            offset += take
            remaining -= take
        }
    }

    fun digest(): ByteArray {
        counter += bufferLength
        buffer.fill(0, bufferLength, 128)
        compress(last = true)
        val out = ByteArray(outLength)
        for (i in 0 until outLength) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        h.fill(0)
        buffer.fill(0)
        m.fill(0)
        v.fill(0)
        return out
    }

    private fun compress(last: Boolean) {
        for (i in 0 until 16) {
            var w = 0L
            for (j in 7 downTo 0) w = (w shl 8) or (buffer[i * 8 + j].toLong() and 0xFF)
            m[i] = w
        }
        h.copyInto(v)
        IV.copyInto(v, 8)
        v[12] = v[12] xor counter
        // v[13]: oberer Teil des Zählers, bei diesen Längen immer 0
        if (last) v[14] = v[14].inv()
        for (round in 0 until 12) {
            val s = SIGMA[round % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }

    private companion object {
        val IV = longArrayOf(
            0x6a09e667f3bcc908L,
            -0x4498517a7b3558c5L,
            0x3c6ef372fe94f82bL,
            -0x5ab00ac5a0e2c90fL,
            0x510e527fade682d1L,
            -0x64fa9773d4c193e1L,
            0x1f83d9abfb41bd6bL,
            0x5be0cd19137e2179L,
        )
        val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )
    }
}
