#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/media-render
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell am instrument -w -e class com.example.privatenotes.VideoMediaRenderTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/media-render/video-result.txt 2>&1
cat build/media-render/video-result.txt
for name in video-before-loading.png video-after-loaded.png; do
  adb exec-out run-as com.example.privatenotes cat "files/media-render-evidence/$name" > "build/media-render/$name"
done
grep -q 'OK (1 test)' build/media-render/video-result.txt
