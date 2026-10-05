param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('measure', 'profile')][string]$Mode = 'measure',
    [int]$Iterations = 5,
    [string]$BookTitle,
    [ValidateSet('none', 'profile')][string]$Compilation = 'none',
    [string]$Methods = 'txtTwentyScreens,epubTwentyScreens,docxTwentyScreens,epubTwentySections,epubPictures,txtColdReader,epubColdReader'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskVariant = if ($Mode -eq 'profile') { 'nonMinifiedReaderPerf' } else { 'benchmarkReaderPerf' }
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskOutput = Join-Path $taskRoot "artifacts/reader-performance/$Mode-$Compilation-$taskStamp"
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
if ($Mode -eq 'profile' -and -not $BookTitle) { throw '业务 Profile 必须提供 BookTitle，并先在开发包登录测试账号。' }
if ($Iterations -lt 1) { throw 'Iterations must be positive' }
function Invoke-ReaderAdb([string[]]$AdbArguments) {
    $taskResult = & $Adb -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($taskResult -join "`n") }
    return $taskResult
}
# Ordinary updates preserve app data. Do not use Gradle connected tests on a personal device:
# their install/cleanup runner can uninstall the target after a permission-grant failure.
Invoke-ReaderAdb @('install', '-r', "$taskRoot/app/build/outputs/apk/$taskVariant/app-$taskVariant.apk") | Out-Host
Invoke-ReaderAdb @('install', '-r', "$taskRoot/readerbenchmark/build/outputs/apk/$taskVariant/readerbenchmark-$taskVariant.apk") | Out-Host
$taskClass = if ($Mode -eq 'profile') { 'com.custodysim.readerbenchmark.ReaderBaselineProfiles' } else { 'com.custodysim.readerbenchmark.ReaderBenchmarks' }
$taskMethods = if ($Mode -eq 'profile') { @('startup', 'libraryReading', 'nativeText') } else { $Methods.Split(',') }
foreach ($taskMethod in $taskMethods) {
    $taskArgs = @('shell', 'am', 'instrument', '-w', '-e', 'class', "$taskClass#$taskMethod",
        '-e', 'iterations', "$Iterations", '-e', 'compilation', $Compilation)
    if ($BookTitle) { $taskArgs += @('-e', 'bookTitle', $BookTitle) }
    if ($Mode -eq 'profile') { $taskArgs += @('-e', 'androidx.benchmark.enabledRules', 'BaselineProfile') }
    $taskArgs += 'com.custodysim.readerbenchmark/androidx.test.runner.AndroidJUnitRunner'
    $taskResult = Invoke-ReaderAdb $taskArgs
    $taskResult | Set-Content -Encoding utf8 (Join-Path $taskOutput "$taskMethod.txt")
    $taskResult | Out-Host
    if (($taskResult -join "`n") -notmatch 'OK \(1 test\)') { throw "测试未通过：$taskMethod；保留输出，不继续、不压制设备校验。" }
    # The instrumentation process writes a fresh report on each invocation; pull before the next.
    Invoke-ReaderAdb @('pull', '/sdcard/Android/media/com.custodysim.readerbenchmark', (Join-Path $taskOutput $taskMethod)) | Out-Host
}
Write-Output "Results: $taskOutput"
