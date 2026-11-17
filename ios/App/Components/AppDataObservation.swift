import SwiftUI

/// `AppData` aus der Umgebung (`.environment(data)`) für eine Ansicht — Ersatz für `@EnvironmentObject`. Liest
/// `AppData.revision` bei jedem Zugriff mit: Eine Veröffentlichung (`AppData.publish`) baut so jede Ansicht neu auf, die
/// im `body` auf `AppData` zugreift, wie früher mit `ObservableObject`. `$data.settings.…` ergibt eine Bindung.
@MainActor
@propertyWrapper
struct AppDataEnv: DynamicProperty {
    @Environment(AppData.self) private var data

    var wrappedValue: AppData {
        _ = data.revision
        return data
    }

    var projectedValue: Bindable<AppData> { Bindable(wrappedValue) }
}

/// Wie [AppDataEnv], aber übergeben statt aus der Umgebung — Ersatz für `@AppDataRef var data: AppData`.
@MainActor
@propertyWrapper
struct AppDataRef: DynamicProperty {
    private let data: AppData

    init(wrappedValue: AppData) {
        data = wrappedValue
    }

    var wrappedValue: AppData {
        _ = data.revision
        return data
    }

    var projectedValue: Bindable<AppData> { Bindable(wrappedValue) }
}
