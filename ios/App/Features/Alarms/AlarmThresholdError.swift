import SwiftUI

/// Fehlertext unter dem Schwellwert-Feld (`AlarmDraft.thresholdProblem`): erklärt, warum der Knopf
/// gesperrt ist; nil = gültig (nichts). Wie Android `thresholdErrorText`.
struct AlarmThresholdError: View {
    let problem: ThresholdProblem?

    var body: some View {
        if let key = Self.key(problem) {
            Label {
                Text(L(key))
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
        }
    }
}
