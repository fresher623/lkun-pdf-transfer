"""Run the app-owned folder UI fixture on the device, resilient to an ADB session ending.

Requires the benchmark APK and matching instrumentation APK to be installed first.
Only four synthetic test-provider roots receive temporary grants. No user directories
or security settings are modified. The production app is never launched by this script.
"""
import argparse
import pathlib
import re
import shlex
import subprocess
import time


def outcome(log):
    if 'FAILURES!!!' in log or 'shortMsg=' in log or 'INSTRUMENTATION_FAILED' in log:
        return 'failed'
    if re.search(r'^INSTRUMENTATION_CODE:', log, re.M):
        if 'INSTRUMENTATION_STATUS_CODE: -4' in log:
            return 'skipped'
        return 'passed' if re.search(r'OK \(\d+ tests?\)', log) else 'failed'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', default='')
    parser.add_argument('--package', default='cn.local.imagepdf.benchmark')
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('device-folder-test.log'))
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--classes', default='cn.local.imagepdf.platform.FolderRepositoryInstrumentedTest',
                        help='Comma-separated instrumentation classes, within cn.local.imagepdf')
    parser.add_argument('--foreground-every', type=int, default=0,
                        help='Optional seconds between normal SINGLE_TOP foreground intents')
    parser.add_argument('--merge-boundary', action='store_true',
                        help='Enable the opt-in synthetic 50-file/1000-page merge test')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+', args.package) or not args.package.endswith('.benchmark'):
        parser.error('Select an isolated benchmark package')
    if any(not re.fullmatch(r'cn\.local\.imagepdf(?:\.[A-Za-z_]\w*)+(?:#[A-Za-z_]\w*)?', name) for name in args.classes.split(',')):
        parser.error('Use fully qualified imagepdf instrumentation class names')
    if args.foreground_every < 0:
        parser.error('foreground-every cannot be negative')
    base = [args.adb] + (['-s', args.serial] if args.serial else [])

    def adb(*parts, timeout=15):
        result = subprocess.run(base + list(parts), check=True, capture_output=True,
                                encoding='utf-8', errors='replace', timeout=timeout)
        return result.stdout.replace('\r', '')

    for package in (args.package, args.package + '.test'):
        if not adb('shell', 'pm', 'path', '--user', '0', package).startswith('package:'):
            raise RuntimeError(f'Install {package} before starting the test')
    owner = args.package + '.test'
    bridge = 'content://' + owner + '.folder-test-grants'
    roots = ('five-hundred', 'mixed', 'too-many', 'empty')
    granted = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    remote = '/data/local/tmp/imagepdf-folder-' + str(int(time.time() * 1000)) + '.log'
    try:
        for root in roots:
            tree = 'content://' + owner + '.folder-test-docs/tree/' + root
            reply = adb('shell', 'content', 'call', '--uri', bridge, '--method', 'grant', '--arg', tree,
                        '--extra', 'target:s:' + args.package, '--extra', 'persistable:b:false')
            if 'success=true' not in reply:
                raise RuntimeError('Synthetic folder grant was not acknowledged: ' + reply)
            granted.append(tree)
        invocation = ['toybox', 'nohup', 'am', 'instrument', '-w', '-r', '-e', 'class', args.classes]
        if args.merge_boundary:
            invocation += ['-e', 'mergeBoundary', 'true']
        invocation += [owner + '/androidx.test.runner.AndroidJUnitRunner']
        adb('shell', shlex.join(invocation) + ' > ' + remote + ' 2>&1 < /dev/null &')
        time.sleep(2)
        # Same intent as ActivityScenario; SINGLE_TOP preserves an already mounted test UI.
        adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
            'android.intent.category.LAUNCHER', '-f', '0x30000000', '-n',
            args.package + '/cn.local.imagepdf.MainActivity')
        deadline = time.monotonic() + args.timeout
        next_foreground = time.monotonic() + args.foreground_every if args.foreground_every else deadline + 1
        printed = 0
        while time.monotonic() < deadline:
            try:
                if time.monotonic() >= next_foreground:
                    adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c',
                        'android.intent.category.LAUNCHER', '-f', '0x30000000', '-n',
                        args.package + '/cn.local.imagepdf.MainActivity')
                    next_foreground = time.monotonic() + args.foreground_every
                log = adb('shell', 'cat', remote)
                args.output.write_text(log, encoding='utf-8')
                stages = [line for line in log.splitlines() if 'Folder UI:' in line or 'Merge UI:' in line]
                for stage in stages[printed:]:
                    print(stage, flush=True)
                printed = len(stages)
                status = outcome(log)
                if status is not None:
                    print(f'Test result: {status}; evidence={args.output.resolve()}', flush=True)
                    return 0 if status == 'passed' else 1
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                # The device-side runner keeps its own watcher and logfile while USB reconnects.
                pass
            time.sleep(2)
        raise TimeoutError('No complete result was received; keep the device logfile: ' + remote)
    finally:
        for tree in granted:
            try:
                adb('shell', 'content', 'call', '--uri', bridge, '--method', 'revoke', '--arg', tree,
                    '--extra', 'target:s:' + args.package, timeout=5)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass


if __name__ == '__main__':
    raise SystemExit(main())
