param(
    [ValidatePattern("^[A-Z]$")]
    [string]$DriveLetter = "R",
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [switch]$KeepDrive
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path.TrimEnd("\")
$androidDir = Join-Path $repoRoot "android"
$driveName = "${DriveLetter}:"
$driveRoot = "${DriveLetter}:\"
$drivePrefix = "${DriveLetter}:\:"
$drivePattern = "^$([regex]::Escape($drivePrefix))\s*=>\s*(.+)$"

if (-not (Test-Path -LiteralPath $androidDir)) {
    throw "Android project directory not found: $androidDir"
}

if (-not (Test-Path -LiteralPath $SdkDir)) {
    throw "Android SDK not found: $SdkDir"
}

$java21 = Join-Path $env:USERPROFILE ".codex\jdks\temurin-21"
if (Test-Path -LiteralPath $java21) {
    $env:JAVA_HOME = $java21
    $env:Path = "$java21\bin;$env:Path"
    Write-Host "Using JDK 21: $java21"
} else {
    Write-Host "JDK 21 was not found at $java21; using current Java."
}

$resolvedSdkDir = (Resolve-Path -LiteralPath $SdkDir).Path.Replace("\", "/")
$localProperties = Join-Path $androidDir "local.properties"
Set-Content -LiteralPath $localProperties -Encoding ASCII -Value "sdk.dir=$resolvedSdkDir"

$repoForSubst = (Resolve-Path -LiteralPath $repoRoot).Path.TrimEnd("\")
$existingLine = (& subst) | Where-Object { $_ -match $drivePattern } | Select-Object -First 1
$createdSubst = $false

if ($existingLine) {
    $existingTarget = ($existingLine -replace $drivePattern, '$1').TrimEnd("\")
    $existingTarget = (Resolve-Path -LiteralPath $existingTarget).Path.TrimEnd("\")
    if ($existingTarget -ine $repoForSubst) {
        throw "$driveName is already mapped to $existingTarget. Pick another drive letter with -DriveLetter."
    }
    Write-Host "Reusing $driveRoot => $repoForSubst"
} else {
    & subst $driveName $repoForSubst
    $createdSubst = $true
    Write-Host "Mapped $driveRoot => $repoForSubst"
}

try {
    Push-Location "${DriveLetter}:\android"
    try {
        & ".\gradlew.bat" ":app:assembleDebug" "--no-daemon"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($createdSubst -and -not $KeepDrive) {
        & subst $driveName /D
        Write-Host "Removed $driveRoot mapping"
    }
}

$apkPath = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path -LiteralPath $apkPath) {
    Write-Host "APK ready: $apkPath"
}
