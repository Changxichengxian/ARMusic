param(
    [string]$CertificateThumbprint = $env:ARMUSIC_WINDOWS_CERT_THUMBPRINT,
    [string]$DigestAlgorithm = "sha256",
    [string]$TimestampUrl = $(if ($env:ARMUSIC_WINDOWS_TIMESTAMP_URL) { $env:ARMUSIC_WINDOWS_TIMESTAMP_URL } else { "http://timestamp.digicert.com" })
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($CertificateThumbprint)) {
    throw "Missing certificate thumbprint. Pass -CertificateThumbprint or set ARMUSIC_WINDOWS_CERT_THUMBPRINT."
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$override = @{
    bundle = @{
        windows = @{
            certificateThumbprint = $CertificateThumbprint
            digestAlgorithm = $DigestAlgorithm
            timestampUrl = $TimestampUrl
        }
    }
} | ConvertTo-Json -Depth 8 -Compress

$oldConfig = $env:TAURI_CONFIG
try {
    $env:TAURI_CONFIG = $override
    Push-Location $repoRoot
    try {
        npm run desktop:package
    } finally {
        Pop-Location
    }
} finally {
    $env:TAURI_CONFIG = $oldConfig
}
