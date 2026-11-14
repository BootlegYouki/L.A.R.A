"""Run only the isolated Group 8 package; preserve raw captures and phone settings."""
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
PACKAGE = 'org.lara.app.group8'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS = 'org.lara.app.reading.Group8UiDeviceTest'
LAYOUT_METHODS = [
    'authAndDiscoveryPresentation', 'approvalAndDownloadPresentation',
    'cachedReaderAndPlayerPresentation', 'inputCallbacksAndKeyboardRemainUsable',
]
SETTINGS = [('system', 'font_scale'), ('system', 'accelerometer_rotation'),
            ('system', 'user_rotation'), ('global', 'stay_on_while_plugged_in')]


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()


def transfer(phase):
    destination = ROOT / phase
    destination.mkdir(exist_ok=True)
    prefix = 'group8-' + phase + '-'
    for name in adb('shell', 'run-as', PACKAGE, 'ls', 'files').splitlines():
        if name.startswith(prefix) and name.endswith('.png'):
            (destination / name[len(prefix):]).write_bytes(subprocess.check_output([
                'adb', 'exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name]))
    return len(list(destination.glob('*.png')))


normal_only = '--normal-only' in sys.argv
settings_file = 'final-normal-phone-settings.json' if normal_only else 'final-phone-settings.json'
original = {key: adb('shell', 'settings', 'get', namespace, key) for namespace, key in SETTINGS}
results = {'original': original, 'runs': {}}
(ROOT / settings_file).write_text(json.dumps(results, indent=2) + '\n')
try:
    adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3')
    runs = [('final-after', original['font_scale'], '0', True)]
    if not normal_only:
        runs += [('final-font200', '2.0', '0', False), ('final-landscape200', '2.0', '1', False)]
    for phase, scale, rotation, live in runs:
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'pm', 'clear', PACKAGE)
        for key, value in [('font_scale', scale), ('accelerometer_rotation', '0'), ('user_rotation', rotation)]:
            adb('shell', 'settings', 'put', 'system', key, value)
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        time.sleep(2)
        classes = TEST_CLASS if live else ','.join(TEST_CLASS + '#' + method for method in LAYOUT_METHODS)
        command = ['adb', 'shell', 'am', 'instrument', '--user', '0', '-w',
                   '-e', 'class', classes, '-e', 'group8Phase', phase]
        if live:
            command += ['-e', 'group8Mock', 'true']
        if rotation == '1':
            command += ['-e', 'group8Landscape', 'true']
        command += [RUNNER]
        with (ROOT / (phase + '-device.txt')).open('w') as output:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=600, check=True)
        log = (ROOT / (phase + '-device.txt')).read_text()
        expected = 7 if live else 4
        results['runs'][phase] = {'passed': 'OK (%d tests)' % expected in log,
                                  'tests': expected, 'captures': transfer(phase),
                                  'font_scale': scale, 'rotation': rotation}
        print(phase, results['runs'][phase], flush=True)
finally:
    for namespace, key in SETTINGS:
        value = original[key]
        if value == 'null':
            adb('shell', 'settings', 'delete', namespace, key)
        else:
            adb('shell', 'settings', 'put', namespace, key, value)
    results['restored'] = {key: adb('shell', 'settings', 'get', namespace, key) for namespace, key in SETTINGS}
    (ROOT / settings_file).write_text(json.dumps(results, indent=2) + '\n')
if len(results['runs']) != (1 if normal_only else 3) or not all(run['passed'] for run in results['runs'].values()):
    raise SystemExit(1)
