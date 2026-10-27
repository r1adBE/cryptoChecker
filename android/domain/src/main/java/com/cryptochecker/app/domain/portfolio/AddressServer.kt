package com.cryptochecker.app.domain.portfolio

/**
 * Eigener Server für Adressabfragen (Einstellungen › Portfolio), nur Bitcoin: Esplora-/mempool-kompatible API, z. B.
 * «https://knoten.example/api» (Abfrage `{basis}/address/{adr}` wie bei mempool.space). Gesetzt: Bitcoin-Adressen und
 * xpub fragen nur dort, ohne Rückfall auf öffentliche Knoten (sonst sähen diese die Adressen doch); leer: wie bisher
 * [AddressBalance.BTC_HOSTS]. Andere Netze bleiben bei den öffentlichen Knoten: Litecoin, XRP, TON, TRON, Solana und
 * Cardano haben kein verbreitetes selbst betriebenes Standardformat in der App, und eine 0x-Adresse geht an mehrere
 * EVM-Netze (dafür bräuchte es je Netz einen eigenen Knoten).
 * Erlaubt: nur https, Host (Name oder IPv4) mit optionalem Port und Pfad; kein Benutzer/Passwort, keine Abfrage, kein
 * Fragment, nur sichtbare ASCII-Zeichen. Reines Kotlin, gleich wie `AddressServer.swift` (Fixture address_server.json).
 */
object AddressServer {

    /** Längste erlaubte Eingabe (Zeichen). */
    const val MAX_LENGTH = 200

    /** Gespeicherter Fehler einer Bitcoin-Adresse, wenn der eigene Server nicht liefert (sprachunabhängig). Wie iOS. */
    const val FAILED_MARKER = "OWN_SERVER_FAILED"

    enum class Problem { NOT_HTTPS, USER_INFO, QUERY_OR_FRAGMENT, INVALID }

    /** Ergebnis der Prüfung: [url] normalisiert (null bei leerer Eingabe = öffentliche Knoten oder bei [problem]). */
    data class Check(val url: String?, val problem: Problem?)

    fun check(input: String): Check {
        val text = input.trim()
        if (text.isEmpty()) return Check(null, null)
        if (text.length > MAX_LENGTH || text.any { it.code !in 0x21..0x7e }) return invalid(Problem.INVALID)
        if (!text.startsWith("https://", ignoreCase = true)) return invalid(Problem.NOT_HTTPS)
        val rest = text.substring(SCHEME.length)
        if ('?' in rest || '#' in rest) return invalid(Problem.QUERY_OR_FRAGMENT)
        val authority = rest.substringBefore('/')
        if ('@' in authority) return invalid(Problem.USER_INFO)
        val path = rest.substring(authority.length).trimEnd('/')
        if (path.any { !(it.isLetterOrDigit() || it in PATH_EXTRA) }) return invalid(Problem.INVALID)
        val parts = authority.split(':')
        if (parts.size > 2) return invalid(Problem.INVALID)
        val host = parts[0].lowercase()
        if (!validHost(host)) return invalid(Problem.INVALID)
        val port = if (parts.size == 2) parsePort(parts[1]) ?: return invalid(Problem.INVALID) else null
        val portText = port?.takeIf { it != 443 }?.let { ":$it" } ?: ""
        return Check(SCHEME + host + portText + path, null)
    }

    /** Hosts für Bitcoin-Abfragen: nur der eigene Server, sonst die öffentlichen. */
    fun btcEntries(server: String?): List<String> = server?.let { listOf(it) } ?: AddressBalance.BTC_HOSTS

    /** Adress-URL: eigener Server (volle Basis mit https://) oder öffentlicher Host. */
    fun btcUrl(entry: String, address: String): String =
        if (entry.startsWith(SCHEME)) "$entry/address/$address" else AddressBalance.btcUrl(entry, address)

    /** «Testen»: aktuelle Blockhöhe (Esplora `GET /blocks/tip/height`, nennt keine Adresse). */
    fun testUrl(server: String): String = "$server/blocks/tip/height"

    /** Antwort von [testUrl]: Blockhöhe > 0, sonst null (kein Esplora-Server). */
    fun parseTipHeight(body: String): Long? = body.trim().takeIf { it.length in 1..9 }?.toLongOrNull()?.takeIf { it > 0 }

    /** Host für Hinweise («knoten.example»). */
    fun host(server: String): String = ChainBalance.host(server)

    /**
     * Zustimmung vor dem Hinzufügen: vor der ersten Adresse überhaupt und vor dem ersten xpub/Deskriptor (alle
     * abgeleiteten Adressen gehen an denselben Knotenbetreiber).
     */
    fun consentNeeded(addressCount: Int, xpubCount: Int, newIsKey: Boolean): Boolean =
        addressCount == 0 || (newIsKey && xpubCount == 0)

    /** Wie oben für die Liste und die geprüfte Eingabe (xpub/ypub/zpub/Taproot-Konto gelten als Schlüssel). */
    fun consentNeeded(addresses: List<WatchedAddress>, check: AddressValidation.Check): Boolean =
        consentNeeded(addresses.size, addresses.count { it.isXpub }, check.kind in KEY_KINDS)

    /**
     * Wert aus einer Sicherung: fehlt der Schlüssel, bleibt [current]; ungültig: bleibt [current]; leer/null löscht.
     * Nur eine Sicherung mit Passwort ([trusted]) ändert ihn: Eine Datei ohne Echtheitsprüfung setzt, ersetzt und
     * löscht nie (sonst gingen die Bitcoin-Adressen an einen fremden Server bzw. an öffentliche Knoten).
     */
    fun restored(present: Boolean, value: String?, current: String?, trusted: Boolean): String? {
        if (!present || !trusted) return current
        if (value.isNullOrBlank()) return null
        val c = check(value)
        return if (c.problem == null) c.url else current
    }

    private const val SCHEME = "https://"
    private const val PATH_EXTRA = "/-._~"
    private val KEY_KINDS = setOf(
        AddressValidation.Kind.XPUB, AddressValidation.Kind.YPUB, AddressValidation.Kind.ZPUB, AddressValidation.Kind.TR_XPUB,
    )

    private fun invalid(problem: Problem) = Check(null, problem)

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || text.any { it !in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    /** Name (Teile aus a–z, 0–9, «-», nicht am Rand) oder IPv4 (vier Zahlen 0–255 ohne führende Null). */
    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (host.all { it in '0'..'9' || it == '.' }) {
            return labels.size == 4 && labels.all { l ->
                l.length in 1..3 && (l.length == 1 || l[0] != '0') && l.toInt() <= 255
            }
        }
        return labels.all { l ->
            l.length in 1..63 && l.first() != '-' && l.last() != '-' && l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }
}
