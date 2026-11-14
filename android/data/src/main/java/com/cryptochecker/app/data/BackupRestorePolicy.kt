package com.cryptochecker.app.data

import com.cryptochecker.app.domain.portfolio.AddressServer

/**
 * Regeln beim Wiederherstellen (reines Kotlin, testbar; gespiegelt in `BackupRestorePolicy.swift`).
 *
 * Eine Sicherung ohne Passwort hat keine Echtheitsprüfung: Eine bearbeitete Datei im gleichen Format
 * wird angenommen. Darum schaltet sie die Portfolio-Sperre nie aus, wenn sie gerade an ist — und
 * auch sonst nichts in Richtung «weniger Schutz»: «Portfolio in Systemsicherung» nie ein ([systemBackup]),
 * «Beträge verbergen» nie aus ([hideAmounts]), eigener Bitcoin-Server nie gesetzt, ersetzt oder gelöscht
 * ([AddressServer.restored], Hinweis [addressServerNote]).
 *
 * Eine Sicherung mit Passwort darf das alles — ein Passwort beweist aber keine Herkunft (jeder kann eine Datei samt
 * Passwort weitergeben). Darum nie still: Schaltet sie Schutz ab, nennt die Meldung jeden Fall ([loweredProtection],
 * neu gesetzter bzw. ersetzter Server mit Hostnamen: [AddressServerNote.SET], [AddressServerNote.CHANGED],
 * [addressServerHost]).
 */
object BackupRestorePolicy {

    /**
     * Was mit «appLock» aus der Sicherung geschieht: [apply] = neuer Wert (null = unverändert lassen);
     * [keptOn] = die Sicherung wollte die Sperre ausschalten, sie bleibt aber an (Hinweis in der Meldung).
     */
    data class AppLockDecision(val apply: Boolean?, val keptOn: Boolean)

    /**
     * [wanted] = Wert in der Sicherung, [current] = Sperre jetzt, [encrypted] = Sicherung
     * mit Passwort, [canAuthenticate] = Gerät kann entsperren (sonst sperrte «an» das Portfolio aus).
     */
    fun appLock(wanted: Boolean, current: Boolean, encrypted: Boolean, canAuthenticate: Boolean): AppLockDecision =
        when {
            wanted -> AppLockDecision(apply = if (canAuthenticate) true else null, keptOn = false)
            current && !encrypted -> AppLockDecision(apply = null, keptOn = true)
            else -> AppLockDecision(apply = false, keptOn = false)
        }

    /**
     * «Portfolio in Systemsicherung» aus der Sicherung: neuer Wert oder null (unverändert). Ohne Echtheitsprüfung
     * ([trusted] false) nur ausschalten — einschalten gäbe die Portfolio-Daten in die Systemsicherung.
     */
    fun systemBackup(wanted: Boolean, current: Boolean, trusted: Boolean): Boolean? =
        if (wanted && !current && !trusted) null else wanted

    /**
     * «Beträge verbergen» aus der Sicherung: neuer Wert oder null (unverändert). Ohne Echtheitsprüfung
     * ([trusted] false) nur einschalten — ausschalten zeigte die Beträge offen.
     */
    fun hideAmounts(wanted: Boolean, current: Boolean, trusted: Boolean): Boolean? =
        if (!wanted && current && !trusted) null else wanted

    /** Hinweis zum eigenen Bitcoin-Server in der Meldung nach dem Wiederherstellen ([addressServerNote]). */
    enum class AddressServerNote {
        /** Sicherung ohne Passwort hatte einen anderen gültigen Wert (oder keinen): nicht übernommen. */
        KEPT,

        /** Sicherung mit Passwort ohne eigenen Server: der bisherige ist weg, wieder öffentliche Knoten. */
        PUBLIC,

        /** Sicherung mit Passwort mit anderem Server: der bisherige ist ersetzt. */
        CHANGED,

        /** Sicherung mit Passwort setzt einen Server, wo keiner war: Bitcoin-Adressen gehen ab jetzt nur dorthin. */
        SET,
    }

    /**
     * Hinweis zum eigenen Bitcoin-Server ([AddressServer.restored], gleiche Fälle in address_server.json «restore»):
     * null = nichts zu sagen (Wert gleich oder ungültig).
     */
    fun addressServerNote(value: String?, current: String?, trusted: Boolean): AddressServerNote? {
        val wanted = AddressServer.restored(value, current, trusted = true)
        return when {
            wanted == current -> null
            !trusted -> AddressServerNote.KEPT
            current == null -> AddressServerNote.SET
            wanted == null -> AddressServerNote.PUBLIC
            else -> AddressServerNote.CHANGED
        }
    }

    /**
     * Hostname des Servers, an den Bitcoin-Adressen ab jetzt gehen, für die Meldung: neu gesetzt ([AddressServerNote.SET])
     * oder ersetzt ([AddressServerNote.CHANGED]), «knoten.example» ohne Port und Pfad; null in jedem anderen Fall.
     */
    fun addressServerHost(value: String?, current: String?, trusted: Boolean): String? =
        when (addressServerNote(value, current, trusted)) {
            AddressServerNote.SET, AddressServerNote.CHANGED ->
                AddressServer.restored(value, current, trusted)?.let { AddressServer.host(it) }
            else -> null
        }

