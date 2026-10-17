package com.cryptochecker.app.widget

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Versiegelung der Momentaufnahme des Portfolio-Widgets (Build 60, reines Kotlin — getestet in
 * PortfolioSnapshotSealTest). Gesamtwert, Positionen, Gewinn/Verlust und gehaltene Coins liegen nicht mehr lesbar in
 * den SharedPreferences `portfolio_widget`, sondern als ein mit AES-256-GCM versiegelter Eintrag; den Schlüssel hält
 * der Android Keystore ([Keys], PortfolioSnapshotKeyStore — eigener Alias, unabhängig von den Datenbank-Schlüsseln).
 *
 * Format (Base64 im Eintrag): 4 Bytes [MAGIC] «CCWS», 1 Byte [VERSION], 12 Bytes IV, Chiffrat samt 16-Byte-Tag.
 * Magie und Version sind als AAD mitauthentifiziert (wie iOS `ProtectedContainer`): ein umgeschriebener Kopf fällt
 * beim Öffnen auf.
 *
 * Regeln ([planRead], [planWrite]): Klartext älterer Versionen wird einmal gelesen und versiegelt neu geschrieben;
 * ohne Schlüssel zeigt das Widget «keine Daten» und es wird nie Klartext geschrieben — schon gar nicht über einen
 * versiegelten Eintrag. Die Aufnahme ist ein Zwischenspeicher (die App berechnet sie neu), darum darf ein
 * unlesbarer Eintrag beim nächsten Speichern ersetzt werden.
 */
object PortfolioSnapshotSeal {

    /** Ergebnis der Schlüsselsuche. */
    sealed interface KeyLookup {
        class Found(val key: SecretKey) : KeyLookup
        /** Keystore gerade nicht erreichbar (beschäftigt, gesperrt): nichts lesen, nichts schreiben. */
        object Unavailable : KeyLookup
    }

    /** Schlüsselquelle; auf Android der Keystore (PortfolioSnapshotKeyStore), in Tests ein Software-Schlüssel. */
    interface Keys {
        /** Vorhandener oder neu erzeugter Schlüssel. */
        fun lookup(): KeyLookup

        /** Nach einem Fehler beim Versiegeln: endgültig kaputten Schlüssel verwerfen (nächste Suche erzeugt neu). */
        fun discardIfBroken(error: Throwable)
    }

    /** Was beim Lesen zu tun ist. */
    sealed interface ReadPlan {
        /** Versiegelten Eintrag öffnen (Klartext daneben ist ein Rest und wird beim nächsten Speichern entfernt). */
        class Open(val sealed: String) : ReadPlan
        /** Nur Klartext einer älteren Version: lesen, versiegelt neu schreiben, Klartext entfernen. */
        object MigratePlain : ReadPlan
        /** Nichts anzeigen (Leer-Zustand des Widgets, `widget_portfolio_empty`): keine Aufnahme oder Schlüssel nicht verfügbar. */
        object Empty : ReadPlan
    }

    fun planRead(sealed: String?, hasPlain: Boolean, keyReady: Boolean): ReadPlan = when {
        !keyReady -> ReadPlan.Empty
        sealed != null -> ReadPlan.Open(sealed)
        hasPlain -> ReadPlan.MigratePlain
        else -> ReadPlan.Empty
    }

    /** Was beim Speichern zu tun ist; Klartext älterer Versionen wird in beiden Fällen entfernt. */
    enum class WritePlan {
        /** Neue Aufnahme versiegelt speichern. */
        SEAL,
        /** Ohne Schlüssel: nichts schreiben, ein vorhandener versiegelter Eintrag bleibt unverändert. */
        KEEP_SEALED,
    }

    fun planWrite(keyReady: Boolean): WritePlan = if (keyReady) WritePlan.SEAL else WritePlan.KEEP_SEALED

    /** [payload] (UTF-8) versiegeln; der IV kommt vom Cipher (Keystore: zufällig, randomizedEncryptionRequired). */
    fun seal(key: SecretKey, payload: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "Unerwartete IV-Länge ${iv?.size}" }
        cipher.updateAAD(header())
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(HEADER_BYTES + IV_BYTES + ciphertext.size)
            .put(header()).put(iv).put(ciphertext)
        return Base64.getEncoder().encodeToString(out.array())
    }

    /**
     * Gegenstück zu [seal]. null = kein Behälter dieser Version (fremd, gekürzt, Version einer neueren App) oder Tag
     * falsch (anderer Schlüssel, verändert) — für das Widget «keine Daten». Andere Keystore-Fehler werfen.
     */
    fun open(key: SecretKey, stored: String): String? {
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (e: IllegalArgumentException) {
            return null // kein Base64: kein versiegelter Eintrag
        }
        if (!isContainer(bytes)) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, bytes, HEADER_BYTES, IV_BYTES))
        cipher.updateAAD(header())
        val plain = try {
            cipher.doFinal(bytes, HEADER_BYTES + IV_BYTES, bytes.size - HEADER_BYTES - IV_BYTES)
        } catch (e: AEADBadTagException) {
            return null // anderer Schlüssel oder verändert: wie keine Aufnahme, die nächste ersetzt sie
        }
        return String(plain, Charsets.UTF_8)
    }

    /** Kopf und Mindestlänge passen zu [VERSION]. */
    fun isContainer(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_BYTES + IV_BYTES + TAG_BYTES &&
            bytes.copyOfRange(0, HEADER_BYTES).contentEquals(header())

    private fun header(): ByteArray = MAGIC + VERSION

    val MAGIC: ByteArray get() = byteArrayOf(0x43, 0x43, 0x57, 0x53) // «CCWS»
    const val VERSION: Byte = 1
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
    private const val HEADER_BYTES = 5
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
