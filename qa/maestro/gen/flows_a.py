# Flow definitions part A: PIN/lock, home, sync, network, tools, broadcast. Run gen/build.py to write flows/.
F = {}
def flow(id, tags, plan, body, expect='pass', known='', title=None):
    F[id] = dict(tags=tags, plan=plan, body=body, expect=expect, known=known)

WRONG = '''- evalScript:
    script: "${output.wrong = (String(MAESTRO_SENTINEL_PIN).indexOf('13579') === 0) ? '24680' : '13579'}"
    label: "Pick a 5-digit wrong PIN (never the real one)"
'''
def tapdigits(var, n, label='Wrong PIN key'):
    return f'''- evalScript:
    script: ${{output.k = 0}}
    label: "reset key index"
- repeat:
    times: {n}
    commands:
      - tapOn:
          id: "t[a-j]"
          text: ${{String({var}).charAt(output.k)}}
          label: "{label}"
      - evalScript:
          script: ${{output.k = output.k + 1}}
          label: "next key"
'''
READ_LAYOUT = lambda var: '\n'.join(
    f'''- copyTextFrom:
    id: "t{c}"
    label: "Read key t{c}"
- evalScript:
    script: ${{output.{var} = (output.{var} || '') + maestro.copiedText}}
    label: "Append key t{c}"''' for c in 'abcdefghij') + '\n'

flow('PIN-04', ['smoke','p0','pin','lock'], 'Unlock with the right PIN', '''- runFlow: ../subflows/home_basic.yaml
- runFlow: ../subflows/lock.yaml
- assertVisible:
    id: "lockScreenText"
    text: "Enter pin code"
- assertNotVisible:
    id: "fab"
- takeScreenshot: PIN-04-lock
- runFlow: ../subflows/unlock.yaml
- extendedWaitUntil:
    visible:
      id: "fab"
    timeout: 90000
- assertVisible:
    id: "rvItemCollectionTitle"
    text: "My Wallet"
- takeScreenshot: PIN-04-home
''')

flow('PIN-03', ['p1','pin','lock'], 'Length limits on the PIN pad (check mark at 5+, max 8). Run on the launch lock pad; the Settings set-PIN pad uses the same LockScreenDialog', '''- runFlow: ../subflows/lock.yaml
# 4 keys: no check mark yet
- tapOn: { id: "ta", label: "key ta" }
- tapOn: { id: "tb", label: "key tb" }
- tapOn: { id: "tc", label: "key tc" }
- tapOn: { id: "td", label: "key td" }
- assertNotVisible: { id: "tconfirm" }
# 5th key: check mark appears
- tapOn: { id: "te", label: "key te" }
- assertVisible: { id: "tconfirm" }
# keys 6-9: if a 9th digit were accepted, 4 deletes would leave 5 digits and keep the check mark
- tapOn: { id: "tf", label: "key tf" }
- tapOn: { id: "tg", label: "key tg" }
- tapOn: { id: "th", label: "key th" }
- tapOn: { id: "ti", label: "key ti" }
- tapOn: { id: "tback", label: "delete 1" }
- tapOn: { id: "tback", label: "delete 2" }
- tapOn: { id: "tback", label: "delete 3" }
- tapOn: { id: "tback", label: "delete 4" }
- assertNotVisible:
    id: "tconfirm"
    label: "Max 8 digits: 9 taps - 4 deletes = 4 digits, so no check mark"
- longPressOn: { id: "tback", label: "clear all" }
- assertVisible: { id: "lockScreenText" }
- runFlow: ../subflows/unlock.yaml
- assertVisible: { id: "fab" }
''')

flow('PIN-05', ['smoke','p0','pin','lock'], 'Wrong PIN is rejected, field cleared, retry works', WRONG + '''- runFlow: ../subflows/lock.yaml
''' + tapdigits('output.wrong', 5) + '''- tapOn: { id: "tconfirm", label: "Confirm wrong PIN" }
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: { id: "lockScreenText" }
- assertNotVisible:
    id: "tconfirm"
    label: "Field cleared after the wrong PIN"
- assertNotVisible: { id: "fab" }
- takeScreenshot: PIN-05-after-wrong
- runFlow: ../subflows/unlock.yaml
- assertVisible: { id: "fab" }
''')

