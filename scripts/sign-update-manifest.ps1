<#
.SYNOPSIS
    Fills in and signs the Android update manifest (latest.json) for colitu.com.

.DESCRIPTION
    Two signatures, same release key (ECDSA P-256 / SHA-256, DER, base64):

    "signature" (v1) - read by Colitu 2.3.0 .. 2.5.x. Must match
    ColituUpdateSignature.message() exactly:

        colitu-android-update-v1
        <latestVersionCode>
        <versionName>
        <forceUpdate: true|false>
        <downloadUrl>
        <sha256, lower case>
        <abi> <variant url> <variant sha256>   (one line per variant, ABIs in ordinal order)

    "signature_v2" - required by Colitu 2.6.0+. Must match
    ColituUpdateSignature.messageV2() exactly; it also covers the APK signing
    certificate, the sizes and minAndroid:

        colitu-android-update-v2
        latestVersionCode=<integer>
        versionName=<text>
        forceUpdate=<true|false>
        minAndroid=<text>
        downloadUrl=<url>
        sha256=<lower-case hex>
        sizeBytes=<integer>
        signingCertSha256=<lower-case hex, no colons>
        variant=<abi> <url> <lower-case sha256> <sizeBytes>   (ABIs in ordinal order)

    With -ApkDir every APK the manifest links (matched by file name) is read
    from that folder: its sha256 and size are written into the manifest and
    the signing certificate is read with apksigner. All APKs must carry the
    same single certificate; that becomes "signingCertSha256". Without
    -ApkDir the manifest must already name the certificate.

    Needs PowerShell 7 (pwsh). The private key stays outside the repository.

.EXAMPLE
    pwsh scripts/sign-update-manifest.ps1 -Manifest dist/latest.json -ApkDir dist
#>
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Output = $Manifest,
    [string]$ApkDir,
    [string]$SigningKeyPath = $(if ($env:COLITU_ANDROID_UPDATE_SIGNING_KEY) { $env:COLITU_ANDROID_UPDATE_SIGNING_KEY } else { Join-Path $PSScriptRoot "..\..\_gizli_anahtarlar\colitu-android--update-signing-private.pem" }),
    [string]$Apksigner = $env:COLITU_APKSIGNER
)
$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) { throw "Run with PowerShell 7 (pwsh)." }
if (-not (Test-Path -LiteralPath $SigningKeyPath)) { throw "Signing key not found: $SigningKeyPath" }

$json = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json -AsHashtable
$json.Remove("signature") | Out-Null
$json.Remove("signature_v2") | Out-Null

function Text($value) { if ($null -eq $value) { "" } else { [string]$value } }
function Int64Text($value) {
    if ($null -eq $value -or "$value" -eq "") { return "0" }
    ([int64]$value).ToString([System.Globalization.CultureInfo]::InvariantCulture)
}
function NormalizeCert([string]$value) { ($value.Trim() -replace ":", "").ToLowerInvariant() }

function Find-Apksigner {
    if ($Apksigner) { return $Apksigner }
    $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "D:\DEV\SDK\android") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $sdk) { throw "Android SDK not found; set ANDROID_HOME or -Apksigner." }
    $tool = Get-ChildItem -Path (Join-Path $sdk "build-tools") -Directory | Sort-Object { [version]($_.Name -replace "[^0-9.].*$", "") } -Descending |
        ForEach-Object { Join-Path $_.FullName $(if ($IsWindows) { "apksigner.bat" } else { "apksigner" }) } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $tool) { throw "apksigner not found under $sdk\build-tools." }
    $tool
}

# SHA-256 of every signer certificate of one APK (apksigner verifies the APK too).
function Get-ApkCerts([string]$path) {
    $tool = Find-Apksigner
    $out = & $tool verify --print-certs $path 2>&1
    if ($LASTEXITCODE -ne 0) { throw "apksigner rejected ${path}: $out" }
    @($out | Select-String -Pattern "Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)" | ForEach-Object { NormalizeCert $_.Matches[0].Groups[1].Value })
}

function Get-LocalApk([string]$url) {
    $name = [System.IO.Path]::GetFileName(([uri]$url).AbsolutePath)
    $path = Join-Path $ApkDir $name
    if (-not (Test-Path -LiteralPath $path)) { throw "APK for $url not found: $path" }
    Get-Item -LiteralPath $path
}

