import Foundation

/// «Speichern» eines Blatts, das erst nach der Rückfrage «nicht gespeichert» schliesst. Der Aufrufer
/// fragt einmal (`AppData.withWatchlistConfirm`), schreibt `Value` und ruft dann `close`; nach «Abbrechen» `cancelled`
/// — das Blatt bleibt mit den Eingaben offen. Wie Android (Dialog schliesst erst in `onDone`).
typealias ConfirmedFinish<Value> = @MainActor (Value, _ close: @escaping @MainActor () -> Void,
                                               _ cancelled: @escaping @MainActor () -> Void) -> Void
