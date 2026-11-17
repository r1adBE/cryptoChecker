package com.cryptochecker.app.data.backup

/**
 * Argon2id nach RFC 9106 (Version 0x13), reines Kotlin ohne Fremdbibliothek — Zeile für Zeile gespiegelt in
 * `App/Services/Argon2id.swift`, damit beide Plattformen dieselben Schlüssel ableiten (Sicherungen v3,
 * [BackupCrypto]). Geprüft mit dem Prüfwert aus RFC 9106 Abschnitt 5.3 (`Argon2idTest`).
 *
 * Die Bahnen (p) werden nacheinander gefüllt: Innerhalb eines Abschnitts greift keine Bahn auf den laufenden
 * Abschnitt einer anderen zu, das Ergebnis ist also dasselbe wie parallel. Speicher: m KiB als [LongArray]
 * (64 MiB bei m = 65 536) — reicht er nicht, folgt [OutOfMemoryError]; [BackupCrypto] meldet das klar.
 * Rechenzeit: einige 100 ms bis wenige Sekunden — nur im Hintergrund aufrufen.
 */
object Argon2id {
    const val VERSION = 0x13
    private const val TYPE_ID = 2L // Argon2id
    private const val BLOCK_WORDS = 128 // 1 KiB je Block
    private const val SYNC_POINTS = 4
    private const val ADDRESSES_IN_BLOCK = 128

    /**
     * Tag der Länge [tagLength] Bytes. [memoryKiB] ≥ 8 · [parallelism]; [secret] und [ad] sind nur für den
     * RFC-Prüfwert nötig (die Sicherung nutzt beide nicht).
     */
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        tagLength: Int,
        secret: ByteArray = ByteArray(0),
        ad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(parallelism in 1..0xFFFFFF && iterations >= 1 && tagLength >= 4)
        require(memoryKiB >= 8 * parallelism && salt.size >= 8)

        // H0 (RFC 9106, 3.2 Schritt 1)
        val h0Hash = Blake2b(64)
        h0Hash.updateInt(parallelism)
        h0Hash.updateInt(tagLength)
        h0Hash.updateInt(memoryKiB)
        h0Hash.updateInt(iterations)
        h0Hash.updateInt(VERSION)
        h0Hash.updateInt(TYPE_ID.toInt())
        h0Hash.updateInt(password.size)
        h0Hash.update(password)
        h0Hash.updateInt(salt.size)
        h0Hash.update(salt)
        h0Hash.updateInt(secret.size)
        h0Hash.update(secret)
        h0Hash.updateInt(ad.size)
        h0Hash.update(ad)
        val h0 = h0Hash.digest()

