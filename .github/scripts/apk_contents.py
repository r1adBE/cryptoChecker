#!/usr/bin/env python3
"""Checks the contents of the Android release APK and AAB (zip level, no Android tools needed).

Appends findings for annotate.py:
  - baseline profile packaged (APK assets/dexopt/baseline.prof, AAB BUNDLE-METADATA/
    com.android.tools.build.profiles/baseline.prof)                    -> warning if missing
  - 64-bit native libraries: ELF LOAD segments aligned to >= 16 KB (Play: 16 KB page size
    support for targetSdk 35+)                                           -> warning
  - native libraries stored uncompressed in the APK (needed for 16 KB zip alignment)
                                                                         -> notice if compressed
  - native debug symbols in the AAB (ndk.debugSymbolLevel)              -> notice if missing
    (expected: SQLCipher ships prebuilt, stripped libraries and AGP needs an installed NDK to
    extract symbols; no NDK is installed on purpose, a mismatching NDK version could break the build)
  - release APK larger than APK_WARN_MB (build 94: 17.6 MB)              -> warning
  - size of APK and AAB, list of native libraries                        -> notice

Usage: python3 -I apk_contents.py <apk> <aab or -> <findings file>
"""
import os
import struct
import sys
import zipfile

PAGE_16K = 16384
# Warning threshold for the release APK: build 94 had 17.6 MB, a jump beyond this means a new
# dependency or resources that were not shrunk
APK_WARN_MB = 22


def elf_min_load_align(data):
    """Smallest p_align of the PT_LOAD segments, or None if the file is not a readable ELF."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    try:
        if is64:
            phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
            phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
        else:
            phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
            phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
        aligns = []
        for i in range(phnum):
            off = phoff + i * phentsize
            p_type = struct.unpack_from(endian + "I", data, off)[0]
            if p_type != 1:  # PT_LOAD
                continue
            if is64:
                p_align = struct.unpack_from(endian + "Q", data, off + 48)[0]
            else:
                p_align = struct.unpack_from(endian + "I", data, off + 28)[0]
            aligns.append(p_align)
        return min(aligns) if aligns else None
    except struct.error:
        return None


def mb(n):
    return f"{n / 1024 / 1024:.1f} MB"


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    apk, aab, findings_path = sys.argv[1:4]
    out = []

    def add(level, message):
        out.append(f"{level}\t\t\t{message.replace(chr(10), chr(92) + 'n')}")

    sizes = []
    libs_info = []
    try:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
            apk_size = os.path.getsize(apk)
            sizes.append(f"APK {os.path.basename(apk)} {mb(apk_size)}")
            if apk_size > APK_WARN_MB * 1024 * 1024:
                add("warning", f"Release APK is {mb(apk_size)}, more than {APK_WARN_MB} MB (build 94: 17.6 MB): "
                               "check new dependencies, native libraries and resource shrinking "
                               "(apkanalyzer / Android Studio «Analyze APK»)")
            if "assets/dexopt/baseline.prof" not in names:
                add("warning", "Release APK contains no baseline profile (assets/dexopt/baseline.prof): "
                               "app/src/main/baseline-prof.txt or the :baselineprofile output was not packaged")
            dex = [n for n in names if n.startswith("classes") and n.endswith(".dex")]
            sizes.append(f"{len(dex)} dex file(s)")
            unaligned, compressed = [], []
            for info in z.infolist():
                if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                    continue
                if info.compress_type != zipfile.ZIP_STORED:
                    compressed.append(info.filename)
                with z.open(info) as f:
                    head = f.read(1 << 20)  # program headers sit at the start
                align = elf_min_load_align(head)
                libs_info.append(f"{info.filename} ({info.file_size // 1024} KB, LOAD align "
                                 f"{align if align is not None else '?'})")
                # 16 KB pages exist only on 64-bit devices; 32-bit ABIs keep 4 KB
                if align is None or not info.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                    continue
                if align < PAGE_16K:
                    unaligned.append(f"{info.filename} (p_align {align})")
            if unaligned:
                add("warning", "Native libraries not aligned to 16 KB pages (Play requires 16 KB page size "
                               "support for targetSdk 35+; update the library):\n- " + "\n- ".join(unaligned))
            if compressed:
                add("notice", "Native libraries stored compressed in the APK (extracted on install; zip "
                              "alignment does not apply):\n- " + "\n- ".join(compressed[:12]))
    except (zipfile.BadZipFile, OSError) as e:
        add("error", f"Release APK unreadable ({apk}): {e}")

    if aab and aab != "-":
        try:
            with zipfile.ZipFile(aab) as z:
                names = z.namelist()
                sizes.append(f"AAB {os.path.basename(aab)} {mb(os.path.getsize(aab))}")
                if "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof" not in names:
                    add("warning", "Release AAB contains no baseline profile "
                                   "(BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof)")
                has_libs = any(n.startswith("base/lib/") and n.endswith(".so") for n in names)
                has_symbols = any(n.startswith("BUNDLE-METADATA/com.android.tools.build.debugsymbols/")
                                  for n in names)
                if has_libs and not has_symbols:
                    add("notice", "Release AAB has native libraries but no native debug symbols: expected. "
                                  "SQLCipher ships prebuilt, stripped libraries and AGP needs an installed NDK "
                                  "to extract symbols (ndk.debugSymbolLevel); the runner installs none on "
                                  "purpose. The Play Console hint about missing native debug symbols can be "
                                  "ignored (see PLAY_STORE.md)")
                if not any(n.startswith("BUNDLE-METADATA/com.android.tools.build.obfuscation/") for n in names):
                    add("warning", "Release AAB contains no R8 mapping (BUNDLE-METADATA/"
                                   "com.android.tools.build.obfuscation/proguard.map)")
        except (zipfile.BadZipFile, OSError) as e:
            add("error", f"Release AAB unreadable ({aab}): {e}")

    add("notice", "Release artefacts: " + ", ".join(sizes)
        + ("\nNative libraries:\n- " + "\n- ".join(libs_info) if libs_info else "\nNo native libraries"))
    with open(findings_path, "a", encoding="utf-8") as f:
        for line in out:
            f.write(line + "\n")
    for line in out:
        print(line.replace("\t", " "))
    return 0


if __name__ == "__main__":
    sys.exit(main())
