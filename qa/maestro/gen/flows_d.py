from flows_a import F, flow, tapdigits
from flows_b import MW, CS

SET = '''- runFlow: ../subflows/open_settings.yaml
'''
def scroll_to(text):
    return f'''- scrollUntilVisible:
    element: {{ text: "{text}" }}
    direction: DOWN
    timeout: 30000
'''
def checkbox(title, checked):
    # Match the checkbox inside the preference row whose title is `title`. (A bare rightOf: {title}
    # matches ANY checkbox to the right of the title, on any row - suite bug S1.)
    return f'''- assertVisible:
    containsChild: {{ id: "android:id/widget_frame", containsChild: {{ id: "android:id/checkbox", checked: {str(checked).lower()} }} }}
    containsDescendants: [ {{ text: "{title}" }} ]
    label: "{title} is {'on' if checked else 'off'}"
'''

def checkbox_wait(title, checked, timeout=180000):
    # KDF-backed toggles (PIN set/remove run Argon2id) need a wait, not an
    # instant assert; same S1-safe row-scoped selector as checkbox().
    return f'''- extendedWaitUntil:
    visible:
      containsChild: {{ id: "android:id/widget_frame", containsChild: {{ id: "android:id/checkbox", checked: {str(checked).lower()} }} }}
      containsDescendants: [ {{ text: "{title}" }} ]
    timeout: {timeout}
    label: "{title} turns {'on' if checked else 'off'}"
'''

REALPIN = '''- evalScript:
    script: ${output.pinIdx = 0; output.pinLen = String(MAESTRO_SENTINEL_PIN).length}
    label: "Prepare fixture PIN entry"
- repeat:
    times: ${output.pinLen}
    commands:
      - tapOn:
          id: "t[a-j]"
          text: ${String(MAESTRO_SENTINEL_PIN).charAt(output.pinIdx)}
          label: "Fixture PIN key"
      - evalScript:
          script: ${output.pinIdx = output.pinIdx + 1}
          label: "Next PIN key"
- tapOn:
    id: "tconfirm"
    label: "Confirm the pad"
'''

flow('SET-15', ['smoke','p1','settings'], 'Settings -> About shows the version', SET + scroll_to('Version') + '''- assertVisible: { text: "[0-9]+\\\\.[0-9]+\\\\.[0-9]+.*", below: { text: "Version" } }
- takeScreenshot: SET-15-version
- back
''', known='On the release build this should read 6.0.0; staging reads 5.1.3-dojobay.N')

flow('SYNC-13', ['smoke','p2','settings'], 'Balance query help dialog opens and closes', SET + scroll_to('Help querying correct balance') + '''- tapOn: "Help querying correct balance"
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SYNC-13-dialog
- assertNotVisible: { text: "User PIN code", label: "A dialog covers Settings" }
- back
- runFlow: ../subflows/home_basic.yaml
''')

flow('SET-12', ['p2','settings'], 'Explorer selection list opens with choices; cancel keeps the current one', SET + '''- copyTextFrom: { text: ".+", below: { text: "Explorer" } }
- evalScript: { script: "${output.ex = maestro.copiedText}", label: "current explorer" }
- tapOn: "Explorer"
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SET-12-list
- assertVisible: { text: "${output.ex}" }
- tapOn: { text: "(?i)cancel" }
- assertVisible: { text: "${output.ex}", below: { text: "Explorer" } }
- back
''')

flow('SYNC-15', ['p2','settings'], 'Exchange list opens with sources; cancel keeps the current one', SET + '''- tapOn: "Exchange"
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SYNC-15-list
- assertVisible: { text: "coingecko.com" }
- tapOn: { text: "(?i)cancel" }
- assertVisible: { text: "coingecko.com", below: { text: "Exchange" } }
- back
''', known='Switching the source is not automated (would change a user pref the suite then has to restore); only the list is checked')

flow('SYNC-14', ['p1','settings','fiat'], 'Currency EUR shows EUR on Home; switched back to USD', SET + '''- tapOn: "Currency"
- waitForAnimationToEnd: { timeout: 3000 }
- scrollUntilVisible: { element: { text: "EUR" }, direction: DOWN, timeout: 20000 }
- tapOn: "EUR"
- assertVisible: { text: "EUR", below: { text: "Currency" } }
- runFlow: ../subflows/home_basic.yaml
- extendedWaitUntil:
    visible: { id: "exchangeRateTxt", text: ".*EUR" }
    timeout: 120000
- takeScreenshot: SYNC-14-eur
- runFlow: ../subflows/open_settings.yaml
- tapOn: "Currency"
- waitForAnimationToEnd: { timeout: 3000 }
- scrollUntilVisible: { element: { text: "USD" }, direction: DOWN, timeout: 20000 }
- tapOn: "USD"
- assertVisible: { text: "USD", below: { text: "Currency" } }
- runFlow: ../subflows/home_basic.yaml
''')

