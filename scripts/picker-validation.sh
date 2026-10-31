#!/usr/bin/env bash
set -euo pipefail
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell mkdir -p /sdcard/Download
adb push app/src/androidTest/assets/media/picker-image.png /data/local/tmp/wabi-picker-image.png >/dev/null
adb push app/src/androidTest/assets/media/sample.mp4 /data/local/tmp/wabi-picker-video.mp4 >/dev/null
adb shell cp /data/local/tmp/wabi-picker-image.png /sdcard/Download/wabi-picker-image.png
adb shell cp /data/local/tmp/wabi-picker-video.mp4 /sdcard/Download/wabi-picker-video.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/wabi-picker-image.png >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/wabi-picker-video.mp4 >/dev/null
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell pm clear com.example.privatenotes >/dev/null
mkdir -p build/e2e
adb shell am instrument -w -e class com.example.privatenotes.PickerValidationTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/e2e/picker-validation.txt 2>&1 || true
cat build/e2e/picker-validation.txt
if ! grep -q 'OK (1 test)' build/e2e/picker-validation.txt; then exit 1; fi
