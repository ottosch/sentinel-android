# Sentinel Android – Maestro suite

Scripted UI checks for Sentinel Android (DojoBay fork), one flow per **M** case in
`../TEST_PLAN_6.0.md`. Built against `com.samourai.sentinel.staging` 5.1.3-dojobay.4 (commit 8cfb74d)
and last run in full against v5.1.3-dojobay.6 (tag commit 0385bc2c), testnet4, paired to a Dojo over Tor.

**The suite never broadcasts, never changes the Dojo pairing or the app PIN, never taps
"Erase all Sentinel data", and only creates and deletes throwaway `MaestroT*` collections.**

## Layout

```
maestro/
  run_batches.sh    run in batches of ~6 flows, one Maestro session each (see Running)
  run.sh            run the suite (device + appId configurable) -> runs/<timestamp>-<label>/
  config.yaml       generated: flow order, continueOnFailure (run.sh writes a per-run copy holding only
                    the selected flows, because Maestro rejects a flowsOrder naming unselected flows)
  flows/<ID>.yaml   generated: one flow per plan ID (tags: smoke, p0/p1/p2, area, known, ...)
  subflows/         unlock, home, lock, open_collection/settings/tools, paste_pubkey,
                    ensure/delete_test_collection, restore_baseline, wait_sync_idle, dismiss_anr
  gen/              flows_a..d.py (flow definitions) + build.py (writes flows/ and config.yaml, YAML-checks)
  scripts/          helper.py (clipboard/QR/timestamp helper), js/ (runScript glue),
                    gen_throwaway.py, make_dummy_backup.py, scrub.py, verify_scrub.py, summarize.py,
                    merge_batches.py, status.sh, progress.sh, qr_decode.py, hier.py
  data/             *.env.example templates. Generate the real throwaway.env / backup.env locally
                    (git-ignored) with scripts/gen_throwaway.py and scripts/make_dummy_backup.py
  runs/             run output (chmod 700, scrubbed, git-ignored)
```

Edit flows in `gen/flows_*.py`, then `python3 gen/build.py`. Don't hand-edit `flows/` or `config.yaml`.

## Setup (Linux x86_64)

1. **Java 17+** (tested with Temurin 21). Put `java` on `PATH` or set `JAVA_HOME`.
2. **Maestro CLI 2.11.0**:
   `export MAESTRO_VERSION=2.11.0; curl -fsSL "https://get.maestro.mobile.dev" | bash`
   (installs to `~/.maestro/bin`). Set `MAESTRO_CLI_NO_ANALYTICS=1` (run.sh does).
3. **Android SDK** with platform-tools and an emulator (`ANDROID_HOME`, default `~/Android/Sdk`). Tested: emulator 37.2.12, AVD API 30
   ATD x86_64 (`sentinel30atd`), 1080x2340 @440dpi. Start it **with the gRPC port** (the helper
   needs it):
   `emulator -avd sentinel30atd -no-window -no-audio -no-boot-anim -grpc 8554 -gpu swiftshader_indirect -accel on`
   Use KVM (`-accel on`) if the host has it: flows run 5-7x faster than with `-accel off`.
   On a real device, the clipboard and QR flows fail (they need the emulator gRPC); everything else works.
4. **Helper Python env** (used by `scripts/helper.py`):
   ```
   QA=${SENTINEL_QA_HOME:-$HOME/.sentinel-qa}
   python3 -m venv $QA/venv && $QA/venv/bin/pip install grpcio grpcio-tools protobuf numpy opencv-python-headless pillow pyyaml cryptography
   mkdir -p $QA/grpcstub && $QA/venv/bin/python -m grpc_tools.protoc \
     -I $ANDROID_HOME/emulator/lib --python_out=$QA/grpcstub --grpc_python_out=$QA/grpcstub \
     $ANDROID_HOME/emulator/lib/emulator_controller.proto
   ```
   (`SENTINEL_QA_HOME`, `HELPER_PY`, `QR_PY`, `GRPC_STUB_DIR`, `GRPC_ADDR`, `HELPER_PORT` override the defaults.)
5. **App state** expected by the flows: the app is installed, a PIN is set, it is paired to a
   testnet Dojo, and it has an imported collection **My Wallet** (from the Dojo/Samourai backup,
   with Deposit/Premix/Postmix/Badbank tabs and some UTXOs) and a watch-only collection **Cold Storage**
   with **two keys** and a balance. Scramble PIN on. USD.
