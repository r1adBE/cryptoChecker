import AudioToolbox
import AVFoundation
import SwiftUI

/// Alarmton (#202): Standardton oder einer der mitgelieferten Töne.
/// Beim Auswählen wird der Ton kurz vorgespielt. iOS erlaubt für
/// Mitteilungen keine eigenen Audiodateien.
@MainActor
struct AlarmSoundRow: View {
    let selection: String?
    let onSelect: (String?) -> Void

    @Environment(\.appAccent) private var accent

    var body: some View {
        Menu {
            Button {
                pick(nil)
            } label: {
                if selection == nil { Label(L("settings_alarm_sound_default"), systemImage: "checkmark") }
                else { Text(L("settings_alarm_sound_default")) }
            }
            ForEach(AlarmSounds.tones) { tone in
                Button {
                    pick(tone.id)
                } label: {
                    if selection == tone.id { Label(tone.name, systemImage: "checkmark") }
                    else { Text(tone.name) }
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "music.note")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("settings_alarm_sound"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    Text(AlarmSounds.tone(selection)?.name ?? L("settings_alarm_sound_default"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func pick(_ id: String?) {
        onSelect(id)
        AlarmSoundPreview.play(id)
    }
}

/// Spielt einen Alarmton zur Probe ab.
@MainActor
enum AlarmSoundPreview {
    private static var player: AVAudioPlayer?

    static func play(_ id: String?) {
        guard let tone = AlarmSounds.tone(id),
              let url = Bundle.main.url(forResource: tone.id, withExtension: "wav") else {
            // Standardton: kurzer Systemklang
            AudioServicesPlaySystemSound(1007)
            return
        }
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
    }
}
