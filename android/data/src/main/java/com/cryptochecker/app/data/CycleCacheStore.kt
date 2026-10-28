package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.domain.market.CycleCachePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ein gespeicherter Wert, wann er geholt wurde (Epoch-ms) und von welchem Anbieter
 * ([provider], z. B. «CoinGecko»; null = unbekannt, auch bei Dateien ohne diese Angabe).
 */
data class CachedValue<T>(val value: T, val savedAt: Long, val provider: String? = null)

/** Wandelt einen Wert in JSON und zurück; [decode] darf bei ungültigem Inhalt werfen. */
class CacheCodec<T>(val encode: (T) -> JSONObject, val decode: (JSONObject) -> T)

/**
 * Zwischenspeicher des Markt-Tabs auf dem Gerät: eine kleine JSON-Datei je Bereich
 * in `cacheDir/cycle` (das System darf sie bei Platzmangel löschen — dann wird eben
 * neu geladen). Format mit Version im Namen und im Inhalt ([CycleCachePolicy.FORMAT_VERSION]);
 * alte, fremde oder kaputte Dateien gelten als «nichts gespeichert». Geschrieben wird
 * über eine Hilfsdatei und Umbenennen, damit nie eine halbe Datei gelesen wird.
 * Nur öffentliche Marktdaten, nichts zur Merkliste.
 */
@Singleton
class CycleCacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.cacheDir, DIR)

    suspend fun <T> read(name: String, codec: CacheCodec<T>): CachedValue<T>? = withContext(Dispatchers.IO) {
        try {
            val file = File(dir, CycleCachePolicy.fileName(name))
            if (!file.isFile) return@withContext null
            val root = JSONObject(file.readText())
            val version = if (root.has(KEY_VERSION)) root.optInt(KEY_VERSION, -1) else null
            if (!CycleCachePolicy.isCurrentFormat(version) || root.optString(KEY_NAME) != name) {
                return@withContext null
            }
            val savedAt = root.optLong(KEY_AT, 0L)
            if (savedAt <= 0L) return@withContext null
            // Anbieter fehlt: einfach unbekannt
            val provider = root.optString(KEY_PROVIDER).takeIf { it.isNotBlank() }
            CachedValue(codec.decode(root.getJSONObject(KEY_DATA)), savedAt, provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Zyklus-Zwischenspeicher %s unlesbar", name)
            null
        }
    }

    suspend fun <T> write(name: String, value: T, savedAt: Long, codec: CacheCodec<T>, provider: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val folder = dir
                if (!folder.isDirectory && !folder.mkdirs()) return@withContext
                val root = JSONObject()
                    .put(KEY_VERSION, CycleCachePolicy.FORMAT_VERSION)
                    .put(KEY_NAME, name)
                    .put(KEY_AT, savedAt)
                    .put(KEY_DATA, codec.encode(value))
                if (!provider.isNullOrBlank()) root.put(KEY_PROVIDER, provider)
                val target = File(folder, CycleCachePolicy.fileName(name))
                val temp = File(folder, target.name + ".tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) temp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Zyklus-Zwischenspeicher %s nicht geschrieben", name)
            }
        }
    }

    private companion object {
        const val DIR = "cycle"
        const val KEY_VERSION = "v"
        const val KEY_NAME = "name"
        const val KEY_AT = "at"
        const val KEY_DATA = "data"
        const val KEY_PROVIDER = "src"
    }
}