flow('PIN-06', ['p1','pin','lock','slow'], '20 wrong PINs: no crash, no lockout (behaviour noted for the maintainers)', WRONG + '''- runFlow: ../subflows/lock.yaml
- repeat:
    times: 20
    commands:
''' + '\n'.join('      ' + l for l in tapdigits('output.wrong', 5).splitlines()) + '''
      - tapOn: { id: "tconfirm", label: "Confirm wrong PIN" }
      - assertVisible: { id: "lockScreenText" }
- takeScreenshot: PIN-06-after-20-wrong
- runFlow: ../subflows/unlock.yaml
- assertVisible:
    id: "fab"
    label: "Right PIN still works after 20 wrong ones (no lockout exists)"
''', known='No attempt limit in code: passing means 20 wrong PINs were accepted without lockout or wipe (flag to the maintainers)')

flow('PIN-09', ['smoke','p1','pin','lock'], 'Scramble on: all 10 digits present, layout differs between two locks, right PIN works', '''- runFlow: ../subflows/lock.yaml
- evalScript: { script: "${output.l1 = ''}", label: "init" }
''' + READ_LAYOUT('l1') + '''- assertTrue:
    condition: ${output.l1.split('').sort().join('') === '0123456789'}
    label: "Keypad 1 has each digit once"
- takeScreenshot: PIN-09-layout1
- runFlow: ../subflows/unlock.yaml
- runFlow: ../subflows/lock.yaml
- evalScript: { script: "${output.l2 = ''}", label: "init" }
''' + READ_LAYOUT('l2') + '''- assertTrue:
    condition: ${output.l2.split('').sort().join('') === '0123456789'}
    label: "Keypad 2 has each digit once"
- assertTrue:
    condition: ${output.l1 !== output.l2}
    label: "Layouts differ between the two locks"
- assertTrue:
    condition: ${output.l1 !== '1234567890' && output.l2 !== '1234567890'}
    label: "Not the sequential layout"
- takeScreenshot: PIN-09-layout2
- runFlow: ../subflows/unlock.yaml
- assertVisible: { id: "fab" }
''')

flow('PIN-10', ['p2','pin','lock','settings'], 'Scramble off gives the sequential keypad; turned back on after', '''- runFlow: ../subflows/open_settings.yaml
- assertVisible: { text: "Scramble PIN entry" }
- tapOn: { text: "Scramble PIN entry" }
- runFlow: ../subflows/home_basic.yaml
- runFlow: ../subflows/lock.yaml
- evalScript: { script: "${output.l = ''}", label: "init" }
''' + READ_LAYOUT('l') + '''- takeScreenshot: PIN-10-sequential
- runFlow: ../subflows/unlock.yaml
# restore Scramble ON before asserting, so a failure never leaves it off
- runFlow: ../subflows/open_settings.yaml
- tapOn: { text: "Scramble PIN entry" }
- runFlow: ../subflows/home_basic.yaml
- assertTrue:
    condition: ${output.l === '1234567890'}
    label: "Scramble off: keys ta..tj read 1,2,...,9,0"
''')

flow('PIN-11', ['p2','pin','lock'], 'Back and long-press clear keys update the entry correctly', '''- runFlow: ../subflows/lock.yaml
- tapOn: { id: "ta", label: "key" }
- tapOn: { id: "tb", label: "key" }
- tapOn: { id: "tc", label: "key" }
- tapOn: { id: "td", label: "key" }
- tapOn: { id: "te", label: "key" }
- assertVisible: { id: "tconfirm" }
- tapOn: { id: "tback", label: "delete one" }
- assertNotVisible:
    id: "tconfirm"
    label: "4 digits left: check mark hidden"
- tapOn: { id: "tf", label: "key" }
- assertVisible:
    id: "tconfirm"
    label: "back to 5 digits"
- longPressOn: { id: "tback", label: "long-press clear" }
- assertNotVisible: { id: "tconfirm" }
- tapOn: { id: "ta", label: "key" }
- tapOn: { id: "tb", label: "key" }
- tapOn: { id: "tc", label: "key" }
- tapOn: { id: "td", label: "key" }
- assertNotVisible:
    id: "tconfirm"
    label: "Cleared fully: 4 new digits, no check mark"
- takeScreenshot: PIN-11-after-clear
- longPressOn: { id: "tback", label: "clear" }
- runFlow: ../subflows/unlock.yaml
- assertVisible: { id: "fab" }
''')

