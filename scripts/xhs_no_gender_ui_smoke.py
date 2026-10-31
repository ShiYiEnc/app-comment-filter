"""Verify XHS has no gender/profile feature, preserving legacy rule conditions.

Only an explicitly selected Android emulator is accepted. All original module
preferences and modified emulator display/input settings are restored. This is
a module UI regression, not a real-host Hook or profile request test.
"""
import argparse
import json
import re
import subprocess
import time
import uuid
from pathlib import Path
from xml.etree import ElementTree as E

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--apk', required=True)
p.add_argument('--out', required=True)
a = p.parse_args()
if not re.fullmatch(r'emulator-\d+', a.serial):
    raise SystemExit('An explicit emulator-NNNN serial is required; physical devices are refused')
ADB = r'D:/AndroidSDK/platform-tools/adb.exe'
PKG = 'io.github.appcommentfilter'
XHS = 'com.xingin.xhs'
out = Path(a.out)
out.mkdir(parents=True, exist_ok=True)
token = uuid.uuid4().hex
backup = 'shared_prefs.no-gender-smoke-' + token
dump_path = '/sdcard/comment-xhs-no-gender-' + token + '.xml'
results = {'serial': a.serial, 'real_host_hooks_tested': False}
isolated = had_prefs = False
original_font = original_ime = original_night = original_size = original_hardware_ime = None


