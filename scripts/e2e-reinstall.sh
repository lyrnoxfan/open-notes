#!/usr/bin/env bash
# Full reinstall round-trip: seed, export, uninstall, reinstall, import, compare, inject faults.
#
#   ./scripts/e2e-reinstall.sh
#
# What makes this different from a database that is simply reopened: `adb uninstall` wipes the
# app's private directory and — on a real device — the Keystore key that protects the database.
# The process that verifies the data is a fresh install that has never seen the original database.
#
# Phases:
#   1. install, seed 49 notes (40 normal, 8 trashed, one 1 MB) + 1 permanently deleted, export both
#      formats, write a manifest of every field
#   2. pull the manifest and the exports to the host
#   3. adb uninstall, adb install, push the exports and the manifest back
#   4. import the plaintext export and compare every note field by field
#   5. fault injection: truncated file, missing file, vanished directory, dead WebDAV
#   6. kill the process mid-import of the 1 MB export, then assert all-or-nothing
#   7. reinstall again and repeat step 4 with the encrypted export and its password
set -uo pipefail

PKG=com.example.privatenotes
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build/e2e}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ADB=(adb)
[ -n "${SERIAL:-}" ] && ADB=(adb -s "$SERIAL")

failures=0
note() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
info() { printf '  ---- %s\n' "$*"; }

mkdir -p "$OUT"

install_apks() {
  "${ADB[@]}" install -r -t "$APK" >"$OUT/install.txt" 2>&1 || { bad "install failed"; tail -5 "$OUT/install.txt"; return 1; }
  "${ADB[@]}" install -r -t "$TEST_APK" >>"$OUT/install.txt" 2>&1 || { bad "test APK install failed"; tail -5 "$OUT/install.txt"; return 1; }
}

run_test() { # run_test <class#method> <logfile>
  local target="$1" log="$2"
  "${ADB[@]}" shell am instrument -w -e class "$target" "$RUNNER" >"$log" 2>&1
  if grep -q "OK (" "$log"; then return 0; else return 1; fi
}

pull_private() { # pull_private <name>...
  local name
  for name in "$@"; do
    "${ADB[@]}" exec-out run-as "$PKG" cat "files/$name" >"$OUT/$name" 2>/dev/null || true
    [ -s "$OUT/$name" ] || { bad "could not pull $name"; return 1; }
  done
}

push_private() { # push_private <local-file>...
  local file name
  # files/ does not exist until the app writes something, and a fresh install has written nothing.
  "${ADB[@]}" shell run-as "$PKG" mkdir -p files >/dev/null 2>&1
  for file in "$@"; do
    name="$(basename "$file")"
    "${ADB[@]}" push "$file" "/data/local/tmp/$name" >/dev/null 2>&1 || { bad "push failed: $name"; return 1; }
    # run-as <cmd> <args> rather than run-as sh -c "...": the device shell eats the quotes and the
    # redirection arrives as separate arguments.
    "${ADB[@]}" shell run-as "$PKG" cp "/data/local/tmp/$name" "files/$name" >/dev/null 2>&1 \
      || { bad "copy into app failed: $name"; return 1; }
  done
}

if [ ! -f "$APK" ] || [ ! -f "$TEST_APK" ]; then
  info "building app and test APKs"
  ( cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -q ) \
    >"$OUT/build.txt" 2>&1 || { bad "build failed"; tail -20 "$OUT/build.txt"; exit 1; }
fi

note "1/7 fresh install and seed at least 50 rows"
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1
install_apks || exit 1
"${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1
if run_test "$PKG.E2eSeedTest" "$OUT/seed.txt"; then ok "seed finished"; else bad "seed failed"; tail -25 "$OUT/seed.txt"; exit 1; fi

