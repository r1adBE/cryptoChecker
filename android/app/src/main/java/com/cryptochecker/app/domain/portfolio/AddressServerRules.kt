package com.cryptochecker.app.domain.portfolio

/**
 * Regeln rund um den eigenen Bitcoin-Server ([AddressServer]) für Einstellungen und Abrufe: Eingabe kürzen, Pausen-Schlüssel,
 * Sperr-Ausnahme, Testergebnis zur Eingabe und Rückfrage beim Wechsel auf öffentliche Knoten. Reines Kotlin, gleich wie
 * `AddressServerRules.swift` (Fixture address_server_ui.json).
 */
object AddressServerRules {

    /** Zu lange Eingabe (z. B. eingefügt) wird gekürzt statt verworfen; die Prüfung meldet dann «ungültig». */
    fun clamp(input: String): String = input.take(AddressServer.MAX_LENGTH)

    /** Schlüssel für Pausen und Sperren: Hostname ohne Schema, Port und Pfad. */
    fun hostKey(entry: String): String = entry.removePrefix("https://").substringBefore('/').substringBefore(':')

    /**
     * Bitcoin-Einträge sind der eigene Server (volle Basis mit https://, öffentliche Hosts stehen ohne Schema): kein
     * 6-h-Sperren nach 403/451 — der Nutzer betreibt ihn selbst und hat keinen Rückfall; nur die kurze Pause gilt.
     */
    fun isOwnServer(entries: List<String>): Boolean = entries.any { it.startsWith("https://") }

    /** Ergebnis von «Testen» nur anzeigen, wenn es zur aktuell geprüften Eingabe gehört. */
    fun testShown(testedUrl: String?, inputUrl: String?): Boolean = testedUrl != null && testedUrl == inputUrl

    /**
     * Rückfrage vor dem Wechsel vom eigenen Server ([current]) auf öffentliche Knoten ([next] = null), wenn
     * Bitcoin-Adressen oder xpub beobachtet werden ([btcCount]): diese gingen danach an die öffentlichen Hosts.
     */
    fun confirmPublicSwitch(current: String?, next: String?, btcCount: Int): Boolean =
        current != null && next == null && btcCount > 0

    /** Anzahl beobachteter Bitcoin-Einträge (Adressen und xpub). */
    fun btcCount(addresses: List<WatchedAddress>): Int = addresses.count { it.chain == AddressChain.BTC }
}
