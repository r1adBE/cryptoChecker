package com.cryptochecker.app.data

/**
 * Ergebnis eines Bild-Durchgangs ([LogoImageStore.downloadMissing]); [network] = am fehlenden Netz gescheitert;
 * [pausedUntil] = frühestes Ende der Pausen gebremster Server, deren Bilder noch fehlen (null = keine) — danach holt
 * [com.cryptochecker.app.logos.CoinLogoSync] nach; [incomplete] = Bilder offen geblieben (gestört bzw. Speicher voll):
 * das erste Laden ist damit nicht erledigt. Beim Zusammenzählen gilt die Pause des späteren Durchgangs [o]: Er versucht
 * alle noch fehlenden Bilder erneut.
 */
data class LogoDownloads(
    val added: Int = 0,
    val network: Boolean = false,
    val pausedUntil: Long? = null,
    val incomplete: Boolean = false,
) {
    operator fun plus(o: LogoDownloads) =
        LogoDownloads(added + o.added, network || o.network, o.pausedUntil, incomplete || o.incomplete)

    /** Durchgang vollständig: nichts am Netz gescheitert, nichts gebremst oder offen. */
    val complete: Boolean get() = !network && pausedUntil == null && !incomplete
}
