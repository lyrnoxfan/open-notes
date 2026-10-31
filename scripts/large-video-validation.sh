#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/e2e
command -v ffmpeg
# Real H.264, generated deterministically at runtime. Content is synthetic, not camera footage.
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=1280x720:rate=24' -t 75 -c:v libx264 -preset ultrafast -b:v 7M -minrate 7M -maxrate 7M -bufsize 14M -x264-params 'nal-hrd=cbr:force-cfr=1' -pix_fmt yuv420p -movflags +faststart build/e2e/large-video.mp4
stat -c 'fixtureBytes=%s' build/e2e/large-video.mp4
ffprobe -v error -show_entries format=duration,size:stream=codec_name,width,height -of default=noprint_wrappers=1 build/e2e/large-video.mp4
sha256sum build/e2e/large-video.mp4
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell pm clear com.example.privatenotes >/dev/null
# pm clear removes the private files directory; stage as shell, then copy as the app UID.
adb push build/e2e/large-video.mp4 /data/local/tmp/open-notes-large-video.mp4 >/dev/null
adb shell run-as com.example.privatenotes mkdir -p /data/user/0/com.example.privatenotes/files
adb shell run-as com.example.privatenotes cp /data/local/tmp/open-notes-large-video.mp4 /data/user/0/com.example.privatenotes/files/large-video.mp4
adb shell run-as com.example.privatenotes ls -l /data/user/0/com.example.privatenotes/files/large-video.mp4
adb shell rm /data/local/tmp/open-notes-large-video.mp4
adb logcat -c || echo "warning: could not clear logcat; capturing fresh test output" >&2
adb shell am instrument -w -e class com.example.privatenotes.LargeVideoValidationTest com.example.privatenotes.test/androidx.test.runner.AndroidJUnitRunner > build/e2e/large-video-validation.txt 2>&1 || true
adb logcat -d -s LargeMediaValidation:I >> build/e2e/large-video-validation.txt
cat build/e2e/large-video-validation.txt
grep -q 'OK (1 test)' build/e2e/large-video-validation.txt
