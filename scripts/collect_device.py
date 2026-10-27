"""Collect only this app's benchmark evidence; binary-safe on Windows PowerShell."""
import argparse
import pathlib
import re
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', default='')
    parser.add_argument('--output', required=True)
    parser.add_argument('--package', default='cn.local.imagepdf.benchmark')
    parser.add_argument('--external', action='store_true', help='Collect the app-owned external mirror when run-as is unavailable')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+', args.package):
        parser.error('Invalid Android package name')
    folder = pathlib.Path(args.output).resolve()
    folder.mkdir(parents=True, exist_ok=True)
    archive = folder / 'device-evidence.tar'
    command = [args.adb] + (['-s', args.serial] if args.serial else [])
    if args.external:
        remote = f'/sdcard/Android/data/{args.package}/files/benchmark-evidence'
        reports = folder / 'files/benchmark-reports'
        results = folder / 'files/results/external-latest'
        reports.mkdir(parents=True, exist_ok=True)
        results.mkdir(parents=True, exist_ok=True)
        subprocess.run(command + ['pull', remote + '/reports/.', str(reports)], check=True)
        subprocess.run(command + ['pull', remote + '/results/.', str(results)], check=True)
        print(f'External synthetic evidence saved to {folder}')
        return
    listing = subprocess.check_output(command + ['shell', 'run-as', args.package,
                                                'ls', 'files/results'], text=True, encoding='utf-8')
    # Export only synthetic test results, never PDFs created by the user in the app.
    results = ['files/results/' + name for name in listing.splitlines()
               if re.fullmatch(r'benchmark-\d+-\d+-\d+', name)]
    command += ['exec-out', 'run-as', args.package, 'tar', '-cf', '-',
                'files/benchmark-reports', *results]
    with archive.open('wb') as output:
        subprocess.run(command, stdout=output, check=True)
    with tarfile.open(archive) as package:
        for member in package.getmembers():
            destination = (folder / member.name).resolve()
            if not destination.is_relative_to(folder) or member.issym() or member.islnk():
                raise ValueError('Unexpected archive entry')
            if member.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = package.extractfile(member)
                if source is None:
                    raise ValueError('Unreadable archive entry')
                with source, destination.open('wb') as output:
                    import shutil
                    shutil.copyfileobj(source, output, 128 * 1024)
    print(f'Evidence saved to {folder}')


if __name__ == '__main__':
    main()