flow('PIN-16', ['smoke','p1','pin','lock','rotation'], 'Rotate during PIN entry: no crash, entry kept or cleanly cleared', '''- runFlow: ../subflows/lock.yaml
- tapOn: { id: "ta", label: "key" }
- tapOn: { id: "tb", label: "key" }
- tapOn: { id: "tc", label: "key" }
- setOrientation: LANDSCAPE_LEFT
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: PIN-16-landscape
- assertVisible: { id: "lockScreenText" }
- setOrientation: PORTRAIT
- waitForAnimationToEnd: { timeout: 5000 }
- assertVisible: { id: "lockScreenText" }
- longPressOn: { id: "tback", label: "clear" }
- runFlow: ../subflows/unlock.yaml
- assertVisible: { id: "fab" }
''')

flow('SYNC-01', ['smoke','p0','home','sync'], 'Home total = sum of the collection balances after a sync', '''- runFlow: ../subflows/home.yaml
- runFlow: ../subflows/wait_sync_idle.yaml
- extendedWaitUntil:
    visible:
      id: "homeBalanceBtc"
      text: "[0-9.,]+ BTC"
    timeout: 120000
- copyTextFrom: { id: "homeBalanceBtc" }
- evalScript: { script: "${output.total = parseFloat(maestro.copiedText.replace(/[^0-9.]/g,''))}", label: "home total" }
- evalScript: { script: "${output.sum = 0; output.i = 0}", label: "init" }
- repeat:
    while:
      visible:
        id: "rvItemCollectionBalance"
        index: ${output.i}
    times: 12
    commands:
      - copyTextFrom:
          id: "rvItemCollectionBalance"
          index: ${output.i}
      - evalScript: { script: "${output.sum = output.sum + parseFloat(maestro.copiedText.replace(/[^0-9.]/g,'')); output.i = output.i + 1}", label: "add row" }
- takeScreenshot: SYNC-01-home
- assertTrue:
    condition: ${output.i >= 2}
    label: "At least My Wallet and Cold Storage listed"
- assertTrue:
    condition: ${Math.abs(output.total - output.sum) < 0.000000005}
    label: "Home total equals the sum of the rows"
''')

flow('SYNC-03', ['smoke','p1','home','sync'], 'Pull to refresh triggers a sync with visible feedback, ending in success', '''- runFlow: ../subflows/home.yaml
- runFlow: ../subflows/wait_sync_idle.yaml
- swipe:
    start: "50%, 35%"
    end: "50%, 85%"
    duration: 800
- extendedWaitUntil:
    visible:
      id: "syncStatusText"
      text: "(Syncing.*|Updated just now|Connecting via Tor.*)"
    timeout: 30000
    label: "Sync banner appears after the pull"
- takeScreenshot: SYNC-03-syncing
- extendedWaitUntil:
    notVisible:
      id: "syncStatusText"
      text: "(Syncing.*|Connecting via Tor.*)"
    timeout: 240000
- assertNotVisible:
    id: "syncRetryButton"
    label: "No failed-sync RETRY banner"
- takeScreenshot: SYNC-03-done
''')

flow('SYNC-06', ['p1','home','sync'], 'Home total while syncing is not blank and fiat is not 00.00', '''- runFlow: ../subflows/lock.yaml
- runFlow: ../subflows/unlock.yaml
- runFlow: ../subflows/dismiss_anr.yaml
- assertVisible: { id: "fab" }
- takeScreenshot: SYNC-06-right-after-unlock
- assertVisible:
    id: "homeBalanceBtc"
    text: "[0-9.,]+ BTC"
    label: "Total shown straight after unlock (Tor restarting)"
- assertNotVisible:
    id: "exchangeRateTxt"
    text: "00\\\\.00.*"
    label: "Fiat is not 00.00"
''')

flow('TOOL-01', ['smoke','p2','tools'], 'Tools menu lists Sweep and Broadcast', '''- runFlow: ../subflows/open_tools.yaml
- assertVisible: { text: "Sweep Private Key" }
- assertVisible: { text: "Broadcast Signed Transaction" }
- takeScreenshot: TOOL-01-tools
- back
- assertVisible: { id: "fab" }
''')

