"""Exercise optional XHS gender controls/dialogs on an explicitly selected Android emulator.

Only the module's disposable test preferences are edited. Original preferences and
device display/input settings are restored even on assertion failure; no AVD wipe.
"""
import argparse, json, re, subprocess, time, uuid
from pathlib import Path
from xml.etree import ElementTree as E

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True); p.add_argument('--apk', required=True); p.add_argument('--out', required=True)
a = p.parse_args()
if not a.serial.startswith('emulator-'): raise SystemExit('An explicit emulator serial is required')
ADB = r'D:\AndroidSDK\platform-tools\adb.exe'
PKG = 'io.github.appcommentfilter'; XHS = 'com.xingin.xhs'
out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
results = {}; backup = 'shared_prefs.gender-smoke-' + uuid.uuid4().hex
isolated = had_prefs = False
original_font = original_ime = original_night = original_size = original_hardware_ime = None

def adb(*command, check=True, data=None):
    r = subprocess.run([ADB, '-s', a.serial, *command], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if check and r.returncode: raise RuntimeError(f'adb {command}: {r.stderr.decode(errors="replace")}')
    return r

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/comment-gender-smoke.xml')
    raw = adb('shell', 'cat', '/sdcard/comment-gender-smoke.xml').stdout
    (out/'last-ui.xml').write_bytes(raw)
    return E.fromstring(raw)

def find(value, attr='resource-id', xml=None, required=True):
    if attr == 'resource-id' and ':' not in value: value = PKG + ':id/' + value
    node = next((n for n in (tree() if xml is None else xml).iter('node') if n.get(attr) == value), None)
    if required and node is None: raise AssertionError(f'UI missing {attr}={value}')
    return node

def tap(value, attr='resource-id'):
    n = find(value, attr, required=False)
    if n is None: n = reveal(value, attr)
    x,y,r,b = map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2))

def shot(name): (out/name).write_bytes(adb('exec-out','screencap','-p').stdout)

def dimensions():
    r = adb('shell','wm','size').stdout.decode()
    return tuple(map(int,re.findall(r'(\d+)x(\d+)',r)[-1]))

