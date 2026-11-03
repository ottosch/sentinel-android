# Sentinel Android QA

QA tooling for the DojoBay fork: the 6.0 test plan and a [Maestro](https://maestro.mobile.dev) UI
suite that automates the plan's repeatable cases. **Nothing here is part of the app build.** Gradle
doesn't read this folder and no app code depends on it.

Everything here runs on **testnet4 only**, against the staging app `com.samourai.sentinel.staging`.

## Layout

```
qa/
  README.md                 this file
  TEST_PLAN_6.0.md          224 cases by area (INS, UPG, PAIR, DOJO, TOR, PIN, COL, SYNC, UTXO, RCV,
                            SEND, PSBT, BC, TOOL, SET, NOT, SEC, ROB, MON), tagged P0/P1/P2,
                            M (Maestro) / E (exploratory), and ✓ (needs a human: signing, real pairing,
                            release key, mainnet)
  maestro/
    README.md               suite setup, flags, secret handling, coverage, app quirks
    run.sh                  one Maestro session -> runs/<timestamp>-<label>/ (scrubbed, chmod 700)
    run_batches.sh          the whole suite (or a list) in batches of 6, one session per batch
    config.yaml             generated: flow order
    flows/<ID>.yaml         generated: 88 flows, one per plan ID (+ 00-SETUP, ZZ-RESTORE, COL-12b)
    subflows/               unlock, home, lock, navigation, test-collection helpers, restore_baseline
    gen/                    flows_a..d.py (flow definitions) + build.py (writes flows/ and config.yaml)
    scripts/                helper.py (clipboard/QR/timestamp helper over the emulator gRPC), js/,
                            scrub.py + verify_scrub.py (secret scrubbing), summarize.py,
                            merge_batches.py, status.sh, progress.sh, gen_throwaway.py,
                            make_dummy_backup.py, qr_decode.py, hier.py
    data/*.env.example      templates for the generated throwaway test data (real *.env is git-ignored)
  docs/
    SETUP.md                        step-by-step setup of the emulator, build, helpers and release watch,
                                    then testing the same way (by hand or with an AI coding assistant)
```

**Edit flows in `maestro/gen/flows_*.py`, then run `python3 maestro/gen/build.py`.** Don't hand-edit
`flows/` or `config.yaml`; build.py rewrites them and YAML-checks every flow.

## How to run

1. **Set up the environment.** Follow [`docs/SETUP.md`](docs/SETUP.md)
   (by hand, or give it to an AI coding assistant to carry out). It covers the JDK, Android SDK, the
   `sentinel30atd` AVD (API 30 ATD x86_64, 1080×2340 @440 dpi, started with `-grpc 8554`), the build,
   pairing your own testnet Dojo, and app-data backup/restore. Then follow
   [`maestro/README.md`](maestro/README.md) → Setup for Maestro 2.11.0, the helper venv and the secrets file.
2. **App state the flows expect:** a PIN set (Scramble PIN on), paired to your own testnet4 Dojo, USD,
   and two collections named exactly **My Wallet** (a watch-only pairing import with Deposit/Premix/
   Postmix/Badbank tabs and a few UTXOs) and **Cold Storage** (watch-only, two keys, non-zero balance).
   The names are fixed in the flows; the keys and balances are your own throwaway testnet ones.
3. **Generate the throwaway data** (random, discarded, never-funded keys and a dummy backup):
   ```
   cd qa/maestro
   python3 scripts/gen_throwaway.py      # writes data/throwaway.env
   python3 scripts/make_dummy_backup.py  # writes data/backup.env (needs the `cryptography` package)
   ```
4. **Secrets file** (only the app PIN), outside the repo, `chmod 600`:
   `~/.sentinel-qa/secret/sentinel.env` containing `SENTINEL_PIN=...` (or point `SENTINEL_SECRETS_FILE` at it).
5. **Back up the app data**, then run:
   ```
   # smoke subset (25 flows, tag "smoke"), in config.yaml order:
   ./run_batches.sh -n smoke $(python3 -c "import yaml;o=yaml.safe_load(open('config.yaml'))['executionOrder']['flowsOrder'];print(*[i for i in o if 'smoke' in (yaml.safe_load(open('flows/'+i+'.yaml').read().split(chr(10)+'---')[0]).get('tags') or [])])")
   ./run_batches.sh -n full                     # whole suite in config.yaml order
   ./run_batches.sh -n rest -s PIN-09           # resume from a flow
   python3 scripts/merge_batches.py runs/full-batches.txt > runs/full-merged.md
   ```
   Or `./run.sh -i smoke -n smoke` for the smoke tag in one session, if it finishes inside 600 s.
6. **Afterwards, restore your backup** before you take a new known-good backup (ZZ-RESTORE resets the
   main settings, see S1 below, but not everything a run can change).

Paths are env-driven: `ANDROID_HOME` (default `~/Android/Sdk`), `JAVA_HOME`, `SENTINEL_QA_HOME`
(default `~/.sentinel-qa`: `venv/`, `grpcstub/`, `secret/`), `SENTINEL_SECRETS_FILE`, `DEVICE`, `APP_ID`.

## Batching: why ~6 flows per session

Maestro's device link drops partway through every long session: at about **600 s** on a
KVM-accelerated emulator, and about 13 min on a software-only one. After that Maestro raises
`DeviceServerDiedException` and fails every later flow in milliseconds. `run_batches.sh` therefore
starts a fresh Maestro session for every 6 flows (`-b 6`, the default), which keeps each session under
600 s with KVM. Use `-b 3` on a software-only emulator (`-accel off`). Rerun a failed flow once in its
own batch before calling it a real failure.

Timings from the v5.1.3-dojobay.6 run (KVM, batches of 6): 13–171 s per flow, about 2¼ h for the
84 distinct flows. Without KVM, expect 3–4 h or more and more link drops.

## Known suite issues

- **Flaky / flow-side failures:** `RCV-06` (amountSAT "12,345" assertion), `RCV-08` (key dropdown element),
  `SEND-04` (compose button state), `SYNC-09` (bottom-nav Send still visible). These fail the same way
  on 8cfb74d and on v5.1.3-dojobay.6, on both a software-only and a KVM emulator, so treat them as
  suite problems until someone fixes the flows.
- **S1 (fixed 2026-10-31): settings left wrong after `ZZ-RESTORE`.** `restore_baseline` used
  `checkbox rightOf: {title}`, which matches *any* checkbox to the right of the title (e.g. User PIN's),
  so it left Scramble PIN off, Street mode on, Hide fiat on and the fiat rate cleared. It now matches
  each checkbox inside its own preference row, asserts every toggle, reselects Currency USD so the rate
  is re-fetched, and checks Home shows unmasked balances and USD fiat. `run_batches.sh` also runs a
  pre-batch guard (prefs via `adb run-as`/root, else the read-only `subflows/baseline_guard.yaml`) and
  stops with exit 3 if Street mode is on. `SKIP_GUARD=1` disables it.
- **Collection-list selectors:** `PAIR-08`, `COL-03` and `COL-04` assume the collection list is below
  "Create new collection"; it's above. They knock on to `COL-15`, `COL-16`, `COL-19`, `COL-24`, `RCV-10`
  and `COL-18`, which depend on the `MaestroT*` collections and key labels those flows set up.
- **Clipboard and QR flows need the emulator gRPC** (`-grpc 8554` and `scripts/helper.py`); they fail on
  a real device. Everything else works on a device.

## Expected failures (as of v5.1.3-dojobay.6, tag commit 0385bc2c; re-baselined on dojobay.8)

Last full run (smoke set of 25, then the other 61; `00-SETUP` and `ZZ-RESTORE` run in both, 84 distinct
flows): **56 pass, 25 fail, 4 expected-fail, 1 unexpected pass**.

Re-baseline on v5.1.3-dojobay.8 (2026-11-01): `SYNC-06` passed on .8 and is no longer tagged `known`
(it was an unexpected pass from .6). `SET-01` stays a failure (#340) until #352 is in a build.

| Flows | Why |
|---|---|
| `BC-09`, `UTXO-05`, `PIN-17`, `COL-12b` | Tagged `known`: open app issues kept as regression cases (expected-fail) |
| `SET-01` | App bug #340 (Export Sentinel Backup shows no options) |
| `SYNC-09`, `RCV-06`, `RCV-08`, `SEND-04` | Flaky / flow-side (above) |
| `SYNC-04`, `UTXO-03`, `SEND-07`, `SEND-16` | Same failure on 8cfb74d (pre-existing; SEND-07: no dust guard for a 300-sat output; SEND-16: available amount doesn't change when switching Cold Storage keys) |
| `UTXO-07` | Pre-existing: `res/menu/utxo_menu.xml` is never inflated, so the filter action isn't in the toolbar |
| `PAIR-08`, `COL-03`, `COL-04` + `COL-15`, `COL-16`, `COL-19`, `COL-24`, `RCV-10`, `COL-18` | Collection-list selector problem (above) and its knock-ons |
| `SYNC-14` | Known fiat issue: the exchange-rate fetch fails, so the EUR rate never shows |
| `COL-06`, `PIN-06`, `SET-06` | Need a manual look; not claimed as app bugs |
| `ZZ-RESTORE` | Fails on "Blocked is not visible" when an earlier flow leaves a UTXO blocked; unblock it by hand |
| `ROB-11` | Double-tapping Compose stacked two Unsigned Transaction screens (probable app bug, not yet confirmed on an older build) |

Regression checks that pass on .6: #287 (failed sync wiping UTXOs), #305 (fee tiers), #337 (launch crash).

## Safety rules

- **Testnet4 only.** Never mainnet keys or funds. The mainnet `xpub/ypub/zpub/bc1` values in
  `data/throwaway.env` exist only to check they're rejected, and come from random discarded seeds.
- **Never commit or print** the app PIN, a Dojo pairing payload or its password, a Dojo API key, a
  real wallet's keys, addresses or balances, or a `.onion` address (write `<dojo>.onion`).
  `data/*.env` and `runs/` are git-ignored; keep secrets in `chmod 600` files outside the repo.
- The PIN reaches Maestro only as `MAESTRO_SENTINEL_PIN` (from the secrets file, never on a command line
  or in YAML). Every PIN tap is labelled, and `run.sh` runs `scrub.py` and `verify_scrub.py` on each run
  folder on exit. Check `scrub.txt` before sharing anything from `runs/`.
- The suite **never broadcasts**, never changes the Dojo pairing or the PIN, never taps "Erase all Sentinel
  data", and only creates and deletes throwaway `MaestroT*` collections (the delete subflow refuses any
  other name). Signing and broadcasting are always a human's call.
- Back up the app data before every run and restore it afterwards.
- Cases that need a fresh install, an unpaired app, PIN changes or destructive actions aren't automated;
  `maestro/README.md` lists them and why.
