$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    & .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round6-reaudit-20261006/audit.init.gradle --console=plain `
        --tests 'com.tscanner.app.VipSessionRound6ProbeTest' --tests 'com.tscanner.app.VipSessionRound5ProbeTest' `
        --tests 'com.tscanner.app.VipSessionRound4ProbeTest' `
        --tests 'com.tscanner.app.VipSessionRound3ProbeTest' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.P01*' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.P02*' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.P04*' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.P05*' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.P06*' `
        --tests 'com.tscanner.app.VipSessionReauditProbeTest.C*' *> "$PSScriptRoot/probes.log"
    $probeExit = $LASTEXITCODE
    Get-ChildItem app/build/test-results/testDebugUnitTest/TEST-*ProbeTest.xml | Copy-Item -Destination $PSScriptRoot
    Write-Output "GRADLE_EXIT=$probeExit"
    Get-Content "$PSScriptRoot/probes.log" -Tail 35
    exit $probeExit
} finally {
    Pop-Location
}

