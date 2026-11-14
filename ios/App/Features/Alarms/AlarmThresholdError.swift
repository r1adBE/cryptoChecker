import SwiftUI

/// Fehlertext unter dem Schwellwert-Feld (`AlarmDraft.thresholdProblem`): erklärt, warum der Knopf
/// gesperrt ist; nil = gültig (nichts). Wie Android `thresholdErrorText`.
struct AlarmThresholdError: View {
    let problem: ThresholdProblem?
    /// Bei `.AMBIGUOUS`: die zwei eindeutigen Schreibweisen («1234 oder 1,234?»), wie Android `thresholdErrorText`.
    var ambiguity: ThresholdParser.Ambiguity? = nil

    var body: some View {
        if let key = Self.key(problem) {
            Label {
                Text(problem == .AMBIGUOUS && ambiguity != nil
                     ? L("number_ambiguous", ambiguity?.grouped ?? "", ambiguity?.decimal ?? "") : L(key))
            } icon: {
                Image(systemName: "exclamationmark.circle")
            }
            .font(.footnote)
            .foregroundStyle(AppColors.error)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    nonisolated static func key(_ problem: ThresholdProblem?) -> String? {
        switch problem {
        case .none: return nil
        case .some(.EMPTY): return "alarm_threshold_error_empty"
        case .some(.INVALID): return "alarm_threshold_error_invalid"
        case .some(.FUNDING_RANGE): return "alarm_threshold_error_funding"
        case .some(.OI_RANGE): return "alarm_threshold_error_oi"
        case .some(.PERCENT_RANGE): return "alarm_threshold_error_percent_down_below" // «unter 100»
        case .some(.PERCENT_MAX): return "alarm_threshold_error_percent_max" // «höchstens 1000»
        case .some(.DISTANCE_RANGE): return "alarm_threshold_error_distance" // «unter 100»
        // Mit Schreibweisen: «number_ambiguous» (body); ohne: allgemein
        case .some(.AMBIGUOUS): return "alarm_threshold_error_invalid"
        }
    }
}
