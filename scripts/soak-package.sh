set -eu
if adb shell getprop ro.product.cpu.abilist | grep -q arm64-v8a; then
    adb install emulator-apks/cities.apk
    SOAK_PACKAGE=com.masalik.yemen.world python3 scripts/drive-soak.py
else
    SOAK_PACKAGE=com.masalik.yemen.preview python3 scripts/drive-soak.py
fi
