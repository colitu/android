<#
.SYNOPSIS
    Stages the colitu.com Android release files and writes the signed latest.json.

.DESCRIPTION
    Run after `COLITU_REQUIRE_RELEASE_SIGNING=true ./gradlew :app:assembleDirectRelease`.
    Reads android/app/build/outputs/apk/direct/release/output-metadata.json,
    copies the universal, arm64-v8a and armeabi-v7a APKs to -OutDir under the
    names the website uses (Colitu-<version>.apk, Colitu-<version>-<abi>.apk),
    writes latest.json and signs it with sign-update-manifest.ps1, which
    computes every sha256, size and the signing certificate from the copied
    APKs (v1 + v2 signatures).

.EXAMPLE
    pwsh scripts/new-update-manifest.ps1 -OutDir dist/android-2.6.0
#>
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$BuildDir = (Join-Path $PSScriptRoot "..\android\app\build\outputs\apk\direct\release"),
    [string]$BaseUrl = "https://colitu.com/downloads/android",
    [string]$MinAndroid = "7.0",
    [switch]$Force,
    [string]$ReleaseNotes = ""
)
$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) { throw "Run with PowerShell 7 (pwsh)." }

$metadata = Get-Content -LiteralPath (Join-Path $BuildDir "output-metadata.json") -Raw | ConvertFrom-Json
if ($metadata.variantName -ne "directRelease") { throw "Expected the directRelease build, found $($metadata.variantName)." }
$codes = @($metadata.elements | ForEach-Object { $_.versionCode } | Sort-Object -Unique)
if ($codes.Count -ne 1) { throw "APKs have different versionCodes: $($codes -join ', ')" }
$version = @($metadata.elements)[0].versionName

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
function Stage([string]$abi, [string]$target) {
    $element = $metadata.elements | Where-Object {
        if ($abi -eq "universal") { $_.type -eq "UNIVERSAL" } else { @($_.filters | Where-Object { $_.value -eq $abi }).Count -gt 0 }
    } | Select-Object -First 1
    if (-not $element) { throw "No $abi APK in $BuildDir." }
    Copy-Item -LiteralPath (Join-Path $BuildDir $element.outputFile) -Destination (Join-Path $OutDir $target) -Force
    "$BaseUrl/$target"
}

$manifest = [ordered]@{
    latestVersionCode = [int64]$codes[0]
    versionName       = $version
    downloadUrl       = (Stage "universal" "Colitu-$version.apk")
    sha256            = ""
    sizeBytes         = 0
    minAndroid        = $MinAndroid
    signingCertSha256 = ""
    forceUpdate       = [bool]$Force
    releaseNotes      = $ReleaseNotes
    variants          = [ordered]@{
        "arm64-v8a"   = [ordered]@{ url = (Stage "arm64-v8a" "Colitu-$version-arm64-v8a.apk"); sha256 = ""; sizeBytes = 0 }
        "armeabi-v7a" = [ordered]@{ url = (Stage "armeabi-v7a" "Colitu-$version-armeabi-v7a.apk"); sha256 = ""; sizeBytes = 0 }
    }
}
$path = Join-Path $OutDir "latest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
& (Join-Path $PSScriptRoot "sign-update-manifest.ps1") -Manifest $path -ApkDir $OutDir