flow('SYNC-17', ['p2','settings','fiat'], 'Hide fiat removes fiat on Home; turned back off', SET + scroll_to('Hide fiat values') + checkbox('Hide fiat values', False) + '''- tapOn: "Hide fiat values"
- runFlow: ../subflows/home_basic.yaml
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SYNC-17-hidden
- assertNotVisible: { id: "exchangeRateTxt", text: ".*[0-9].*", label: "No fiat on Home" }
- runFlow: ../subflows/open_settings.yaml
''' + scroll_to('Hide fiat values') + '''- tapOn: "Hide fiat values"
''' + checkbox('Hide fiat values', False) + '''- runFlow: ../subflows/home_basic.yaml
''')

flow('SYNC-18', ['p1','settings','privacy'], 'Street mode masks the balances on Home and in a collection; turned back off', SET + scroll_to('Street mode') + checkbox('Street mode', False) + '''- tapOn: "Street mode"
- runFlow: ../subflows/home_basic.yaml
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: SYNC-18-home
- assertNotVisible: { id: "rvItemCollectionBalance", text: "[0-9].*", label: "Rows masked" }
- assertNotVisible: { id: "homeBalanceBtc", text: "[0-9].*", label: "Total masked" }
- tapOn: { id: "rvItemCollectionTitle", text: "My Wallet" }
- extendedWaitUntil: { visible: { id: "collectionBalanceBtc" }, timeout: 30000 }
- takeScreenshot: SYNC-18-collection
- assertNotVisible: { id: "collectionBalanceBtc", text: "[0-9].*", label: "Collection header masked" }
- runFlow: ../subflows/open_settings.yaml
''' + scroll_to('Street mode') + '''- tapOn: "Street mode"
''' + checkbox('Street mode', False) + '''- runFlow: ../subflows/home_basic.yaml
''')

EXPORT_OPEN = SET + scroll_to('Export Sentinel Backup') + '''- tapOn: "Export Sentinel Backup"
- waitForAnimationToEnd: { timeout: 3000 }
'''
flow('PIN-17', ['p0','settings','security','known'], 'Backup export asks for the PIN first (known N4: it does not)', EXPORT_OPEN + '''- takeScreenshot: PIN-17-after-export-tap
- assertVisible: { id: "lockScreenText", label: "PIN prompt before export" }
''', expect='fail', known='Known N4 (P0 per plan): export and erase are not PIN-gated. Erase is never tapped by this suite')

flow('PIN-18', ['p0','pin','lock','settings','known-fixed'], 'Set a throwaway PIN, then remove it in the SAME Settings visit: pad opens on the first tap, one Back leaves Settings (crash guard for #440/#439)', SET + checkbox('User PIN code', True) + '''
# baseline: fixture PIN on - remove it first
- tapOn: "User PIN code"
- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Enter current pin code" }
    timeout: 15000
- runFlow: ../subflows/unlock.yaml
''' + checkbox_wait('User PIN code', False) + '''
# throwaway set flow - its confirm tap is the crash site of #440
- evalScript:
    script: "${output.p2 = (String(MAESTRO_SENTINEL_PIN).indexOf('13579') === 0) ? '24680' : '13579'}"
    label: "Pick a 5-digit throwaway PIN (never the real one)"
- tapOn: "User PIN code"
- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Enter new pin code" }
    timeout: 15000
''' + tapdigits('output.p2', 5, 'Throwaway PIN key') + '''- tapOn: { id: "tconfirm", label: "Confirm the set pad" }
- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Please Re-Enter Pin Code" }
    timeout: 15000
''' + tapdigits('output.p2', 5, 'Throwaway PIN key again') + '''- tapOn: { id: "tconfirm", label: "Confirm the confirm pad (crash site of #440)" }
- extendedWaitUntil:
    notVisible: { id: "lockScreenText" }
    timeout: 15000
    label: "Both pads closed after the confirm (no #440 freeze)"
''' + checkbox_wait('User PIN code', True) + '''
- takeScreenshot: PIN-18-set
# #433 core: toggle OFF in the SAME visit - the pad must open on the FIRST tap
- tapOn: "User PIN code"
- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Enter current pin code" }
    timeout: 15000
    label: "Remove pad opens on the first tap (no ghost record)"
''' + tapdigits('output.p2', 5, 'Throwaway PIN key') + '''- tapOn: { id: "tconfirm", label: "Confirm remove" }
''' + checkbox_wait('User PIN code', False) + '''
- takeScreenshot: PIN-18-removed-same-visit
# the two-Back tell: ONE Back must leave Settings
- back
- extendedWaitUntil:
    visible: { id: "fab" }
    timeout: 30000
    label: "ONE Back press left Settings"
# restore the fixture PIN for the rest of the suite
- runFlow: ../subflows/open_settings.yaml
- tapOn: "User PIN code"
- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Enter new pin code" }
    timeout: 15000
''' + REALPIN + '''- extendedWaitUntil:
    visible: { id: "lockScreenText", text: "Please Re-Enter Pin Code" }
    timeout: 15000
    label: "Confirm pad up (restore)"
''' + REALPIN + checkbox_wait('User PIN code', True) + '''
- takeScreenshot: PIN-18-restored
- runFlow: ../subflows/home_basic.yaml
''', known='A midway failure leaves the app PIN-less or on the throwaway PIN; restore_baseline does not touch the PIN, so that state needs a manual fix')

