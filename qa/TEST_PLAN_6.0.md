# Sentinel 6.0 test plan (stability pass)

**Build under test:** fork `tetragrammalol/sentinel-android-dojobay` master `e96a983`, staging `5.1.3-dojobay.4` (versionCode 57, minSdk 24, targetSdk 33). The same plan applies to every new 6.0 candidate APK. Last full Maestro pass against `v5.1.3-dojobay.6` (tag commit `0385bc2c`); see `README.md` for the expected failures.
**Baseline:** V5.1.2 (production, `com.samourai.sentinel`, versionCode 55).
**Goal:** a stable 6.0. Later this gets split into a repeatable regression suite and change-specific plans.

**Tags**
- **P:** P0 = blocks 6.0 · P1 = must be fixed or knowingly accepted · P2 = polish
- **Auto:** M = good candidate for the scripted Maestro suite · E = exploratory only
- **Human:** ✓ = needs a human maintainer (signing in Ashigaru, real pairing, the release key, or a mainnet check)
- **(known)** = a bug already seen, kept as a regression case. **confirm** = not fully verified the behaviour in code

---

## 1. How runs are done

- **Where:** testnet only, on the QA emulator (AVD `sentinel30atd`, Android 11 ATD, software rendering, slow). Extra AVDs for API 24 and 34/35 get created for the matrix. Nothing runs on mainnet unless a human maintainer does it (marked ✓).
- **Data:** the throwaway testnet wallets "My Wallet" (Ashigaru pairing) and "Cold Storage" (one vpub), plus random throwaway vpubs/tpubs/zpubs made from discarded seeds. Secrets (pairing payload, its password, Dojo API key, app PIN) never go into notes, screenshots or issues.
- **Spending:** the tester composes PSBTs. Signing is always done by a human in Ashigaru. The tester only broadcasts a tx a human has signed and asked to be broadcast.
- **Evidence:** a screenshot (emulator gRPC capture, which works on FLAG_SECURE screens), `adb logcat` for the app's PID, and the APK SHA-256 for each run.
- **Per run output:** a pass/fail summary table (ID, result, note) plus one `.md` issue draft per new bug, written for `tetragrammalol/sentinel-android-dojobay`, with steps, expected vs actual, build, device and evidence. The tester doesn't post anything; a maintainer decides what goes up.
- **Order:** P0 cases first, then the smoke subset across the matrix, then P1/P2, then the monkey runs.

### Exit criteria for a stable 6.0
1. No open P0. Every known bug marked P0 below has been fixed and re-tested.
2. Every P1 is fixed, or the maintainers have accepted it in writing (release notes or a known-issues list).
3. The smoke subset passes on API 24, 30 and 34/35.
4. The upgrade from V5.1.2 passes on the **production** flavour, signed with the release key (UPG-01 to UPG-06).
5. 5 monkey seeds × 10,000 events with no crash or ANR.
6. No secrets in logcat or the error log on the release build (SEC-03, SEC-04).

---

## 2. Install, first launch, network choice (INS)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| INS-01 | Fresh install, launch | No app | Install the APK and open it | "Choose network" sheet (Mainnet / Testnet), no crash | P0 | M | |
| INS-02 | Choose Testnet | INS-01 | Tap Testnet | Testnet saved. Camera prompt, then the notification prompt (API 33+), then the Dojo sheet, one at a time | P0 | M | |
| INS-03 | Choose Mainnet | INS-01 | Tap Mainnet | Mainnet saved. Network screen says Mainnet | P1 | M | |
| INS-04 | Back/dismiss the network sheet | INS-01 | Press back or tap outside | A choice is still forced, or a sane default is applied (confirm) | P1 | E | |
| INS-05 | Camera permission denied | INS-02 | NO in the in-app dialog, then deny in the system dialog | The paste path still works and nothing crashes | P1 | M | |
| INS-06 | Notification permission denied (API 33+) | INS-02 | Deny | "Notification permissions denied." toast. The app works | P2 | M | |
| INS-07 | Dismiss the first-run Dojo sheet | INS-02 | Swipe it away | Empty home with NEW. The Dojo can be set up later from Network | P1 | M | |
| INS-08 | Empty home header | Fresh install | Look at the total | Shows `0.00000000 BTC` like V5.1.2, not blank **(known)** | P2 | M | |
| INS-09 | Kill during first-run sheets | INS-01 | Swipe the app away mid-sequence, then reopen | Resumes cleanly with no duplicate sheets or crash | P1 | E | |
| INS-10 | Reinstall after uninstall | Data present | Uninstall, reinstall | Clean first-run state with no leftover data | P2 | E | |

## 3. Upgrade from V5.1.2 and migration (UPG)

