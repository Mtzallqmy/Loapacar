# 3.0 release verification

Build: 3.0.0, code 5, package com.masalik.yemen. Minimum API 26, target 36. Release variant is not debuggable. The original debug-origin certificate is retained; this is not a production-key migration.

## Checked

- Final release assembles. Release lint: 0 errors, 59 warnings.
- 18 JVM tests: 0 failures, 0 errors, 0 skipped.
- APK v2 signature verifies, same SHA-256 certificate as prior updates.
- ARM64, ARM32, x86 and x86_64 native libraries present.
- zipalign check, including 16 KB page alignment, passes.
- Final release installs and opens on an Android 8/API 26 software emulator. Menu, first-run full-screen prompt dismissal and normal driving HUD were visually inspected. No AndroidRuntime crash appeared in the captured checks.
- Actual debug Android frames were inspected for driver hands/cockpit, night rain/wipers, Sana'a-inspired facades and a Shibam-inspired street junction. These are not generated illustrations.
- Source archive excludes signing material, machine configuration, build caches and build outputs, while retaining wrapper, source, resources, tests and instructions.

## Not certified

- Physical-phone frame rate, thermal behavior, long sessions, low-end hardware performance, gyro calibration, every UI/font size, every car/city/weather combination visually, or all quality presets.
- A full player-driven branch loop in the emulator. Side-street movement and boundaries have automated tests; the junction has a visual check.
- Real-city geographic accuracy, real-manufacturer vehicle accuracy, motion-captured pedestrians, recorded engine audio, or professionally calibrated tire/suspension physics. All remain procedural approximations.
- Exhaustive mission completion, save restoration, in-place upgrade with real user data or screenshot permission flows. Export progress before installation; do not uninstall to bypass a signature error.
- Store publication readiness or original-asset rights clearance.

No upload to a public repository or app store was made. No cloud service was added. The private signing key has not been included or transferred. Durable key custody still needs a separate secure arrangement.