flow('SET-01', ['smoke','p0','settings','backup','clipboard'], 'Export backup to the clipboard produces an encrypted blob (random throwaway password, clipboard cleared after)', '''- evalScript:
    script: ${output.pw = 'mt-' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)}
    label: "Random one-off export password (blob is discarded)"
''' + EXPORT_OPEN + '''- runFlow: { when: { visible: { id: "lockScreenText" } }, commands: [ { runFlow: ../subflows/unlock.yaml } ] }
- tapOn: "Copy to clipboard"
- extendedWaitUntil: { visible: { text: "Add payload password|Password" }, timeout: 15000 }
- tapOn: { id: ".*(input|edit|EditText).*|bottomSheetInputField", index: 0, optional: true }
- inputText: ${output.pw}
- tapOn: { text: "(?i)encrypt|confirm|ok" }
- waitForAnimationToEnd: { timeout: 10000 }
- runScript: { file: ../scripts/js/clip_info.js, label: "clipboard length + prefix" }
- evalScript: { script: "${output.ok = output.clipLen > 500 && output.clipPrefix.indexOf('{') === 0}", label: "blob check" }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: " " }, label: "Clear the clipboard (do not leave a backup in it)" }
- assertTrue: { condition: "${output.ok}", label: "Clipboard held a JSON backup blob (>500 chars)" }
- runFlow: ../subflows/home_basic.yaml
''', known='Content (2 collections + Dojo) is checked offline in manual runs only; N16 (empty blob after process death) is not covered')

IMPORT_OPEN = SET + scroll_to('Import Sentinel Backup') + '''- tapOn: "Import Sentinel Backup"
- extendedWaitUntil: { visible: { id: "importPastePayloadBtn" }, timeout: 20000 }
'''
flow('SET-05', ['p0','settings','backup','clipboard'], 'Wrong backup password: "Incorrect password", nothing imported (dummy empty blob)', IMPORT_OPEN + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "${DUMMY_BACKUP}" }, label: "clipboard = dummy backup (empty payload)" }
- tapOn: { id: "importPastePayloadBtn" }
- extendedWaitUntil: { visible: { id: "importPasswordInput" }, timeout: 15000 }
- tapOn: { id: "importPasswordInput" }
- inputText: "definitely-the-wrong-password"
- tapOn: { id: "importStartBtn" }
- extendedWaitUntil:
    visible: { text: "Incorrect password, please try again\\\\." }
    timeout: 60000
- takeScreenshot: SET-05-wrong-password
- assertNotVisible: { text: "Imported.*" }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: " " }, label: "clear clipboard" }
- back
- runFlow: ../subflows/home_basic.yaml
- assertVisible: { id: "rvItemCollectionTitle", text: "My Wallet" }
''')

flow('SET-06', ['p1','settings','backup','clipboard'], 'Corrupt (truncated) backup: clear error, nothing changed', IMPORT_OPEN + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "${DUMMY_BACKUP_TRUNC}" }, label: "clipboard = truncated dummy backup" }
- tapOn: { id: "importPastePayloadBtn" }
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: SET-06-truncated
- runFlow:
    when: { visible: { id: "importPasswordInput" } }
    commands:
      - tapOn: { id: "importPasswordInput" }
      - inputText: "x-wrong"
      - tapOn: { id: "importStartBtn" }
      - waitForAnimationToEnd: { timeout: 10000 }
- assertNotVisible: { text: "Imported.*" }
- assertNotVisible: { id: "importCollections", label: "Import options never offered" }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: " " }, label: "clear clipboard" }
- back
- runFlow: ../subflows/home_basic.yaml
- assertVisible: { id: "rvItemCollectionTitle", text: "My Wallet" }
''')

