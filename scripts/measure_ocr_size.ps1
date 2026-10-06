<#
.SYNOPSIS
    Measures Android APK/AAB size and calculates size delta against baseline for OCR features.
.DESCRIPTION
    Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md:
    - Measures exact byte count of release APK/AAB.
    - Inspects internal breakdown (dex, native libs, assets, resources).
    - Evaluates delta against 8 MB warning and 10 MB hard ceiling.
    - Exits with non-zero error if artifact is missing or threshold exceeded.
.PARAMETER BaselineApk
    Path to baseline APK file.
.PARAMETER CandidateApk
    Path to candidate APK file.
.PARAMETER BaselineAab
    Path to baseline AAB file.
.PARAMETER CandidateAab
    Path to candidate AAB file.
.PARAMETER RecordBaseline
    Captures current build outputs as the baseline stored in app/build/baseline/.
.PARAMETER MaxIncreaseBytes
    Hard limit in bytes (default: 10,000,000 bytes = 10 MB decimal).
.PARAMETER WarnIncreaseBytes
    Warning threshold in bytes (default: 8,000,000 bytes = 8 MB decimal).
.PARAMETER Json
    Outputs measurement results in JSON format.
#>

[CmdletBinding()]
param (
    [string]$BaselineApk,
    [string]$CandidateApk,
    [string]$BaselineAab,
    [string]$CandidateAab,
    [switch]$RecordBaseline,
    [long]$MaxIncreaseBytes = 10000000,
    [long]$WarnIncreaseBytes = 8000000,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$DefaultBaselineDir = Join-Path $ProjectRoot "app\build\baseline"
$DefaultOutputApk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
$DefaultOutputAab = Join-Path $ProjectRoot "app\build\outputs\bundle\release\app-release.aab"

function Get-ArtifactDetails([string]$FilePath) {
    if (-not (Test-Path -LiteralPath $FilePath)) {
        throw "Artifact file not found: '$FilePath'"
    }

    $fileItem = Get-Item -LiteralPath $FilePath
    $sha256 = (Get-FileHash -LiteralPath $FilePath -Algorithm SHA256).Hash
    $length = $fileItem.Length

    $breakdown = @{}
    $zip = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($fileItem.FullName)
        $categories = @{
            'dex' = 0L
            'native_arm64-v8a' = 0L
            'native_armeabi-v7a' = 0L
            'assets' = 0L
            'res' = 0L
            'resources.arsc' = 0L
            'other' = 0L
        }

        foreach ($entry in $zip.Entries) {
            $cat = 'other'
            if ($entry.FullName -like "lib/arm64-v8a/*") {
                $cat = 'native_arm64-v8a'
            } elseif ($entry.FullName -like "lib/armeabi-v7a/*") {
                $cat = 'native_armeabi-v7a'
            } elseif ($entry.FullName -like "*.dex") {
                $cat = 'dex'
            } elseif ($entry.FullName -like "assets/*") {
                $cat = 'assets'
            } elseif ($entry.FullName -like "res/*") {
                $cat = 'res'
            } elseif ($entry.FullName -eq "resources.arsc") {
                $cat = 'resources.arsc'
            }
            $categories[$cat] += $entry.CompressedLength
        }
        $breakdown = $categories
    } catch {
        # Not a zip or cannot inspect internal structure
        $breakdown = $null
    } finally {
        if ($null -ne $zip) {
            $zip.Dispose()
        }
    }

    return [PSCustomObject]@{
        Path = $fileItem.FullName
        FileName = $fileItem.Name
        SizeBytes = $length
        Sha256 = $sha256
        Breakdown = $breakdown
    }
}

