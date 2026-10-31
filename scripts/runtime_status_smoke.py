"""Exercise diagnostic persistence/permissions/UI on an explicitly selected emulator.

Requires a signed, code-free com.xingin.xhs fixture (99.0.0/990000).
Refuses to replace an installed XHS, and restores all original module preferences.
No target-app Hook compatibility is asserted by this test.
"""
import argparse
import json
import re
import subprocess
import uuid
from pathlib import Path
from xml.etree import ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--apk', required=True)
p.add_argument('--test-apk', required=True)
p.add_argument('--stub-apk', required=True)
p.add_argument('--out', required=True)
a = p.parse_args()
if not a.serial.startswith('emulator-'):
    raise SystemExit('Select an emulator explicitly')
ADB = r'D:/AndroidSDK/platform-tools/adb.exe'
PKG = 'io.github.appcommentfilter'
HOST = 'com.xingin.xhs'
RUNNER = PKG + '.test/' + PKG + '.RuntimeStatusInstrumentation'
out = Path(a.out)
out.mkdir(parents=True, exist_ok=True)
results = {'host_hooks_tested': False}


def adb(*args, check=True, data=None):
    r = subprocess.run([ADB, '-s', a.serial, *args], input=data, capture_output=True, timeout=50)
    if check and r.returncode:
        raise RuntimeError(r.stdout.decode(errors='replace') + r.stderr.decode(errors='replace'))
    return r


def instrument(seed=None):
    command = ['shell', 'am', 'instrument', '-w']
    if seed:
        command += ['-e', 'seed', seed]
    command += [RUNNER]
    r = adb(*command)
    text = r.stdout.decode(errors='replace')
    assert 'INSTRUMENTATION_CODE: -1' in text and 'error=' not in text, text
    return text


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/comment-runtime-smoke.xml')
    raw = adb('shell', 'cat', '/sdcard/comment-runtime-smoke.xml').stdout
    return ET.fromstring(raw), raw


def tap_id(name):
    xml, _ = tree()
    node = next(n for n in xml.iter('node') if n.get('resource-id') == PKG + ':id/' + name)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2)//2), str((y1 + y2)//2))


def capture(name):
    _, xml = tree()
    (out / (name + '.xml')).write_bytes(xml)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    return xml.decode()


existing = adb('shell', 'pm', 'path', HOST, check=False)
if b'package:' in existing.stdout:
    raise SystemExit('Refusing to replace an installed XHS app')
backup = 'runtime_smoke_backup_' + uuid.uuid4().hex
had_prefs = isolated = stub_installed = False
night = adb('shell', 'settings', 'get', 'secure', 'ui_night_mode').stdout.decode().strip()
try:
    adb('install', '--no-incremental', '-r', a.apk)
    adb('install', '--no-incremental', '-r', a.test_apk)
    adb('shell', 'am', 'force-stop', PKG)
    had_prefs = adb('shell', 'run-as', PKG, 'test', '-d', 'shared_prefs', check=False).returncode == 0
    if had_prefs:
        adb('shell', 'run-as', PKG, 'mv', 'shared_prefs', backup)
    isolated = True
    adb('shell', 'run-as', PKG, 'mkdir', '-p', 'shared_prefs')
    adb('install', '--no-incremental', a.stub_apk)
    stub_installed = True
    results['binder_tests'] = instrument()
    adb('shell', 'am', 'force-stop', PKG)
    selection = b'<?xml version="1.0" encoding="utf-8"?><map><string name="selectedApp">com.xingin.xhs</string></map>'
    adb('shell', 'run-as', PKG, 'tee', 'shared_prefs/ui.xml', data=selection)
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ui.MainActivity')
    assert '尚无运行记录' in capture('00-no-record')
    for scenario, label in [('waiting', '已加载，等待评论'), ('active', '已读取评论'),
                            ('partial', '部分功能异常'), ('failed', '适配失败'),
                            ('old', '更新后等待验证')]:
        adb('shell', 'am', 'force-stop', PKG)
        instrument(scenario)
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.ui.MainActivity')
        assert label in capture(scenario), scenario
        tap_id('runtime_status')
        detail = capture(scenario + '-detail')
        assert '99.0.0' in detail and '运行记录' in detail
        if scenario == 'partial':
            assert 'CommentShapeError' in detail
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        results[scenario] = True
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    assert '更新后等待验证' in capture('dark')
    denied = adb('shell', 'content', 'call', '--uri', 'content://' + PKG + '.config/rules',
                 '--method', 'reportStatus', '--arg', HOST, check=False)
    assert b'SecurityException' in denied.stdout + denied.stderr
    results['unrelated_uid_rejected'] = True
    results['api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').stdout)
    print('Runtime status Binder/persistence/UI checks passed', flush=True)
finally:
    adb('shell', 'am', 'force-stop', PKG, check=False)
    if isolated:
        adb('shell', 'run-as', PKG, 'rm', '-r', 'shared_prefs', check=False)
        if had_prefs:
            adb('shell', 'run-as', PKG, 'mv', backup, 'shared_prefs')
        results['original_preferences_restored'] = True
    if stub_installed:
        adb('uninstall', HOST, check=False)
    adb('shell', 'cmd', 'uimode', 'night', {'1': 'no', '2': 'yes'}.get(night, 'auto'), check=False)
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