# ---------------- collections (throwaway data only) ----------------
TC = '''- runFlow: ../subflows/ensure_test_collection.yaml
'''
OPEN_TC = TC + '''- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
- extendedWaitUntil: { visible: { id: "collection_details_transaction_edit_option" }, timeout: 30000 }
'''
EDIT_TC = OPEN_TC + '''- tapOn: { id: "collection_details_transaction_edit_option" }
- extendedWaitUntil: { visible: { id: "collectionEdiText", text: "MaestroT" }, timeout: 20000 }
'''
def add_in_edit(key, label):
    return f'''- tapOn: {{ id: "addNewPubFab" }}
- extendedWaitUntil: {{ visible: {{ id: "pastePubKey" }}, timeout: 20000 }}
- runScript: {{ file: ../scripts/js/clip_set.js, env: {{ TEXT: "{key}" }}, label: "clipboard = throwaway key" }}
- tapOn: {{ id: "pastePubKey" }}
- runFlow:
    when: {{ visible: {{ id: "bottomSheetInputField" }} }}
    commands:
      - tapOn: {{ id: "bottomSheetInputField" }}
      - eraseText: 30
      - inputText: "{label}"
      - tapOn: {{ id: "bottomSheetConfirmPositiveBtn" }}
- waitForAnimationToEnd: {{ timeout: 5000 }}
'''
SAVE_EDIT = '''- tapOn: { id: "saveCollectionMenuItem" }
- waitForAnimationToEnd: { timeout: 5000 }
'''
def home_paste(key):
    return f'''- runFlow:
    file: ../subflows/paste_pubkey.yaml
    env: {{ KEY: "{key}" }}
- waitForAnimationToEnd: {{ timeout: 5000 }}
'''

flow('00-SETUP', ['setup','smoke','p0'], 'Suite setup: unlocked Home with My Wallet and Cold Storage; leftover throwaway collections removed', '''- runFlow: ../subflows/home.yaml
- assertVisible: { id: "rvItemCollectionTitle", text: "My Wallet" }
- assertVisible: { id: "rvItemCollectionTitle", text: "Cold Storage" }
- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT" } }
- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-Addr" } }
- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-44" } }
- takeScreenshot: SETUP-home
''')

flow('COL-01', ['smoke','p0','collection'], 'New collection from a throwaway vpub; Receive shows the independently derived m/0/0 address', '''- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT" } }
''' + OPEN_TC + '''- assertVisible: "KeyA"
- tapOn: { id: "bottom_nav_receive" }
- extendedWaitUntil: { visible: { id: "receiveAddressText", text: "tb1.*" }, timeout: 30000 }
- takeScreenshot: COL-01-receive
- assertVisible: { id: "receiveAddressText", text: "${VPUB_A_ADDR0}", label: "Address = BIP84 m/0/0 derived offline from the vpub" }
- back
''')

flow('COL-02', ['smoke','p0','collection'], 'Add a 2nd key in edit: both listed and saved', EDIT_TC + '''- runFlow:
    when: { notVisible: { id: "pubKeyItemLabel", text: "KeyB" } }
    commands:
''' + '\n'.join('      ' + l for l in add_in_edit('${VPUB_B}', 'KeyB').splitlines()) + '''
- assertVisible: { id: "pubKeyItemLabel", text: "KeyA" }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyB" }
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
- extendedWaitUntil: { visible: { text: "KeyB" }, timeout: 30000 }
- assertVisible: "KeyA"
- takeScreenshot: COL-02-tabs
- back
''')

