"""Non-destructive Android emulator UI regression for independent application profiles.

Backs up the module's original SharedPreferences, uses an isolated test directory,
then restores the original preferences even if an assertion fails. Never wipes an AVD.
"""
import argparse
import json
import re
import subprocess
import time
import uuid
from pathlib import Path
from xml.etree import ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--apk', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('Use an explicitly selected emulator')
ADB = r'D:\AndroidSDK\platform-tools\adb.exe'
PKG = 'io.github.kuaishou.filter'
APPS = {
    '快手': 'com.smile.gifmaker', '哔哩哔哩': 'tv.danmaku.bili',
    '哔哩哔哩 HD': 'tv.danmaku.bilibilihd', '小红书': 'com.xingin.xhs',
    '抖音': 'com.ss.android.ugc.aweme',
}
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
results = {}


def adb(*command, check=True, data=None):
    result = subprocess.run([ADB, '-s', args.serial, *command], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and result.returncode:
        raise RuntimeError(f'adb {command}: {result.stderr.decode(errors="replace")}')
    return result


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/comment-profile-smoke.xml')
    raw = adb('shell', 'cat', '/sdcard/comment-profile-smoke.xml').stdout
    (out / 'last-ui.xml').write_bytes(raw)
    return ET.fromstring(raw)


def find(attr, value, xml=None):
    xml = tree() if xml is None else xml
    found = next((n for n in xml.iter('node') if n.get(attr) == value), None)
    if found is None:
        raise AssertionError(f'UI element missing: {attr}={value}')
    return found


def tap(attr, value):
    node = find(attr, value)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_id(value):
    tap('resource-id', PKG + ':id/' + value)


def checked(xml=None):
    return find('resource-id', PKG + ':id/filter_enabled', xml).get('checked') == 'true'


def white(xml=None):
    return find('resource-id', PKG + ':id/mode_whitelist', xml).get('selected') == 'true'


def choose(name):
    tap_id('app_selector')
    tap('text', name)


def add_rule(word):
    tap_id('add_rule')
    tap_id('rule_keywords')
    adb('shell', 'input', 'text', word)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('text', '完成')


def prefs(file):
    result = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/' + file, check=False)
    if result.returncode or not result.stdout.strip():
        return None
    xml = ET.fromstring(result.stdout)
    value = xml.find("string[@name='json']")
    if value is None:
        return None
    revision = xml.find("long[@name='revision']")
    return {'revision': int(revision.get('value')), 'json': json.loads(value.text)}


def read(name):
    return prefs('rules.' + APPS[name] + '.xml')


def saved(name, predicate):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        value = read(name)
        if value is not None and predicate(value):
            return value
        time.sleep(.1)
    raise AssertionError('Expected saved profile missing: ' + name)


def restart():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ui.MainActivity')


def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p').stdout)


def restore_setting(namespace, key, value):
    if value == 'null':
        adb('shell', 'settings', 'delete', namespace, key, check=False)
    else:
        adb('shell', 'settings', 'put', namespace, key, value, check=False)


backup = 'shared_prefs.profile-smoke-' + uuid.uuid4().hex
original_font = None
original_ime = None
original_night = None
isolated = False
had_prefs = False
try:
    assert adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == b'1'
    adb('install', '-r', str(Path(args.apk).resolve()))
    adb('shell', 'am', 'force-stop', PKG)
    directory = adb('shell', 'run-as', PKG, 'pwd').stdout.decode().strip()
    assert directory in {'/data/user/0/' + PKG, '/data/data/' + PKG}, directory
    had_prefs = adb('shell', 'run-as', PKG, 'ls', '-d', 'shared_prefs', check=False).returncode == 0
    if had_prefs:
        (out / 'original-shared-prefs.tar').write_bytes(
            adb('exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'shared_prefs').stdout)
        adb('shell', 'run-as', PKG, 'mv', 'shared_prefs', backup)
    adb('shell', 'run-as', PKG, 'mkdir', 'shared_prefs')
    isolated = True

    original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').stdout.decode().strip()
    original_ime = adb('shell', 'settings', 'get', 'secure', 'selected_input_method_subtype').stdout.decode().strip()
    night = adb('shell', 'cmd', 'uimode', 'night').stdout.decode().strip()
    night_match = re.search(r'Night mode: (\S+)', night)
    original_night = night_match.group(1) if night_match else None
    ime = adb('shell', 'dumpsys', 'input_method').stdout.decode(errors='replace')
    english = re.findall(r'mSubtypeLocale=en_US[^\n]*mSubtypeHashCode=(-?\d+)', ime)
    if english:
        # Gboard may expose an inactive built-in subtype and an enabled user subtype.
        adb('shell', 'settings', 'put', 'secure', 'selected_input_method_subtype', english[-1])

    legacy = {'schemaVersion': 1, 'enabled': True, 'mode': 'BLACKLIST', 'ignoreKeywordCase': True,
              'blacklist': [{'id': 'legacy', 'keywords': ['legacykeyword']}], 'whitelist': []}
    legacy_xml = ET.Element('map')
    ET.SubElement(legacy_xml, 'long', name='revision', value='41')
    ET.SubElement(legacy_xml, 'string', name='json').text = json.dumps(legacy)
    adb('shell', 'run-as', PKG, 'tee', 'shared_prefs/rules.xml', data=ET.tostring(legacy_xml))
    restart()
    initial = tree()
    assert checked(initial) and not white(initial)
    find('text', '关键词 · legacykeyword', initial)
    assert all(read(name) is None for name in APPS)
    results['legacy_kuaishou_read_without_migration_write'] = True
    print('Legacy profile loaded without writes', flush=True)

    tap_id('filter_enabled')
    tap_id('mode_whitelist')
    choose('哔哩哔哩')
    bili = tree()
    assert not checked(bili) and not white(bili)
    find('text', '暂无规则', bili)
    assert read('哔哩哔哩') is None
    tap_id('filter_enabled')
    add_rule('biliword')
    tap_id('mode_whitelist')
    choose('快手')
    kuaishou = tree()
    assert not checked(kuaishou) and white(kuaishou)
    assert prefs('rules.xml') == {'revision': 41, 'json': legacy}
    assert all(read(name) is None for name in APPS)
    choose('哔哩哔哩')
    bili = tree()
    assert checked(bili) and white(bili)
    tap_id('mode_blacklist')
    find('text', '关键词 · biliword')
    tap_id('mode_whitelist')
    screenshot('01-bilibili-draft.png')
    tap_id('save_rules')
    bili_saved = saved('哔哩哔哩', lambda value: value['json']['enabled'])
    assert bili_saved['revision'] == 1
    assert bili_saved['json']['mode'] == 'WHITELIST_ONLY'
    assert bili_saved['json']['blacklist'][0]['keywords'] == ['biliword']
    assert read('快手') is None
    results['switch_keeps_independent_unsaved_drafts_and_save_only_current_profile'] = True
    print('Draft switching and current-profile manual save passed', flush=True)

    # Recreate the Activity while a different application's unsaved draft is retained.
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3' if original_font != '1.3' else '1.0')
    choose('快手')
    kuaishou = tree()
    assert not checked(kuaishou) and white(kuaishou)
    tap_id('save_rules')
    ks_saved = saved('快手', lambda value: value['revision'] == 42)
    assert ks_saved['json']['mode'] == 'WHITELIST_ONLY' and not ks_saved['json']['enabled']
    assert prefs('rules.xml') == {'revision': 41, 'json': legacy}
    assert read('哔哩哔哩') == bili_saved
    results['drafts_survive_activity_recreation_and_first_ks_save_migrates_revision'] = True
    restore_setting('system', 'font_scale', original_font)
    print('Activity recreation and legacy revision migration passed', flush=True)

    snapshots = {'快手': ks_saved, '哔哩哔哩': bili_saved}
    for name, word, enabled, whitelist in [
        ('哔哩哔哩 HD', 'hdword', False, False), ('小红书', 'xhsword', True, False),
        ('抖音', 'douyinword', True, True),
    ]:
        choose(name)
        clean = tree()
        assert not checked(clean) and not white(clean)
        find('text', '暂无规则', clean)
        add_rule(word)
        if enabled:
            tap_id('filter_enabled')
        if whitelist:
            tap_id('mode_whitelist')
        assert read(name) is None
        tap_id('save_rules')
        current = saved(name, lambda value: value['revision'] == 1)
        assert current['json']['enabled'] == enabled
        assert current['json']['mode'] == ('WHITELIST_ONLY' if whitelist else 'BLACKLIST')
        assert current['json']['blacklist'][0]['keywords'] == [word]
        for other, previous in snapshots.items():
            assert read(other) == previous, 'Cross-profile write: ' + other
        snapshots[name] = current
        screenshot('profile-' + APPS[name] + '.png')
        print('Independent save passed: ' + name, flush=True)
    results['all_five_profiles_keep_independent_rules_modes_switches_revisions'] = True

    # Unsaved changes must disappear after a fresh process, while saved profiles persist.
    tap_id('filter_enabled')
    tap_id('mode_blacklist')
    assert read('抖音') == snapshots['抖音']
    restart()
    restored = tree()
    assert checked(restored) and white(restored)
    assert '抖音' in find('resource-id', PKG + ':id/app_selector', restored).get('text')
    for name, snapshot in snapshots.items():
        assert read(name) == snapshot
    choose('哔哩哔哩 HD')
    restored = tree()
    assert not checked(restored) and not white(restored)
    find('text', '关键词 · hdword', restored)
    results['fresh_process_restores_saved_profiles_and_last_selection_discards_unsaved_edits'] = True
    screenshot('05-hd-restored.png')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    dark = tree()
    assert not checked(dark) and not white(dark)
    find('text', '关键词 · hdword', dark)
    screenshot('06-hd-dark.png')
    results['dark_mode_recreation_retains_selected_profile'] = True

    denied = adb('shell', 'content', 'query', '--uri',
                 'content://io.github.kuaishou.filter.config/rules/' + APPS['抖音'], check=False)
    assert b'SecurityException' in denied.stdout + denied.stderr
    results['unrelated_shell_uid_cannot_query_profile'] = True
    results['device_api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').stdout)
    results['host_hooks_tested'] = False
    print('Process restore and unauthorized caller rejection passed', flush=True)
except Exception:
    screenshot('failure.png')
    raise
finally:
    adb('shell', 'am', 'force-stop', PKG, check=False)
    if isolated:
        # Both directories are inside the validated test module's private data directory.
        adb('shell', 'run-as', PKG, 'rm', '-r', 'shared_prefs')
        if had_prefs:
            adb('shell', 'run-as', PKG, 'mv', backup, 'shared_prefs')
        results['original_module_preferences_restored'] = True
    if original_font is not None:
        restore_setting('system', 'font_scale', original_font)
    if original_ime is not None:
        restore_setting('secure', 'selected_input_method_subtype', original_ime)
    if original_night is not None:
        adb('shell', 'cmd', 'uimode', 'night', original_night, check=False)
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

print('Per-application UI smoke passed; original preferences restored', flush=True)
(out / 'failure.png').unlink(missing_ok=True)
