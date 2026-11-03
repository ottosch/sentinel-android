#!/bin/bash
# Run the suite in small batches, one Maestro session per batch.
# Why: Maestro's device link drops partway through every long session (about 600 s on a
# KVM-accelerated emulator, about 13 min on a software-only one; DeviceServerDiedException /
# "device offline"), and Maestro can't recover in-session, so every later flow fails in
# milliseconds. Short sessions avoid it. ~6 flows per batch fits under 600 s with KVM;
# use -b 3 on a software-only emulator.
# Usage: ./run_batches.sh [-b BATCH_SIZE (default 6)] [-n LABEL] [-d DEVICE] [-a APP_ID] [-s FIRST_FLOW] [FLOW_ID ...]
# Writes runs/<ts>-<label>-bNN/ per batch and runs/<label>-batches.txt listing them.
# Pre-batch guard (S1): before every batch the app prefs are checked; if Street mode is on the run
# stops with exit 3 (fix the state, e.g. run ZZ-RESTORE, then resume with -s FIRST_FLOW).
# Set SKIP_GUARD=1 to disable.
SUITE=$(cd "$(dirname "$0")" && pwd); B=6; LABEL=batch; START=""; PASS=()
export PATH=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH
DEV=${DEVICE:-emulator-5554}; APP=${APP_ID:-com.samourai.sentinel.staging}
while getopts "b:n:d:a:s:" o; do case $o in b) B=$OPTARG;; n) LABEL=$OPTARG;; d) PASS+=(-d "$OPTARG"); DEV=$OPTARG;; a) PASS+=(-a "$OPTARG"); APP=$OPTARG;; s) START=$OPTARG;; *) sed -n 2,12p "$0"; exit 2;; esac; done
shift $((OPTIND-1))
if [ $# -gt 0 ]; then IDS=("$@"); else
  mapfile -t IDS < <(python3 -c "import yaml,sys;o=yaml.safe_load(open('$SUITE/config.yaml'))['executionOrder']['flowsOrder'];s='$START';print('\n'.join(o[o.index(s):] if s else o))"); fi
# Guard: read streetMode from the app's shared_prefs over adb (run-as on a debuggable build such as
# staging, else root on an emulator image that allows `adb root`). If neither works, fall back to the
# read-only Maestro guard flow subflows/baseline_guard.yaml. Returns 0 = OK, 1 = street mode on.
read_prefs() {
  local x
  x=$(adb -s "$DEV" shell "run-as $APP sh -c 'cat shared_prefs/*.xml'" 2>/dev/null | tr -d '\r')
  echo "$x" | grep -q '<map' && { echo "$x"; return 0; }
  x=$(adb -s "$DEV" shell "su 0 sh -c 'cat /data/data/$APP/shared_prefs/*.xml' 2>/dev/null || cat /data/data/$APP/shared_prefs/*.xml" 2>/dev/null | tr -d '\r')
  echo "$x" | grep -q '<map' && { echo "$x"; return 0; }
  return 1
}
guard() {
  [ "${SKIP_GUARD:-0}" = 1 ] && return 0
  local prefs
  if prefs=$(read_prefs); then
    if echo "$prefs" | grep -q '<boolean name="streetMode" value="true"'; then
      echo "GUARD FAILED before $1: streetMode=true in shared_prefs (suite bug S1 state)."; return 1; fi
    echo "$prefs" | grep -q '<boolean name="fiatDisabled" value="true"' && echo "GUARD WARN before $1: fiatDisabled=true"
    echo "$prefs" | grep -q '<boolean name="scramblePin" value="false"' && echo "GUARD WARN before $1: scramblePin=false"
    echo "[guard] prefs OK before $1 (adb)"; return 0
  fi
  "$SUITE/run.sh" "${PASS[@]}" -n "$1-guard" "$SUITE/subflows/baseline_guard.yaml" > "$LOGDIR/$1-guard.log" 2>&1 \
    && { echo "[guard] UI OK before $1 (Maestro guard flow)"; return 0; }
  echo "GUARD FAILED before $1: Street mode on / balances masked, or the guard flow could not run (see $LOGDIR/$1-guard.log)."; return 1
}
# Console logs stay under runs/ (gitignored, chmod 700) instead of /tmp.
LOGDIR="$SUITE/runs/console"; ( umask 077; mkdir -p "$LOGDIR" ); chmod 700 "$LOGDIR"
mkdir -p "$SUITE/runs"; LIST="$SUITE/runs/$LABEL-batches.txt"; : > "$LIST"; i=0; n=1
while [ $i -lt ${#IDS[@]} ]; do
  files=(); for id in "${IDS[@]:i:B}"; do files+=("$SUITE/flows/$id.yaml"); done
  tag=$(printf "%s-b%02d" "$LABEL" $n)
  guard "$tag" || { echo "Stopping: next batch would start at ${IDS[$i]}. Fix the app state (run flows/ZZ-RESTORE.yaml), then resume with -s ${IDS[$i]}."; exit 3; }
  "$SUITE/run.sh" "${PASS[@]}" -n "$tag" "${files[@]}" > "$LOGDIR/$tag.log" 2>&1
  ls -td "$SUITE"/runs/*-"$tag" | head -1 >> "$LIST"
  grep "^\[" "$LOGDIR/$tag.log"
  i=$((i+B)); n=$((n+1))
done
echo "ALL BATCHES DONE"