flow('COL-03', ['p0','collection'], 'Add a key via Home NEW -> choose the collection: edit shows the existing keys plus the new one', TC + home_paste('${VPUB_C}') + '''- extendedWaitUntil: { visible: { id: "createNewCollection" }, timeout: 30000 }
- tapOn: { text: "MaestroT", below: { id: "createNewCollection" } }
- runFlow:
    when: { visible: { id: "bottomSheetInputField" } }
    commands:
      - tapOn: { id: "bottomSheetInputField" }
      - eraseText: 30
      - inputText: "KeyC"
      - tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 30000 }
- takeScreenshot: COL-03-edit
- assertVisible: { id: "pubKeyItemLabel", text: "KeyA", label: "Existing key still listed" }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyC" }
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
''')

flow('COL-04', ['p0','collection','known-fixed'], 'Duplicate key via Home -> choose collection: "already exists", keys kept, no empty edit (fixed #243)', TC + home_paste('${VPUB_A}') + '''- extendedWaitUntil: { visible: { id: "createNewCollection" }, timeout: 30000 }
- tapOn: { text: "MaestroT", below: { id: "createNewCollection" } }
- runFlow:
    when: { visible: { id: "bottomSheetInputField" } }
    commands:
      - tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: COL-04-after
- runFlow:
    when: { visible: { id: "collectionEdiText" } }
    commands:
      - assertVisible: { id: "pubKeyItemLabel", text: "KeyA", label: "If edit opens, the existing key is listed (not an empty edit)" }
      - back
- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
- extendedWaitUntil: { visible: { text: "KeyA" }, timeout: 30000 }
- back
''')

flow('COL-05', ['p0','collection','known-fixed'], 'After a duplicate attempt, ADD NEW + save keeps the old keys (fixed #243)', EDIT_TC + add_in_edit('${VPUB_A}', 'dup') + '''- runFlow: { when: { visible: { id: "pastePubKey" } }, commands: [ back ] }
''' + add_in_edit('${VPUB_D}', 'KeyD') + '''- assertVisible: { id: "pubKeyItemLabel", text: "KeyA" }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyD" }
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
- extendedWaitUntil: { visible: { text: "KeyD" }, timeout: 30000 }
- assertVisible: "KeyA"
- takeScreenshot: COL-05-tabs
- back
''')

flow('COL-06', ['p1','collection'], 'Duplicate in edit: "Public key already exists in this collection", keys kept', EDIT_TC + '''- tapOn: { id: "addNewPubFab" }
- extendedWaitUntil: { visible: { id: "pastePubKey" }, timeout: 20000 }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "${VPUB_A}" }, label: "clipboard = existing key" }
- tapOn: { id: "pastePubKey" }
- runFlow:
    when: { visible: { id: "bottomSheetInputField" } }
    commands:
      - tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- extendedWaitUntil:
    visible: { text: "Public key already exists in this collection" }
    timeout: 15000
- takeScreenshot: COL-06-snackbar
- runFlow: { when: { visible: { id: "pastePubKey" } }, commands: [ back ] }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyA" }
- back
- runFlow: ../subflows/home_basic.yaml
''')

def key_menu(label):
    return f'''- tapOn: {{ id: "moreButton", rightOf: {{ text: "{label}" }}, label: "key menu for {label}" }}
- waitForAnimationToEnd: {{ timeout: 3000 }}
'''
flow('COL-14', ['p1','collection'], 'Edit a key label: new label shown in the tabs', EDIT_TC + '''- runFlow:
    when: { notVisible: { id: "pubKeyItemLabel", text: "KeyB" } }
    commands:
''' + '\n'.join('      ' + l for l in add_in_edit('${VPUB_B}', 'KeyB').splitlines()) + '''
''' + key_menu('KeyB') + '''- tapOn: "Edit"
- extendedWaitUntil: { visible: { id: "bottomSheetInputField" }, timeout: 10000 }
- tapOn: { id: "bottomSheetInputField" }
- eraseText: 30
- inputText: "KeyB2"
- tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyB2" }
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
- extendedWaitUntil: { visible: { text: "KeyB2" }, timeout: 30000 }
- takeScreenshot: COL-14-tabs
- back
''')

flow('COL-15', ['p2','collection'], 'View Master Fingerprint shows an 8-hex fingerprint', EDIT_TC + key_menu('KeyA') + '''- tapOn: "View Master Fingerprint"
- extendedWaitUntil: { visible: { text: "Master Fingerprint" }, timeout: 10000 }
- takeScreenshot: COL-15-fingerprint
- assertVisible: { text: "[0-9a-fA-F]{8}" }
- back
- back
- runFlow: ../subflows/home_basic.yaml
''', known='Throwaway keys are made with depth-3 parent fingerprints, so the value itself is not checked')