function Find-Bundletool {
    if ($env:BUNDLETOOL_PATH -and (Test-Path $env:BUNDLETOOL_PATH)) {
        return $env:BUNDLETOOL_PATH
    }
    $cmd = Get-Command "bundletool" -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    $sdkDir = $null
    $localProps = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match "^sdk\.dir\s*=" } | Select-Object -First 1
        if ($line) {
            $sdkDir = ($line -split "=", 2)[1].Trim().Replace("\:", ":").Replace("\\", "\")
        }
    }
    if (-not $sdkDir -and $env:ANDROID_HOME) { $sdkDir = $env:ANDROID_HOME }
    if (-not $sdkDir -and $env:ANDROID_SDK_ROOT) { $sdkDir = $env:ANDROID_SDK_ROOT }

    if ($sdkDir -and (Test-Path $sdkDir)) {
        $found = Get-ChildItem -Path $sdkDir -Filter "bundletool*.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

if ($RecordBaseline) {
    Write-Host "Recording current build outputs as baseline in: $DefaultBaselineDir"
    if (-not (Test-Path -LiteralPath $DefaultOutputApk)) {
        throw "Cannot record baseline: current release APK does not exist at '$DefaultOutputApk'. Run gradlew :app:assembleRelease first."
    }

    if (-not (Test-Path -LiteralPath $DefaultBaselineDir)) {
        New-Item -ItemType Directory -Path $DefaultBaselineDir -Force | Out-Null
    }

    $savedApkPath = Join-Path $DefaultBaselineDir "baseline-release-unsigned.apk"
    Copy-Item -LiteralPath $DefaultOutputApk -Destination $savedApkPath -Force
    $apkInfo = Get-ArtifactDetails $savedApkPath

    $aabInfo = $null
    if (Test-Path -LiteralPath $DefaultOutputAab) {
        $savedAabPath = Join-Path $DefaultBaselineDir "baseline-release.aab"
        Copy-Item -LiteralPath $DefaultOutputAab -Destination $savedAabPath -Force
        $aabInfo = Get-ArtifactDetails $savedAabPath
    }

    $gitHead = (git rev-parse HEAD 2>$null)
    if (-not $gitHead) { $gitHead = "UNKNOWN" }

    $manifest = [PSCustomObject]@{
        RecordedAt = (Get-Date).ToString("o")
        GitHead = $gitHead.Trim()
        Apk = $apkInfo
        Aab = $aabInfo
    }

    $manifestPath = Join-Path $DefaultBaselineDir "baseline_manifest.json"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    Write-Host "Baseline successfully recorded to $manifestPath"
    Write-Host ("Baseline APK: {0:N0} bytes ({1})" -f $apkInfo.SizeBytes, $apkInfo.Sha256)
    if ($aabInfo) {
        Write-Host ("Baseline AAB: {0:N0} bytes ({1})" -f $aabInfo.SizeBytes, $aabInfo.Sha256)
    }
    exit 0
}

# Auto-detect baseline paths if not explicitly provided
if (-not $BaselineApk) {
    $candApk = Join-Path $DefaultBaselineDir "baseline-release-unsigned.apk"
    if (Test-Path -LiteralPath $candApk) {
        $BaselineApk = $candApk
    } else {
        throw "No Baseline APK provided, and default '$candApk' not found. Specify -BaselineApk or run with -RecordBaseline first."
    }
}

if (-not $CandidateApk) {
    if (Test-Path -LiteralPath $DefaultOutputApk) {
        $CandidateApk = $DefaultOutputApk
    } else {
        throw "No Candidate APK provided, and default '$DefaultOutputApk' not found. Specify -CandidateApk or run gradlew :app:assembleRelease first."
    }
}

$baseApkDetails = Get-ArtifactDetails $BaselineApk
$candApkDetails = Get-ArtifactDetails $CandidateApk

$deltaApkBytes = $candApkDetails.SizeBytes - $baseApkDetails.SizeBytes
$deltaApkPercent = 0.0
if ($baseApkDetails.SizeBytes -gt 0) {
    $deltaApkPercent = ($deltaApkBytes / [double]$baseApkDetails.SizeBytes) * 100.0
}

# AAB comparison if available
$baseAabDetails = $null
$candAabDetails = $null
$deltaAabBytes = $null
if (-not $BaselineAab) {
    $defBaseAab = Join-Path $DefaultBaselineDir "baseline-release.aab"
    if (Test-Path -LiteralPath $defBaseAab) { $BaselineAab = $defBaseAab }
}
if (-not $CandidateAab) {
    if (Test-Path -LiteralPath $DefaultOutputAab) { $CandidateAab = $DefaultOutputAab }
}

if ($BaselineAab -and (Test-Path -LiteralPath $BaselineAab) -and $CandidateAab -and (Test-Path -LiteralPath $CandidateAab)) {
    $baseAabDetails = Get-ArtifactDetails $BaselineAab
    $candAabDetails = Get-ArtifactDetails $CandidateAab
    $deltaAabBytes = $candAabDetails.SizeBytes - $baseAabDetails.SizeBytes
}

$bundletoolPath = Find-Bundletool
$bundletoolStatus = if ($bundletoolPath) { "Found ($bundletoolPath)" } else { "Not found (device download measurement requires bundletool)" }

$gateStatus = "PASS"
$gateReason = ""
if ($deltaApkBytes -gt $MaxIncreaseBytes) {
    $gateStatus = "FAIL"
    $gateReason = "Delta (+$deltaApkBytes bytes) exceeds hard ceiling of $MaxIncreaseBytes bytes (10 MB)."
} elseif ($deltaApkBytes -gt $WarnIncreaseBytes) {
    $gateStatus = "WARNING"
    $gateReason = "Delta (+$deltaApkBytes bytes) exceeds warning threshold of $WarnIncreaseBytes bytes (8 MB). Remaining budget: $($MaxIncreaseBytes - $deltaApkBytes) bytes."
} else {
    $gateStatus = "PASS"
    $gateReason = "Delta ($deltaApkBytes bytes) is within budget ($MaxIncreaseBytes bytes max)."
}

$report = [PSCustomObject]@{
    GateStatus = $gateStatus
    GateReason = $gateReason
    CeilingBytes = $MaxIncreaseBytes
    WarningBytes = $WarnIncreaseBytes
    BaselineApk = $baseApkDetails
    CandidateApk = $candApkDetails
    DeltaApkBytes = $deltaApkBytes
    DeltaApkPercent = [Math]::Round($deltaApkPercent, 4)
    BaselineAab = $baseAabDetails
    CandidateAab = $candAabDetails
    DeltaAabBytes = $deltaAabBytes
    Bundletool = $bundletoolStatus
}

if ($Json) {
    $report | ConvertTo-Json -Depth 6
} else {
    Write-Host "================================================================" -ForegroundColor Cyan
    Write-Host "          OCR FEATURE SIZE MEASUREMENT REPORT                   " -ForegroundColor Cyan
    Write-Host "================================================================" -ForegroundColor Cyan
    Write-Host ("Baseline APK  : {0}" -f $baseApkDetails.Path)
    Write-Host ("  Size        : {0:N0} bytes" -f $baseApkDetails.SizeBytes)
    Write-Host ("  SHA256      : {0}" -f $baseApkDetails.Sha256)
    Write-Host ("Candidate APK : {0}" -f $candApkDetails.Path)
    Write-Host ("  Size        : {0:N0} bytes" -f $candApkDetails.SizeBytes)
    Write-Host ("  SHA256      : {0}" -f $candApkDetails.Sha256)
    Write-Host "----------------------------------------------------------------"
    Write-Host ("APK Delta     : {0:+#,##0;-#,##0;0} bytes ({1:F2}%)" -f $deltaApkBytes, $deltaApkPercent) -ForegroundColor $(
        if ($gateStatus -eq "PASS") { "Green" } elseif ($gateStatus -eq "WARNING") { "Yellow" } else { "Red" }
    )
    Write-Host ("Ceiling       : 10,000,000 bytes (10.0 MB decimal)")
    Write-Host ("Warning       : 8,000,000 bytes (8.0 MB decimal)")
    Write-Host ("Gate Status   : {0} - {1}" -f $gateStatus, $gateReason) -ForegroundColor $(
        if ($gateStatus -eq "PASS") { "Green" } elseif ($gateStatus -eq "WARNING") { "Yellow" } else { "Red" }
    )

    if ($baseAabDetails -and $candAabDetails) {
        Write-Host "----------------------------------------------------------------"
        Write-Host ("Baseline AAB  : {0:N0} bytes ({1})" -f $baseAabDetails.SizeBytes, $baseAabDetails.Sha256)
        Write-Host ("Candidate AAB : {0:N0} bytes ({1})" -f $candAabDetails.SizeBytes, $candAabDetails.Sha256)
        Write-Host ("AAB Delta     : {0:+#,##0;-#,##0;0} bytes" -f $deltaAabBytes)
    }

    Write-Host "----------------------------------------------------------------"
    Write-Host ("Bundletool    : {0}" -f $bundletoolStatus)

    if ($baseApkDetails.Breakdown -and $candApkDetails.Breakdown) {
        Write-Host "----------------------------------------------------------------"
        Write-Host "Internal APK Category Breakdown (Compressed bytes):"
        $allCats = @('dex', 'native_arm64-v8a', 'native_armeabi-v7a', 'assets', 'res', 'resources.arsc', 'other')
        $breakdownTable = foreach ($cat in $allCats) {
            $baseVal = if ($baseApkDetails.Breakdown.ContainsKey($cat)) { $baseApkDetails.Breakdown[$cat] } else { 0L }
            $candVal = if ($candApkDetails.Breakdown.ContainsKey($cat)) { $candApkDetails.Breakdown[$cat] } else { 0L }
            $diff = $candVal - $baseVal
            [PSCustomObject]@{
                Category = $cat
                BaselineBytes = $baseVal
                CandidateBytes = $candVal
                DeltaBytes = $diff
            }
        }
        $breakdownTable | Format-Table -AutoSize
    }
    Write-Host "================================================================" -ForegroundColor Cyan
}

if ($gateStatus -eq "FAIL") {
    exit 2
} elseif ($gateStatus -eq "WARNING") {
    exit 0
} else {
    exit 0
}