The staging build uses a separate applicationId (`.staging`), so it **cannot** test the real upgrade. UPG-01 to UPG-06 need a production-flavour 6.0 APK signed with the V5.1.2 release key.

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| UPG-01 | Install over V5.1.2 | V5.1.2 with collections, PIN, Dojo, Tor on | `adb install -r` with the 6.0 production APK | Installs with no signature or downgrade error. versionCode is above 55 | P0 | E | ✓ |
| UPG-02 | Collections survive | UPG-01 | Open the app | Same collections, keys, labels and balances as before | P0 | E | ✓ |
| UPG-03 | PIN survives | UPG-01 | Relaunch | Asks for the old PIN, which works. Scramble setting kept | P0 | E | ✓ |
| UPG-04 | Dojo and Tor survive | UPG-01 | Open Network | Dojo still paired. Tor starts on the new kmp-tor and syncs | P0 | E | ✓ |
| UPG-05 | Prefs survive | UPG-01 | Open Settings | Currency, exchange, explorer, street mode, hide fiat, screenshots setting all kept | P1 | E | ✓ |
| UPG-06 | UTXO blocked list and labels survive | Do-not-spend UTXO set in 5.1.2 | Upgrade, then open UTXOs | Still in Blocked. Tx and UTXO labels kept | P1 | E | ✓ |
| UPG-07 | Same-key reinstall of the staging build | Staging 5.1.3 beta with data | Install the new staging APK over it | Data kept (proxy for UPG-02 that the tester can run alone) | P1 | M | |
| UPG-08 | Legacy Sentinel migration screen | Old pre-5.x data (confirm if still reachable) | Launch | MigrationActivity moves the pubkeys and offers the Dojo dialog, or is never shown on 5.x data | P2 | E | |
| UPG-09 | Downgrade attempt | 6.0 installed | Install V5.1.2 over it | Refused cleanly (`INSTALL_FAILED_VERSION_DOWNGRADE`), 6.0 data intact | P2 | E | |
| UPG-10 | 5.1.2 backup into 6.0 | Backup file exported from 5.1.2 | Fresh 6.0 → Import Sentinel Backup | Collections, prefs and Dojo imported | P0 | M | |

## 4. Pairing import from Ashigaru (PAIR)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| PAIR-01 | Import a watch-only pairing (paste) | Testnet, Ashigaru payload + password | NEW → Paste → password | "My Wallet" with Deposit, Premix, Postmix, Badbank. Balances right after sync | P0 | E | ✓ |
| PAIR-02 | Import by QR scan | Same, camera | NEW → scan the Ashigaru QR | Same as PAIR-01 | P1 | E | ✓ |
| PAIR-03 | Wrong pairing password | Payload | Enter a wrong password | Clear error, nothing created, can retry | P0 | M | |
| PAIR-04 | Mainnet payload on a testnet install | Testnet install | Paste a mainnet payload | Refused with a clear message | P1 | E | ✓ |
| PAIR-05 | Payload that includes a Dojo | Payload with Dojo | Import | Dojo is set up too (or offered), and keys are registered "x of n" | P1 | E | ✓ |
| PAIR-06 | Import the same pairing twice | PAIR-01 done | Import again | No duplicate collection, or a clear message | P1 | E | ✓ |
| PAIR-07 | Imported collection restrictions | PAIR-01 | Open edit; ⋮ on a key | No ADD NEW. Key menu has View Master Fingerprint and Delete, no Edit | P2 | M | |
| PAIR-08 | Imported collection not offered as a target | PAIR-01 | NEW → paste a vpub → Choose collection | My Wallet isn't listed | P2 | M | |

## 5. Dojo (DOJO)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| DOJO-01 | Pair by QR | Tor on, own Dojo QR | Network → Dojo ENABLE → Connect to your Dojo → scan | Connected, "n derivation paths registered" | P0 | E | ✓ |
| DOJO-02 | Pair by pasting JSON | Payload in the clipboard | Paste JSON Credentials | Same as DOJO-01 | P0 | M | ✓ |
| DOJO-03 | DojoBay privacy warning | Tor on | Browse community Dojos | Privacy dialog first. CANCEL backs out, CONTINUE shows the list | P1 | M | |
| DOJO-04 | Pick a community Dojo | DOJO-03 | Pick wanderinKing072 | Connects and registers paths. Snackbar shown | P0 | M | |
| DOJO-05 | DojoBay list unreachable | Tor blocked or offline | Open the picker | Error with retry, no endless spinner | P1 | E | |
| DOJO-06 | Malformed JSON | — | Paste random text or broken JSON | "Invalid payload"-type error, no crash | P0 | M | |
| DOJO-07 | Expired or wrong API key | Old payload | Paste | Auth fails with a clear message. Not shown as connected | P1 | E | |
| DOJO-08 | Mainnet Dojo on testnet | Testnet | Paste a mainnet payload | Refused, or the mismatch is flagged | P1 | E | ✓ |
| DOJO-09 | Dojo unreachable after pairing | Paired | Dojo goes offline (or block the onion) → sync | Sync fails visibly, nothing reported as success (see SYNC-05) | P0 | E | |
| DOJO-10 | Registration progress | Many keys | Pair | "Registering derivation paths… x of n" counts up. A failed path is retried or reported | P1 | E | |
| DOJO-11 | Disable Dojo | Paired | Network → Dojo disable | Unpaired. The app falls back to its default backend (confirm which) and says so | P1 | E | |
| DOJO-12 | Switch Dojo | Paired to A | Pair to B | Uses B only. Keys registered on B | P2 | E | |
| DOJO-13 | Dojo with Tor off | Tor off | Try to pair an onion Dojo | Blocked or prompts to enable Tor. No clearnet leak | P1 | E | |
| DOJO-14 | Scanner sheet with a non-Dojo QR | — | Scan an address QR in the Dojo scanner | Rejected with a message | P2 | E | |

