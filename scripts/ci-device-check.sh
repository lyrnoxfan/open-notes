#!/usr/bin/env bash
# emulator-runner executes script lines separately: preserve status/loops in a single shell.
set -uo pipefail
result=0
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
# Long documents must be exercised through actual scroll, tap and keyboard interaction.
adb shell pm clear com.example.privatenotes >/dev/null
adb shell settings put secure show_ime_with_hard_keyboard 1
adb logcat -c
adb shell am instrument -w -e class com.example.privatenotes.LongDocumentEditorTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/long-document.txt 2>&1
cat build/long-document.txt
adb logcat -d -s LongDocument:I >> build/long-document.txt
if ! grep -q "OK (2 tests)" build/long-document.txt; then
  cat build/long-document.txt
  result=1
fi
exit "$result"
