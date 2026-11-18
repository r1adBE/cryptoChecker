"""Pick an available iOS Simulator and print «UDID<TAB>name» (for -destination id=…).

Usage: pick_simulator.py iphone|ipad. iPhone: «iPhone 17» if installed, otherwise the first available iPhone; iPad: the
first «iPad Pro 13-inch» (App Store 13" screenshots, names with the chip in parentheses), otherwise any iPad Pro.
Reads `xcrun simctl list devices available -j`, so names with parentheses work; exit 1 without a match.
"""
import json
import subprocess
import sys


def pick(kind, devices):
    """First device of `devices` (dicts with name/udid, newest runtime first) that fits `kind`; None without one."""
    if kind == "ipad":
        rules = [lambda n: n.startswith("iPad Pro 13-inch"), lambda n: n.startswith("iPad Pro")]
    else:
        rules = [lambda n: n == "iPhone 17", lambda n: n.startswith("iPhone")]
    for rule in rules:
        for device in devices:
            if rule(device["name"]):
                return device
    return None


def available():
    """Available devices of iOS runtimes, newest runtime first."""
    out = subprocess.run(["xcrun", "simctl", "list", "devices", "available", "-j"],
                         check=True, capture_output=True, text=True).stdout
    runtimes = json.loads(out)["devices"]
    ios = sorted((r for r in runtimes if ".SimRuntime.iOS-" in r), reverse=True,
                 key=lambda r: [int(p) for p in r.rsplit("iOS-", 1)[1].split("-") if p.isdigit()])
    return [d for r in ios for d in runtimes[r] if d.get("isAvailable", True)]


def main():
    kind = sys.argv[1] if len(sys.argv) > 1 else "iphone"
    device = pick(kind, available())
    if device is None:
        print(f"no {kind} simulator", file=sys.stderr)
        return 1
    print(f"{device['udid']}\t{device['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