if ($ApkDir) {
    $certs = [System.Collections.Generic.HashSet[string]]::new()
    $fill = {
        param($entry, [string]$urlKey)
        $file = Get-LocalApk $entry[$urlKey]
        $entry["sha256"] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $entry["sizeBytes"] = [int64]$file.Length
        $apkCerts = @(Get-ApkCerts $file.FullName)
        if ($apkCerts.Count -ne 1) { throw "$($file.Name) has $($apkCerts.Count) signer certificates; exactly one is expected." }
        [void]$certs.Add($apkCerts[0])
    }
    & $fill $json "downloadUrl"
    if ($json["variants"]) {
        foreach ($abi in @($json["variants"].Keys)) { & $fill $json["variants"][$abi] "url" }
    }
    if ($certs.Count -ne 1) { throw "The APKs are signed with different certificates: $($certs -join ', ')" }
    $cert = @($certs)[0]
    if ($json["signingCertSha256"] -and (NormalizeCert $json["signingCertSha256"]) -ne $cert) {
        Write-Warning "signingCertSha256 in the manifest ($($json["signingCertSha256"])) is replaced by the APKs' certificate $cert"
    }
    $json["signingCertSha256"] = $cert
}
$certText = NormalizeCert (Text $json["signingCertSha256"])
if ($certText -notmatch "^[0-9a-f]{64}$") { throw "signingCertSha256 is missing or malformed; pass -ApkDir to read it from the APKs." }
$json["signingCertSha256"] = $certText

# ── v1 (Colitu 2.3.0 .. 2.5.x) ──
$v1 = [System.Collections.Generic.List[string]]::new()
$v1.Add("colitu-android-update-v1")
$v1.Add((Int64Text $json["latestVersionCode"]))
$v1.Add((Text $json["versionName"]))
$v1.Add($(if ($json["forceUpdate"] -eq $true) { "true" } else { "false" }))
$v1.Add((Text $json["downloadUrl"]))
$v1.Add((Text $json["sha256"]).ToLowerInvariant())
[string[]]$abis = @()
if ($json["variants"]) {
    $abis = @($json["variants"].Keys)
    [Array]::Sort($abis, [StringComparer]::Ordinal)
    foreach ($abi in $abis) {
        $variant = $json["variants"][$abi]
        $v1.Add("$abi $(Text $variant["url"]) $((Text $variant["sha256"]).ToLowerInvariant())")
    }
}

# ── v2 (Colitu 2.6.0+) ──
$v2 = [System.Collections.Generic.List[string]]::new()
$v2.Add("colitu-android-update-v2")
$v2.Add("latestVersionCode=$(Int64Text $json["latestVersionCode"])")
$v2.Add("versionName=$(Text $json["versionName"])")
$v2.Add("forceUpdate=$(if ($json["forceUpdate"] -eq $true) { "true" } else { "false" })")
$v2.Add("minAndroid=$(Text $json["minAndroid"])")
$v2.Add("downloadUrl=$(Text $json["downloadUrl"])")
$v2.Add("sha256=$((Text $json["sha256"]).ToLowerInvariant())")
$v2.Add("sizeBytes=$(Int64Text $json["sizeBytes"])")
$v2.Add("signingCertSha256=$certText")
foreach ($abi in $abis) {
    $variant = $json["variants"][$abi]
    $parts = @($abi, (Text $variant["url"]), (Text $variant["sha256"]).ToLowerInvariant(), (Int64Text $variant["sizeBytes"]))
    if ($parts | Where-Object { $_ -match "\s" }) { throw "Variant $abi has whitespace in a field." }
    $v2.Add("variant=$($parts -join ' ')")
}
if ($v2 | Where-Object { $_ -match "[`r`n]" }) { throw "A manifest field contains a line break." }

$messageV1 = [string]::Join("`n", $v1)
$messageV2 = [string]::Join("`n", $v2)

$key = [System.Security.Cryptography.ECDsa]::Create()
try {
    $key.ImportFromPem((Get-Content -LiteralPath $SigningKeyPath -Raw))
    $sign = {
        param([string]$message)
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($message)
        $signature = $key.SignData($bytes, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
        if (-not $key.VerifyData($bytes, $signature, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)) {
            throw "Self-check of the signature failed."
        }
        [Convert]::ToBase64String($signature)
    }
    $json["signature"] = & $sign $messageV1
    $json["signature_v2"] = & $sign $messageV2
} finally {
    $key.Dispose()
}
$json | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8NoBOM
Write-Host "Signed $Output (v1 + v2, signingCertSha256 $certText)"