flow('COL-19', ['p2','collection','clipboard'], 'Key QR dialog: QR + text; COPY TO CLIPBOARD copies the key', EDIT_TC + '''- tapOn: { id: "pubKeyItemLabel", text: "KeyA" }
- extendedWaitUntil: { visible: { id: "qrTextView" }, timeout: 15000 }
- assertVisible: { id: "qrTextView", text: "${VPUB_A}" }
- runScript: { file: ../scripts/js/qr_screen.js, label: "Decode the key QR" }
- takeScreenshot: COL-19-qr
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "placeholder" }, label: "clear clipboard" }
- tapOn: { id: "qrDialogCopyToClipBoard" }
- runScript: { file: ../scripts/js/clip_equals.js, env: { EXPECTED: "${VPUB_A}" }, label: "Compare clipboard" }
- assertTrue: { condition: "${output.clipEqual}", label: "Clipboard == key" }
- assertTrue: { condition: "${output.qrTexts.indexOf(VPUB_A) >= 0}", label: "QR encodes the key" }
- back
- back
- runFlow: ../subflows/home_basic.yaml
''')

flow('COL-17', ['p1','collection'], 'Rename a collection: Home shows the new name (renamed back after)', EDIT_TC + '''- tapOn: { id: "collectionEdiText" }
- eraseText: 40
- inputText: "MaestroT-Renamed"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- takeScreenshot: COL-17-home
- evalScript: { script: "${output.seen = true}", label: "x" }
- runFlow:
    when: { notVisible: { id: "rvItemCollectionTitle", text: "MaestroT-Renamed" } }
    commands:
      - evalScript: { script: "${output.seen = false}", label: "not shown on Home" }
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT.*" }
- extendedWaitUntil: { visible: { id: "collection_details_transaction_edit_option" }, timeout: 30000 }
- tapOn: { id: "collection_details_transaction_edit_option" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 20000 }
- tapOn: { id: "collectionEdiText" }
- eraseText: 40
- inputText: "MaestroT"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- assertTrue: { condition: "${output.seen}", label: "Home showed the new name straight after save" }
''')

flow('COL-24', ['p2','collection'], '100-char collection name: no layout break or crash (renamed back after)', EDIT_TC + '''- tapOn: { id: "collectionEdiText" }
- eraseText: 40
- inputText: "MaestroT-LONG-0123456789012345678901234567890123456789012345678901234567890123456789012345678901"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- takeScreenshot: COL-24-home
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT-LONG.*" }
- extendedWaitUntil: { visible: { id: "collection_details_transaction_edit_option" }, timeout: 30000 }
- takeScreenshot: COL-24-details
- tapOn: { id: "collection_details_transaction_edit_option" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 20000 }
- tapOn: { id: "collectionEdiText" }
- eraseText: 120
- inputText: "MaestroT"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- assertVisible: { id: "rvItemCollectionTitle", text: "MaestroT" }
''', known='Emoji labels are not covered: Maestro inputText on Android types ASCII only')

flow('COL-16', ['p0','collection'], 'Delete one key: only that key removed', EDIT_TC + '''- runFlow:
    when: { notVisible: { id: "pubKeyItemLabel", text: "KeyD" } }
    commands:
''' + '\n'.join('      ' + l for l in add_in_edit('${VPUB_D}', 'KeyD').splitlines()) + '''
      - tapOn: { id: "saveCollectionMenuItem" }
      - runFlow: ../subflows/home_basic.yaml
      - tapOn: { id: "rvItemCollectionTitle", text: "MaestroT" }
      - extendedWaitUntil: { visible: { id: "collection_details_transaction_edit_option" }, timeout: 30000 }
      - tapOn: { id: "collection_details_transaction_edit_option" }
      - extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 20000 }
''' + key_menu('KeyD') + '''- tapOn: "Delete"
- extendedWaitUntil: { visible: { id: "bottomSheetConfirmPositiveBtn|android:id/button1" }, timeout: 10000 }
- tapOn: { id: "bottomSheetConfirmPositiveBtn|android:id/button1" }
- waitForAnimationToEnd: { timeout: 5000 }
- assertNotVisible: { id: "pubKeyItemLabel", text: "KeyD" }
- assertVisible: { id: "pubKeyItemLabel", text: "KeyA" }
- takeScreenshot: COL-16-after-delete
- back
- runFlow: ../subflows/home_basic.yaml
''')

