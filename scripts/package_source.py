"""Create a source-only handoff, excluding machine paths, builds, keystores and logs."""
import hashlib
import json
import pathlib
import shutil
import zipfile


def main():
    root = pathlib.Path(__file__).resolve().parents[1]
    destination = root / 'dist'
    destination.mkdir(exist_ok=True)
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    if not apk.is_file():
        raise FileNotFoundError('Build :app:assembleDebug first')
    output_apk = destination / 'l君のpdf转换-1.0.3-test.apk'
    shutil.copyfile(apk, output_apk)
    source = destination / 'l君のpdf转换-1.0.3-source.zip'
    names = ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew',
             'gradlew.bat', '.gitignore', 'README.md', 'THIRD_PARTY_NOTICES.md',
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
                 'build/release-1.0.3-verification.json']:
        candidate = root / path
        if candidate.is_file():
            shutil.copyfile(candidate, evidence / candidate.name)
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
        'Current 1.0.3 install/start/hash verification: release-1.0.3-verification.json.\n'
        'Current 1.0.3 UI regression: device-folder-1.0.3-test.log '
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
    checksums = {file.name: {'bytes': file.stat().st_size,
                           'sha256': hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()}
                 for file in [output_apk, source]}
    (destination / 'SHA256.json').write_text(json.dumps(checksums, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with zipfile.ZipFile(source) as package:
        if package.testzip() is not None:
            raise ValueError('Source archive failed integrity validation')
    print(json.dumps(checksums, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
