import Foundation
import os
import WidgetKit

// Arbeit eines Laufs der Hintergrund-Aufgabe (Teil von `BackgroundRefresh.handleRefresh`, eigene Datei): Kurse holen und
// speichern (`runWork`), danach melden, Widgets, Portfolio, Alarme mit Zusatzdaten und Nebenprüfungen (`finishRun`).

extension BackgroundRefresh {
    /// Kurse holen und der Reihe nach speichern (`PriceWriteGate`); abgebrochen oder abgelaufen: nicht melden, Ende
    /// melden. Sonst weiter mit `finishRun`.
    @MainActor
    static func runWork(seams: BackgroundRunSeams, startedAt: Int64, zoneDue: Bool, logGate: OnceGate,
                        priceBox: CheckResultBox, completion: BackgroundTaskCompletion) async {
        let settings = SharedStorage.loadSettings()
        // Datei-Arbeit (Abstimmung, AES, JSON) abseits des Hauptfadens (`offMain`)
        let snapshot = await offMain { SharedStorage.loadSnapshot() }
        // Zeitbudget: iOS gibt der Aufgabe nur rund 30 s. Eine Endzeit gilt für ALLE Schritte (Kurse,
        // Tages-Bezüge, Lease, Alarm-Daten, Nebenprüfungen). Reihenfolge nach Wichtigkeit
        // (`RefreshBudget.Step`): Kurse und Kursalarme speichern und melden, dann Portfolio (braucht nur
        // Kurse und Devisen), dann Alarme mit Zusatzdaten, Fear & Greed (wenn fällig), Aktivität, Gas — jeweils nur, solange Zeit
        // bleibt. Sonst endet alles im Ablauf, nichts ist gespeichert, und iOS gibt künftig weniger Hintergrundzeit.
        let budget = RefreshBudget(start: EpochTime.nowMillis)
        let deadline = budget.deadline
        let outcome = await seams.refresh(snapshot, settings, deadline)
        // Speichern der Reihe nach mit den Schreibern der App (`PriceWriteGate`: Aktualisierung, Live-Kurse).
        // Von iOS abgebrochen (auch während des Wartens aufs Gate): abgebrochene Anfragen nicht als Fehler speichern.
        let gate = PriceWriteGate.shared
        let entered = await gate.acquire()
        if Task.isCancelled || !entered {
            if entered { gate.release() }
            // Nicht gespeichert → auch nichts melden; die Alarme bleiben scharf.
            outcome.releaseAlarmLease()
            if logGate.claim() {
                await CheckRunStore.logDetached(.bgTask, .failed, startedAt: startedAt,
                                                reason: CheckRunLog.cancelledReason(nil)).value
            }
            completion.complete(success: false)
            return
        }
        // 1) Kurse und Kursalarme ZUERST speichern. Frisch lesen und nur Kurse/Alarm-Zustand je Id
        // übertragen — als ein abgestimmter Schritt mit Widget und App (der Nutzer könnte inzwischen
        // etwas geändert haben). Unlesbare oder gerade nicht zugreifbare Datei: nichts schreiben.
        let saved = await offMain { SharedStorage.updateSnapshot { outcome.apply(to: &$0) } }
        gate.release()
        let current = saved ?? snapshot
        // Ergebnis für «Letzte Prüfungen»: Kurse geholt, aber nicht gespeichert zählt als fehlgeschlagen
        let notSaved = saved == nil && outcome.checked > 0
        let priceResult: CheckResult = notSaved ? .failed : outcome.checkResult
        let priceReason: String? = notSaved ? CheckRunLog.reasonNotSaved : nil
        priceBox.set(priceResult)
        countFailures(outcome, saved: saved != nil)
        await seams.afterPriceSave()
        // Während oder nach dem Speichern abgelaufen (Ende schon gemeldet) → nicht mehr melden; die
        // Kurse bleiben gespeichert, die Alarme scharf (wie Android: abgebrochener Lauf meldet nicht). Einmal gelesen,
        // damit «Alarme geprüft» und Melden zusammenpassen
        let expired = Task.isCancelled
        let fullCheck = markRefreshed(outcome, saved: saved != nil, expired: expired)
        if let report = outcome.report { SharedStorage.lastRefreshReport = report }
        // Mit dieser %-Basis gerechnet (Änderung, Widgets und Live-Aktivität prüfen das)
        if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
        if expired {
            outcome.releaseAlarmLease()
            if logGate.claim() {
                await CheckRunStore.logDetached(.bgTask, priceResult, startedAt: startedAt, reason: priceReason).value
            }
            completion.complete(success: false)
            return
        }
        await finishRun(outcome, saved: saved, current: current, settings: settings, deadline: deadline,
                        fullCheck: fullCheck, zoneDue: zoneDue, seams: seams,
                        log: RunLog(gate: logGate, startedAt: startedAt, result: priceResult, reason: priceReason),
                        completion: completion)
    }

