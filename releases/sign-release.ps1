<#
.SYNOPSIS
  Signs the built "direct" release APK and writes the update manifest and README download details
  from the signed file. Nothing is uploaded, committed or pushed.

.DESCRIPTION
  Build first (android\gradlew.bat assembleDirectRelease). apksigner asks for the keystore password
  itself, so the password is never stored in this script or the repository.
#>
param(
    [string]$Keystore = 'D:\dev\keystores\ledgerflow-release.jks',
    [string]$KeyAlias = '',
    # Optional apksigner password source, such as "file:D:\path\to\file". Omit to be prompted.
    [string]$KeystorePass = '',
    [string]$BuildTools = 'D:\dev\android-sdk\build-tools\36.0.0',
    # Installed apps only accept updates signed with this certificate.
    [string]$ExpectedCertSha256 = '40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0'
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$root = Split-Path $PSScriptRoot -Parent
$unsigned = Join-Path $root 'android\app\build\outputs\apk\direct\release\app-direct-release-unsigned.apk'
if (-not (Test-Path $unsigned)) { throw "Release APK not found. Build it first: $unsigned" }
if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\dev\jdk' }

$aapt2 = Join-Path $BuildTools 'aapt2.exe'
$zipalign = Join-Path $BuildTools 'zipalign.exe'
$apksigner = Join-Path $BuildTools 'apksigner.bat'

$badging = (& $aapt2 dump badging $unsigned | Select-String '^package:').Line
if ($badging -notmatch "versionCode='(\d+)' versionName='([^']+)'") { throw 'Could not read the APK version.' }
$versionCode = [int]$Matches[1]
$versionName = $Matches[2]

$notesFile = Join-Path $PSScriptRoot "release-notes-v$versionName.txt"
if (-not (Test-Path $notesFile)) { throw "Release notes not found: $notesFile" }
$notes = @([System.IO.File]::ReadAllLines($notesFile, $utf8) | Where-Object { $_.Trim() })

$apkName = "LedgerFlow-v$versionName.apk"
$signed = Join-Path $PSScriptRoot $apkName
if (Test-Path $signed) { throw "$signed already exists. Move or delete it before signing again." }
$aligned = Join-Path $PSScriptRoot "LedgerFlow-v$versionName-aligned.tmp.apk"

try {
    & $zipalign -f -P 16 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

    $signArgs = @('sign', '--ks', $Keystore, '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $signed)
    if ($KeyAlias) { $signArgs += @('--ks-key-alias', $KeyAlias) }
    if ($KeystorePass) { $signArgs += @('--ks-pass', $KeystorePass) }
    & $apksigner @signArgs $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
}
finally {
    Remove-Item $aligned -ErrorAction SilentlyContinue
}

$certs = & $apksigner verify --print-certs $signed
if ($LASTEXITCODE -ne 0) { Remove-Item $signed; throw 'The signed APK did not verify.' }
$certLine = ($certs | Select-String 'certificate SHA-256 digest:\s*([0-9a-f]{64})' | Select-Object -First 1)
$cert = if ($certLine) { $certLine.Matches[0].Groups[1].Value } else { '' }
if ($cert -ne $ExpectedCertSha256) {
    Remove-Item $signed
    throw "Signed with the wrong key (certificate '$cert'). Installed apps would reject this update."
}

$sha = (Get-FileHash $signed -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $signed).Length
$baseUrl = 'https://github.com/itripathiharsh/LedgerFlow/releases/download'

$manifest = [ordered]@{
    versionCode             = $versionCode
    versionName             = $versionName
    minSupportedVersionCode = 1
    releasedAt              = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz', [cultureinfo]::InvariantCulture)
    apkUrl                  = "$baseUrl/v$versionName/$apkName"
    apkSizeBytes            = $size
    sha256                  = $sha
    signingCertSha256       = $cert
    releaseNotes            = $notes
    mandatory               = $false
}
$manifestPath = Join-Path $root 'update-manifest.json'
[System.IO.File]::WriteAllText($manifestPath, (($manifest | ConvertTo-Json -Depth 4) + "`n"), $utf8)

$readmePath = Join-Path $root 'README.md'
$readme = [System.IO.File]::ReadAllText($readmePath, $utf8)
$mib = ($size / 1MB).ToString('0.00', [cultureinfo]::InvariantCulture)
$bytes = $size.ToString('N0', [cultureinfo]::InvariantCulture)
$readme = $readme -replace 'LedgerFlow-v\d+\.\d+\.\d+\.apk', $apkName
$readme = $readme -replace 'releases/download/v\d+\.\d+\.\d+/', "releases/download/v$versionName/"
$readme = $readme -replace '\(`v\d+\.\d+\.\d+`\)', "(``v$versionName``)"
$readme = $readme -replace '(\| Version \| )`[^`]+` \(`versionCode: \d+`\)', "`${1}``$versionName`` (``versionCode: $versionCode``)"
$readme = $readme -replace '(\| APK File Size \| )[^|]+\|', "`${1}``$bytes`` bytes (~``$mib MiB``) |"
$readme = $readme -replace '(\| APK SHA-256 \| )`[0-9a-f]+`', "`${1}``$sha``"
$readme = $readme -replace '(# Expected: )[0-9a-f]+', "`${1}$sha"
[System.IO.File]::WriteAllText($readmePath, $readme, $utf8)

Write-Host ''
Write-Host "Signed   : $signed"
Write-Host "Size     : $bytes bytes"
Write-Host "SHA-256  : $sha"
Write-Host "Cert     : $cert"
Write-Host 'Updated  : update-manifest.json and README.md'
Write-Host ''
Write-Host "To publish: create GitHub release v$versionName with $apkName and update-manifest.json attached,"
Write-Host 'then commit and push. Push only after the APK is uploaded: installed apps also read the manifest on main.'
