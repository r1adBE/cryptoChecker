// xpub/ypub/zpub/Taproot-Konten (Teil von `WatchedAddressSource`): Adressen ableiten und der Reihe nach fragen.
// Erweiterungsfunktionen; Client, Pausen und Hosts bleiben in der Klasse.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.Bip32
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.XpubCache
import com.cryptochecker.app.domain.portfolio.XpubDeriver
import com.cryptochecker.app.domain.portfolio.XpubScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.cryptochecker.app.data.portfolio.WatchedAddressSource.Fetched
import com.cryptochecker.app.data.portfolio.WatchedAddressSource.Companion.XPUB_PAUSE_MILLIS
import com.cryptochecker.app.data.portfolio.WatchedAddressSource.Companion.XPUB_PAUSE_BACKGROUND_MILLIS

/**
 * xpub/ypub/zpub/Taproot-Konto: Adressen auf dem Gerät ableiten ([XpubDeriver]) und einzeln wie eine Adresse fragen
 * ([XpubScan]: Gap-Regel, vollständig höchstens täglich). Hintergrund ohne Zwischenspeicher: null (bleibt fällig).
 * Zwischen zwei Anfragen eine kurze Pause (schont die Anbieter). xpub ohne bekannten Adresstyp
 * ([XpubScan.scripts]): vollständig als P2PKH, ohne Verlauf als P2SH-P2WPKH, dann P2WPKH (Segwit-Konto mit xpub,
 * Ledger Live); der erste Typ mit Verlauf wird gemerkt ([XpubCache.script]), im Hintergrund wird nicht gesucht.
 * Hintergrund unvollständig und tiefer als zuletzt ([XpubScan.keepBackground]): null (bleibt fällig).
 * Vollständige Suche zu gross ([XpubScan.TooMany]) mit Zwischenspeicher: einmal schnell wiederholen statt Fehler; dieser
 * Rückfall und der von [XpubScan.modeFor] setzen [XpubCache.limitedFallback]. Ohne Zwischenspeicher oder wenn auch die
 * schnelle zu gross ist: Fehler («Zu viele Adressen»).
 */
internal suspend fun WatchedAddressSource.fetchXpub(a: WatchedAddress, deadline: Long?): Fetched? {
    val key = Bip32.parse(a.address) ?: throw IllegalArgumentException("xpub ungültig")
    // Zwischenspeicher nur, wenn er zu diesem Schlüssel gehört (erste Adresse im gemerkten Typ stimmt)
    val stored = a.xpub?.let { c ->
        val deriver = XpubDeriver(a.address, c.script.takeIf { key.detectsScript })
        c.takeIf {
            withContext(Dispatchers.Default) {
                (c.receive.isEmpty() || c.receive[0] == deriver.address(0, 0)) &&
                    (c.change.isEmpty() || c.change[0] == deriver.address(1, 0))
            }
        }
    }
    val storedScript = stored?.let { it.script.takeIf { key.detectsScript } ?: key.script }
    val scripts = XpubScan.scripts(key, stored)
    val tried = ArrayList<Pair<Bip32.Script, Int>>()
    val results = ArrayList<Fetched>()
    val scans = ArrayList<XpubScan>()
    for (script in scripts) {
        val cache = stored?.takeIf { storedScript == script }
        val now = System.currentTimeMillis()
        val mode = if (scripts.size > 1) {
            if (deadline != null) return null
            XpubScan.Mode.FULL
        } else {
            XpubScan.modeFor(cache, now, background = deadline != null) ?: return null
        }
        // Fällige vollständige Suche schon vorab durch die schnelle ersetzt (sicher zu gross)
        var limited = deadline == null && mode == XpubScan.Mode.QUICK && XpubScan.fullDue(cache, now)
        var retried = false
        val deriver = XpubDeriver(a.address, script)
        val (scan, fetched) = try {
            scanXpub(deriver, cache, mode, deadline)
        } catch (e: XpubScan.TooMany) {
            // Vollständige Suche doch zu gross: mit Zwischenspeicher einmal schnell (wirft sie auch TooMany: Fehler)
            if (mode != XpubScan.Mode.FULL || cache == null || cache.fullScan <= 0) throw e
            limited = true
            retried = true
            scanXpub(deriver, cache, XpubScan.Mode.QUICK, deadline)
        }
        tried += script to scan.usedCount
        // Nach einer gescheiterten vollständigen Suche diese erst wieder nach einem Tag versuchen (fullScan = jetzt),
        // sonst kostete jede Aktualisierung im Vordergrund erneut über MAX_REQUESTS Anfragen
        results += if (limited) {
            fetched.copy(xpub = fetched.xpub?.let { x -> x.copy(limitedFallback = true, fullScan = if (retried) now else x.fullScan) })
        } else {
            fetched
        }
        scans += scan
        if (scan.usedCount > 0) break
    }
    val pick = XpubScan.pickScript(key, tried)
    val index = tried.indexOfFirst { it.first == pick }.coerceAtLeast(0)
    val chosen = results[index]
    // Hintergrund mit möglicherweise übersehener neuer Adresse und tieferer Summe: nicht speichern (keine Beobachtung,
    // kein Wert für Alarme) — bleibt fällig für die Suche im Vordergrund
    if (deadline != null && !XpubScan.keepBackground(scans[index].complete, scans[index].totalSats, a.lastBalance)) return null
    return chosen.copy(xpub = chosen.xpub?.copy(script = pick))
}

/** Eine Suche über die Adressen eines Adresstyps ([deriver]). */
internal suspend fun WatchedAddressSource.scanXpub(deriver: XpubDeriver, cache: XpubCache?, mode: XpubScan.Mode, deadline: Long?): Pair<XpubScan, Fetched> {
    val lists = listOf(ArrayList(cache?.receive.orEmpty()), ArrayList(cache?.change.orEmpty()))
    val scan = XpubScan(mode, cache)
    var provider = AddressBalance.providerName(AddressBalance.BTC_HOSTS.first())
    while (true) {
        val target = scan.next() ?: break
        val list = lists[target.chain]
        if (list.size <= target.index) {
            withContext(Dispatchers.Default) {
                while (list.size <= target.index) list += deriver.address(target.chain, list.size)
            }
        }
        val address = list[target.index]
        if (scan.requests > 1) delay(if (deadline == null) XPUB_PAUSE_MILLIS else XPUB_PAUSE_BACKGROUND_MILLIS)
        val (stats, host) = firstWorking(AddressBalance.BTC_HOSTS, deadline) { h ->
            AddressBalance.parseBtc(client.callMarket(AddressBalance.btcUrl(h, address), null), address)
        }
        provider = host
        scan.report(target, stats.txCount, stats.totalSats)
    }
    val balance = AddressBalance.unitsText(scan.totalSats.toString(), AddressChain.BTC.decimals)
    return scan to Fetched(balance, provider, xpub = scan.cache(lists[0], lists[1], System.currentTimeMillis()))
}
