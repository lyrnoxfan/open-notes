#!/usr/bin/env bash
# emulator-runner executes script lines separately: preserve status/loops in a single shell.
set -uo pipefail
result=0
./gradlew --no-daemon --stacktrace connectedCheck -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.example.privatenotes.ScriptDriven || result=$?
mkdir -p app/build/ui-previews
for name in list-light list-dark editor-light editor-dark list-large-narrow editor-large-narrow; do
  target="app/build/ui-previews/$name.png"
  if ! adb exec-out run-as com.example.privatenotes cat "files/ui-previews/$name.png" > "$target" || ! test -s "$target"; then
    rm -f "$target"
    echo "Missing required UI preview: $name" >&2
    result=1
  fi
done
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
exit "$result"
