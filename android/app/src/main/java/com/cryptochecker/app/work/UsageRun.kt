package com.cryptochecker.app.work

import com.cryptochecker.marketdata.usage.UsageMeter

/**
 * Hintergrund-Lauf ausserhalb der Kursprüfung (Marktmeldungen, Wirtschaftsdaten, Widget-Abruf): zählt für «Akku &
 * Aktivität» als Lauf und schreibt die Zähler am Ende ([UsageMeter.flush]) — Android kann den Prozess danach beenden.
 * Die Kursprüfung zählt über «Letzte Prüfungen» (`CheckRunStore.add`).
 */
internal inline fun <T> countedRun(block: () -> T): T {
    UsageMeter.shared.countRun()
    try {
        return block()
    } finally {
        UsageMeter.shared.flush()
    }
}