    /// Eintrag in «Letzte Prüfungen» am Ende eines Laufs (genau einer je Lauf, `OnceGate`).
    struct RunLog {
        let gate: OnceGate
        let startedAt: Int64
        let result: CheckResult
        let reason: String?
    }

    /// Nach dem Speichern: Alarme melden, Widgets und Live-Aktivität, Portfolio, Alarme mit Zusatzdaten und
    /// Nebenprüfungen (je nur mit Restzeit), dann alles auf die Platte und das Ende melden.
    @MainActor
    static func finishRun(_ outcome: PriceRefresher.Outcome, saved: SharedStorage.Snapshot?,
                          current: SharedStorage.Snapshot, settings: AppSettings, deadline: RefreshDeadline,
                          fullCheck: Bool, zoneDue: Bool, seams: BackgroundRunSeams, log: RunLog,
                          completion: BackgroundTaskCompletion) async {
        let logGate = log.gate, startedAt = log.startedAt, priceResult = log.result, priceReason = log.reason
        await deliverAlarms(outcome, saved: saved, settings: settings, seams: seams)
        await AppData.shared.reloadFromDisk()
        // Widgets nur neu laden, wenn sich der gespeicherte Stand geändert hat (Kurse, Fehler, %-Basis) — sonst
        // verbraucht jeder Lauf ohne Ergebnis (offline, alles pausiert) Teile des Widget-Budgets
        if saved != nil && (outcome.checked > 0 || outcome.changeStampChanged) {
            WidgetCenter.shared.reloadAllTimelines()
        }
        // Live-Aktivität (Sperrbildschirm) mit dem neuen Kurs
        await LiveActivityController.update(watches: current.watches)

        // 2) Portfolio-Widget: eigener Stand, damit es nicht vom Öffnen der App abhängt — gleich nach den
        // Kursalarmen (braucht nur Kurse und Devisen), vor den Alarmen mit Zusatzdaten (nur mit Restzeit,
        // höchstens bis zur Endzeit; ohne Ergebnis keine Portfolio-Alarme diesmal)
        await portfolioStep(deadline: deadline,
                            extraAlarmsWaiting: saved != nil && !outcome.deferredAlarms.isEmpty)
        // 3) Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) — nur mit Restzeit;
        // was nicht rechtzeitig kommt, bleibt diese Runde ungeprüft (Alarm bleibt scharf).
        if saved != nil && !outcome.deferredAlarms.isEmpty && !Task.isCancelled
            && RefreshBudget.shouldRun(.extraAlarms, remaining: deadline.remaining()) {
            await deferredAlarmsStep(outcome, settings: settings, deadline: deadline, fullCheck: fullCheck,
                                     seams: seams)
        }
        // Depeg, Fear & Greed (wenn fällig), Aktivität und Gas — je nur mit Restzeit
        await sideChecks(settings: settings, deadline: deadline, zoneDue: zoneDue, watches: current.watches)
        // Alles Geschriebene auf die Platte, bevor iOS die App wieder anhält — abgewartet, ohne den Hauptfaden zu
        // blockieren
        await SharedStorage.snapshotWritesSettled()
        if logGate.claim() {
            await CheckRunStore.logDetached(.bgTask, priceResult, startedAt: startedAt, reason: priceReason).value
        }
        let elapsed = EpochTime.nowMillis - startedAt
        let checked = outcome.checked, failed = outcome.failed
        AppLog.background.info("""
            BG-Aufgabe beendet nach \(elapsed, privacy: .public) ms, geprüft \(checked, privacy: .public), \
            fehlgeschlagen \(failed, privacy: .public)
            """)
        completion.complete(success: !Task.isCancelled)
    }
}
