"""Manual-save regression, only for an explicitly selected disposable emulator."""
import argparse, json, re, subprocess, time
from pathlib import Path
from xml.etree import ElementTree as E

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'): raise SystemExit('Disposable emulator required')
ADB = r'D:\AndroidSDK\platform-tools\adb.exe'
PKG = 'io.github.kuaishou.filter'
out = Path(args.out); out.mkdir(parents=True, exist_ok=True)

def adb(*command, check=True):
    return subprocess.run([ADB, '-s', args.serial, *command], check=check,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25).stdout

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ks-manual.xml')
    return E.fromstring(adb('shell', 'cat', '/sdcard/ks-manual.xml'))

def find(attr, value):
    return next(n for n in tree().iter('node') if n.get(attr) == value)

def tap(attr, value):
    n = find(attr, value)
    x,y,r,b = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x+r)//2), str((y+b)//2))

def tap_id(value): tap('resource-id', PKG+':id/'+value)

def fill(value):
    tap_id('rule_keywords')
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')

def read():
    raw = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/rules.xml', check=False)
    if not raw.strip(): return None
    xml = E.fromstring(raw)
    return int(xml.find("long[@name='revision']").get('value')), json.loads(xml.find("string[@name='json']").text)

def saved(predicate):
    deadline = time.monotonic()+8
    while time.monotonic() < deadline:
        state = read()
        if state is not None and predicate(state): return state
        time.sleep(.1)
    raise AssertionError('Expected durable state missing')

def restart():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG+'/.ui.MainActivity')

def no_footer():
    xml = E.tostring(tree(), encoding='unicode')
    assert ':id/save_status' not in xml and '已自动保存' not in xml

ime = adb('shell', 'dumpsys', 'input_method').decode(errors='replace')
english = re.search(r'mSubtypeLocale=en_US[^\n]*mSubtypeHashCode=(-?\d+)', ime)
if english: adb('shell', 'settings', 'put', 'secure', 'selected_input_method_subtype', english.group(1))
adb('shell', 'pm', 'clear', PKG); restart()
tap_id('save_rules'); baseline = saved(lambda s: True)
tap_id('filter_enabled'); tap_id('add_rule'); fill('manualword'); tap('text', '完成')
assert read() == baseline, 'Rule completion or master switch auto-saved'
tap('content-desc', '启用规则：规则 1')
tap_id('mode_whitelist')
tap('content-desc', '更多设置'); tap('text', '关键词忽略大小写')
assert read() == baseline, 'Rule switch, mode or advanced option auto-saved'
adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
assert find('resource-id', PKG+':id/filter_enabled').get('checked') == 'true'
assert find('resource-id', PKG+':id/mode_whitelist').get('selected') == 'true'
assert read() == baseline, 'Activity recreation saved draft'
adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
tap_id('save_rules')
snapshot = saved(lambda s: s[0] > baseline[0])
config = snapshot[1]
assert config['enabled'] and config['mode'] == 'WHITELIST_ONLY' and not config['ignoreKeywordCase']
assert config['blacklist'][0]['keywords'] == ['manualword'] and not config['blacklist'][0]['enabled']
restart(); no_footer(); assert read() == snapshot
assert find('resource-id', PKG+':id/mode_whitelist').get('selected') == 'true'
tap_id('mode_blacklist'); tap('content-desc', '编辑规则：规则 1')
tap_id('rule_keywords'); adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
adb('shell', 'input', 'text', 'edited'); adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); tap('text', '完成')
assert read() == snapshot, 'Rule editing auto-saved'
tap('content-desc', '规则操作：规则 1'); tap('text', '删除')
assert read() == snapshot, 'Deletion auto-saved'
tap('content-desc', '更多设置'); tap('text', '撤销删除')
assert find('text', '关键词 · manualwordedited') is not None
assert read() == snapshot, 'Undo auto-saved'
restart()
assert find('resource-id', PKG+':id/mode_whitelist').get('selected') == 'true'
tap_id('mode_blacklist')
assert find('text', '关键词 · manualword') is not None, 'Unsaved changes survived fresh process'
restart()
adb('shell', 'run-as', PKG, 'chmod', '500', 'shared_prefs')
try:
    tap_id('filter_enabled'); tap_id('save_rules')
    assert find('resource-id', PKG+':id/save_rules').get('enabled') == 'true'
    assert read() == snapshot, 'Failed write altered durable file'
    assert find('resource-id', PKG+':id/filter_enabled').get('checked') == 'false'
    no_footer()
finally: adb('shell', 'run-as', PKG, 'chmod', '700', 'shared_prefs')
tap_id('save_rules'); final = saved(lambda s: not s[1]['enabled'])
restart(); assert read() == final
no_footer()
(out/'manual-save.png').write_bytes(adb('exec-out', 'screencap', '-p'))
(out/'results.json').write_text(json.dumps({'all_edits_stay_unsaved':True, 'draft_survives_activity_recreation':True,
    'manual_save_persists_snapshot':True, 'restart_loads_saved_rules':True, 'unsaved_edits_discarded_after_force_stop':True,
    'failed_save_retains_input_and_button_retry_works':True, 'footer_removed':True, 'api':37}, indent=2)+'\n', encoding='utf-8')
print('Manual-save UI regression passed')
