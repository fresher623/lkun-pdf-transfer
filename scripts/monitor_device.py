"""One-second external memory snapshots for this application's device test only."""
import argparse
import json
import pathlib
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', default='')
    parser.add_argument('--output', required=True)
    parser.add_argument('--stop-file', required=True)
    parser.add_argument('--seconds', type=int, default=1800)
    parser.add_argument('--package', default='cn.local.imagepdf.benchmark')
    args = parser.parse_args()
    base = [args.adb] + (['-s', args.serial] if args.serial else [])
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    stop = pathlib.Path(args.stop_file)
    started = time.monotonic()
    samples = 0
    with output.open('w', encoding='utf-8') as stream:
        while time.monotonic() - started < args.seconds and not stop.exists():
            tick = time.monotonic()
            result = subprocess.run(base + ['shell', 'dumpsys', 'meminfo', args.package],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=15)
            stream.write(json.dumps({'elapsed_seconds': tick - started, 'unix_time': time.time(),
                                     'return_code': result.returncode, 'meminfo': result.stdout,
                                     'error': result.stderr}, ensure_ascii=False) + '\n')
            stream.flush()
            samples += 1
            time.sleep(max(0, 1 - (time.monotonic() - tick)))
    print(f'Saved {samples} external snapshots to {output}')


if __name__ == '__main__':
    main()
