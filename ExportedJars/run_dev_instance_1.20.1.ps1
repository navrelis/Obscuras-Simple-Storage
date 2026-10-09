#Requires -Version 7.0
<#
  Optional: quick-package all four release jars (rebuild_all_and_export_jar.ps1 -SkipTests)
  without running the JUnit tests or GameTests, then launch the
  Fabric-1.20.1 dev client (Gradle :runClient).

  Use -SkipBuild to skip the rebuild and just open the dev client
  against whatever is currently in Fabric-1.20.1\build\.
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExportedJars = $PSScriptRoot
$RepoRoot = Split-Path -Parent $ExportedJars
$RebuildScript = Join-Path $ExportedJars 'rebuild_all_and_export_jar.ps1'
$ProjectDir = Join-Path $RepoRoot 'Fabric-1.20.1'

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $RebuildScript)) {
        throw "Missing rebuild script: $RebuildScript"
    }
    & $RebuildScript -SkipTests
    if (-not $?) {
        throw 'rebuild_all_and_export_jar.ps1 failed.'
    }
}

$gradlew = Join-Path $ProjectDir 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "Missing gradlew.bat in $ProjectDir"
}

Push-Location -LiteralPath $ProjectDir
try {
    Write-Host "Starting runClient from $ProjectDir ..." -ForegroundColor Cyan
    & .\gradlew.bat runClient --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "runClient failed (exit $LASTEXITCODE)"
    }
} finally {
    Pop-Location
}
