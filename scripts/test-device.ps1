param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$Serial = '',
    [switch]$Performance,
    [switch]$ExternalEvidence
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
if (-not $SdkPath) {
    $localSettings = Join-Path $projectPath 'local.properties'
    if (Test-Path -LiteralPath $localSettings) {
        $sdkLine = Get-Content -LiteralPath $localSettings | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
        if ($sdkLine) { $SdkPath = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':') }
    }
}
if (-not $SdkPath) { throw '请通过 -SdkPath 指定 Android SDK，或创建 local.properties。' }
$adbPath = Join-Path $SdkPath 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw "找不到 adb：$adbPath" }
$deviceArguments = @()
if ($Serial) { $deviceArguments = @('-s', $Serial) }
Push-Location $projectPath
try {
    & '.\gradlew.bat' '-PtestBuildType=benchmark' ':app:assembleBenchmark' ':app:assembleBenchmarkAndroidTest' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw '测试构建失败，请检查 Gradle 输出。' }
    & $adbPath @deviceArguments install --no-streaming -r (Join-Path $projectPath 'app/build/outputs/apk/benchmark/app-benchmark.apk')
    if ($LASTEXITCODE -ne 0) { throw '手机拒绝或无法安装。请在手机上确认 USB 安装权限；脚本不会绕过系统限制。' }
    & $adbPath @deviceArguments install --no-streaming -r (Join-Path $projectPath 'app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw '测试 APK 安装失败，请检查手机提示。' }
    $testArguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'cn.local.imagepdf.platform.PipelineInstrumentedTest,cn.local.imagepdf.AppFlowInstrumentedTest,cn.local.imagepdf.BatchImportInstrumentedTest,cn.local.imagepdf.platform.FolderRepositoryInstrumentedTest')
    if ($Performance) {
        $testArguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'cn.local.imagepdf.platform.BatchPerformanceTest', '-e', 'performance', 'true', '-e', 'sizes', '100,300,500', '-e', 'repeats500', '3')
    }
    $testArguments += 'cn.local.imagepdf.benchmark.test/androidx.test.runner.AndroidJUnitRunner'
    $output = & $adbPath @deviceArguments @testArguments 2>&1
    $nativeResult = $LASTEXITCODE
    $output | Write-Output
    $output | Set-Content -LiteralPath (Join-Path $projectPath 'device-test.log') -Encoding utf8
    if ($nativeResult -ne 0 -or ($output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') { throw '仪器测试未通过，请查看 device-test.log。' }
    if (($output -join "`n") -match 'INSTRUMENTATION_STATUS_CODE: -4') { Write-Warning '部分测试因已有草稿或其他前置条件而跳过，不能将跳过项当作已验收。' }
    if ($Performance) {
        $collectArguments = @((Join-Path $PSScriptRoot 'collect_device.py'), '--adb', $adbPath, '--output', (Join-Path $projectPath 'build/device-evidence'))
        if ($Serial) { $collectArguments += @('--serial', $Serial) }
        if ($ExternalEvidence) { $collectArguments += '--external' }
        & python @collectArguments
        if ($LASTEXITCODE -ne 0) { throw '测试结束，但证据导出失败；请保留手机上的应用数据重试。' }
    }
} finally { Pop-Location }