        // m' = 4 · p · floor(m / 4p); q Spalten je Bahn, 4 Abschnitte je Bahn
        val segmentLength = memoryKiB / (SYNC_POINTS * parallelism)
        val laneLength = segmentLength * SYNC_POINTS
        val blockCount = laneLength * parallelism
        val memory = LongArray(blockCount * BLOCK_WORDS)
        try {
            // Erste zwei Blöcke jeder Bahn aus H0
            val seed = ByteArray(h0.size + 8)
            h0.copyInto(seed)
            for (lane in 0 until parallelism) {
                for (column in 0..1) {
                    putIntLe(seed, h0.size, column)
                    putIntLe(seed, h0.size + 4, lane)
                    val block = hashLong(seed, BLOCK_WORDS * 8)
                    val offset = (lane * laneLength + column) * BLOCK_WORDS
                    for (w in 0 until BLOCK_WORDS) memory[offset + w] = getLongLe(block, w * 8)
                    block.fill(0)
                }
            }
            seed.fill(0)

            val filler = Filler(memory, laneLength, segmentLength, parallelism, blockCount, iterations)
            try {
                for (pass in 0 until iterations) {
                    for (slice in 0 until SYNC_POINTS) {
                        for (lane in 0 until parallelism) filler.fillSegment(pass, slice, lane)
                    }
                }
            } finally {
                // Arbeitsblöcke überschreiben (bei einer Bahn ergäbe tmp xor r den letzten Block) — wie Swift `deinit`
                filler.wipe()
            }

            // Letzte Blöcke aller Bahnen XOR → Tag
            val last = LongArray(BLOCK_WORDS)
            for (lane in 0 until parallelism) {
                val offset = (lane * laneLength + laneLength - 1) * BLOCK_WORDS
                for (w in 0 until BLOCK_WORDS) last[w] = last[w] xor memory[offset + w]
            }
            val lastBytes = ByteArray(BLOCK_WORDS * 8)
            for (w in 0 until BLOCK_WORDS) putLongLe(lastBytes, w * 8, last[w])
            last.fill(0)
            return hashLong(lastBytes, tagLength).also { lastBytes.fill(0) }
        } finally {
            memory.fill(0)
            h0.fill(0)
        }
    }

    /** Füllt die Abschnitte (RFC 9106, 3.4); hält die Hilfsblöcke, damit je Block nichts neu angelegt wird. */
    private class Filler(
        private val memory: LongArray,
        private val laneLength: Int,
        private val segmentLength: Int,
        private val lanes: Int,
        private val blockCount: Int,
        private val passes: Int,
    ) {
        private val r = LongArray(BLOCK_WORDS)
        private val tmp = LongArray(BLOCK_WORDS)
        private val zero = LongArray(BLOCK_WORDS)
        private val input = LongArray(BLOCK_WORDS)
        private val address = LongArray(BLOCK_WORDS)

        /** Arbeitsblöcke mit Nullen überschreiben (nach dem Füllen, auch bei einem Fehler). */
        fun wipe() {
            r.fill(0)
            tmp.fill(0)
            input.fill(0)
            address.fill(0)
        }

        fun fillSegment(pass: Int, slice: Int, lane: Int) {
            // Argon2id: im ersten Durchgang die ersten zwei Abschnitte datenunabhängig adressieren
            val independent = pass == 0 && slice < SYNC_POINTS / 2
            if (independent) {
                input.fill(0)
                input[0] = pass.toLong()
                input[1] = lane.toLong()
                input[2] = slice.toLong()
                input[3] = blockCount.toLong()
                input[4] = passes.toLong()
                input[5] = TYPE_ID
            }
            var start = 0
            if (pass == 0 && slice == 0) {
                start = 2
                if (independent) nextAddresses()
            }
            var current = lane * laneLength + slice * segmentLength + start
            var previous = if (current % laneLength == 0) current + laneLength - 1 else current - 1
            for (index in start until segmentLength) {
                if (current % laneLength == 1) previous = current - 1
                val pseudoRandom = if (independent) {
                    if (index % ADDRESSES_IN_BLOCK == 0) nextAddresses()
                    address[index % ADDRESSES_IN_BLOCK]
                } else {
                    memory[previous * BLOCK_WORDS]
                }
                val refLane = if (pass == 0 && slice == 0) lane else ((pseudoRandom ushr 32) % lanes).toInt()
                val refIndex = referenceIndex(pass, slice, index, pseudoRandom and 0xFFFFFFFFL, refLane == lane)
                fillBlock(previous, refLane * laneLength + refIndex, current, withXor = pass > 0)
                current++
                previous++
            }
        }

        /** Position des Bezugsblocks in seiner Bahn (RFC 9106, 3.4.1.2). */
        private fun referenceIndex(pass: Int, slice: Int, index: Int, j1: Long, sameLane: Boolean): Int {
            val area: Long = if (pass == 0) {
                when {
                    slice == 0 -> (index - 1).toLong()
                    sameLane -> (slice * segmentLength + index - 1).toLong()
                    else -> (slice * segmentLength + if (index == 0) -1 else 0).toLong()
                }
            } else {
                if (sameLane) {
                    (laneLength - segmentLength + index - 1).toLong()
                } else {
                    (laneLength - segmentLength + if (index == 0) -1 else 0).toLong()
                }
            }
            // j1 < 2^32: j1² passt ohne Überlauf in 64 Bit (vorzeichenlos gelesen mit ushr)
            val x = (j1 * j1) ushr 32
            val y = (area * x) ushr 32
            val relative = area - 1 - y
            val startPosition = if (pass == 0 || slice == SYNC_POINTS - 1) 0L else ((slice + 1) * segmentLength).toLong()
            return ((startPosition + relative) % laneLength).toInt()
        }

        private fun nextAddresses() {
            input[6]++
            compress(zero, 0, input, 0, address, withXor = false)
            compress(zero, 0, address, 0, address, withXor = false)
        }

        private fun fillBlock(prev: Int, ref: Int, next: Int, withXor: Boolean) {
            compress(memory, prev * BLOCK_WORDS, memory, ref * BLOCK_WORDS, memory, next * BLOCK_WORDS, withXor)
        }

        private fun compress(x: LongArray, xo: Int, y: LongArray, yo: Int, out: LongArray, withXor: Boolean) =
            compress(x, xo, y, yo, out, 0, withXor)

        /** G(X, Y) (RFC 9106, 3.5); mit [withXor] wird der alte Inhalt von [out] zusätzlich eingerechnet (ab Durchgang 2). */
        private fun compress(
            x: LongArray,
            xo: Int,
            y: LongArray,
            yo: Int,
            out: LongArray,
            oo: Int,
            withXor: Boolean,
        ) {
            for (i in 0 until BLOCK_WORDS) {
                val v = x[xo + i] xor y[yo + i]
                r[i] = v
                tmp[i] = if (withXor) v xor out[oo + i] else v
            }
            // Zeilen: 8 × 16 Wörter
            for (i in 0 until 8) {
                val b = 16 * i
                round(
                    b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                    b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15
                )
            }
            // Spalten: je 2 Wörter aus jeder Zeile
            for (i in 0 until 8) {
                val b = 2 * i
                round(
                    b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                    b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113
                )
            }
            for (i in 0 until BLOCK_WORDS) out[oo + i] = tmp[i] xor r[i]
        }

        private fun round(
            v0: Int,
            v1: Int,
            v2: Int,
            v3: Int,
            v4: Int,
            v5: Int,
            v6: Int,
            v7: Int,
            v8: Int,
            v9: Int,
            v10: Int,
            v11: Int,
            v12: Int,
            v13: Int,
            v14: Int,
            v15: Int,
        ) {
            g(v0, v4, v8, v12)
            g(v1, v5, v9, v13)
            g(v2, v6, v10, v14)
            g(v3, v7, v11, v15)
            g(v0, v5, v10, v15)
            g(v1, v6, v11, v12)
            g(v2, v7, v8, v13)
            g(v3, v4, v9, v14)
        }

        /** BLAKE2b-G mit der Multiplikation aus Argon2 (fBlaMka). */
        private fun g(a: Int, b: Int, c: Int, d: Int) {
            val v = r
            v[a] = blaMka(v[a], v[b])
            v[d] = (v[d] xor v[a]).rotateRight(32)
            v[c] = blaMka(v[c], v[d])
            v[b] = (v[b] xor v[c]).rotateRight(24)
            v[a] = blaMka(v[a], v[b])
            v[d] = (v[d] xor v[a]).rotateRight(16)
            v[c] = blaMka(v[c], v[d])
            v[b] = (v[b] xor v[c]).rotateRight(63)
        }

        private fun blaMka(x: Long, y: Long): Long = x + y + 2 * (x and 0xFFFFFFFFL) * (y and 0xFFFFFFFFL)
    }

    /** H' (RFC 9106, 3.3): Hash variabler Länge aus BLAKE2b. */
    fun hashLong(input: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        if (length <= 64) {
            Blake2b(length).apply {
                updateInt(length)
                update(input)
            }.digest().copyInto(out)
            return out
        }
        val r = (length + 31) / 32 - 2
        var v = Blake2b(64).apply {
            updateInt(length)
            update(input)
        }.digest()
        v.copyInto(out, 0, 0, 32)
        for (i in 1 until r) {
            val next = Blake2b(64).apply { update(v) }.digest()
            v.fill(0)
            v = next
            v.copyInto(out, i * 32, 0, 32)
        }
        val tail = Blake2b(length - 32 * r).apply { update(v) }.digest()
        v.fill(0)
        tail.copyInto(out, 32 * r)
        tail.fill(0)
        return out
    }

    private fun putIntLe(b: ByteArray, off: Int, value: Int) {
        for (i in 0 until 4) b[off + i] = (value ushr (8 * i)).toByte()
    }

    private fun putLongLe(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = (value ushr (8 * i)).toByte()
    }

    private fun getLongLe(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
