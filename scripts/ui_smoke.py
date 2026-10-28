"""Historical auto-save UI regression for versions 0.1.3–0.1.5 only.

Current versions use scripts/manual_save_smoke.py.

Usage: python scripts/ui_smoke.py --serial emulator-5580 --out docs/ui-0.1.3
Never targets a physical device. This modifies only this module's test configuration.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('Use an explicitly selected disposable emulator')
ADB = r'D:\AndroidSDK\platform-tools\adb.exe'
PKG = 'io.github.kuaishou.filter'
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
results = []


def adb(*command, check=True):
    return subprocess.run([ADB, '-s', args.serial, *command], check=check,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ks-filter-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/ks-filter-ui.xml'))


def find(attr, value):
    for node in tree().iter('node'):
        if node.get(attr) == value:
            return node
    raise AssertionError(f'No {attr}={value}')


def tap(attr, value):
    node = find(attr, value)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    return node


def tap_id(name):
    return tap('resource-id', PKG + ':id/' + name)


def fill(name, value):
    old = tap_id(name).get('text', '')
    # Empty EditText.text can expose its hint; MOVE_END and excess DELs remain safe.
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    if old:
        adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * len(old)))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def read():
    raw = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/rules.xml', check=False)
    if not raw.strip():
        return None
    node = ET.fromstring(raw).find("string[@name='json']")
    return json.loads(node.text) if node is not None else None


def saved(predicate):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        value = read()
        if value is not None and predicate(value):
            return value
        time.sleep(.1)
    raise AssertionError('Auto-save did not reach expected durable state')


def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p'))


def restart():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-n', PKG + '/.ui.MainActivity')


def has_rule(config, name, word=None):
    return any(rule['id'] == name and (word is None or rule.get('keywords') == [word]) for rule in config['blacklist'])


# Hardware key injection must use the emulator's English subtype, not Pinyin composition.
ime = adb('shell', 'dumpsys', 'input_method').decode('utf-8', errors='replace')
english = re.search(r'mSubtypeLocale=en_US[^\n]*mSubtypeHashCode=(-?\d+)', ime)
if english:
    adb('shell', 'settings', 'put', 'secure', 'selected_input_method_subtype', english.group(1))
adb('shell', 'pm', 'clear', PKG)
restart()
for cycle in range(1, 4):
    name = f'promo{cycle}'
    tap_id('mode_blacklist')
    if find('resource-id', PKG + ':id/filter_enabled').get('checked') != 'true':
        tap_id('filter_enabled')
    saved(lambda x: x['enabled'] and x['mode'] == 'BLACKLIST')
    tap_id('add_rule')
    fill('rule_name', name)
    fill('rule_keywords', f'qzxv{cycle}')
    tap('text', '完成')
    saved(lambda x: has_rule(x, name, f'qzxv{cycle}'))
    if cycle == 1:
        screenshot('populated.png')
    tap('content-desc', '编辑规则：' + name)
    fill('rule_keywords', f'zxqv{cycle}')
    tap('text', '完成')
    saved(lambda x: has_rule(x, name, f'zxqv{cycle}'))
    tap('content-desc', '规则操作：' + name)
    tap('text', '删除')
    saved(lambda x: not has_rule(x, name))
    tap_id('save_status')
    saved(lambda x: has_rule(x, name, f'zxqv{cycle}'))
    tap('content-desc', '规则操作：' + name)
    tap('text', '删除')
    saved(lambda x: not has_rule(x, name))
    tap_id('mode_whitelist')
    saved(lambda x: x['mode'] == 'WHITELIST_ONLY')
    tap_id('mode_blacklist')
    saved(lambda x: x['mode'] == 'BLACKLIST')
    switch = find('resource-id', PKG + ':id/filter_enabled')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', switch.get('bounds')))
    for _ in range(2):
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    saved(lambda x: x['enabled'])
    restart()
    assert find('resource-id', PKG + ':id/filter_enabled').get('checked') == 'true'
    assert not has_rule(read(), name)
    xml = ET.tostring(tree(), encoding='unicode')
    assert '复制备份' not in xml and '测试一条评论' not in xml and '保存并应用' not in xml
    results.append({'cycle': cycle, 'add_edit_delete_undo_modes_rapid_switch_restart': 'passed'})
    print(f'cycle {cycle} passed', flush=True)

# Force the real SharedPreferences atomic write to fail; restore permissions even on assertion failure.
before = read()
try:
    adb('shell', 'run-as', PKG, 'chmod', '500', 'shared_prefs')
    tap_id('filter_enabled')
    assert find('resource-id', PKG + ':id/save_status').get('text') == '保存失败，点此重试'
    assert read() == before
    screenshot('save-failure.png')
finally:
    adb('shell', 'run-as', PKG, 'chmod', '700', 'shared_prefs')
tap_id('save_status')
saved(lambda x: x['enabled'] != before['enabled'])
restart()
assert read()['enabled'] != before['enabled']
results.append({'real_write_failure_retains_ui_and_retry_persists': 'passed'})
(out / 'interaction-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('durable failure/retry passed', flush=True)