note "2/7 pull the manifest and both exports"
pull_private e2e-manifest.json e2e-plain.json e2e-encrypted.pnb e2e-truncated.pnb || exit 1
ok "manifest $(wc -c <"$OUT/e2e-manifest.json" | tr -d ' ') bytes, plaintext $(wc -c <"$OUT/e2e-plain.json" | tr -d ' ') bytes, encrypted $(wc -c <"$OUT/e2e-encrypted.pnb" | tr -d ' ') bytes"
python3 - "$OUT/e2e-manifest.json" <<'PYMAN'
import json, sys
m = json.load(open(sys.argv[1]))
notes = m["notes"]
active = [n for n in notes if n["deletedAt"] is None]
trash = [n for n in notes if n["deletedAt"] is not None]
big = max(notes, key=lambda n: n["contentBytes"])
print(f"  ---- {len(notes)} notes in the manifest: {len(active)} active, {len(trash)} in the trash, "
      f"largest {big['contentBytes']} bytes, permanently deleted id recorded")
PYMAN

note "3/7 uninstall, reinstall, push the files back"
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1
if "${ADB[@]}" shell pm list packages | grep -q "^package:$PKG$"; then bad "uninstall left the package installed"; else ok "package really uninstalled"; fi
install_apks || exit 1
"${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1
push_private "$OUT/e2e-manifest.json" "$OUT/e2e-plain.json" "$OUT/e2e-encrypted.pnb" "$OUT/e2e-truncated.pnb" || exit 1
ok "files restored into the new install"

note "4/7 kill the process in the middle of the 1 MB import, before anything is restored"
"${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1
# Start the import in the background, then kill the app while the merge is running.
# The test kills its own process 120 ms into the merge, so there is nothing for the host to time.
"${ADB[@]}" shell am instrument -w -e class "$PKG.E2eSlowImportTest" "$RUNNER" >"$OUT/slow-import.txt" 2>&1 || true
sleep 2
info "instrumentation survived the kill: $(grep -c "Process crashed" "$OUT/slow-import.txt" || true) crash marker(s)"
sleep 2
if run_test "$PKG.E2eAtomicityTest" "$OUT/atomicity.txt"; then
  "${ADB[@]}" exec-out run-as "$PKG" cat files/atomicity.txt 2>/dev/null | head -1 >"$OUT/atomicity.txt" || true
  ok "after the kill the database was untouched or complete: $(cat "$OUT/atomicity.txt")"
else
  bad "a kill mid-import left a half-merged database"; grep -A8 -i "failure\|AssertionError" "$OUT/atomicity.txt" | head -20
fi

note "5/7 import the plaintext export and compare every field"
if run_test "$PKG.E2eImportVerifyTest#plaintextExportRestoresEveryField" "$OUT/verify-plain.txt"; then
  ok "plaintext import restored ids, titles, content bytes, tags and all three timestamps"
else
  bad "plaintext verification failed"; grep -A6 -i "failure\|AssertionError" "$OUT/verify-plain.txt" | head -20
fi

note "6/7 fault injection (data must be unchanged, failure must be reported)"
if run_test "$PKG.E2eFaultTest" "$OUT/faults.txt"; then
  ok "truncated file, missing file, vanished directory and dead WebDAV all failed safely"
else
  bad "fault injection failed"; grep -A6 -i "failure\|AssertionError" "$OUT/faults.txt" | head -25
fi

note "7/7 reinstall and import the encrypted export"
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1
install_apks || exit 1
"${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1
push_private "$OUT/e2e-manifest.json" "$OUT/e2e-encrypted.pnb" "$OUT/e2e-truncated.pnb" || exit 1
if run_test "$PKG.E2eImportVerifyTest#encryptedExportRestoresEveryField" "$OUT/verify-encrypted.txt"; then
  ok "encrypted import with the original password restored the same every-field match"
else
  bad "encrypted verification failed"; grep -A6 -i "failure\|AssertionError" "$OUT/verify-encrypted.txt" | head -20
fi

note "result"
if [ "$failures" -eq 0 ]; then
  printf '  \033[32mreinstall round-trip clean\033[0m - artifacts in %s\n' "$OUT"
  exit 0
else
  printf '  \033[31m%d check(s) failed\033[0m - artifacts in %s\n' "$failures" "$OUT"
  exit 1
fi
