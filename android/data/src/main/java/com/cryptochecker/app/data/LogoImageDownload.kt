package com.cryptochecker.app.data

import com.cryptochecker.app.data.LogoImageCodec.pngBytes
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import okhttp3.OkHttpClient
import okhttp3.Request

/** Ein Logo-Bild von einem Bild-Server holen (für [LogoImageStore]): Antwort einordnen, Grösse begrenzen, als PNG. */
internal object LogoImageDownload {
    /** Bild-Server verlangt eine Pause ([CoinLogoRetry.pausesImages]). */
    class Pause(val host: String, val retryAfterSeconds: Long?) : Exception("pause $host")

    /** Antwort ohne Erfolg und ohne Pause (z. B. 404, 403 ohne «Retry-After»). */
    class HttpError(val code: Int) : Exception("HTTP $code")

    /**
     * Bild laden → PNG-Bytes zum Speichern; null = kein lesbares Bild. Wartet auf die Antwort ohne einen Thread
     * zu blockieren ([await]): wird die Coroutine abgebrochen (Abgleich beendet), bricht auch die Anfrage ab.
     * Keine Antwort (Zeitüberschreitung, Verbindung abgebrochen) wirft eine [java.io.IOException]: kurze Störung.
     */
    suspend fun png(client: OkHttpClient, url: String, userAgent: String): ByteArray? {
        val request = Request.Builder().url(url).header(HttpUserAgent.HEADER, userAgent).build()
        client.newCall(request).await().use { response ->
            val retryAfter = ExchangeBackoff.parseRetryAfterSeconds(response.header("Retry-After"), System.currentTimeMillis())
            if (CoinLogoRetry.pausesImages(response.code, retryAfter != null)) {
                throw Pause(CoinLogoRetry.hostOf(url) ?: "", retryAfter)
            }
            if (!response.isSuccessful) throw HttpError(response.code)
            val body = response.body
            // Auch ohne Content-Length nicht mehr als die Höchstgrösse lesen
            val bytes = HttpBodyLimit.read(body.byteStream(), body.contentLength(), MAX_IMAGE_BYTES)
                ?: error("too large")
            return pngBytes(bytes)
        }
    }

    /**
     * Höchstgrösse eines Quellbilds (wie iOS `CoinLogoStore.maxImageBytes`): 4 MB — Binance lädt Logos seit 2025
     * in hoher Auflösung hoch (über 512 KB, dann standen Initialen). Gespeichert wird nur das verkleinerte Bild
     * ([CoinLogos.STORED_PX]); die Pixel-Grenze [CoinLogos.MAX_SOURCE_PIXELS] bleibt.
     */
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
}
