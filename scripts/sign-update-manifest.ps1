<#
.SYNOPSIS
    Signs the Android update manifest (latest.json) for colitu.com.

.DESCRIPTION
    Colitu 2.3.0+ (the "direct" build from colitu.com) ignores a latest.json
    without a valid signature. The signed text must match
    ColituUpdateSignature.message() in the app exactly:

        colitu-android-update-v1
        <latestVersionCode>
        <versionName>
        <forceUpdate: true|false>
        <downloadUrl>
        <sha256, lower case>
        <abi> <variant url> <variant sha256>   (one line per variant, ABIs in ordinal order)

    ECDSA P-256 / SHA-256, DER signature, base64 in the "signature" field.
    Needs PowerShell 7 (pwsh). The private key stays outside the repository.

.EXAMPLE
    pwsh scripts/sign-update-manifest.ps1 -Manifest latest.json
#>
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Output = $Manifest,
    [string]$SigningKeyPath = $(if ($env:COLITU_ANDROID_UPDATE_SIGNING_KEY) { $env:COLITU_ANDROID_UPDATE_SIGNING_KEY } else { Join-Path $PSScriptRoot "..\..\_gizli_anahtarlar\colitu-android--update-signing-private.pem" })
)
$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) { throw "Run with PowerShell 7 (pwsh)." }
if (-not (Test-Path -LiteralPath $SigningKeyPath)) { throw "Signing key not found: $SigningKeyPath" }

$json = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json -AsHashtable
$json.Remove("signature") | Out-Null

function Text($value) { if ($null -eq $value) { "" } else { [string]$value } }

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("colitu-android-update-v1")
$lines.Add(([int64]$json["latestVersionCode"]).ToString([System.Globalization.CultureInfo]::InvariantCulture))
$lines.Add((Text $json["versionName"]))
$lines.Add($(if ($json["forceUpdate"] -eq $true) { "true" } else { "false" }))
$lines.Add((Text $json["downloadUrl"]))
$lines.Add((Text $json["sha256"]).ToLowerInvariant())
if ($json["variants"]) {
    [string[]]$abis = @($json["variants"].Keys)
    [Array]::Sort($abis, [StringComparer]::Ordinal)
    foreach ($abi in $abis) {
        $variant = $json["variants"][$abi]
        $lines.Add("$abi $(Text $variant["url"]) $((Text $variant["sha256"]).ToLowerInvariant())")
    }
}
$message = [string]::Join("`n", $lines)

$key = [System.Security.Cryptography.ECDsa]::Create()
try {
    $key.ImportFromPem((Get-Content -LiteralPath $SigningKeyPath -Raw))
    $signature = $key.SignData(
        [System.Text.Encoding]::UTF8.GetBytes($message),
        [System.Security.Cryptography.HashAlgorithmName]::SHA256,
        [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
} finally {
    $key.Dispose()
}
$json["signature"] = [Convert]::ToBase64String($signature)
$json | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8NoBOM
Write-Host "Signed $Output"
