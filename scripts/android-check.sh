set -eu
case "$1" in
    touch)
        adb install emulator-apks/touch.apk
        adb shell am instrument -w com.masalik.yemen.preview.test/com.masalik.yemen.TouchDriveProbe | tee journey-captures/touch.txt
        grep -q 'PASS two pointers' journey-captures/touch.txt
        ;;
    migration)
        adb install emulator-apks/legacy.apk
        python3 scripts/backup-handoff.py
        ;;
    benchmark) python3 scripts/drive-benchmark.py ;;
    journeys) python3 scripts/smoke-journeys.py ;;
    soak) bash scripts/soak-package.sh ;;
    *) exit 2 ;;
esac
