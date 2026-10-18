#!/usr/bin/env python3
"""Erzeugt die vier Alarmtöne der iOS-App (App/Resources/Sounds/cc_*.wav) rein synthetisch.

Die Töne sind selbst erzeugt (Sinus-Teiltöne mit Hüllkurve, kein Fremdmaterial) und gemeinfrei
(CC0 1.0). Das Skript ist der Herkunftsnachweis: Wer es erneut ausführt, erhält dieselben Dateien.

Format: WAV, 44.1 kHz, mono, 16 bit, unter 30 s (Vorgabe von UNNotificationSound).

Aufruf:  python3 tools/gen_alarm_sounds.py
"""
import math
import os
import struct
import wave

RATE = 44_100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "App", "Resources", "Sounds")


def note(freq, start, length, partials=((1.0, 1.0),), decay=4.0, attack=0.005):
    """Ein Ton: Liste von (Faktor, Lautstärke)-Teiltönen, exponentiell abklingend."""
    return (freq, start, length, partials, decay, attack)


def render(name, seconds, notes, gain=0.6):
    total = int(seconds * RATE)
    buf = [0.0] * total
    for freq, start, length, partials, decay, attack in notes:
        s0 = int(start * RATE)
        n = int(length * RATE)
        for i in range(n):
            j = s0 + i
            if j >= total:
                break
            t = i / RATE
            env = min(1.0, t / attack) * math.exp(-decay * t)
            # Weiches Ende, damit kein Knacken entsteht
            tail = min(1.0, (n - i) / (0.02 * RATE))
            v = sum(a * math.sin(2 * math.pi * freq * f * t) for f, a in partials)
            buf[j] += v * env * tail
    peak = max(1e-9, max(abs(x) for x in buf))
    scale = gain * 32767 / peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(x * scale)) for x in buf))


BELL = ((1.0, 1.0), (2.0, 0.35), (3.01, 0.15), (4.2, 0.08))
GLASS = ((1.0, 1.0), (2.76, 0.4), (5.4, 0.2))


def main():
    os.makedirs(OUT, exist_ok=True)
    # Glockenspiel: drei aufsteigende Töne (E6, G6, C7)
    render("cc_chime", 1.8, [
        note(1318.5, 0.00, 1.2, BELL, decay=3.5),
        note(1568.0, 0.18, 1.2, BELL, decay=3.5),
        note(2093.0, 0.36, 1.4, BELL, decay=3.0),
    ])
    # Kristall: zwei helle, glasartige Töne
    render("cc_crystal", 1.6, [
        note(2637.0, 0.00, 1.0, GLASS, decay=5.0),
        note(3136.0, 0.12, 1.4, GLASS, decay=4.0),
    ])
    # Ding-Dong: Terz abwärts (E5 → C5)
    render("cc_dingdong", 2.0, [
        note(659.3, 0.00, 1.0, BELL, decay=3.0),
        note(523.3, 0.45, 1.5, BELL, decay=2.5),
    ])
    # Puls: drei kurze gleiche Töne (A5)
    render("cc_pulse", 1.2, [
        note(880.0, 0.00, 0.22, ((1.0, 1.0), (2.0, 0.2)), decay=10.0),
        note(880.0, 0.35, 0.22, ((1.0, 1.0), (2.0, 0.2)), decay=10.0),
        note(880.0, 0.70, 0.30, ((1.0, 1.0), (2.0, 0.2)), decay=8.0),
    ])


if __name__ == "__main__":
    main()
