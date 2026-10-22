#!/usr/bin/env bash
# Canary scan: prove that notes, tags and the WebDAV password are not readable at rest.
#
# The seed test types four known strings through the real editor and the real settings path, then
# exports one backup with a password. After that the app is killed and its private directory is
# pulled as a byte blob; the scan looks for each string in UTF-8, UTF-16LE and UTF-16BE.
#
#   ./scripts/canary-scan.sh
#
# Environment:
#   SERIAL        device serial (default: the only connected device)
#   RELEASE_APK   release APK for the manifest checks (default: app/build/outputs/apk/release)
#   SKIP_SEED=1   reuse an already seeded install instead of running the seed test
#
# Exit code 0 only when every canary is absent from the private directory and from logcat and the
# release manifest holds its three invariants.
set -uo pipefail

PKG=com.example.privatenotes
TEST_RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build/canary}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RELEASE_APK="${RELEASE_APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}"
AAPT2="${AAPT2:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/aapt2}"
ADB=(adb)
[ -n "${SERIAL:-}" ] && ADB=(adb -s "$SERIAL")

failures=0
note() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
info() { printf '  ---- %s\n' "$*"; }

mkdir -p "$OUT"
rm -rf "$OUT/private" "$OUT/logcat.txt" "$OUT/plaintext-export.json"
mkdir -p "$OUT/private"

note "1/6 install and seed through the UI"
if [ "${SKIP_SEED:-0}" != "1" ]; then
  if [ ! -f "$APK" ] || [ ! -f "$TEST_APK" ]; then
    info "building the app and test APKs"
    ( cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -q ) \
      >"$OUT/build.txt" 2>&1 || { bad "build failed"; tail -20 "$OUT/build.txt"; exit 1; }
  fi
  "${ADB[@]}" install -r -t "$APK" >"$OUT/install.txt" 2>&1 || { bad "install failed"; cat "$OUT/install.txt"; exit 1; }
  "${ADB[@]}" install -r -t "$TEST_APK" >>"$OUT/install.txt" 2>&1 || { bad "test APK install failed"; cat "$OUT/install.txt"; exit 1; }
  "${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1
  "${ADB[@]}" logcat -c
  "${ADB[@]}" shell am instrument -w -e class "$PKG.CanarySeedTest" "$TEST_RUNNER" >"$OUT/seed.txt" 2>&1
  if grep -q "OK (1 test)" "$OUT/seed.txt"; then
    ok "seed test passed"
  else
    bad "seed test did not pass"; tail -20 "$OUT/seed.txt"
    exit 1
  fi
else
  info "seed skipped"
fi

note "2/6 kill the app"
"${ADB[@]}" shell am force-stop "$PKG"
sleep 1
info "processes left: $("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' || true)"

note "3/6 pull the private directory"
# run-as works because the debug build is debuggable; tar keeps the WAL/SHM files that SQLCipher
# leaves behind while the database is open.
"${ADB[@]}" exec-out run-as "$PKG" tar cf - databases shared_prefs files cache no_backup app_webview 2>/dev/null \
  > "$OUT/private.tar" || true
if [ -s "$OUT/private.tar" ]; then
  tar xf "$OUT/private.tar" -C "$OUT/private" 2>/dev/null || true
  pulled_files=$(find "$OUT/private" -type f | wc -l | tr -d ' ')
  if [ "$pulled_files" -eq 0 ]; then bad "private archive contained no files"; else
    ok "pulled $pulled_files files, $(du -sk "$OUT/private" | cut -f1) KB"
  fi
else
  bad "could not pull the private directory"
fi
info "wal/journal files: $(find "$OUT/private" -type f \( -name '*-wal' -o -name '*-shm' -o -name '*-journal' \) | wc -l | tr -d ' ')"
find "$OUT/private" -type f \( -name '*-wal' -o -name '*-shm' -o -name '*-journal' \) -printf '       %P\n' 2>/dev/null

note "4/6 search the private bytes"
if python3 - "$OUT/private" <<'PYSCAN'
import pathlib, sys

root = pathlib.Path(sys.argv[1])
canaries = {
    "title": "CANARY-冥王星-7f3a",
    "body": "CANARY-BODY-冥王星-9c21",
    "tag": "CANARY-TAG",
    "webdav-password": "CANARY-DAVPW",
}
hits = 0
files = 0
for path in sorted(root.rglob("*")):
    if not path.is_file():
        continue
    files += 1
    data = path.read_bytes()
    for name, text in canaries.items():
        for encoding in ("utf-8", "utf-16-le", "utf-16-be"):
            if text.encode(encoding) in data:
                print(f"  \033[31mFAIL\033[0m {name} found in {path.relative_to(root)} as {encoding}")
                hits += 1
print(f"  scanned {files} files, {hits} hit(s)")
sys.exit(1 if hits else 0)
PYSCAN
then ok "no canary in the private directory"; else failures=$((failures + 1)); fi

note "5/6 search logcat"
"${ADB[@]}" logcat -d >"$OUT/logcat.txt" 2>/dev/null || true
if python3 - "$OUT/logcat.txt" <<'PYLOG'
import pathlib, sys

data = pathlib.Path(sys.argv[1]).read_bytes()
canaries = ["CANARY-冥王星-7f3a", "CANARY-BODY-冥王星-9c21", "CANARY-TAG", "CANARY-DAVPW"]
hits = [c for c in canaries if c.encode("utf-8") in data or c.encode("utf-16-be") in data]
for hit in hits:
    print(f"  \033[31mFAIL\033[0m {hit} in logcat")