flow('TOOL-04', ['p1','tools'], 'Sweep with an invalid key: clear refusal, no crash', '''- runFlow: ../subflows/open_tools.yaml
- tapOn: "Sweep Private Key"
- extendedWaitUntil: { visible: { id: "textPrivateKey|pastePrivKeyButton" }, timeout: 20000 }
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "${GARBAGE}" }, label: "clipboard = garbage" }
- tapOn: { id: "pastePrivKeyButton" }
- runFlow:
    when: { visible: { id: "sweepStartBtn", enabled: true } }
    commands:
      - tapOn: { id: "sweepStartBtn" }
- waitForAnimationToEnd: { timeout: 5000 }
- takeScreenshot: TOOL-04-invalid
- assertNotVisible: { text: "(?i).*sweep preview.*|.*confirm sweep.*" }
- assertVisible:
    text: "Broadcast Signed Transaction|Sweep Private Key"
    label: "Still in Tools / sweep sheet, app alive"
- back
''')

BC_OPEN = '''- runFlow: ../subflows/open_tools.yaml
- tapOn: "Broadcast Signed Transaction"
- extendedWaitUntil: { visible: { id: "pasteHex" }, timeout: 20000 }
'''
flow('BC-05', ['smoke','p0','broadcast'], 'Invalid hex: button disabled or clear error, no crash (never broadcasts)', BC_OPEN + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "zz-not-hex-at-all" }, label: "clipboard = garbage" }
- tapOn: { id: "pasteHex" }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible:
    id: "broadCastTransactionBtn"
    enabled: false
    label: "BROADCAST disabled for garbage"
- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "deadbeef00" }, label: "clipboard = short hex" }
- tapOn: { id: "pasteHex" }
- waitForAnimationToEnd: { timeout: 3000 }
- assertVisible:
    id: "broadCastTransactionBtn"
    enabled: false
    label: "BROADCAST disabled for a non-transaction hex"
- takeScreenshot: BC-05
- back
''')

flow('BC-06', ['p1','broadcast'], 'Unsigned PSBT pasted: refused / explains it must be signed (never broadcasts)', BC_OPEN + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "cHNidP8BAHECAAAAAQ==" }, label: "clipboard = unsigned PSBT stub" }
- tapOn: { id: "pasteHex" }
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: BC-06
- assertVisible:
    id: "broadCastTransactionBtn"
    enabled: false
    label: "BROADCAST stays disabled for a PSBT"
- back
''', known='No explanatory message is expected to be seen by automation (toast); the check is that broadcasting is blocked')

flow('BC-09', ['p2','broadcast','known'], 'Hex box keeps the text after an invalid paste (known: cleared)', BC_OPEN + '''- runScript: { file: ../scripts/js/clip_set.js, env: { TEXT: "0100000001abcdef" }, label: "clipboard = bad tx hex" }
- tapOn: { id: "pasteHex" }
- waitForAnimationToEnd: { timeout: 3000 }
- takeScreenshot: BC-09
- assertVisible:
    id: "hexTextView"
    text: "0100000001abcdef"
    label: "Hex kept in the box for a retry"
- back
''', expect='fail', known='Known (plan BC-09): invalid hex is cleared from the box (RESULTS BC-05: box left empty)')

flow('TOR-01', ['p0','network','tor'], 'Network screen: Tor and Dojo enabled (Tor on at launch)', '''- runFlow: ../subflows/home.yaml
- tapOn: { id: "activity_home_menu_network" }
- extendedWaitUntil: { visible: { id: "network_tor_status" }, timeout: 20000 }
- extendedWaitUntil:
    visible: { id: "network_tor_status", text: "Enabled" }
    timeout: 240000
- assertVisible: { id: "network_dojo_status", text: "Enabled" }
- assertVisible: { id: "network_bitcoin_status", text: "Testnet.*" }
- takeScreenshot: TOR-01-network
- back
''')

flow('TOR-03', ['p1','network','tor'], 'Disable Tor while the Dojo is enabled is refused with a message (Tor stays on)', '''- runFlow: ../subflows/home.yaml
- tapOn: { id: "activity_home_menu_network" }
- extendedWaitUntil:
    visible: { id: "network_tor_status", text: "Enabled" }
    timeout: 240000
- tapOn: { id: "networking_tor_btn", text: "DISABLE" }
- extendedWaitUntil:
    visible: { text: "(?i).*won.t be able to disable Tor.*" }
    timeout: 10000
- takeScreenshot: TOR-03-refused
- assertVisible: { id: "network_tor_status", text: "Enabled" }
- back
''')