6. **Secrets file**, `chmod 600`, default `$SENTINEL_QA_HOME/secret/sentinel.env` (`~/.sentinel-qa/secret/sentinel.env`)
   (override with `SENTINEL_SECRETS_FILE`):
   ```
   SENTINEL_PIN=<the app PIN>
   ```
   run.sh refuses to start if the file isn't mode 600. The PIN only reaches Maestro as the env var
   `MAESTRO_SENTINEL_PIN`, never on a command line or in YAML.
7. **Back up the app data first** (needs a rootable emulator image):
   `adb root; adb shell am force-stop <appId>; adb shell "cd /data/data/<appId> && tar czf - databases shared_prefs files app_wallet" > backup.tgz`
   (`chmod 600` it, keep it outside the repo). To restore: force-stop the app, push the tgz, delete those four
   folders, untar in `/data/data/<appId>`, `chown -R` them to the app's uid, `restorecon -R`.
8. `python3 scripts/gen_throwaway.py` and `python3 scripts/make_dummy_backup.py` regenerate `data/`
   (random throwaway keys; the dummy backups are empty payloads under random, discarded passwords;
   DUMMY_BACKUP_NET adds the #404 "network":"mainnet" envelope field and DUMMY_SAMOURAI_SHAPE is
   an inert foreign-shape carrier; none of them can ever restore anything).

## Running

**Use `run_batches.sh`.** Maestro's device link drops partway through every long session: at about
600 s on a KVM emulator, about 13 min on a software-only one (adb marks the device offline, Maestro raises
DeviceServerDiedException and fails every later flow in milliseconds). `run_batches.sh` runs the
suite in batches of 6 flows by default (`-b 3` on a software-only emulator), one short Maestro session
each, and lists the batch folders in `runs/<label>-batches.txt`:
```
./run_batches.sh -n full                # whole suite in config.yaml order
./run_batches.sh -n rest -s PIN-09      # resume from a flow
./run_batches.sh -n rerun SEND-01 COL-02
```
Before each batch `run_batches.sh` runs a guard: it reads the app's `shared_prefs` via `adb run-as`
(or root) and stops with exit 3 if Street mode is on; if prefs can't be read it runs the read-only
`subflows/baseline_guard.yaml` instead. Set `SKIP_GUARD=1` to disable it.
`scripts/merge_batches.py runs/<label>-batches.txt` writes one combined summary. Plain `run.sh` is fine
for a handful of flows that finish well inside 600 s.

```
./run.sh                                  # whole suite, default device emulator-5554, staging appId
./run.sh -i smoke -n smoke                # smoke subset only
./run.sh -a com.samourai.sentinel -n rel  # release package
./run.sh -d emulator-5556 flows/SEND-01.yaml flows/SEND-04.yaml
```
Flags: `-d` adb serial (`$DEVICE`), `-a` appId (`$APP_ID`), `-i` include tags, `-x` exclude tags
(default `blocked`), `-n` run label. Explicit flow files run in the order given. Always start with `00-SETUP` and end with `ZZ-RESTORE` when you
run a subset that changes settings (passing whole `flows/` does this automatically).

Don't edit `run.sh`, `flows/` or `subflows/` while a run is going (bash and Maestro read them lazily).

Output in `runs/<YYYYmmdd-HHMMSS>-<label>/`: `junit.xml`, `summary.md`, `console.txt`, `app.txt`
(versions, apk sha256), `config.yaml` (the per-run order), `artifacts/` (screenshots + per-flow commands), `debug/`, `logcat.redacted.txt`, `scrub.txt`.
A full run takes about 2¼ h on a KVM emulator in batches of 6 (13–171 s per flow), and 3–4 h or more
without KVM (≈3 s per tap, ≈18 s per PIN entry).

Env vars the flows see: `APP_ID`, `HELPER`, `MAESTRO_SENTINEL_PIN`, and everything in `data/*.env`
(`VPUB_A..D`, `VPUB_A_ADDR0`, `TPUB`, `UPUB`, `XPUB/YPUB/ZPUB_MAIN`, `TB1_ADDR`, `MAIN_ADDR`, `TB1_BADSUM`,
`VPUB_TRUNC`, `VPUB_BADSUM`, `GARBAGE`, `DUMMY_BACKUP`, `DUMMY_BACKUP_TRUNC`,
`DUMMY_BACKUP_NET`, `DUMMY_SAMOURAI_SHAPE`).