def adb(*command, check=True, data=None):
    r = subprocess.run([ADB, '-s', a.serial, *command], input=data,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and r.returncode:
        raise RuntimeError(f'adb {command}: {r.stderr.decode(errors="replace")}')
    return r


def tree():
    adb('shell', 'uiautomator', 'dump', dump_path)
    raw = adb('shell', 'cat', dump_path).stdout
    (out / 'last-ui.xml').write_bytes(raw)
    return E.fromstring(raw)


def find(value, attr='resource-id', xml=None, required=True):
    if attr == 'resource-id' and ':' not in value:
        value = PKG + ':id/' + value
    found = next((n for n in (tree() if xml is None else xml).iter('node')
                  if n.get(attr) == value), None)
    if required and found is None:
        raise AssertionError(f'UI missing {attr}={value}')
    return found


def dimensions():
    return tuple(map(int, re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size').stdout.decode())[-1]))


def reveal(value, attr='resource-id'):
    top = value in {'app_selector', 'mode_whitelist', 'mode_blacklist', '更多设置'}
    for upward in ((False, True) if top else (True, False)):
        for _ in range(6):
            node = find(value, attr, required=False)
            if node is not None:
                return node
            w, h = dimensions()
            start, end = (.68, .36) if upward else (.36, .68)
            adb('shell', 'input', 'swipe', str(w // 2), str(int(h * start)),
                str(w // 2), str(int(h * end)), '220')
    raise AssertionError('Not scroll-reachable: ' + value)


def tap(value, attr='resource-id'):
    node = find(value, attr, required=False)
    if node is None:
        node = reveal(value, attr)
    l, t, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((l + r) // 2), str((t + b) // 2))


def shot(name):
    xml = tree()
    (out / (name + '.xml')).write_bytes(E.tostring(xml, encoding='utf-8'))
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)


def choose(name):
    tap('app_selector')
    tap(name, 'text')


def restart():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ui.MainActivity')


def raw_rules():
    return adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/rules.' + XHS + '.xml').stdout


def saved():
    xml = E.fromstring(raw_rules())
    return {'revision': int(xml.find("long[@name='revision']").get('value')),
            'json': json.loads(xml.find("string[@name='json']").text)}


def wait_saved(predicate):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        state = saved()
        if predicate(state):
            return state
        time.sleep(.1)
    raise AssertionError('Save not observed')


def assert_no_xhs_gender_ui():
    xml = tree()
    ids = {n.get('resource-id') for n in xml.iter('node')}
    text = '\n'.join(n.get('text', '') for n in xml.iter('node'))
    for name in ('xhs_gender_settings', 'xhs_gender_filter_enabled', 'xhs_gender_filter_note',
                 'rule_gender_section', 'rule_gender_male', 'rule_gender_female', 'rule_gender_unknown'):
        assert PKG + ':id/' + name not in ids, name
    for phrase in ('启用性别过滤', '用户性别', '原生评论不提供性别', '开启后需额外加载作者资料',
                   '按作者资料中的性别匹配'):
        assert phrase not in text, phrase


def open_json():
    tap('更多设置', 'content-desc')
    tap('高级配置', 'text')
    editors = [n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(editors) == 1
    parsed = json.loads(editors[0].get('text'))
    assert 'resolveProfileGender' not in parsed
    return parsed


def assert_keyboard_actions_visible():
    density = int(re.findall(r'\d+', adb('shell', 'wm', 'density').stdout.decode())[-1]) / 160
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        windows = adb('shell', 'dumpsys', 'window', 'windows').stdout.decode(errors='replace')
        ime = re.search(r'Window #\d+ Window\{[^\n]* InputMethod\}:([\s\S]*?)(?=\n  Window #|\Z)', windows)
        if ime:
            content = re.search(r'mGivenContentInsets=\[\d+,(\d+)\]', ime[1])
            frame = re.search(r'Frames:[^\n]* frame=\[\d+,(\d+)\]', ime[1])
            if content and frame:
                keyboard_top = int(content[1]) + int(frame[1])
                xml = tree()
                nodes = [find(label, 'text', xml=xml, required=False) for label in ('取消', '完成')]
                bounds = [list(map(int, re.findall(r'\d+', n.get('bounds')))) for n in nodes if n is not None]
                if len(bounds) == 2 and all(b <= keyboard_top and b - t >= 44 * density for l, t, r, b in bounds):
                    return {'keyboard_top': keyboard_top, 'cancel_bounds': bounds[0], 'complete_bounds': bounds[1]}
        time.sleep(.1)
    raise AssertionError('Dialog actions are clipped or overlap the software keyboard')


def restore(namespace, key, value):
    adb('shell', 'settings', 'delete' if value == 'null' else 'put', namespace, key,
        *(() if value == 'null' else (value,)), check=False)


if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != b'1':
    raise SystemExit('Refusing a non-emulator target')
if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() != b'1':
    raise SystemExit('Selected emulator has not finished booting')

try:
    adb('install', '--no-incremental', '-r', str(Path(a.apk).resolve()))
    adb('shell', 'am', 'force-stop', PKG)
    directory = adb('shell', 'run-as', PKG, 'pwd').stdout.decode().strip()
    assert directory in {'/data/user/0/' + PKG, '/data/data/' + PKG}
    had_prefs = adb('shell', 'run-as', PKG, 'test', '-d', 'shared_prefs', check=False).returncode == 0
    if had_prefs:
        adb('shell', 'run-as', PKG, 'mv', 'shared_prefs', backup)
    isolated = True
    adb('shell', 'run-as', PKG, 'mkdir', '-p', 'shared_prefs')
    original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').stdout.decode().strip()
    original_ime = adb('shell', 'settings', 'get', 'secure', 'selected_input_method_subtype').stdout.decode().strip()
    original_hardware_ime = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').stdout.decode().strip()
    night = adb('shell', 'cmd', 'uimode', 'night').stdout.decode()
    match = re.search(r'Night mode: (\S+)', night)
    original_night = match.group(1) if match else None
    sizes = re.findall(r'Override size: (\d+x\d+)', adb('shell', 'wm', 'size').stdout.decode())
    original_size = sizes[-1] if sizes else 'reset'
    ime = adb('shell', 'dumpsys', 'input_method').stdout.decode(errors='replace')
    english = re.findall(r'mSubtypeLocale=en_US[^\n]*mSubtypeHashCode=(-?\d+)', ime)
    if english:
        adb('shell', 'settings', 'put', 'secure', 'selected_input_method_subtype', english[-1])
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'cmd', 'uimode', 'night', 'no')

    legacy = {'schemaVersion': 1, 'enabled': True, 'mode': 'BLACKLIST', 'resolveProfileGender': True,
              'ignoreKeywordCase': True,
              'blacklist': [{'id': 'mixed', 'keywords': ['promo'], 'genders': ['M']},
                            {'id': 'genderonly', 'genders': ['F']}],
              'whitelist': [{'id': 'old-whitelist-genderonly', 'genders': ['UNKNOWN']}]}
    xml = E.Element('map')
    E.SubElement(xml, 'long', name='revision', value='7')
    E.SubElement(xml, 'string', name='json').text = json.dumps(legacy)
    seeded = E.tostring(xml)
    adb('shell', 'run-as', PKG, 'tee', 'shared_prefs/rules.' + XHS + '.xml', data=seeded)
    restart()
    choose('小红书')
    assert_no_xhs_gender_ui()
    reveal('规则已暂停 · 小红书不支持性别过滤', 'text')
    reveal('关键词 · promo\n性别条件已暂停', 'text')
    assert raw_rules() == seeded
    shot('01-legacy-rules-no-gender')
    results['legacy_true_flag_read_without_rule_write_and_paused_gender_labels'] = True

    normalized = open_json()
    assert normalized['blacklist'][0]['genders'] == ['M']
    assert normalized['blacklist'][1]['genders'] == ['F']
    shot('02-advanced-json-no-profile-flag')
    tap('取消', 'text')
    assert raw_rules() == seeded
    results['advanced_json_hides_removed_root_flag_without_overwriting_legacy_file'] = True

    tap('add_rule')
    assert_no_xhs_gender_ui()
    shot('03-add-no-gender')
    tap('取消', 'text')
    tap('mixed', 'text')
    assert_no_xhs_gender_ui()
    tap('rule_keywords')
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'text', 'edited')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('完成', 'text')
    assert raw_rules() == seeded
    tap('save_rules')
    state = wait_saved(lambda s: s['json']['blacklist'][0]['keywords'] == ['promoedited'])
    assert state['revision'] == 8 and 'resolveProfileGender' not in state['json']
    assert state['json']['blacklist'][0]['genders'] == ['M']
    assert state['json']['blacklist'][1]['genders'] == ['F']
    assert state['json']['whitelist'][0]['genders'] == ['UNKNOWN']
    results['editing_preserves_legacy_arrays_and_only_explicit_save_removes_root_flag'] = True

    tap('add_rule')
    assert_no_xhs_gender_ui()
    tap('rule_keywords')
    adb('shell', 'input', 'text', 'ordinary')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('完成', 'text')
    tap('save_rules')
    state = wait_saved(lambda s: len(s['json']['blacklist']) == 3)
    assert not state['json']['blacklist'][2].get('genders')
    restart()
    assert_no_xhs_gender_ui()
    reveal('关键词 · ordinary', 'text')
    assert saved() == state
    shot('04-saved-rules-restored')
    tap('mode_whitelist')
    reveal('无启用条件时显示全部评论', 'text')
    reveal('规则已暂停 · 小红书不支持性别过滤', 'text')
    results['save_restart_and_gender_only_whitelist_pause'] = True

    for app, name in [('哔哩哔哩', '05-bilibili-still-has-gender'), ('抖音', '06-douyin-still-has-gender')]:
        choose(app)
        tap('add_rule')
        reveal('rule_gender_female')
        find('rule_gender_male')
        find('rule_gender_unknown')
        shot(name)
        tap('取消', 'text')
    choose('小红书')
    assert_no_xhs_gender_ui()
    find('无启用条件时显示全部评论', 'text')
    assert saved() == state
    results['other_apps_keep_gender_and_xhs_unsaved_mode_is_isolated'] = True

    tap('add_rule')
    assert_no_xhs_gender_ui()
    tap('rule_name')
    results['keyboard_visible_action_bounds'] = assert_keyboard_actions_visible()
    shot('07-editor-keyboard')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('取消', 'text')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    tap('add_rule')
    assert_no_xhs_gender_ui()
    shot('08-editor-dark')
    tap('取消', 'text')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    tap('add_rule')
    assert_no_xhs_gender_ui()
    find('完成', 'text')
    shot('09-editor-large-font')
    tap('取消', 'text')
    restore('system', 'font_scale', '1.0')
    adb('shell', 'wm', 'size', '960x1920')
    tap('add_rule')
    assert_no_xhs_gender_ui()
    find('完成', 'text')
    shot('10-editor-narrow')
    tap('取消', 'text')
    adb('shell', 'wm', 'size', original_size)
    normalized = open_json()
    assert normalized['blacklist'][0]['genders'] == ['M']
    shot('11-json-dark')
    tap('取消', 'text')
    results['keyboard_dark_large_font_narrow_and_json_verified'] = True
    results['device_api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').stdout)
except Exception:
    try:
        shot('failure')
    except Exception:
        pass
    raise
finally:
    adb('shell', 'am', 'force-stop', PKG, check=False)
    if isolated:
        adb('shell', 'run-as', PKG, 'rm', '-r', 'shared_prefs', check=False)
        if had_prefs:
            adb('shell', 'run-as', PKG, 'mv', backup, 'shared_prefs')
        results['original_preferences_restored'] = True
    if original_font is not None:
        restore('system', 'font_scale', original_font)
    if original_ime is not None:
        restore('secure', 'selected_input_method_subtype', original_ime)
    if original_hardware_ime is not None:
        restore('secure', 'show_ime_with_hard_keyboard', original_hardware_ime)
    if original_night is not None:
        adb('shell', 'cmd', 'uimode', 'night', original_night, check=False)
    if original_size is not None:
        adb('shell', 'wm', 'size', original_size, check=False)
    adb('shell', 'rm', '-f', dump_path, check=False)
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

print('XHS no-gender UI regression passed; original preferences/settings restored', flush=True)
(out / 'failure.png').unlink(missing_ok=True)
(out / 'failure.xml').unlink(missing_ok=True)
