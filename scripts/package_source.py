"""Create a source-only handoff, excluding machine paths, builds, keystores and logs."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=['release', 'debug'], default='release')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    destination = root / 'dist'
    destination.mkdir(exist_ok=True)
    config = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
    version_code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
    apk_folder = root / f'app/build/outputs/apk/{args.variant}'
    apk = apk_folder / f'app-{args.variant}.apk'
    if not apk.is_file():
        raise FileNotFoundError(f'Build :app:assemble{args.variant.title()} first')
    metadata = json.loads((apk_folder / 'output-metadata.json').read_text(encoding='utf-8'))
    built = metadata['elements'][0]
    if built['versionName'] != version or built['versionCode'] != version_code:
        raise ValueError('APK version differs from source; rebuild before packaging')
    inputs = [root / name for name in ['app/build.gradle.kts', 'core/build.gradle.kts',
                                      'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']]
    for folder in ['app/src/main', f'app/src/{args.variant}', 'core/src/main']:
        if (root / folder).is_dir():
            inputs += [p for p in (root / folder).rglob('*') if p.is_file()]
    if any(p.stat().st_mtime > apk.stat().st_mtime + 1 for p in inputs):
        raise ValueError('Application sources changed after APK build; rebuild before packaging')
    suffix = 'release' if args.variant == 'release' else 'test'
    output_apk = destination / f'l君のpdf转换-{version}-{suffix}.apk'
    shutil.copyfile(apk, output_apk)
    source = destination / f'l君のpdf转换-{version}-source.zip'
    names = ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew',
             'gradlew.bat', '.gitignore', '.gitattributes', 'README.md', 'THIRD_PARTY_NOTICES.md',
             '安卓图片合并PDF_App需求文档.md', 'app/build.gradle.kts', 'app/proguard-rules.pro',
             'core/build.gradle.kts', 'PLAN.md']
    files = [root / name for name in names]
    for folder in ['app/src', 'core/src', 'gradle/wrapper', 'scripts', 'docs']:
        files += [file for file in (root / folder).rglob('*')
                  if file.is_file() and '__pycache__' not in file.parts and file.suffix != '.pyc']
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as package:
        for file in sorted(set(files)):
            package.write(file, file.relative_to(root).as_posix())
    evidence = destination / 'verification'
    evidence.mkdir(exist_ok=True)
    for path in ['build/engine-probe/host-metrics.json', 'build/engine-probe/independent-validation.json',
                 'app/build/reports/lint-results-debug.txt', 'core/build/test-results/test/TEST-cn.local.imagepdf.core.CoreTest.xml',
                 'device-quick-test.log', 'device-functional-test.log', 'device-functional-isolated-test.log', 'device-build.log',
                 'device-batch-import-test.log', 'device-folder-test-first-attempt.log', 'folder-final-build.log',
                 'folder-verification-build.log', 'test-evidence-build.log', 'device-folder-broadcast-attempt.log',
                 'device-performance-test.log', 'device-exit-info.log',
                 'device-folder-bridge-test.log', 'device-folder-visible-test.log',
                 'device-folder-detached-test.log', 'device-folder-bfs-test.log',
                 'test-selector-build.log', 'device-performance-final-test.log',
                 'rename-1.0.3-build.log', 'device-install-1.0.3.log',
                 'device-launch-1.0.3.log', 'device-install-1.0.3-benchmark.log',
                 'device-install-1.0.3-test-component.log', 'device-folder-1.0.3-test.log',
                 'build/release-1.0.3-verification.json', 'merge-validation-build.log',
                 'merge-final-build.log', f'device-merge-{version}-test.log',
                 f'device-merge-{version}-boundary.log']:
        candidate = root / path
        if candidate.is_file():
            shutil.copyfile(candidate, evidence / candidate.name)
    for candidate in sorted((root / 'build').glob('merge-*-device*.log')):
        shutil.copyfile(candidate, evidence / candidate.name)
    for candidate in sorted(root.glob('merge-*-build.log')):
        shutil.copyfile(candidate, evidence / candidate.name)
    merge_evidence = root / 'build/merge-device-evidence'
    if merge_evidence.is_dir():
        for pattern in ('summary.txt', 'memory.csv', 'independent-validation.json'):
            for candidate in merge_evidence.rglob(pattern):
                shutil.copyfile(candidate, evidence / ('merge-' + candidate.name))
    install_check = root / 'build/merge-installed-check/verification.json'
    if install_check.is_file():
        shutil.copyfile(install_check, evidence / 'merge-install-verification.json')
    for name in ('merge-memory-budget-failed.csv', 'merge-defender-scan.json'):
        candidate = root / 'build' / name
        if candidate.is_file():
            shutil.copyfile(candidate, evidence / name)
    final_evidence = root / 'build/device-evidence-final'
    for source_name, output_name in [
            ('device-summary.json', 'device-final-summary.json'),
            ('independent-validation.json', 'device-final-independent-validation.json'),
            ('validated-pdf.sha256', 'device-final-validated-pdf.sha256'),
            ('external-memory.jsonl', 'device-final-external-memory.jsonl')]:
        candidate = final_evidence / source_name
        if candidate.is_file():
            shutil.copyfile(candidate, evidence / output_name)
    reports = final_evidence / 'files/benchmark-reports'
    if reports.is_dir():
        for candidate in reports.glob('*.csv'):
            shutil.copyfile(candidate, evidence / candidate.name)
    (evidence / 'README.txt').write_text(
        f'Packaged source/APK version: {version} ({version_code}), variant {args.variant}.\n'
        'A successful package build does not mean device acceptance passed. '
        'See docs/TEST_REPORT.md for current executed and pending checks.\n'
        'Functional passes do not certify security. When present, docs/SECURITY_REVIEW.md '
        'records outstanding alerts; local packaging does not resolve or override them.\n'
        'merge-* files record current 1.1.0 synthetic merge/device checks when present.\n'
        'A device test failure log remains a failure even when a later fixed run passes.\n'
        'Historical 1.0.3 install/start/hash verification: release-1.0.3-verification.json.\n'
        'Historical 1.0.3 UI regression: device-folder-1.0.3-test.log '
        '(4 passed, 0 failed, 0 skipped, 6.231 seconds).\n'
        'Historical 1.0.2 UI run: device-folder-bfs-test.log (4 passed, no skipped tests).\n'
        'Historical 1.0.2 500-image x3 run: device-performance-final-test.log (1 passed test).\n'
        'Run times and sampled memory: device-final-summary.json and the CSV files.\n'
        'Independent 500-page validation and final PDF hash: '
        'device-final-independent-validation.json.\n'
        'The final PDF is synthetic test data; its 153891616-byte file stays in '
        'build/device-evidence-final/files/results/external-latest/latest.pdf, '
        'outside this small evidence handoff.\n'
        'Per-second raw memory: device-final-external-memory.jsonl. Rendered previews stay in '
        'build/device-evidence-final/previews/. JSON paths identify their original workspace locations.\n'
        'Earlier failures and partial runs are retained as historical evidence. '
        'See docs/TEST_REPORT.md for scope, foreground assistance, system freezes, '
        'the first run exceeding five minutes, and unverified acceptance items.\n',
        encoding='utf-8')
    manifest_file = destination / 'SHA256.json'
    checksums = json.loads(manifest_file.read_text(encoding='utf-8')) if manifest_file.is_file() else {}
    checksums.update({file.name: {'bytes': file.stat().st_size,
                           'sha256': hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()}
                 for file in [output_apk, source]})
    manifest_file.write_text(json.dumps(checksums, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with zipfile.ZipFile(source) as package:
        if package.testzip() is not None:
            raise ValueError('Source archive failed integrity validation')
    print(json.dumps(checksums, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