## 6. Tor (TOR)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| TOR-01 | Enable Tor | Tor off | Network → Tor enable | Bootstraps to ON with progress. Notification shown | P0 | M | |
| TOR-02 | Tor on by default | Fresh install | Check Network after setup | Tor on by default (confirm for the fork) | P1 | M | |
| TOR-03 | Disable Tor | Tor on | Disable | Stops. Notification gone. Clearnet calls only where allowed | P1 | M | |
| TOR-04 | Stuck bootstrap: watchdog cheap restart | Block Tor traffic (emulator firewall) | Enable Tor, wait | Watchdog restarts Tor after the stall time (log "watchdog: boot=0 stall") | P0 | E | |
| TOR-05 | Stuck bootstrap: give up | TOR-04 still blocked | Wait past all recoveries | Toast "Tor failed to bootstrap — retry Tor from settings", state OFF, not "initializing" forever | P0 | E | |
| TOR-06 | Retry after unblocking | TOR-05 | Unblock and retry from Network | Bootstraps. The retry budget resets | P1 | E | |
| TOR-07 | Stale-state wipe recovery | Long stall | Keep blocked past the wipe threshold | Tor data wiped and restarted once. No crash | P1 | E | |
| TOR-08 | TLS over Tor fails closed (#229/#230) | Tor starting, not live | Trigger a clearnet-TLS call (exchange rate, DojoBay list) before SOCKS is live | Request waits or fails. Never goes direct | P0 | E | |
| TOR-09 | Host-scoped TLS (#229) | Tor on | Exchange rate, DojoBay, explorer | Work over Tor. Certificate checks are only relaxed for the intended hosts (confirm with TlsPolicy) | P0 | E | |
| TOR-10 | Network change mid-bootstrap | Tor bootstrapping | Toggle Wi-Fi/airplane | Recovers when the network returns | P1 | E | |
| TOR-11 | App swiped from recents | Tor on | Swipe away | Tor service stops per manifest config. Clean start next launch | P2 | E | |
| TOR-12 | Tor status in the explorer | Tor on | Tx → explorer → ⋮ Tor status | Shows status. Page loads over the Tor proxy | P2 | E | |

## 7. PIN and lock (PIN)

The PIN is 5–8 digits (AccessFactory). Lock timeout is 15 min. No wrong-attempt limit was found or wipe in code (confirm).

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| PIN-01 | Set a PIN | No PIN | Settings → User PIN code → enter → confirm | PIN saved. Next launch asks for it | P0 | M | |
| PIN-02 | Confirmation mismatch | PIN-01 flow | Confirm with different digits | Error. Nothing saved | P0 | M | |
| PIN-03 | Length limits | PIN-01 flow | 4 digits, then 9 digits | ✓ only appears at 5+. No more than 8 digits accepted | P1 | M | |
| PIN-04 | Unlock with the right PIN | PIN set | Launch, enter PIN | Home opens | P0 | M | |
| PIN-05 | Wrong PIN | PIN set | Enter a wrong PIN | Rejected, field cleared, can retry. No crash | P0 | M | |
| PIN-06 | Many wrong attempts | PIN set | 20 wrong PINs | Behaviour noted. No lockout exists in code, so flag it to the maintainers (confirm) | P1 | M | |
| PIN-07 | Change the PIN | PIN set | Old PIN → new → confirm | Only the new PIN works | P0 | M | |
| PIN-08 | Turn the PIN off | PIN set | Toggle off (needs the PIN) | No prompt on the next launch | P1 | M | |
| PIN-09 | Scramble on | PIN set | Enable Scramble PIN entry, lock | Digits shuffled, and differently each time. The right PIN still works | P1 | M | |
| PIN-10 | Scramble off | PIN-09 | Disable | Keypad in order | P2 | M | |
| PIN-11 | Back and clear keys | Any PIN screen | Delete one, long-press clear | Mask dots update correctly | P2 | M | |
| PIN-12 | Haptics toggle | PIN set | Haptic off/on | Vibrates only when on (confirm on a real device) | P2 | E | |
| PIN-13 | Lock after 15 min | PIN set | Background 16 min (or set the clock forward) → resume | Back to the PIN screen. Nothing shown behind it | P0 | E | |
| PIN-14 | No lock before timeout | PIN set | Background 1 min → resume | No prompt | P2 | E | |
| PIN-15 | Recents thumbnail | PIN set, Disable Screenshots on | Open the app switcher | Thumbnail blank | P1 | E | |
| PIN-16 | Rotate during PIN entry | PIN screen | Enter 3 digits, rotate | No crash. Entry kept or cleared cleanly | P1 | M | |
| PIN-17 | PIN prompts for sensitive actions | PIN set | Backup export, import, erase, PIN change | Each asks for the PIN first | P0 | M | |
| PIN-18 | Process death on the PIN screen | PIN screen | `am kill` while backgrounded → resume | Returns to the PIN, not past it | P0 | E | |

## 8. Collections and keys (COL)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| COL-01 | New collection from a vpub | Testnet | NEW → paste vpub → Create new → label → name → save | Collection with a BIP84 key. Syncs | P0 | M | |
| COL-02 | Add a 2nd key in edit | COL-01 + a 2nd vpub | Edit → ADD NEW → paste → label → save | Both keys listed and summed | P0 | M | |
| COL-03 | Add a key via Home → choose collection | COL-01 + a 2nd vpub | NEW → paste → pick Cold Storage | Edit opens **with the existing key listed**, plus the new one | P0 | M | |
| COL-04 | Duplicate via Home: empty edit (known) | COL-01 | NEW → paste the same vpub → pick the collection | "Already exists" message. Existing keys stay listed. Not an empty edit screen **(known)** | P0 | M | |
| COL-05 | Duplicate then add: data loss (known) | COL-04 state | ADD NEW → paste a new key → save | Old keys are **kept**. Today they're silently deleted (CollectionEditActivity ~L150-158) **(known)** | P0 | M | |
| COL-06 | Duplicate in edit | COL-01 | Edit → ADD NEW → same vpub | Snackbar "Public key already exists in this collection". Keys kept | P1 | M | |
| COL-07 | tpub on testnet (known) | Testnet | Paste a tpub (BIP44) | Accepted as BIP44, or rejected with a message. Never silently ignored **(known)** | P1 | M | |
| COL-08 | upub/BIP49 on testnet | Testnet | Paste upub | Accepted as BIP49 (confirm), correct addresses | P1 | M | |
| COL-09 | Mainnet keys on testnet | Testnet | Paste xpub, ypub, zpub | Rejected with a network-mismatch message | P1 | M | |
| COL-10 | Mainnet xpub/ypub/zpub | Mainnet install | Add each | Right address type (1…/3…/bc1q…). Balance matches an explorer | P0 | E | ✓ |
| COL-11 | Single address | Testnet | Paste a tb1 address | Address-only key. Excluded from Send's From list | P1 | M | |
| COL-12 | Invalid input | — | Paste garbage, a truncated vpub, a vpub with a bad checksum, whitespace around it | Clear error for the bad ones. Trimmed for the whitespace | P0 | M | |
| COL-13 | Scan a key QR | Camera | Scan a vpub QR | Same as paste | P1 | E | |
| COL-14 | Edit a key label | COL-01 | ⋮ → Edit → rename | New label everywhere (tabs, Send From, Receive) | P1 | M | |
| COL-15 | View master fingerprint | COL-01 | ⋮ → View Master Fingerprint | Shows the fingerprint (e.g. af79922f) | P2 | M | |
| COL-16 | Delete a key | 2 keys | ⋮ → Delete → confirm | Only that key removed. Balance updates | P0 | M | |
| COL-17 | Rename a collection | COL-01 | Edit name → save | Home shows the new name | P1 | M | |
| COL-18 | Delete a collection | COL-01 | Edit → Delete collection | Gone from home. Total updates. Other collections untouched | P0 | M | |
| COL-19 | Key QR dialog | COL-01 | Tap the key card | QR + text. COPY TO CLIPBOARD and SHARE QR work | P2 | M | |
| COL-20 | Many keys | — | 15 keys in one collection, 10 collections | Scrolls, syncs, tabs usable, no ANR | P1 | E | |
| COL-21 | Unsaved edit + back | Edit with changes | Press back | Discard prompt, or changes clearly lost (confirm) | P2 | E | |
| COL-22 | Same key in two collections | Two collections | Add the same vpub to both | Allowed or blocked. If allowed, the home total doesn't double-count (seen on 5.1.2) | P1 | E | |
| COL-23 | Reorder | 3 collections | Try drag to reorder | No reorder UI found in code (confirm). Order stays stable across restarts | P2 | E | |
| COL-24 | Very long names and labels, emoji | — | 100-char name, emoji label | No layout break or crash | P2 | M | |

## 9. Sync, balances, fiat, transactions (SYNC)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| SYNC-01 | First sync | New collection | Wait | Balances match the Dojo. Home total = sum of collections | P0 | M | |
| SYNC-02 | List refreshes after sync (known) | Incoming tx | Wait for sync | Balance and list update without needing another sync **(known)** | P0 | E | |
| SYNC-03 | Pull to refresh (known) | Collection | Pull down | Triggers a sync with a spinner **(known: does nothing)** | P1 | M | |
| SYNC-04 | UTXO ⋮ Refresh | UTXO screen | ⋮ → Refresh | Re-fetches | P2 | M | |
| SYNC-05 | Failed sync reported as failed (known) | Tor SOCKS failing (TTL expired) | Sync | Error shown. Never "success" **(known)** | P0 | E | |
| SYNC-06 | Total while syncing | Sync in progress | Look at home | Last known total, not blank. Fiat not "00.00" (fixed; passes on dojobay.6 and .8) | P1 | M | |
| SYNC-07 | Pending incoming tx | Send tBTC to a watched address | Wait | Yellow Pending row with the amount. Then it confirms | P0 | E | ✓ |
| SYNC-08 | Outgoing tx shown | After a broadcast | Wait | Outgoing amount, fee, change handled. Balance drops by amount + fee | P0 | E | ✓ |
| SYNC-09 | Key tabs | Multi-key collection | All, then each key tab | Header and list filtered per key | P0 | M | |
| SYNC-10 | Key tab before Receive (known) | Fresh process | Open collection → tap a key tab first | No crash. Today: StackOverflowError in ReceiveFragment.setDropDownPub **(known)** | P0 | M | |
| SYNC-11 | Tx detail sheet | Tx list | Tap a tx | Amount, fiat, txid, block, confirmations, fee and sat/vB right. Copy works | P1 | M | |
| SYNC-12 | Open in explorer | SYNC-11 | Explorer button | In-app explorer over Tor. ⋮ Copy tx id works | P1 | E | |
| SYNC-13 | Balance help | Settings → Balance query help | Open | Dialog readable | P2 | M | |
| SYNC-14 | Currency change | Settings → Currency → EUR | Back to home | Fiat in EUR everywhere | P1 | M | |
| SYNC-15 | Exchange change | Settings → Exchange | Switch source | Rate updates, no crash | P2 | M | |
| SYNC-16 | Rate unavailable | Offline | Open home | Fiat hidden or the last rate. Never 0 or "00.00" | P1 | E | |
| SYNC-17 | Hide fiat | Toggle on | Look around | No fiat on home, details, Send | P2 | M | |
| SYNC-18 | Street mode | Toggle on | Home, collection, tx list | Balances hidden. Tap to reveal (confirm), lists masked | P1 | M | |
| SYNC-19 | Whirlpool auto-labels | Toggle on, Ashigaru pairing | Tx list | Premix/Postmix txs labelled. Off → labels gone | P2 | E | |
| SYNC-20 | Large history | Wallet with 500+ txs (confirm if we have one) | Scroll | Smooth, no ANR, dates grouped right | P2 | E | |
| SYNC-21 | New block / new tx via websocket | App open, Dojo paired | Tx arrives | Appears without manual refresh (WebSocketService) | P1 | E | ✓ |

## 10. UTXOs (UTXO)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| UTXO-01 | List | Collection ⋮ → View Unspent Outputs | Open | Active list, sum = balance | P1 | M | |
| UTXO-02 | Mark Do Not Spend | UTXO-01 | Select → Mark as do not spend | Moves to Blocked. Send's available amount drops | P0 | M | |
| UTXO-03 | Mark Spendable | UTXO-02 | Mark as Spendable | Back in Active | P1 | M | |
| UTXO-04 | Blocked UTXO not spent | UTXO-02 | Compose with Max | PSBT doesn't use the blocked UTXO | P0 | M | |
| UTXO-05 | Detail toolbar (known) | UTXO-01 | Open a UTXO | Toolbar shows its title and actions **(known: empty)** | P2 | M | |
| UTXO-06 | Send from a UTXO | UTXO detail | ⋮ Send | Send opens with that UTXO (confirm coin control behaviour) | P1 | E | |
| UTXO-07 | Filter | UTXO screen | Filter icon | Filters work. Reset restores | P2 | M | |
| UTXO-08 | Label chips (#227) | UTXOs with labels | Look at rows | Labels shown per the scope-first rule, no stale chips | P2 | E | |

## 11. Receive (RCV)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| RCV-01 | Address + QR | Collection | Receive | tb1q… address and matching QR | P0 | M | |
| RCV-02 | Address correct | RCV-01 | Compare with Ashigaru/Sparrow for the same key | Same address at the shown path | P0 | E | ✓ |
| RCV-03 | Next unused address | Address got funds | Reopen Receive after sync | Moves to the next unused index | P1 | E | ✓ |
| RCV-04 | Copy | RCV-01 | Tap copy | Clipboard has exactly the address | P1 | M | |
| RCV-05 | Share | RCV-01 | ⋮ Share | Share sheet with address/QR | P2 | M | |
| RCV-06 | Advanced: amount | RCV-01 | Advanced → BTC and sat amounts | QR becomes a BIP21 URI with the amount | P1 | M | |
| RCV-07 | Advanced: type and path | RCV-01 | Advanced | Type and path match the key | P2 | M | |
| RCV-08 | Switch key | Multi-key | Pick another key in the dropdown | Address changes to that key | P0 | M | |
| RCV-09 | Switch key tabs repeatedly | Multi-key | Tab ↔ Receive ↔ tab 20 times | No crash or loop (see SYNC-10) | P0 | M | |
| RCV-10 | BIP44/49 keys | tpub/upub keys | Receive | m…/n… or 2… addresses with the right path | P1 | M | |

## 12. Send and compose (SEND)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| SEND-01 | Compose a normal send | Funded key | Address, 0.001, Normal → COMPOSE | Unsigned Transaction screen with a PSBT | P0 | M | |
| SEND-02 | Address paste and scan | Send | Paste icon; scan a QR | Field filled with the full address (typing truncated it before) | P1 | M | |
| SEND-03 | BIP21 URI | Send | Scan `bitcoin:` with an amount | Address and amount filled | P1 | E | |
| SEND-04 | Invalid address | Send | Garbage, mainnet address on testnet, bad checksum | "Invalid address". COMPOSE blocked | P0 | M | |
| SEND-05 | Amount > balance | Send | Amount above available | Blocked with a message | P0 | M | |
| SEND-06 | Zero / empty / too many decimals | Send | 0, empty, 0.123456789 | Blocked or limited to 8 decimals | P1 | M | |
| SEND-07 | Dust output | Send | 0.00000300 | Refused as dust, or warns (confirm threshold) | P1 | M | |
| SEND-08 | Max (tap the available amount) | Send | Tap the total | Fills the available minus blocked UTXOs. Composes with fee taken from the amount, no change | P0 | M | |
| SEND-09 | BTC ↔ fiat fields | Send | Type fiat, then BTC | Each converts correctly | P2 | M | |
| SEND-10 | Fee Low/Normal/High | Send | Each priority | Rate and total fee shown. Normal sits between Low and High (see risks) | P1 | M | |
| SEND-11 | Fee slider custom | Send | Drag the slider to 1 sat/vB and to max | Fee updates. PSBT uses it | P1 | M | |
| SEND-12 | All fee estimates equal | Low fee environment (testnet) | Open Send | Low/Normal/High still sensible, no 0 sat/vB | P2 | E | |
| SEND-13 | Multiple UTXOs | Key with several small UTXOs | Send more than the largest | PSBT has several inputs, sums right | P0 | M | |
| SEND-14 | Change address (known) | Multi-output case | Compose where change isn't output 0 | Change goes to the wallet's own next change address with the right derivation (TransactionComposer 277-292) **(known)** | P0 | E | ✓ |
| SEND-15 | Sign the PSBT in Ashigaru | SEND-01 | You scan/sign | Ashigaru accepts it, shows the right amounts and change as "own" | P0 | E | ✓ |
| SEND-16 | From dropdown | Multi-key | Pick each key | Balance switches. Address-only and premix keys excluded | P1 | M | |
| SEND-17 | Back from the PSBT screen | SEND-01 | Back | Form kept or cleared cleanly. No crash | P2 | M | |
| SEND-18 | Rotate on the Send form | Filled form | Rotate | Values kept, no crash | P1 | M | |

## 13. PSBT export (PSBT)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| PSBT-01 | Animated QR | SEND-01 | Watch the QR | UR crypto-psbt parts 1…n cycle. Decoding them gives the same PSBT | P0 | E | |
| PSBT-02 | Copy | SEND-01 | COPY | Clipboard = PSBT (base64/hex as stated) | P1 | M | |
| PSBT-03 | Share as text | EXPORT | Share as text | Share sheet with the text | P2 | M | |
| PSBT-04 | Save as text file | EXPORT | Save as text file | File created and readable | P2 | E | |
| PSBT-05 | Share as .psbt | EXPORT | Share as psbt | Valid binary PSBT | P1 | E | |
| PSBT-06 | Save as .psbt | EXPORT | Save as psbt file | File opens in Sparrow | P1 | E | |
| PSBT-07 | PSBT contents | SEND-01 | Decode with `bitcoin-cli decodepsbt` | Inputs, outputs, fee, bip32 derivations and fingerprint right | P0 | E | |
| PSBT-08 | Large PSBT QR | 10+ inputs | Compose | QR still animates and scans | P2 | E | |

## 14. Broadcast (BC)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| BC-01 | Broadcast a signed tx | Signed hex from a human signer | Tools → Broadcast → PASTE → BROADCAST | "Broadcast Successful" sheet with the txid | P0 | E | ✓ |
| BC-02 | From the PSBT screen | SEND-01 + signed tx | BROADCAST TRANSACTION → import the signed tx | Same as BC-01 | P0 | E | ✓ |
| BC-03 | Scan a signed tx QR | Signed tx QR | SCAN | Hex filled | P1 | E | ✓ |
| BC-04 | Import from file | Signed tx file | IMPORT | Hex filled | P1 | E | ✓ |
| BC-05 | Invalid hex | — | Paste garbage | Button disabled or clear error. No crash | P0 | M | |
| BC-06 | Unsigned PSBT pasted | SEND-01 | Paste the unsigned PSBT | Explains it must be signed first | P1 | M | |
| BC-07 | Already broadcast | BC-01 done | Broadcast the same hex | Clear "already in mempool/chain" message, not success | P1 | E | ✓ |
| BC-08 | Fee too low | Tx at 0.5 sat/vB | Broadcast | Dojo rejection shown. Not reported as success | P1 | E | ✓ |
| BC-09 | Hex box cleared too early (known) | Invalid hex or failure | Broadcast | Hex stays for a retry **(known)** | P2 | M | |
| BC-10 | Broadcast offline | Airplane mode | Broadcast | Error. Hex kept. No false success | P0 | E | |
| BC-11 | Kill during broadcast | BC-01 in progress | Kill the app | No duplicate or corrupted state on reopen. Tx state honest | P1 | E | ✓ |

## 15. Tools (TOOL)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| TOOL-01 | Tools menu | Home ⋮ → Tools | Open | Sweep private key and Broadcast Signed Transaction | P2 | M | |
| TOOL-02 | Sweep: valid testnet WIF | Throwaway testnet WIF with funds | Sweep → paste → preview | Finds UTXOs across formats and shows a preview | P1 | E | |
| TOOL-03 | Sweep: complete | TOOL-02 | Confirm the sweep | Funds land at a watched address. Key not stored | P1 | E | ✓ |
| TOOL-04 | Sweep: invalid / empty key | — | Garbage; a key with no funds | Clear messages | P1 | M | |
| TOOL-05 | Sweep: BIP38 or timelocked (confirm) | — | Paste | Handled or refused cleanly | P2 | E | |
| TOOL-06 | Sweep key not in logs | TOOL-02 | Check logcat and the error log | No WIF anywhere | P0 | E | |

## 16. Settings, backup, labels, wipe (SET)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| SET-01 | Export backup to clipboard | Data, PIN | Export → PIN → Copy → password → ENCRYPT | Encrypted blob copied | P0 | M | |
| SET-02 | Export backup to file | — | Save to file | Picker opens and the file is written (needs a non-ATD image) | P1 | E | |
| SET-03 | Import backup, all options | SET-01 blob | Fresh install → Import → paste → password → import | Collections, prefs, Dojo restored. Sync works | P0 | M | |
| SET-04 | Import options individually | SET-01 | Untick Dojo / Preferences / Clear existing | Only the ticked parts change | P1 | M | |
| SET-05 | Wrong backup password | SET-01 | Wrong password | "Incorrect password, please try again." | P0 | M | |
| SET-06 | Corrupt backup | — | Truncated blob | Clear error, nothing changed | P1 | M | |
| SET-07 | Import Dojo over Tor retry | Tor just started | Import with Dojo | "Waiting for Tor circuit… (attempt x of 3)". Then success or a clear failure | P1 | E | |
| SET-08 | Restore from the first-run menu | Fresh install | ⋮ Restore | Opens the import | P2 | M | |
| SET-09 | Export BIP-329 labels | Labels exist | Export Labels | Valid JSONL file | P2 | E | |
| SET-10 | Import BIP-329 labels (file) | JSONL from Sparrow | Import Labels | Labels appear on txs/UTXOs | P2 | E | |
| SET-11 | Import labels by QR | Label QR | Import Labels (QR) | Same as SET-10 | P2 | E | |
| SET-12 | Explorer selection | Settings → Explorer | Switch, then open a tx | Opens the chosen explorer | P2 | M | |
| SET-13 | Erase all data | Data | Erase all Sentinel data → PIN → confirm | Back to first run. No keys, Dojo, PIN left | P0 | M | |
| SET-14 | Share error log | After a crash | Share error log | Shares the log. Contains no secrets (see SEC-04) | P1 | E | |
| SET-15 | Version shown | Settings → About | Look | Shows 6.0.0 on the release build | P1 | M | |
| SET-16 | Disable Screenshots toggle | Off and on | Screenshot each screen | Matches the setting. Settings and PIN screens always secure (confirm intended) | P1 | E | |

## 17. Notifications (NOT)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| NOT-01 | Incoming payment notification | App backgrounded, permission granted | Receive tBTC | Notification on the payments channel. Tap opens the app (behind the PIN) | P1 | E | ✓ |
| NOT-02 | Permission denied | Denied | Receive tBTC | No notification, no crash | P2 | E | ✓ |
| NOT-03 | Tor notification | Tor on | Look | Shows state. Restart/stop actions work | P2 | E | |
| NOT-04 | Street mode / privacy in notifications | Street mode on | Receive | Amount not shown on the lock screen (confirm) | P2 | E | ✓ |
| NOT-05 | Reboot | Data, Tor on | Reboot the emulator | App and services come back cleanly when opened. No crash at boot | P2 | E | |

## 18. Security and privacy (SEC)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| SEC-01 | FLAG_SECURE | Disable Screenshots on | `adb screencap` on home, PIN, settings | Black frames | P1 | M | |
| SEC-02 | No clearnet leaks with Tor on | Tor + Dojo | Use the app with a packet capture on the emulator | Only Tor traffic (no direct DNS or TLS) | P0 | E | |
| SEC-03 | Dojo API key not logged (known) | Debug and release builds | Pair, grep logcat for the key | Never printed (DojoUtil.readPayload Timber) **(known in debug)** | P0 | E | |
| SEC-04 | No secrets in logs | After all flows | grep logcat + `error_dump.log` for the payload, passwords, PIN, xpubs, WIF | None found (xpubs flagged to the maintainers if present) | P0 | E | |
| SEC-05 | Clipboard | Copy an address, PSBT, backup | Check | Only what you copied. Backup not left in the clipboard silently (confirm any auto-clear) | P2 | E | |
| SEC-06 | Exported files | SET-02, PSBT-04 | Check the location | Saved where you chose. No world-readable copy in shared storage | P2 | E | |
| SEC-07 | FileProvider | Share the error log | Inspect the URI | Only `provider_paths` files reachable | P2 | E | |
| SEC-08 | Backup encryption | SET-01 | Inspect the blob | No plaintext keys or API key in it | P0 | E | |
| SEC-09 | Release build has no debug logging | Release APK | logcat during use | No Timber debug output | P1 | E | ✓ |

## 19. Robustness (ROB)

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| ROB-01 | Don't keep activities | Dev option on | Walk every screen and back | No crash, no lost state | P0 | M | |
| ROB-02 | Process death per screen | Each screen | Background → `am kill` → resume | Restores, or returns to the PIN/home cleanly | P0 | E | |
| ROB-03 | Low memory | `am send-trim-memory` | On collection and Send | No crash | P2 | E | |
| ROB-04 | Offline launch | Airplane mode | Launch | Cached balances. Honest offline state | P1 | M | |
| ROB-05 | Airplane mode mid-sync | Syncing | Airplane on, then off | Sync fails visibly, then recovers | P1 | E | |
| ROB-06 | Wi-Fi ↔ mobile switch | Tor on | Switch networks | Tor and websocket reconnect (ReconnectPolicy) | P1 | E | |
| ROB-07 | Rotation everywhere | — | Rotate on each screen and sheet | No crash, no duplicated sheets | P1 | M | |
| ROB-08 | Back stack | — | Deep navigation, mash back, swipe-back gesture | Expected screens. No blank activity | P1 | E | |
| ROB-09 | Clock change | — | Change the time zone, set the clock ahead | Dates and the lock timeout behave | P2 | E | |
| ROB-10 | Long run | Tor + Dojo | App open 2 h, periodic syncs | No memory growth or ANR | P2 | E | |
| ROB-11 | Double taps | — | Double-tap COMPOSE, BROADCAST, save, import | Only one action runs | P1 | M | |

## 20. Random-tap stress (MON)

Run with `adb shell monkey -p com.samourai.sentinel.staging --pct-syskeys 0 -s <seed> --throttle 100 -v 10000`, the PIN off or set (both), on a throwaway wallet with no Dojo-side effects. Seeds are logged so each crash can be replayed.

| ID | Title | Pre | Steps | Expected | P | Auto | Human |
|---|---|---|---|---|---|---|---|
| MON-01 | Seeds 1–5, PIN off | Wallet with 2 collections | 5 × 10k events | No crash or ANR | P0 | M | |
| MON-02 | Seeds 6–10, PIN on + scramble | Same | 5 × 10k | No crash. Never gets past the PIN | P1 | M | |
| MON-03 | Monkey with network flapping | Script toggles airplane mode | 3 × 10k | No crash | P1 | E | |
| MON-04 | Monkey, landscape and 2.0 font | Matrix setting | 2 × 10k | No crash | P2 | E | |
| MON-05 | Replay each found crash | Seed + event count | Re-run to the crash | Reproduces. Issue draft with the seed | P1 | E | |

---

## 21. Environment matrix

| Dimension | Values |
|---|---|
| Android | API 24 (7.0, new minSdk), API 30 (11, main AVD), API 34/35 (14/15, notification permission, newer back handling) |
| Navigation | Gesture, three-button |
| Theme | Dark, light (system) |
| Font scale | 1.0, 1.3, 2.0 |
| Display size | Default, largest |
| Orientation | Portrait, landscape |
| Locale | English, a non-English locale (German, with comma decimals), an RTL locale (Arabic) |
| Screen | Small (≈ 720×1280 @320 dpi), large (tablet ≈ 1600×2560) |

**Run once (on API 30, default settings):** everything not listed below.

**Smoke subset across the matrix** (about 20 cases, every value of every dimension at least once, with API 24/30/34 each getting the full subset): INS-01, INS-02, PIN-04, PIN-05, PIN-09, PIN-16, COL-01, COL-02, SYNC-01, SYNC-09, SYNC-10, SYNC-11, RCV-01, RCV-06, SEND-01, SEND-04, SEND-08, PSBT-01, BC-05, SET-01, SET-03, ROB-07. The rest of the matrix is combined (pairwise), not every combination.

**Matrix focus points:** amounts with comma decimals (SEND-06/09 in German), clipped buttons at 2.0 font and the largest display size (PIN keypad, Send form, bottom nav), the three-button bar covering the bottom of sheets, RTL mirroring of the address/amount fields, and the camera/notification permission flow on API 34.

---

## 22. Counts

| Priority | Cases |
|---|---|
| P0 | 74 |
| P1 | 94 |
| P2 | 56 |
| **Total** | **224** |

Maestro candidates (M): 113. Exploratory only (E): 111. Needs a human (✓): 34.
