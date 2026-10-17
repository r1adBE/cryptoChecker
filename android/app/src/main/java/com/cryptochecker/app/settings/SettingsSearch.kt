package com.cryptochecker.app.settings

import java.text.Normalizer
import java.util.Locale

/**
 * Suche in den Einstellungen (Runde 31) — reine Logik, wie `SettingsSearch.swift`.
 *
 * Ein Eintrag ist eine Zeile der Hauptseite oder ein Punkt einer Unterseite: [title] wie
 * angezeigt, [path] die Seite bzw. Gruppe darüber (z. B. «Alarme»), [synonyms] weitere
 * Wörter aus den Hinweistexten darunter. [id] führt die Oberfläche zum Ziel.
 *
 * Verglichen wird ohne Gross-/Kleinschreibung (nach den Regeln der Sprache) und ohne
 * Akzente: «uberwachung» findet «Überwachung», «ALARM» findet «Alarme», «cafe» «Café».
 * Jedes Wort der Suche muss passen; Treffer im Titel stehen vor Treffern im Hinweis.
 */
data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val path: String = "",
    val synonyms: List<String> = emptyList(),
)

object SettingsSearch {

    /** Kürzeste Suchwortlänge für Treffer in Hinweistexten (sonst passt «a» überall). */
    const val MIN_SYNONYM_TOKEN = 2

    // Punkte je Suchwort, höchster zählt
    private const val TITLE_START = 100
    private const val TITLE_WORD = 80
    private const val TITLE_PART = 60
    private const val PATH_WORD = 30
    private const val SYNONYM_WORD = 25
    private const val SYNONYM_PART = 20

    /** Vorbereiteter Eintrag: einmal normalisiert, dann für jede Eingabe wiederverwendet. */
    class Index internal constructor(internal val items: List<Item>, internal val locale: Locale) {
        val size: Int get() = items.size
    }

    internal class Item(
        val entry: SettingsSearchEntry,
        val title: String,
        val titleWords: List<String>,
        val pathWords: List<String>,
        val synonyms: List<String>,
        val synonymWords: List<String>,
    )

    /** Baut den Index; doppelte [SettingsSearchEntry.id] zählen nur beim ersten Mal. */
    fun index(entries: List<SettingsSearchEntry>, locale: Locale): Index {
        val seen = HashSet<String>()
        val items = entries.filter { seen.add(it.id) }.map { entry ->
            val title = normalize(entry.title, locale)
            val synonyms = entry.synonyms.map { normalize(it, locale) }.filter { it.isNotEmpty() }
            Item(
                entry = entry,
                title = title,
                titleWords = words(title),
                pathWords = words(normalize(entry.path, locale)),
                synonyms = synonyms,
                synonymWords = synonyms.flatMap { words(it) },
            )
        }
        return Index(items, locale)
    }

    /** Treffer zu [query], beste zuerst (bei Gleichstand in der Reihenfolge des Index). Leer bei leerer Suche. */
    fun search(index: Index, query: String): List<SettingsSearchEntry> {
        val tokens = tokens(query, index.locale)
        if (tokens.isEmpty()) return emptyList()
        return index.items
            .mapIndexedNotNull { position, item ->
                var total = 0
                for (token in tokens) {
                    val points = score(item, token)
                    if (points == 0) return@mapIndexedNotNull null
                    total += points
                }
                Triple(item.entry, total, position)
            }
            .sortedWith(compareByDescending<Triple<SettingsSearchEntry, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /** Bequem für einmalige Suchen (Tests): Index bauen und suchen. */
    fun search(entries: List<SettingsSearchEntry>, query: String, locale: Locale): List<SettingsSearchEntry> =
        search(index(entries, locale), query)

    /** Suchwörter: normalisiert, an Leerraum getrennt. */
    fun tokens(query: String, locale: Locale): List<String> =
        normalize(query, locale).split(' ').filter { it.isNotEmpty() }

    /**
     * Vergleichsform: Kleinbuchstaben nach [locale] (Türkisch «I» → «ı»), Akzente und
     * Vokalzeichen (Arabisch, Hebräisch) entfernt, Sonderbuchstaben ausgeschrieben
     * («ß» → «ss», «ø» → «o», «ı» → «i»), Leerraum zu einem Leerzeichen. Japanische
     * Dakuten (が ≠ か) und Schriftzeichen anderer Schriften bleiben unverändert.
     */
    fun normalize(text: String, locale: Locale): String {
        val lower = text.lowercase(locale)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var lastSpace = true
        for (ch in decomposed) {
            // Akzente und Tatweel (arabische Dehnung) tragen nichts zum Vergleich bei
            if (isStrippedMark(ch) || ch == TATWEEL) continue
            if (ch.isWhitespace()) {
                if (!lastSpace) out.append(' ')
                lastSpace = true
                continue
            }
            lastSpace = false
            when (ch) {
                'ß' -> out.append("ss")
                'ı' -> out.append('i')
                'ø' -> out.append('o')
                'æ' -> out.append("ae")
                'œ' -> out.append("oe")
                'đ' -> out.append('d')
                'ł' -> out.append('l')
                else -> out.append(ch)
            }
        }
        return Normalizer.normalize(out.toString().trimEnd(), Normalizer.Form.NFC)
    }

    /** Akzente (U+0300–036F), arabische Vokalzeichen, hebräische Punkte. */
    internal fun isStrippedMark(ch: Char): Boolean {
        val c = ch.code
        return c in 0x0300..0x036F || c in 0x064B..0x065F || c == 0x0670 ||
            (c in 0x0591..0x05C7 && Character.getType(ch) == Character.NON_SPACING_MARK.toInt())
    }

    private const val TATWEEL = '\u0640'

    /** Wörter: Folgen aus Buchstaben, Ziffern und Zeichen (Schriften ohne Leerzeichen bleiben am Stück). */
    internal fun words(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit() || Character.getType(ch).let {
                    it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
                }
            ) {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                result.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun score(item: Item, token: String): Int = when {
        item.title.startsWith(token) -> TITLE_START
        item.titleWords.any { it.startsWith(token) } -> TITLE_WORD
        item.title.contains(token) -> TITLE_PART
        item.pathWords.any { it.startsWith(token) } -> PATH_WORD
        token.length < MIN_SYNONYM_TOKEN -> 0
        item.synonymWords.any { it.startsWith(token) } -> SYNONYM_WORD
        item.synonyms.any { it.contains(token) } -> SYNONYM_PART
        else -> 0
    }
}