    /**
     * Schutz, den die Sicherung abgeschaltet hat: [systemBackupOn] = «Portfolio in Systemsicherung» war aus und ist jetzt
     * an, [amountsShown] = «Beträge verbergen» war an und ist jetzt aus, [lockOff] = die Portfolio-Sperre war an und ist
     * jetzt aus. Jeden Fall nennt die Meldung nach dem Wiederherstellen in einem eigenen Satz.
     */
    data class LoweredProtection(
        val systemBackupOn: Boolean = false,
        val amountsShown: Boolean = false,
        val lockOff: Boolean = false,
    ) {
        val any: Boolean get() = systemBackupOn || amountsShown || lockOff
    }

    /**
     * Was die Sicherung an Schutz abschaltet ([systemBackup], [hideAmounts], [appLock]) — ohne Passwort ([trusted] false)
     * nie etwas. [wantedSystemBackup]/[wantedHideAmounts]/[wantedAppLock] = Werte der Sicherung,
     * [currentSystemBackup]/[currentHideAmounts]/[currentAppLock] = bisher.
     */
    fun loweredProtection(
        wantedSystemBackup: Boolean,
        currentSystemBackup: Boolean,
        wantedHideAmounts: Boolean,
        currentHideAmounts: Boolean,
        trusted: Boolean,
        wantedAppLock: Boolean,
        currentAppLock: Boolean,
    ): LoweredProtection = LoweredProtection(
        systemBackupOn = !currentSystemBackup && systemBackup(wantedSystemBackup, currentSystemBackup, trusted) == true,
        amountsShown = currentHideAmounts && hideAmounts(wantedHideAmounts, currentHideAmounts, trusted) == false,
        // Ausschalten hängt nicht davon ab, ob das Gerät entsperren kann
        lockOff = currentAppLock && appLock(wantedAppLock, currentAppLock, trusted, canAuthenticate = true).apply == false,
    )

    /**
     * Nur Android: Gemerkten Stand (Journal) in eine Datenbank zurückschreiben? Nicht, solange sie nur leer im
     * Arbeitsspeicher liegt ([storedKept]: Schlüssel der vorhandenen Datei gerade nicht lesbar; [inMemory]: überhaupt nur
     * im Arbeitsspeicher) — das Geschriebene ginge mit dem Lauf verloren, das Journal muss bleiben.
     */
    fun mayRollBackInto(storedKept: Boolean, inMemory: Boolean): Boolean = !storedKept && !inMemory

    /** Warum Sichern bzw. Einspielen gerade abgelehnt wird ([storageRefusal]). */
    enum class StorageRefusal {
        /** Gespeicherter Stand gerade nicht lesbar: Sichern ergäbe eine leere Datei, Einspielen ginge verloren. */
        KEPT,

        /** Nur im Arbeitsspeicher (kein Schlüssel): Einspielen meldete Erfolg, nach dem Neustart wäre alles weg. */
        IN_MEMORY,
    }

    /**
     * Nur Android: Darf mit diesem Zustand einer Datenbank gesichert ([restoring] false) bzw. eingespielt werden? null = ja.
     * Sichern bleibt im Arbeitsspeicher erlaubt (es nimmt, was gerade zu sehen ist). Einspielen nicht: Die Einstellungen
     * wären schon ersetzt, Merkliste bzw. Portfolio nach dem Neustart aber wieder die alten oder leer — gleiche Regel wie
     * fürs Zurückrollen ([mayRollBackInto]).
     */
    fun storageRefusal(storedKept: Boolean, inMemory: Boolean, restoring: Boolean): StorageRefusal? = when {
        storedKept -> StorageRefusal.KEPT
        restoring && !mayRollBackInto(storedKept, inMemory) -> StorageRefusal.IN_MEMORY
        else -> null
    }

    /**
     * Welche Einträge einer Liste mit den Kennungen [ids] übernommen werden (Transaktionen, Portfolio-Alarme): je Kennung
     * > 0 nur der erste. Weitere mit derselben Kennung (z. B. von Hand zusammengeführte Sicherungen) zählen wie ungültige
     * Einträge — die Rückfrage nennt sie, statt dass der zweite den ersten beim Speichern still ersetzt. Kennung ≤ 0
     * bekommt beim Speichern eine neue und ist nie doppelt. Gleiche Fälle: backup_duplicate_ids.json.
     */
    fun keepFirstIds(ids: List<Long>): List<Boolean> {
        val seen = HashSet<Long>()
        return ids.map { it <= 0L || seen.add(it) }
    }

    /** Ungültige Einträge aus der Systemsicherung, die noch zu melden sind (aufsummiert, ohne Überlauf). */
    fun noticeTotal(pending: Int, added: Int): Int =
        (pending.coerceAtLeast(0).toLong() + added.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
