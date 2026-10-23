#!/usr/bin/env bash
# emulator-runner executes script lines separately: preserve status/loops in a single shell.
set -uo pipefail
result=0
copy_longdoc_views() {
  local mode="$1"
  mkdir -p "build/long-document-evidence/$mode"
  for file in $(adb shell run-as com.example.privatenotes ls files/longdoc-evidence 2>/dev/null | tr -d '\r'); do
    adb exec-out run-as com.example.privatenotes cat "files/longdoc-evidence/$file" > "build/long-document-evidence/$mode/$file"
  done
}
./gradlew --no-daemon --stacktrace connectedCheck -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.example.privatenotes.ScriptDriven || result=$?
# connectedCheck's UTP removes packages when it finishes. Reinstall the two test APKs,
# render synthetic content in a separate host, and pull while that install is still present.
# This does not disable MainActivity's FLAG_SECURE or app authentication.
bash scripts/capture-ui-previews.sh || result=1
# Dedicated reinstall/kill/fault and at-rest canary suites intentionally use their own install.
# Run only after ordinary tests and previews, never against real user data.
bash scripts/e2e-reinstall.sh || result=1
bash scripts/canary-scan.sh || result=1
# TextFieldValue/IME round-trip tests likewise require a clean, unlocked install.
adb shell pm clear com.example.privatenotes >/dev/null
adb shell am instrument -w -e class com.example.privatenotes.EditorInputRoundTripTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/ime-roundtrip.txt 2>&1
if ! grep -q "OK (2 tests)" build/ime-roundtrip.txt; then
  cat build/ime-roundtrip.txt
  result=1
fi
# Isolated real-pointer left-swipe/search tests; collect actual MainActivity views
# before clearing this synthetic test install. FLAG_SECURE stays enabled.
adb shell pm clear com.example.privatenotes >/dev/null
adb shell am instrument -w -e class com.example.privatenotes.SwipeDeleteRegressionTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/swipe-search-regression.txt 2>&1
cat build/swipe-search-regression.txt
if ! grep -q "OK (5 tests)" build/swipe-search-regression.txt; then result=1; fi
mkdir -p build/swipe-evidence
for file in $(adb shell run-as com.example.privatenotes ls files/swipe-evidence 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as com.example.privatenotes cat "files/swipe-evidence/$file" > "build/swipe-evidence/$file"
done
# Long documents must be exercised through actual scroll, tap and keyboard interaction.
adb shell pm clear com.example.privatenotes >/dev/null
adb shell settings put secure show_ime_with_hard_keyboard 1
adb logcat -c
adb shell am instrument -w -e class com.example.privatenotes.LongDocumentEditorTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/long-document.txt 2>&1
copy_longdoc_views default
cat build/long-document.txt
adb logcat -d -s LongDocument:I >> build/long-document.txt
if ! grep -q "OK (2 tests)" build/long-document.txt; then
  cat build/long-document.txt
  result=1
fi
# Repeat at an explicit 320dp width and 2x font scale. This is the real MainActivity
# with the same security flags and soft keyboard, not the synthetic preview host.
adb shell am force-stop com.example.privatenotes
adb shell wm size 640x1280
adb shell wm density 320
adb shell settings put system font_scale 2.0
adb shell pm clear com.example.privatenotes >/dev/null
adb logcat -c
adb shell am instrument -w -e class com.example.privatenotes.LongDocumentEditorTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/long-document-large.txt 2>&1
copy_longdoc_views large
cat build/long-document-large.txt
adb logcat -d -s LongDocument:I >> build/long-document-large.txt
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
if ! grep -q "OK (2 tests)" build/long-document-large.txt; then
  cat build/long-document-large.txt
  result=1
fi
exit "$result"
