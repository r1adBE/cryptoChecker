package com.cryptochecker.app.data.backup

import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * Arbeitsspeicher von [Argon2id] (m KiB, 64 MiB bei den Sicherungen) ausserhalb des Java-Heaps: ab Android 8.1 als
 * anonymer gemeinsamer Speicher ([SharedMemory], ohne Datei), sonst und in den JVM-Tests als
 * [ByteBuffer.allocateDirect] (auf Android 8.0 liegt der wieder im Heap; reicht er nicht, folgt [OutOfMemoryError],
 * den [BackupCrypto] klar meldet). So braucht die App kein `largeHeap`. Blöcke werden am Stück gelesen und
 * geschrieben ([read], [write]); gerechnet wird in den `LongArray`s von [Argon2id]. [close] überschreibt alles mit
 * Nullen und gibt den Speicher frei.
 */
internal class Argon2Memory private constructor(
    private val words: LongBuffer,
    private val blocks: Int,
    private val release: () -> Unit,
) : AutoCloseable {

    /** Block [block] nach [into] (128 Wörter). */
    fun read(block: Int, into: LongArray) {
        words.position(block * BLOCK_WORDS)
        words.get(into, 0, BLOCK_WORDS)
    }

    /** [from] (128 Wörter) als Block [block]. */
    fun write(block: Int, from: LongArray) {
        words.position(block * BLOCK_WORDS)
        words.put(from, 0, BLOCK_WORDS)
    }

    /** Erstes Wort von Block [block] (Zufallswert der datenabhängigen Adressierung). */
    fun firstWord(block: Int): Long = words.get(block * BLOCK_WORDS)

    override fun close() {
        val zero = LongArray(BLOCK_WORDS)
        for (block in 0 until blocks) write(block, zero)
        release()
    }

    companion object {
        const val BLOCK_WORDS = 128

        /** Speicher für [blocks] Blöcke zu 1 KiB. */
        fun allocate(blocks: Int): Argon2Memory {
            val bytes = blocks * BLOCK_WORDS * Long.SIZE_BYTES
            shared(bytes)?.let { (buffer, release) -> return Argon2Memory(view(buffer), blocks, release) }
            return Argon2Memory(view(ByteBuffer.allocateDirect(bytes)), blocks) {}
        }

        private fun view(buffer: ByteBuffer): LongBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()

        /** Anonymer gemeinsamer Speicher (ab API 27); null = nicht verfügbar (ältere Android-Version, JVM-Test, Fehler). */
        private fun shared(bytes: Int): Pair<ByteBuffer, () -> Unit>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            return runCatching {
                val memory = SharedMemory.create("argon2id", bytes)
                val buffer = runCatching { memory.mapReadWrite() }.getOrElse {
                    memory.close()
                    throw it
                }
                buffer to {
                    SharedMemory.unmap(buffer)
                    memory.close()
                }
            }.getOrNull()
        }
    }
}
