# Sentinel Android test setup

This guide sets up an Android emulator test environment on your computer, builds Sentinel's staging app from the DojoBay fork, and then tests it the same way the maintainer's QA runs do.

You can follow the steps by hand, or give the whole file to an AI coding assistant to carry out. The same rules apply either way. If an assistant does it, it should ask you for the items below when it needs them, and you keep control of every secret and every signature.

**You supply these yourself:**
- **Your own Dojo** running on testnet4 and reachable over Tor, plus its pairing payload and password. Pair only with your own Dojo, never anyone else's.
- **Testnet4 wallets** to watch: an Ashigaru testnet wallet for the pairing import, and/or a vpub. Testnet coins only.
- **A PIN** for the app, kept in a private file.
- **Signing:** you sign any transactions in your own wallet, outside the test setup, and use the signed hex or QR.
- **The Maestro suite and test plan:** they're in this repo under `qa/` (see `qa/README.md`).
- **Optional:** a GitHub login on the test computer. It isn't needed for public releases.

---

## Steps

You'll be testing **Sentinel**, a watch-only Bitcoin wallet. Use the DojoBay fork **`tetragrammalol/sentinel-android-dojobay`**. Set everything up on your own computer, run the emulator headless, and work through the steps below in order. Note when each step is done, and anything that blocks you.

### Rules (these apply all the time)
1. **Testnet only** (testnet4). Never use mainnet keys or funds.
2. **Never print, log, echo or screenshot** the app PIN, the Dojo pairing payload, its password or the Dojo API key. Keep each one in its own `chmod 600` file in a `chmod 700` folder, and load them into environment variables only when a script needs them. Before you save a logcat, check it for secrets and redact anything you find.
3. **Redact onion addresses** as `<dojo>.onion` everywhere: notes, logs you keep, issue drafts, chat and screenshots you share.
4. **Only broadcast a transaction you've signed in your own wallet,** and only when you mean to. Compose PSBTs in the test setup, but never sign there.
5. **Don't change the app's Dojo pairing** (re-pair, remove it or edit it) unless a test needs it and you've taken a backup.
6. **Back up the app's data before any risky test,** such as an upgrade, an erase, a restore, process death or a stress run, and know how to restore it (step 5).
7. **Draft issues before posting them.** Write each issue as a draft for `tetragrammalol/sentinel-android-dojobay` and read it through for secrets before you post it. Use real issue numbers you've checked and no placeholders. An assistant should never post to GitHub itself.
8. **A follow-up to a closed issue becomes a new issue** that links to the old one, such as "Follow-up to #NNN", using the real number.
9. **Don't start testing a new pre-release until you've decided to.** An assistant should wait for your go-ahead.

### Setup
1. **Tools.** Install these in a working folder on your computer:
   - **Temurin JDK 21.** The project builds on JDK 17 or later; the reference setup uses 21.
   - **Android command-line tools**, with `platform-tools`, `emulator`, `platforms;android-35` and `build-tools;35.0.0`.
   - **System images** `system-images;android-30;aosp_atd;x86_64` and `system-images;android-34;google_apis;x86_64`.

   Then set `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`, and add `platform-tools`, `emulator` and `cmdline-tools/latest/bin` to your PATH. The reference setup uses Android Emulator 37.2.12.

2. **Main emulator.** Create an AVD called `sentinel30atd` from the API 30 ATD x86_64 image, with these settings in its `config.ini`:

   ```
   hw.lcd.width=1080
   hw.lcd.height=2340
   hw.lcd.density=440
   hw.ramSize=1536
   hw.cpu.ncore=4
   hw.keyboard=yes
   hw.camera.back=emulated
   ```

   Start it headless:

   ```
   emulator -avd sentinel30atd -no-window -no-audio -no-boot-anim -grpc 8554 -gpu swiftshader_indirect
   ```

   - Use KVM if your computer allows it. If not, add `-accel off`, and expect it to be slow: UI dumps take 5–20 s and boots take minutes.
   - Wait until `sys.boot_completed` is 1.
   - The ATD image doesn't draw apps by default. After every boot, run `adb root` and `adb shell setprop debug.hwui.drawing_enabled 1`, then relaunch any app that was already running.

3. **Second emulator, for navigation.** The ATD image has no navigation bar, so create an AVD called `sentinel34` from the **API 34 Google APIs** x86_64 image:
   - 1080×2400 at 420 dpi, 2048 MB of RAM, `hw.mainKeys=no`.
   - Run it with a different `-grpc` port.

   Use it for three-button and gesture navigation, and for the API 34 permission cases. Switch between the two with `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton` or `…navbar.gestural`. Only run one emulator at a time if memory is tight.

