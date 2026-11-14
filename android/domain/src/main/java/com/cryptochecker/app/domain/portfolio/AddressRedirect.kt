package com.cryptochecker.app.domain.portfolio

/**
 * Weiterleitungen der Adressabfragen (beobachtete Adressen, xpub, eigener Server): Die Adresse steht im Pfad oder im
 * Inhalt der Anfrage. Folgte der Client einer Weiterleitung auf einen anderen Host, ginge sie samt IP-Adresse an einen
 * Dritten, den der Nutzer nie gewählt hat. Darum nur auf denselben Host (Gross-/Kleinschreibung egal) und Port und nie
 * von HTTPS auf HTTP; sonst gilt die Weiterleitung als Antwort (HTTP-Fehler, nächster Host). Gleich wie iOS
 * `AddressRedirect.allowed`; Fälle in `testdata/parity/address_privacy.json` («redirectCases»).
 */
object AddressRedirect {
    /** Höchstens so viele Weiterleitungen je Anfrage. */
    const val MAX_HOPS = 5

    /**
     * [fromPort]/[toPort]: Port der Adresse, -1 = keiner angegeben (Standard des Schemas). Auch der Port muss gleich
     * bleiben — sonst ginge die Adresse an einen anderen Dienst desselben Hosts; einzig HTTP (80) auf HTTPS (443) ist ein
     * Wechsel auf demselben Host.
     */
    fun allowed(
        fromScheme: String?,
        fromHost: String?,
        toScheme: String?,
        toHost: String?,
        fromPort: Int = -1,
        toPort: Int = -1,
    ): Boolean {
        if (fromHost.isNullOrEmpty() || toHost.isNullOrEmpty()) return false
        if (!fromHost.equals(toHost, ignoreCase = true)) return false
        val from = fromScheme?.lowercase()
        val to = toScheme?.lowercase()
        if (to != "https" && to != "http") return false
        if (from == "https" && to == "http") return false
        val a = port(from, fromPort)
        val b = port(to, toPort)
        return a == b || (from == "http" && to == "https" && a == 80 && b == 443)
    }

    private fun port(scheme: String?, port: Int): Int = if (port >= 0) port else if (scheme == "http") 80 else 443
}