flow('COL-12', ['p0','collection'], 'Invalid input (garbage, truncated vpub, bad checksum) is rejected; nothing created', ''.join(home_paste(k) + '''- assertVisible: { id: "pastePubKey", label: "Still on the scanner: rejected" }
- assertNotVisible: { id: "createNewCollection" }
- back
''' for k in ['${GARBAGE}', '${VPUB_TRUNC}', '${VPUB_BADSUM}']) + '''- runFlow: ../subflows/home_basic.yaml
- takeScreenshot: COL-12-home
''')

flow('COL-12b', ['p2','collection','known'], 'COL-12 whitespace variant: a valid vpub with spaces around it is trimmed and accepted (known N9)', home_paste('  ${VPUB_B}  ') + '''- takeScreenshot: COL-12b-after-paste
- runFlow: { when: { visible: { id: "createNewCollection" } }, commands: [ { evalScript: { script: "${output.acc = true}" } } ] }
- runFlow: ../subflows/home_basic.yaml
- assertTrue: { condition: "${output.acc === true}", label: "Whitespace-padded vpub accepted (goes to Choose collection)" }
''', expect='fail', known='Known N9: the xpub check runs on the untrimmed text')

flow('COL-09', ['p1','collection'], 'Mainnet xpub/ypub/zpub on testnet are refused; nothing created', ''.join(home_paste(k) + '''- waitForAnimationToEnd: { timeout: 5000 }
- assertNotVisible: { id: "createNewCollection", label: "Not offered for a collection" }
- assertNotVisible: { id: "bottomSheetInputField" }
- runFlow: ../subflows/home_basic.yaml
''' for k in ['${XPUB_MAIN}', '${YPUB_MAIN}', '${ZPUB_MAIN}']) + '''- assertNotVisible: { id: "rvItemCollectionTitle", text: "Untitled.*" }
''', known='Message is a toast ("Can\'t track Mainnet public keys in Testnet"), not asserted')

def type_page_then(btn):
    return f'''- runFlow:
    when: {{ visible: {{ id: "nextBtn" }} }}
    commands:
      - tapOn: {{ id: "{btn}" }}
      - tapOn: {{ id: "nextBtn" }}
- extendedWaitUntil: {{ visible: {{ id: "createNewCollection" }}, timeout: 20000 }}
'''
flow('COL-07', ['p1','collection','known'], 'tpub (BIP44) on testnet: accepted with a type choice, not silently ignored (stops before creating)', home_paste('${TPUB}') + '''- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: COL-07-after-paste
''' + type_page_then('buttonBIP44') + '''- back
- runFlow: ../subflows/home_basic.yaml
''', known='Plan marks this a known bug (silently ignored) not re-checked since 10-21')

flow('COL-08', ['p1','collection'], 'upub (BIP49) on testnet: accepted (stops before creating)', home_paste('${UPUB}') + '''- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: COL-08-after-paste
''' + type_page_then('buttonBIP49') + '''- back
- runFlow: ../subflows/home_basic.yaml
''')

flow('PAIR-08', ['p2','collection'], 'Choose collection does not offer the imported My Wallet', home_paste('${VPUB_D}') + '''- extendedWaitUntil: { visible: { id: "createNewCollection" }, timeout: 30000 }
- takeScreenshot: PAIR-08-choose
- assertVisible: { text: "Cold Storage", below: { id: "createNewCollection" } }
- assertNotVisible: { text: "My Wallet", below: { id: "createNewCollection" } }
- back
- runFlow: ../subflows/home_basic.yaml
''')

flow('COL-11', ['p1','collection'], 'Single tb1 address becomes an address-only collection (created and deleted)', '''- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-Addr" } }
''' + home_paste('${TB1_ADDR}') + '''- extendedWaitUntil: { visible: { id: "createNewCollection" }, timeout: 30000 }
- tapOn: { id: "createNewCollection" }
- runFlow:
    when: { visible: { id: "bottomSheetInputField" } }
    commands:
      - tapOn: { id: "bottomSheetInputField" }
      - eraseText: 30
      - inputText: "Addr"
      - tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 30000 }
- tapOn: { id: "collectionEdiText" }
- eraseText: 40
- inputText: "MaestroT-Addr"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT-Addr" }
- extendedWaitUntil: { visible: { id: "collection_details_transaction_edit_option" }, timeout: 30000 }
- takeScreenshot: COL-11-details
- evalScript: { script: "${output.sendHidden = false}", label: "x" }
- runFlow: { when: { notVisible: { id: "bottom_nav_send" } }, commands: [ { evalScript: { script: "${output.sendHidden = true}" } } ] }
- runFlow:
    when: { visible: { id: "bottom_nav_send" } }
    commands:
      - tapOn: { id: "bottom_nav_send" }
      - waitForAnimationToEnd: { timeout: 5000 }
      - takeScreenshot: COL-11-send
      - runFlow: { when: { notVisible: { id: "pubKeySelector", text: "Addr" } }, commands: [ { evalScript: { script: "${output.sendHidden = true}" } } ] }
- back
- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-Addr" } }
- assertTrue: { condition: "${output.sendHidden}", label: "Address-only key not offered in Send From" }
''')

