param(
    [ValidatePattern("^[A-Z]$")]
    [string]$DriveLetter = "R",
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [switch]$KeepDrive,
    [switch]$SkipCacheRepair
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

function Remove-GeneratedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $target = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $target)) {
        return
    }

    $resolved = (Resolve-Path -LiteralPath $target).Path
    if (-not $resolved.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean outside repository: $resolved"
    }

    Write-Host "Cleaning generated cache: $resolved"
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Repair-MixedPathCaches {
    if ($SkipCacheRepair) {
        return
    }

    $sensitivePaths = @(
        "android\app\build",
        "android\lhistory\build",
        "android\lmedia\build",
        "android\lmedia\.cxx"
    )
    $nativeRoot = $repoRoot.ToLowerInvariant().Replace("\", "/")
    $substRoot = "$($DriveLetter.ToLowerInvariant()):".Replace("\", "/")
    $mixedPathFound = $false

    foreach ($relativePath in $sensitivePaths) {
        $target = Join-Path $repoRoot $relativePath
        if (-not (Test-Path -LiteralPath $target)) {
            continue
        }

        $matches = Get-ChildItem -LiteralPath $target -Recurse -File -ErrorAction SilentlyContinue |
            Select-String -SimpleMatch -Pattern $nativeRoot, $substRoot -List -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($matches) {
            $mixedPathFound = $true
            break
        }
    }

    if (-not $mixedPathFound) {
        return
    }

    Write-Host "Detected generated caches with mixed native/subst paths; repairing before build."
    foreach ($relativePath in $sensitivePaths) {
        Remove-GeneratedPath -RelativePath $relativePath
    }
}

function Test-DriveMapsCurrentRepo {
    $driveScript = Join-Path $driveRoot "scripts\android-armusic-build.ps1"
    if (-not (Test-Path -LiteralPath $driveScript)) {
        return $false
    }

    $sourceHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    $driveHash = (Get-FileHash -LiteralPath $driveScript -Algorithm SHA256).Hash
    return $sourceHash -eq $driveHash
}

if ($existingLine) {
    $existingTarget = ($existingLine -replace $drivePattern, '$1').TrimEnd("\")
    $resolvedExistingTarget = $null
    try {
        $resolvedExistingTarget = (Resolve-Path -LiteralPath $existingTarget).Path.TrimEnd("\")
    } catch {
        if (-not (Test-DriveMapsCurrentRepo)) {
            throw "$driveName is already mapped, but its target could not be resolved from subst output. Pick another drive letter with -DriveLetter."
        }
    }

    if ($resolvedExistingTarget -and $resolvedExistingTarget -ine $repoForSubst) {
        throw "$driveName is already mapped to $resolvedExistingTarget. Pick another drive letter with -DriveLetter."
    }
    Write-Host "Reusing $driveRoot => $repoForSubst"
} else {
    & subst $driveName $repoForSubst
    $createdSubst = $true
    Write-Host "Mapped $driveRoot => $repoForSubst"
}

try {
    Repair-MixedPathCaches
    Push-Location "${DriveLetter}:\android"
    try {
        & ".\gradlew.bat" ":app:assembleArmusicPreview" "--no-daemon"
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

$apkDir = Join-Path $androidDir "app\build\outputs\apk\armusicPreview"
$apkPath = Get-ChildItem -LiteralPath $apkDir -Filter "*.apk" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($apkPath) {
    Write-Host "APK ready: $($apkPath.FullName)"
    Write-Host "Package id: com.armusic"
    Write-Host "ABI: arm64-v8a"
} else {
    Write-Host "Build finished, but APK was not found in $apkDir"
}