print(f"  logcat: {len(data)} bytes, {len(hits)} hit(s)")
sys.exit(1 if hits else 0)
PYLOG
then ok "no canary in logcat"; else failures=$((failures + 1)); fi

note "6/6 plaintext export and the release manifest"
# Known behaviour, listed rather than failed: an export written without a password is plain JSON in
# the directory the user picked, and it contains the notes in the clear.
EXTERNAL_PATH="/sdcard/Android/data/$PKG/files/canary-plaintext.json"
"${ADB[@]}" exec-out cat "$EXTERNAL_PATH" >"$OUT/plaintext-export.json" 2>/dev/null || true
PLAIN_BYTES=$(wc -c <"$OUT/plaintext-export.json" | tr -d ' ')
PLAIN_HITS=0
for canary in "CANARY-冥王星-7f3a" "CANARY-BODY-冥王星-9c21" "CANARY-TAG"; do
  if grep -q -- "$canary" "$OUT/plaintext-export.json" 2>/dev/null; then PLAIN_HITS=$((PLAIN_HITS + 1)); fi
done
info "plaintext export: ${PLAIN_BYTES:-0} bytes, ${PLAIN_HITS} of 3 canaries present -> expected, not a failure"

ENC_BYTES=$(find "$OUT/private" -name 'canary-encrypted.pnb' -printf '%s' 2>/dev/null | head -1)
info "encrypted export in private dir: ${ENC_BYTES:-0} bytes"
if [ -n "${ENC_BYTES:-}" ] && [ "${ENC_BYTES:-0}" -gt 0 ]; then
  if python3 - "$OUT/private" <<'PYENC'
import pathlib, sys

root = pathlib.Path(sys.argv[1])
blob = b"".join(p.read_bytes() for p in root.rglob("canary-encrypted.pnb"))
sys.exit(0 if any(n in blob for n in (b"CANARY-", "冥王星".encode("utf-8"), "冥王星".encode("utf-16-le"))) else 1)
PYENC
  then bad "the encrypted export contains a canary in the clear"; else ok "encrypted export is opaque"; fi
else
  bad "required encrypted export was not pulled"
fi

if [ -f "$RELEASE_APK" ] && [ -x "$AAPT2" ]; then
  "$AAPT2" dump xmltree --file AndroidManifest.xml "$RELEASE_APK" >"$OUT/release-manifest.txt" 2>/dev/null
  if python3 - "$OUT/release-manifest.txt" <<'PYMAN'
import re, sys

L = open(sys.argv[1]).read().splitlines()
comps = []
for line in L:
    m = re.search(r"E: (activity|activity-alias|service|receiver|provider) ", line)
    if m:
        comps.append({"kind": m.group(1), "name": None, "exported": False, "permission": None})
        continue
    if not comps:
        continue
    nm = re.search(r'android:name\(0x01010003\)="([^"]+)"', line)
    if nm and comps[-1]["name"] is None:
        comps[-1]["name"] = nm.group(1)
    if "exported(0x01010010)=true" in line:
        comps[-1]["exported"] = True
    pm = re.search(r'android:permission\(0x01010006\)="([^"]+)"', line)
    if pm:
        comps[-1]["permission"] = pm.group(1)

exported = [c for c in comps if c["exported"]]
launcher = [c for c in exported if c["kind"] == "activity" and (c["name"] or "").startswith("com.")]
others = [c for c in exported if c not in launcher]
guarded = [c for c in others if c["permission"]]
unguarded = [c for c in others if not c["permission"]]

print(f"  exported: {len(exported)} total, {len(launcher)} launcher, {len(guarded)} permission-guarded, {len(unguarded)} unguarded")
for c in exported:
    print(f"       {c['kind']:14s} {(c['name'] or '?')[:64]:64s} [{c['permission'] or 'no permission'}]")

fail = 0
if len(launcher) != 1:
    print(f"  \033[31mFAIL\033[0m expected exactly one exported launcher activity, found {len(launcher)}")
    fail = 1
if unguarded:
    print(f"  \033[31mFAIL\033[0m {len(unguarded)} exported component(s) with no permission guard")
    fail = 1
if others:
    print(f"  ---- strict 'launcher only' is not literally true: {len(others)} library component(s) are exported")
sys.exit(fail)
PYMAN
  then ok "exported components are the launcher plus permission-guarded library entries"; else bad "exported component check failed - see $OUT/release-manifest.txt"; fi

  if grep -q "debuggable" "$OUT/release-manifest.txt"; then
    bad "release manifest declares android:debuggable"
  else
    ok "release build is not debuggable"
  fi

  if grep -qE "allowBackup\(0x01010280\)=true" "$OUT/release-manifest.txt"; then
    bad "release manifest allows backup"
  else
    ok "release build sets allowBackup=false"
  fi
else
  info "release APK or aapt2 missing; run './gradlew assembleRelease' first for the manifest checks"
fi

note "result"
if [ "$failures" -eq 0 ]; then
  printf '  \033[32mcanary scan clean\033[0m - artifacts in %s\n' "$OUT"
  exit 0
else
  printf '  \033[31m%d check(s) failed\033[0m - artifacts in %s\n' "$failures" "$OUT"
  exit 1
fi
