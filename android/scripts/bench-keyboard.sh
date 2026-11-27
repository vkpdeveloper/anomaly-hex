#!/usr/bin/env bash
# Measures the Hex keyboard on an Android emulator (1080x2400, Google Messages installed):
#
#   android/scripts/bench-keyboard.sh [--runs 3] [--opens 3] [--out DIR] app.apk
#
# It installs the APK, makes Hex the keyboard, opens a new message in Google Messages and types one fixed
# passage (letters, spaces, deletes, glides; about 190 touches) with `input tap` and `input motionevent`, then
# prints, as the median over the runs:
#   frame time p50/p90/p95/p99 and janky frame % of the keyboard process (dumpsys gfxinfo framestats),
#   per-key handling time and the editor calls (binder round trips) each key makes (atrace sections that
#   KeyboardEngine and InputConnectionTarget write: hex:key:*, hex:suggest, hex:select, hex:ic:*),
#   keyboard open time, warm and cold (from onStartInputView to the first frame drawn after it).
# Raw captures stay in DIR (default $TMPDIR/hex-keyboard-bench/<apk name>), one folder per run; read them again with
#   android/scripts/bench-keyboard-report.py table DIR/run-*
#
# Measure a release build (debug builds are slower): HEX_ANDROID_KEYSTORE_PROPERTIES=<keystore.properties>
# ./gradlew :app:assembleRelease, then app/build/outputs/apk/release/app-release.apk. Installing over a build signed
# with another key uninstalls the installed one first (its settings go). The emulator is noisy: judge by
# medians and by the spread the table prints, and keep nothing else running on it.
# The key positions come from the keyboard window's top edge and fixed row offsets (KEY_ROWS below); if the
# layout's key heights change, adjust them (look at DIR/run-1/end.png).
set -euo pipefail

serial=${SERIAL:-emulator-5554}
adb=${ADB:-$(command -v adb || echo ~/Library/Android/sdk/platform-tools/adb)}
runs=3 opens=3 out="" apk=""
while [ $# -gt 0 ]; do
  case "$1" in
    --runs) runs=$2; shift 2 ;;
    --opens) opens=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) apk=$1; shift ;;
  esac
done
[ -f "$apk" ] || { echo "usage: $0 [--runs N] [--opens N] [--out DIR] app.apk" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
out=${out:-${TMPDIR:-/tmp}/hex-keyboard-bench/$(basename "$apk" .apk)}
ime=ly.anoma.hex/.keyboard.HexKeyboard
messages=com.google.android.apps.messaging
a() { "$adb" -s "$serial" "$@"; }
dev() { a shell "$@"; }

[ "$(dev wm size | awk '{print $3}')" = 1080x2400 ] || { echo "the key positions assume a 1080x2400 screen" >&2; exit 1; }

shown() { dev dumpsys input_method | grep -q "mInputShown=true"; }
wait_for() { # wait_for <shown|hidden>
  for _ in $(seq 60); do
    if shown; then [ "$1" = shown ] && return 0; else [ "$1" = hidden ] && return 0; fi
    sleep 0.25
  done
  echo "the keyboard never became $1" >&2; exit 1
}
field() { dev input tap 556 2253; } # the message box of a conversation whose keyboard is closed

install() {
  a install -r "$apk" >/dev/null 2>&1 || { dev pm uninstall ly.anoma.hex >/dev/null; a install -r "$apk" >/dev/null; }
  sleep 2
  # Compile as Google Play's installs do, so the first minutes after the install do not skew the frames.
  dev cmd package compile -f -m speed-profile ly.anoma.hex >/dev/null
  # The system takes a moment to list a replaced keyboard.
  for _ in $(seq 20); do dev ime list -s | grep -q "$ime" && break; sleep 1; done
  dev ime enable "$ime" >/dev/null; dev ime set "$ime" >/dev/null
  dev pm grant ly.anoma.hex android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
}
open_compose() {
  dev am force-stop $messages
  dev am start -a android.intent.action.SENDTO -d sms:5551234 $messages >/dev/null
  sleep 3
  # A tap that lands while the conversation is still opening does nothing: tap again.
  for _ in 1 2 3; do
    field
    for _ in $(seq 12); do shown && break; sleep 0.25; done
    shown && break
  done
  wait_for shown; sleep 1
}
clear_field() { dev input keycombination CTRL_LEFT A; dev input keyevent 67; }
trace_start() { dev atrace --async_start -a ly.anoma.hex -b 65536 "$@" >/dev/null; }
trace_stop() { a exec-out atrace --async_stop > "$1" 2>/dev/null; }

install
mkdir -p "$out"
for run in $(seq "$runs"); do
  dir=$out/run-$run; rm -rf "$dir"; mkdir -p "$dir"
  echo "run $run of $runs: typing"
  open_compose
  # The keyboard window's top edge; the key rows sit at fixed offsets below it.
  top=$(dev dumpsys window windows | grep -A40 "Window #.*InputMethod" | sed -n 's/.*frame=\[0,\([0-9]*\)\]\[1080,2400\].*/\1/p' | head -1)
  [ -n "$top" ] || { echo "cannot find the keyboard window" >&2; exit 1; }
  python3 -I "$here/bench-keyboard-report.py" passage "$top" "$dir/warmup.sh" "$dir"/batch-
  dev < "$dir/warmup.sh"
  sleep 1
  # Frame times first, with no trace running (tracing itself slows the main thread), then the same
  # passage again under atrace for the per-key sections.
  i=0
  for batch in "$dir"/batch-*.sh; do
    i=$((i + 1))
    dev dumpsys gfxinfo ly.anoma.hex reset >/dev/null
    dev < "$batch"
    sleep 0.5
    dev dumpsys gfxinfo ly.anoma.hex framestats > "$dir/frames-$i.txt"
  done
  a exec-out screencap -p > "$dir/end.png"
  clear_field
  echo "run $run of $runs: typing again under trace"
  trace_start
  for batch in "$dir"/batch-*.sh; do dev < "$batch"; done
  sleep 0.5
  trace_stop "$dir/typing.trace"
  clear_field

  for kind in warm cold; do
    for n in $(seq "$opens"); do
      shown && { dev input keyevent 4; wait_for hidden; }
      if [ $kind = cold ]; then
        # A killed keyboard is not started again by the next tap while it is still the chosen one: pick another,
        # stop Hex, and choose Hex again, so the tap below starts its process afresh.
        dev ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME >/dev/null
        dev am force-stop ly.anoma.hex; sleep 1
        dev ime set "$ime" >/dev/null
      fi
      sleep 1
      trace_start view
      field; wait_for shown
      sleep 2.5
      trace_stop "$dir/open-$kind-$n.trace"
    done
  done
done
python3 -I "$here/bench-keyboard-report.py" table "$out"/run-*
