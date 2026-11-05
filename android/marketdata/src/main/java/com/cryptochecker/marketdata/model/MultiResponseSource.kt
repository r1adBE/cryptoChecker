package com.cryptochecker.marketdata.model

/**
 * Quelle, deren Kurse aus mehreren Antworten zusammen entstehen (NBP: Tabelle B mit Tabelle A desselben Tags) und lange
 * gleich bleiben (Referenzkurse der Zentralbanken). Die App holt alle [responseAddresses] gemeinsam — eine Anfrage je
 * Adresse und Aktualisierung, egal wie viele Paare —, behält die Antworten bis [validUntil] im Speicher und reicht sie,
 * mit [SEPARATOR] verbunden, als Antwort von Anfrage 0 an die gewöhnliche Einzel- bzw. Sammelabfrage weiter
 * (`MultiResponses` in der App). iOS: `MultiResponseSource`.
 */
interface MultiResponseSource {
    /** GET-Adressen, die zusammen gelesen werden, zum Zeitpunkt [now] (ms). */
    fun responseAddresses(now: Long): List<String>

    /** Bis wann (ms) die Antworten [bodies] gültig bleiben; ≤ [now] = nicht behalten (z. B. unlesbar). */
    fun validUntil(bodies: List<String>, now: Long): Long

    companion object {
        /** Trennzeichen der verbundenen Antworten (ASCII «Record Separator», kommt in JSON nie roh vor). */
        const val SEPARATOR = "\u001E"

        fun join(bodies: List<String>): String = bodies.joinToString(SEPARATOR)

        fun split(body: String): List<String> = body.split(SEPARATOR)
    }
}
