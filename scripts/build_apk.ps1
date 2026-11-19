$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

$jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1)
if (-not $jdk) {
    throw "Eclipse Adoptium JDK 17 not found."
}

$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path $sdkRoot)) {
    throw "Android SDK not found at $sdkRoot"
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdkRoot
$env:PATH = "$($jdk.FullName)\bin;$sdkRoot\platform-tools;$env:PATH"

$proxyArgs = @()
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", 7897, $null, $null)
    if ($iar.AsyncWaitHandle.WaitOne(200, $false) -and $tcp.Connected) {
        $proxyArgs = @(
            "-Dhttp.proxyHost=127.0.0.1",
            "-Dhttp.proxyPort=7897",
            "-Dhttps.proxyHost=127.0.0.1",
            "-Dhttps.proxyPort=7897"
        )
    }
    $tcp.Close()
} catch {}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"

& "$repoRoot\gradlew.bat" -p $repoRoot :app:assembleDebug --no-daemon @proxyArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$apk = "$repoRoot\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Write-Host "`nBuild succeeded! APK: $apk"
}