### Secret handling
* Each PIN tap has a `label:` so the console and JUnit show "Tap PIN key", not the digit.
* Maestro's own debug logs still record env vars and evaluated selectors, so on exit run.sh runs
  `scripts/scrub.py`, which replaces any secret value from the secrets file (and each PIN-digit selector)
  with `<PIN-REDACTED>` / `<SECRET-REDACTED>` in every text file (binary files still containing a secret are deleted) of the run folder and `~/.maestro/tests`, and rewrites onion
  addresses as `<dojo>.onion`. `scrub.txt` logs what was replaced (counts only).
* Then `scripts/verify_scrub.py` re-scans the folder (counts only) for any secret, PIN key id or
  onion address and warns in `scrub.txt`. Run it by hand on any folder: `scripts/verify_scrub.py runs/<run>`.
* Inside a PIN entry the tapped key's text, id, bounds and tap coordinates are masked too (they
  would reveal the digit order).
* The lock screen is FLAG_SECURE, so screenshots there come back empty; screenshots are `optional`
  and never fail a flow.
* The clipboard helper never returns full clipboard contents: only equal/length/10-char prefix.
* SET-01 exports with a random password generated inside the flow and clears the clipboard after.

### App quirks the subflows work around
* **N3** lock timeout is 15 min after the last PIN entry; `home.yaml` re-locks and re-enters the
  PIN when the last entry is more than 9 min old.
* **N7/#295** ANR dialog after the PIN: `dismiss_anr.yaml` taps **Wait** (never "Close app").
* Maestro's `launchApp` always re-creates the launcher activity, which shows the PIN, so
  `home_basic.yaml` only launches when no app screen is in front.
* Lock cycles use Home → Back → "Yes" (exit) → relaunch, not force-stop. Exit stops Tor; the next
  Home restarts it.

## Coverage

113 plan cases are marked M. **85 have a flow**; 28 are not automated (below). Extras:
`00-SETUP`, `ZZ-RESTORE` (setup/teardown), `COL-12b` (whitespace variant of COL-12). Total 88 flows.
Tags: `smoke` (25 flows), `p0/p1/p2`, area tags, `known` (expected to fail because of a known
issue), `known-fixed` (regression checks for fixed issues), `slow`.

Covered: PIN-03/04/05/06/09/10/11/16/17 · SYNC-01/03/04/06/09/10/11/13/14/15/17/18 ·
TOR-01/03 · TOOL-01/04 · BC-05/06/09 · RCV-01/04/05/06/07/08/09/10 · PAIR-07/08 ·
UTXO-01/02/03/04/05/07 · SEND-01/02/04/05/06/07/08/09/10/11/13/16/17/18 · PSBT-02/03 ·
ROB-07/11 · SET-01/05/06/12/15 · COL-01..09/11/12/14..19/24.

Expected failures (tagged `known`): BC-09, UTXO-05, PIN-17 (N4), COL-12b (N9). SYNC-06 was `known` on
8cfb74d; the tag was dropped after it passed on v5.1.3-dojobay.6 and dojobay.8. See `../README.md` for the full list of failures to expect.

Not automated, and why:
| Cases | Reason |
|---|---|
| INS-01/02/03/05/06/07/08, TOR-02 | Need a fresh install / `pm clear`, which deletes the tester's data and pairing. Run on a separate clean emulator. |
| UPG-07, UPG-10 | Need installing another build. |
| PAIR-03, DOJO-02/03/04/06 | Need an unpaired app or the secret pairing payload; the pairing must not change. |
| PIN-01/02/07/08 | Would change or remove the PIN (and N8: the Settings PIN dialog was invisible in the 10-23 run; #296 retest later found the pad exposed). |
| SET-03/04/08/13 | Destructive (import over real data, erase) or need a fresh install. |
| SEC-01 | FLAG_SECURE check needs screencap comparison outside Maestro. |
| ROB-01 | ATD image has no Developer options ("Don't keep activities"). |
| ROB-04 | Needs toggling the emulator network. |
| MON-01/02 | `adb shell monkey`, not Maestro. |

To run the blocked install/pairing cases later: use a second, throwaway AVD and add flows tagged
`destructive`, excluded by default.