4. **Build and install the app.**
   - Clone `https://github.com/tetragrammalol/sentinel-android-dojobay`, check out the commit or tag you want to test (default: `master`), and write `sdk.dir` to `local.properties`.
   - Run `./gradlew :app:assembleStagingDebug` (Gradle 8.14.3 through the wrapper).
   - The APK is under `app/build/outputs/apk/staging/debug/`. Record its SHA-256, the commit, `versionName` and `versionCode`.
   - Install it with `adb install -r <apk>`. The package is `com.samourai.sentinel.staging`.
   - For an upgrade test, take a backup first (step 5), then install over the old build with `-r` so its data is kept.
   - **Upgrade-in-place only works between APKs signed with the same key.** The GitHub release APKs (`v5.1.3-dojobay.1` to `.6`) all share one certificate, so one release installs over another. An APK you build yourself is signed with your machine's debug key, so don't mix a local build with a release build on the same install. Check with `apksigner verify --print-certs <apk>` if unsure.
   - If `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the signers differ. Back up the app data (step 5), `adb uninstall com.samourai.sentinel.staging`, install the new APK, then restore the backup. Note in the results that this was a reinstall plus restore, not a true in-place upgrade.

5. **Helper scripts.** Write small scripts for these jobs. Each one should run without printing anything secret.
   - **Screenshot:** use the emulator's gRPC `getScreenshot` on the `-grpc` port. Generate the Python stubs from the emulator's `emulator_controller.proto` with `grpcio-tools`. This also captures FLAG_SECURE screens, such as the PIN pad and backup, which come out black with `adb exec-out screencap -p`. Fall back to `screencap` for normal screens, and reject all-black images.
   - **UI dump:** run `adb shell uiautomator dump`, then parse the text, content-desc, resource-id and bounds so you can tap elements by ID.
   - **PIN entry:** read the PIN from its private file, find each digit's button by its bounds on the lock screen (the keypad may be scrambled), and tap them. Never echo the digits.
   - **Backup/restore:** as root, force-stop the app and tar `databases`, `shared_prefs`, `files` and `app_wallet` from `/data/data/com.samourai.sentinel.staging` into your private folder. To restore, push the tar back, extract it, then fix ownership to the app's UID and run `restorecon`.
   - **Logcat:** capture `adb logcat` for the app's PID per test, then redact it.

6. **Pairing and wallets.** Do the Dojo pairing yourself:
   1. Start the app.
   2. Choose Testnet.
   3. Enter the Dojo pairing payload and password directly in the app. Never paste them into chat, issues or logs.
   4. Add your test wallets: the Ashigaru pairing import and/or a vpub.

   After that, check that Tor reaches "Enabled", the Dojo connects, and the balances sync. Take a backup straight away. That's your known-good restore point.

### How to test
7. **Test plan.** Use `qa/TEST_PLAN_6.0.md` from the repo (at the commit you're testing), unless you have a newer one.
   - Cases are grouped by area: INS, UPG, PAIR, DOJO, TOR, PIN, COL, SYNC, UTXO, RCV, SEND, PSBT, BC, TOOL, SET, NOT, SEC, ROB and MON.
   - Each case is tagged P0/P1/P2. **M** marks cases for Maestro, **E** marks exploratory cases, and **✓** (the "Human" column) marks cases that need a person, such as signing.
   - Run P0 first, then the smoke subset across both emulators, then P1/P2, and then monkey stress runs (`adb shell monkey` with fixed seeds).

8. **Maestro.** Install the Maestro CLI (`curl -fsSL https://get.maestro.mobile.dev | bash`) and point it at the running emulator. Use the suite in `qa/maestro/` of the repo exactly as provided (follow `qa/maestro/README.md` for setup, and run it with `run_batches.sh`). Don't rewrite it; propose changes as a PR instead.
   - Maestro runs the repeatable **M** cases on every build.
   - Test the **E** cases by driving the app with adb and UI dumps.
   - Cases that need a person (signing, real pairing, mainnet) are done by you, outside any automation.

   Inject the PIN into flows from an environment variable, never as a literal in a flow file.

9. **Results.** Keep each run in its own folder, `runs/<date>-<shortcommit>/`, with a `RESULTS.md` that has:
   - **A header:** build (repo, commit, the gradle task, package, versionName/versionCode), APK SHA-256, device/AVD, install method and the backup you took, the date with time zone, any interruptions, and a line confirming no secrets were captured.
   - **A final summary:** counts of pass/fail/blocked/partial by case ID, P0 cases not run and why, known bugs (fixed or still present), and a one-line list of new bugs.
   - **A results table:** `| ID | Result | Notes |`, where each note says what you did, what happened, and which evidence files back it up.
   - **One issue draft per new bug,** with title, steps, expected vs actual, build, device, evidence, and a stack trace if there is one. Before calling a bug new, search the fork's open and closed issues and say whether it's a duplicate.
   - **Credit for fixes** based only on what the commit history shows.

   Save screenshots in `shots/` and redacted logs in `logs/` (`chmod 600`).

10. **Transactions.** For a send test:
    1. Compose the PSBT in the app.
    2. Copy it out and decode it to check inputs, outputs, change derivation and fee.
    3. Sign it in your own wallet.
    4. Broadcast only that signed hex or QR, through **Tools → Broadcast Signed Transaction**.

    Testnet4 confirmations can take a day or more, so check back on them later rather than waiting.

### Watching for releases
11. **Release watch.** Set up a scheduled job that checks `https://api.github.com/repos/tetragrammalol/sentinel-android-dojobay/releases` every 2 hours, including pre-releases.
    - Record the releases you've already seen in a small state file.
    - When a new tag appears, report the tag, the date (in your time zone), the release notes and the APK asset.
    - **Don't download or test it until you've decided to.**

When the setup is done, check and note:
- the tool versions you installed
- both AVDs and their settings
- the build commit, APK SHA-256 and versionName
- confirmation that the screenshot, PIN, backup and restore helpers work (without showing any secrets)
- whether Maestro is installed and the suite is in place
- that the release watch is running

Then decide when to start a test run. An assistant should wait for your go-ahead.