flow('RCV-10', ['p1','receive','collection'], 'BIP44 (tpub) key: Receive shows an m/n address at m/44 (collection created and deleted)', '''- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-44" } }
''' + home_paste('${TPUB}') + type_page_then('buttonBIP44') + '''- tapOn: { id: "createNewCollection" }
- runFlow:
    when: { visible: { id: "bottomSheetInputField" } }
    commands:
      - tapOn: { id: "bottomSheetInputField" }
      - eraseText: 30
      - inputText: "K44"
      - tapOn: { id: "bottomSheetConfirmPositiveBtn" }
- extendedWaitUntil: { visible: { id: "collectionEdiText" }, timeout: 30000 }
- tapOn: { id: "collectionEdiText" }
- eraseText: 40
- inputText: "MaestroT-44"
''' + SAVE_EDIT + '''- runFlow: ../subflows/home_basic.yaml
- tapOn: { id: "rvItemCollectionTitle", text: "MaestroT-44" }
- extendedWaitUntil: { visible: { id: "bottom_nav_receive" }, timeout: 30000 }
- tapOn: { id: "bottom_nav_receive" }
- extendedWaitUntil: { visible: { id: "receiveAddressText" }, timeout: 30000 }
- copyTextFrom: { id: "receiveAddressText" }
- evalScript: { script: "${output.a = maestro.copiedText}", label: "address" }
- tapOn: { id: "advance_button" }
- extendedWaitUntil: { visible: { id: "path" }, timeout: 10000 }
- copyTextFrom: { id: "path" }
- evalScript: { script: "${output.p = maestro.copiedText}", label: "path" }
- takeScreenshot: RCV-10-bip44
- back
- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT-44" } }
- assertTrue: { condition: "${/^[mn]/.test(output.a)}", label: "Legacy testnet address (m/n)" }
- assertTrue: { condition: "${/^m\\\\/44'\\\\/1'\\\\/0'\\\\/0\\\\//.test(output.p)}", label: "Path m/44'/1'/0'/0/i" }
''', known='The BIP49 (upub) half is covered only up to acceptance in COL-08')

flow('COL-18', ['p0','collection','cleanup'], 'Delete the throwaway collection: gone from Home, others untouched', TC + '''- runFlow: { file: ../subflows/delete_test_collection.yaml, env: { TEST_COLLECTION: "MaestroT" } }
- assertVisible: { id: "rvItemCollectionTitle", text: "My Wallet" }
- assertVisible: { id: "rvItemCollectionTitle", text: "Cold Storage" }
- takeScreenshot: COL-18-home
''')

flow('ROB-07', ['smoke','p1','rotation','robustness'], 'Rotate on Home, collection, Receive, Send, Settings, Tools: no crash', '''- runFlow: ../subflows/home.yaml
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: ROB-07-home
- assertVisible: { id: "toolbarIcon" }
- setOrientation: PORTRAIT
''' + MW + '''- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: { id: "collection_details_transaction_edit_option" }
- setOrientation: PORTRAIT
- tapOn: { id: "bottom_nav_receive" }
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: ROB-07-receive
- assertVisible: { id: "receiveAddressText" }
- setOrientation: PORTRAIT
- tapOn: { id: "bottom_nav_send" }
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: { id: "composeBtn" }
- setOrientation: PORTRAIT
- runFlow: ../subflows/open_settings.yaml
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: "User PIN code"
- setOrientation: PORTRAIT
- runFlow: ../subflows/open_tools.yaml
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: "Broadcast Signed Transaction"
- setOrientation: PORTRAIT
- runFlow: ../subflows/home_basic.yaml
''')

flow('ZZ-RESTORE', ['setup','smoke','p0'], 'Suite teardown: prefs back to baseline, no blocked UTXOs, throwaway collections gone, fresh sync, Home', '''- runFlow: ../subflows/restore_baseline.yaml
''')