def reveal(value, attr='resource-id'):
    # Status cards and accessibility fonts change how much of the rule list fits.
    # Locate real scrollable content instead of assuming a rule/control is on page one.
    top = value in {'app_selector', 'xhs_gender_filter_enabled', 'mode_whitelist', 'mode_blacklist', '更多设置'}
    for upward in ((False, True) if top else (True, False)):
        for _ in range(6):
            n = find(value, attr, required=False)
            if n is not None: return n
            w,h = dimensions(); start,end = (.68,.36) if upward else (.36,.68)
            adb('shell','input','swipe',str(w//2),str(int(h*start)),str(w//2),str(int(h*end)),'220')
    raise AssertionError('Not scroll-reachable: '+value)

def choose(name): tap('app_selector'); tap(name,'text')

def restart():
    adb('shell','am','force-stop',PKG)
    adb('shell','am','start','-W','-n',PKG+'/.ui.MainActivity')

def read():
    r=adb('shell','run-as',PKG,'cat','shared_prefs/rules.'+XHS+'.xml',check=False)
    if r.returncode:return None
    xml=E.fromstring(r.stdout)
    return json.loads(xml.find("string[@name='json']").text)

def wait_saved(predicate):
    deadline=time.monotonic()+8
    while time.monotonic()<deadline:
        state=read()
        if state is not None and predicate(state): return state
        time.sleep(.1)
    raise AssertionError('Save not observed')

def save(): tap('save_rules')

def gender_checked(): return find('xhs_gender_filter_enabled').get('checked')=='true'

def assert_keyboard_actions_visible():
    density = int(re.findall(r'\d+', adb('shell','wm','density').stdout.decode())[-1]) / 160
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        windows = adb('shell','dumpsys','window','windows').stdout.decode(errors='replace')
        ime = re.search(r'Window #\d+ Window\{[^\n]* InputMethod\}:([\s\S]*?)(?=\n  Window #|\Z)', windows)
        if ime:
            content = re.search(r'mGivenContentInsets=\[\d+,(\d+)\]', ime[1])
            frame = re.search(r'Frames:[^\n]* frame=\[\d+,(\d+)\]', ime[1])
            if content and frame:
                keyboard_top = int(content[1]) + int(frame[1])
                xml = tree()
                actions = [find(label, 'text', xml=xml, required=False) for label in ('取消', '完成')]
                bounds = [list(map(int,re.findall(r'\d+', n.get('bounds')))) for n in actions if n is not None]
                if len(bounds) == 2 and all(b <= keyboard_top and b-t >= 44*density for l,t,r,b in bounds):
                    return {'keyboard_top':keyboard_top, 'cancel_bounds':bounds[0], 'complete_bounds':bounds[1]}
        time.sleep(.1)
    raise AssertionError('Dialog actions are clipped or overlap the software keyboard')

def restore(namespace,key,value):
    adb('shell','settings','delete' if value=='null' else 'put',namespace,key,*(() if value=='null' else (value,)),check=False)

try:
    assert adb('shell','getprop','sys.boot_completed').stdout.strip()==b'1'
    adb('install','-r',str(Path(a.apk).resolve()))
    adb('shell','am','force-stop',PKG)
    directory=adb('shell','run-as',PKG,'pwd').stdout.decode().strip()
    assert directory in {'/data/user/0/'+PKG,'/data/data/'+PKG}
    had_prefs=adb('shell','run-as',PKG,'ls','-d','shared_prefs',check=False).returncode==0
    if had_prefs: adb('shell','run-as',PKG,'mv','shared_prefs',backup)
    adb('shell','run-as',PKG,'mkdir','shared_prefs'); isolated=True
    original_font=adb('shell','settings','get','system','font_scale').stdout.decode().strip()
    original_ime=adb('shell','settings','get','secure','selected_input_method_subtype').stdout.decode().strip()
    original_hardware_ime=adb('shell','settings','get','secure','show_ime_with_hard_keyboard').stdout.decode().strip()
    adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
    night=adb('shell','cmd','uimode','night').stdout.decode(); match=re.search(r'Night mode: (\S+)',night)
    original_night=match.group(1) if match else None
    sizes=re.findall(r'Override size: (\d+x\d+)',adb('shell','wm','size').stdout.decode()); original_size=sizes[-1] if sizes else 'reset'
    ime=adb('shell','dumpsys','input_method').stdout.decode(errors='replace')
    english=re.findall(r'mSubtypeLocale=en_US[^\n]*mSubtypeHashCode=(-?\d+)',ime)
    if english: adb('shell','settings','put','secure','selected_input_method_subtype',english[-1])
    adb('shell','settings','put','system','font_scale','1.0'); adb('shell','cmd','uimode','night','no')
    data={'schemaVersion':1,'enabled':True,'mode':'BLACKLIST','resolveProfileGender':False,'ignoreKeywordCase':True,
          'blacklist':[{'id':'mixed','keywords':['promo'],'genders':['M']},{'id':'genderonly','genders':['F']}], 'whitelist':[]}
    xml=E.Element('map'); E.SubElement(xml,'long',name='revision',value='1');E.SubElement(xml,'string',name='json').text=json.dumps(data)
    adb('shell','run-as',PKG,'tee','shared_prefs/rules.'+XHS+'.xml',data=E.tostring(xml))
    restart(); tap('app_selector'); shot('01-app-picker.png'); tap('小红书','text')
    assert not gender_checked(); find('xhs_gender_filter_note'); reveal('规则已暂停 · 性别过滤未开启','text')
    shot('02-xhs-off.png')
    tap('add_rule'); assert find('rule_gender_section',required=False) is None
    shot('03-add-off.png'); tap('取消','text')
    tap('mixed','text'); assert find('rule_gender_section',required=False) is None
    tap('rule_keywords'); adb('shell','input','keyevent','KEYCODE_MOVE_END'); adb('shell','input','text','edited'); adb('shell','input','keyevent','KEYCODE_BACK')
    tap('完成','text'); save()
    state=wait_saved(lambda s:s['blacklist'][0]['keywords']==['promoedited'])
    assert state['blacklist'][0]['genders']==['M'] and not state['resolveProfileGender']
    results['off_hides_gender_and_edit_preserves_saved_conditions']=True

    tap('xhs_gender_filter_enabled'); assert gender_checked() and not read()['resolveProfileGender']
    tap('add_rule'); reveal('rule_gender_female'); shot('04-add-on.png')
    tap('rule_gender_female'); tap('完成','text')
    reveal('性别 · 女','text'); save()
    wait_saved(lambda s:s['resolveProfileGender'] and len(s['blacklist'])==3)
    restart(); assert gender_checked(); reveal('性别 · 女','text')
    results['on_shows_gender_manual_save_and_process_restore']=True

    choose('抖音'); assert find('xhs_gender_settings',required=False) is None
    tap('add_rule'); reveal('rule_gender_female');tap('取消','text')
    choose('小红书');assert gender_checked()
    tap('xhs_gender_filter_enabled'); assert not gender_checked()
    choose('哔哩哔哩');choose('小红书');assert not gender_checked()
    assert read()['resolveProfileGender'];save();wait_saved(lambda s:not s['resolveProfileGender'])
    results['gate_is_xhs_only_and_drafts_are_isolated']=True

    tap('mode_whitelist');tap('xhs_gender_filter_enabled');tap('add_rule');reveal('rule_gender_unknown');tap('rule_gender_unknown');tap('完成','text')
    tap('xhs_gender_filter_enabled');find('无启用条件时显示全部评论','text')
    results['gender_only_whitelist_paused_hint_matches_runtime']=True

    tap('xhs_gender_filter_enabled');tap('add_rule');reveal('rule_gender_female')
    adb('shell','settings','put','system','font_scale','1.3');reveal('rule_gender_female');shot('05-editor-large-font.png')
    tap('取消','text');restore('system','font_scale','1.0')
    tap('add_rule');tap('rule_name')
    deadline=time.monotonic()+5
    keyboard_visible=False
    while time.monotonic()<deadline:
        ime_state=adb('shell','dumpsys','input_method').stdout.decode(errors='replace')
        if re.search(r'mInputShown=true|mIsInputViewShown=true',ime_state): keyboard_visible=True;break
        time.sleep(.1)
    assert keyboard_visible,'Software keyboard did not become visible'
    results['keyboard_visible_action_bounds'] = assert_keyboard_actions_visible();shot('06-editor-keyboard.png')
    results['software_keyboard_visible_and_actions_reachable']=True
    adb('shell','input','keyevent','KEYCODE_BACK');tap('取消','text')
    adb('shell','cmd','uimode','night','yes');tap('add_rule');reveal('rule_gender_female');shot('07-editor-dark.png');tap('取消','text')
    adb('shell','wm','size','960x1920');tap('add_rule');reveal('rule_gender_female');shot('08-editor-narrow.png');tap('取消','text')
    adb('shell','wm','size',original_size)
    tap('更多设置','content-desc');tap('使用说明','text');shot('09-help-dark.png');tap('知道了','text')
    tap('更多设置','content-desc');tap('高级配置','text');shot('10-json-dark.png');tap('取消','text')
    results['dialogs_keyboard_dark_largefont_narrow_reachable']=True
    results['device_api']=int(adb('shell','getprop','ro.build.version.sdk').stdout)
    results['host_profile_requests_tested']=False
except Exception:
    shot('failure.png');raise
finally:
    adb('shell','am','force-stop',PKG,check=False)
    if isolated:
        adb('shell','run-as',PKG,'rm','-r','shared_prefs',check=False)
        if had_prefs:adb('shell','run-as',PKG,'mv',backup,'shared_prefs')
        results['original_preferences_restored']=True
    if original_font is not None:restore('system','font_scale',original_font)
    if original_ime is not None:restore('secure','selected_input_method_subtype',original_ime)
    if original_hardware_ime is not None:restore('secure','show_ime_with_hard_keyboard',original_hardware_ime)
    if original_night is not None:adb('shell','cmd','uimode','night',original_night,check=False)
    if original_size is not None:adb('shell','wm','size',original_size,check=False)
    (out/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('XHS gender/dialog smoke passed; original preferences restored',flush=True)
