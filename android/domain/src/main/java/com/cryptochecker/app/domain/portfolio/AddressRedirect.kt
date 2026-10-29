package com.cryptochecker.app.domain.portfolio

/**
 * Weiterleitungen der Adressabfragen (beobachtete Adressen, xpub, eigener Server): Die Adresse steht im Pfad oder im
 * Inhalt der Anfrage. Folgte der Client einer Weiterleitung auf einen anderen Host, ginge sie samt IP-Adresse an einen
 * Dritten, den der Nutzer nie gewählt hat. Darum nur auf denselben Host (Gross-/Kleinschreibung egal) und nie von
 * HTTPS auf HTTP; sonst gilt die Weiterleitung als Antwort (HTTP-Fehler, nächster Host). Gleich wie iOS
 * `AddressRedirect.allowed`; Fälle in `testdata/parity/address_privacy.json` («redirectCases»).
 */
object AddressRedirect {
    /** Höchstens so viele Weiterleitungen je Anfrage. */
    const val MAX_HOPS = 5

    fun allowed(fromScheme: String?, fromHost: String?, toScheme: String?, toHost: String?): Boolean {
        if (fromHost.isNullOrEmpty() || toHost.isNullOrEmpty()) return false
        if (!fromHost.equals(toHost, ignoreCase = true)) return false
        val from = fromScheme?.lowercase()
        val to = toScheme?.lowercase()
        if (to != "https" && to != "http") return false
        return !(from == "https" && to == "http")
    }
}
