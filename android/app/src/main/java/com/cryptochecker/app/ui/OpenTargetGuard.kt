package com.cryptochecker.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ziel eines Aufrufs ([MainActivity.EXTRA_OPEN]) nur von der App selbst (seit Build 97): `MainActivity` ist exportiert,
 * jede andere App könnte sie mit einem Ziel starten — etwa direkt in den Portfolio-Tab. Darum tragen eigene
 * PendingIntents (Mitteilungen, Widgets) ein Geheimnis dieser Installation mit ([EXTRA_TOKEN]); ohne passendes Geheimnis
 * gelten nur die harmlosen Ziele der statischen App-Verknüpfungen (res/xml/shortcuts.xml, die kein dynamisches
 * Geheimnis tragen können) und «Paar öffnen» (wie [MainActivity.EXTRA_WATCH_ID]). Die Portfolio-Sperre bleibt
 * unabhängig davon. Gleiche Regel wie iOS `OpenTargetGuard`.
 */
object OpenTargetGuard {

    /** Extra mit dem Geheimnis; gesetzt von [withOpenToken]. */
    const val EXTRA_TOKEN = "open_token"

    /** Eigene Datei, nie gesichert (nicht in den Backup-Regeln eingeschlossen): gilt nur auf diesem Gerät. */
    private const val PREFS = "open_target"
    private const val KEY_TOKEN = "token"

    /** Ziele, die auch ohne Geheimnis gelten (App-Verknüpfungen; nur Navigation zu nicht geschützten Bereichen). */
    val PUBLIC_TARGETS = setOf("add", "alarms", "cycle")

    @Volatile
    private var cached: String? = null

    /**
     * Gilt [target]? Öffentliche Ziele ([PUBLIC_TARGETS], «Paar öffnen») immer, alle anderen (Portfolio, Markt-Termine,
     * «Was bewegt sich mit?», Alarm bearbeiten) nur mit dem Geheimnis [expected] in [token]. Rein, getestet.
     */
    fun accepts(target: String, token: String?, expected: String): Boolean =
        isPublic(target) || (token != null && expected.isNotEmpty() && sameToken(token, expected))

    /** Ohne Geheimnis erlaubt: Verknüpfungs-Ziele und «Paar öffnen» (`watch/<id>`). */
    fun isPublic(target: String): Boolean =
        target in PUBLIC_TARGETS ||
            (target.startsWith(WATCH_PREFIX) && (target.removePrefix(WATCH_PREFIX).toLongOrNull() ?: 0L) > 0L)

    /** Wie [MainActivity.openWatchTarget] (hier wiederholt, damit die Regel ohne Activity-Klasse prüfbar bleibt). */
    private const val WATCH_PREFIX = "watch/"

    /** Vergleich in konstanter Zeit (verrät über die Laufzeit nichts vom Geheimnis). */
    private fun sameToken(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Ziel aus [intent] oder null, wenn es ohne passendes Geheimnis kam. */
    fun acceptedTarget(context: Context, intent: Intent, target: String): String? =
        target.takeIf { accepts(it, intent.getStringExtra(EXTRA_TOKEN), token(context)) }

    /** Geheimnis dieser Installation; beim ersten Mal erzeugt (128 Bit Zufall) und gespeichert. */
    @Synchronized
    fun token(context: Context): String {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null) ?: run {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            // sofort geschrieben (nicht verzögert): das Geheimnis steckt gleich in Mitteilungen und Widgets
            bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit(commit = true) { putString(KEY_TOKEN, it) } }
        }
        cached = token
        return token
    }
}

/** Geheimnis für [MainActivity.EXTRA_OPEN] anhängen — an jeden eigenen Intent mit einem Ziel. */
fun Intent.withOpenToken(context: Context): Intent = putExtra(OpenTargetGuard.EXTRA_TOKEN, OpenTargetGuard.token(context))
