#!/usr/bin/env bash
set -uo pipefail
mkdir -p build
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put secure show_ime_with_hard_keyboard 1
result=0
for mode in default large; do
  if [ "$mode" = large ]; then
    adb shell wm size 640x1280
    adb shell wm density 320
    adb shell settings put system font_scale 2.0
  fi
  adb shell am force-stop com.example.privatenotes
  adb shell pm clear com.example.privatenotes >/dev/null
  adb logcat -c
  adb shell am instrument -w -e class com.example.privatenotes.LongDocumentEditorTest,com.example.privatenotes.EditorInputRoundTripTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > "build/long-document-$mode.txt" 2>&1
  mkdir -p "build/long-document-evidence/$mode"
  for file in $(adb shell run-as com.example.privatenotes ls files/longdoc-evidence 2>/dev/null | tr -d '\r'); do
    adb exec-out run-as com.example.privatenotes cat "files/longdoc-evidence/$file" > "build/long-document-evidence/$mode/$file"
  done
  cat "build/long-document-$mode.txt"
  adb logcat -d -s LongDocument:I >> "build/long-document-$mode.txt"
  grep -q 'OK (4 tests)' "build/long-document-$mode.txt" || result=1
done
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
exit "$result"
