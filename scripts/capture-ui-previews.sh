#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/ui-previews build
rm -f app/build/ui-previews/*.png
adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell am instrument -w -e class com.example.privatenotes.UiPalettePreviewTest \
  com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/ui-render.txt 2>&1
cat build/ui-render.txt
grep -q 'OK (10 tests)' build/ui-render.txt
for name in list-light list-dark editor-light editor-dark list-large-narrow editor-large-narrow settings-light settings-dark trash-light trash-dark; do
  target="app/build/ui-previews/$name.png"
  # adb may return 0 for run-as failures: the PNG decoder below is mandatory.
  adb exec-out run-as com.example.privatenotes cat "files/ui-previews/$name.png" > "$target"
done
python3 scripts/validate-ui-previews.py app/build/ui-previews
