#Requires -Version 7.0
<#
.SYNOPSIS
  Release build for Obscura's Simple Storage: builds, tests and exports the jars of all four loader projects.

.DESCRIPTION
  For each project, in this order:
    Fabric-1.21.1, NeoForge-1.21.1, Fabric-1.20.1, Forge-1.20.1
  the script runs
    1. gradlew clean build        (includes the JUnit tests, where the project has any)
    2. the project's GameTest task (Fabric: runGametest, NeoForge/Forge: runGameTestServer)
    3. Forge-1.20.1 only: the same GameTests again on NeoForge 1.20.1 (gradlew -PneoForge clean runGameTestServer)
  and stops with a clear error on the first failing step. Only when every project passed, the four release jars
  (obscuras_simple_storage-<loader>-mc<ver>-<version>.jar, never the -sources jars) are copied into ExportedJars,
  overwriting older jars of the same name. Jars of other versions (for example v3.0.2) are left alone.
  Every jar is checked for its loader metadata file and for leaked GameTest classes before it is copied.
  A summary table (project, JUnit tests, GameTests, jar, size) is printed at the end.

  Jars are git-ignored (ExportedJars/*.jar) and are not committed.

.PARAMETER SkipTests
  Quick packaging: still runs "clean build", but without the JUnit tests (-x test) and without any GameTest run.

.PARAMETER SkipBuild
  Runs no Gradle at all: only verifies and copies the jars that are already in each project's build\libs.
  Tests are reported as "not run". Use it to re-export after a build you did yourself.

.PARAMETER DryRun
  Prints the Gradle commands that would run and which jars would be copied, without running or copying anything.

.PARAMETER OutputDir
  Where the jars are copied to. Defaults to this ExportedJars folder.

.EXAMPLE
  .\rebuild_all_and_export_jar.ps1
  Full release run: clean build + JUnit + GameTests for all four projects, then export the jars.

.EXAMPLE
  .\rebuild_all_and_export_jar.ps1 -SkipTests
  Quick package: clean build of all four projects without tests, then export the jars.

.NOTES
  Requires PowerShell 7 and a JDK 21 (JAVA_HOME or PATH) for the Gradle wrapper. Forge-1.20.1 compiles with Java 17,
  which Gradle downloads on its own (toolchain), so no separate JDK 17 is needed. The first run needs network access
  for Gradle dependencies. The full run takes a while; Gradle output is shown live and also saved to a log folder
  in the temp directory (its path is printed on start and on failure).
#>
[CmdletBinding()]
param(
    [switch] $SkipTests,
    [switch] $SkipBuild,
    [switch] $DryRun,
    [string] $OutputDir = $PSScriptRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$ModRoot = Split-Path -Parent $PSScriptRoot

# GameTestKind: 'junit-xml'  = Fabric, result file build\gametest\junit.xml
#               'server-log' = NeoForge / Forge, "All N required tests passed" in the Gradle output (fallback: the
#                              server's own debug.log in <build dir>\gametest\logs)
$Projects = @(
    [pscustomobject]@{
        Name = 'Fabric 1.21.1';   Dir = 'Fabric-1.21.1';   Common = 'Common-1.21.1'
        GameTestTask = 'runGametest';       GameTestKind = 'junit-xml';  MetaFile = 'fabric.mod.json'
        NeoGameTests = $false
    },
    [pscustomobject]@{
        Name = 'NeoForge 1.21.1'; Dir = 'NeoForge-1.21.1'; Common = 'Common-1.21.1'
        GameTestTask = 'runGameTestServer'; GameTestKind = 'server-log'; MetaFile = 'META-INF/neoforge.mods.toml'
        NeoGameTests = $false
    },
    [pscustomobject]@{
        Name = 'Fabric 1.20.1';   Dir = 'Fabric-1.20.1';   Common = 'Common-1.20.1'
        GameTestTask = 'runGametest';       GameTestKind = 'junit-xml';  MetaFile = 'fabric.mod.json'
        NeoGameTests = $false
    },
    [pscustomobject]@{
        Name = 'Forge 1.20.1';    Dir = 'Forge-1.20.1';    Common = 'Common-1.20.1'
        GameTestTask = 'runGameTestServer'; GameTestKind = 'server-log'; MetaFile = 'META-INF/mods.toml'
        NeoGameTests = $true   # second GameTest run with -PneoForge (NeoForge 1.20.1, build dir build-neo)
    }
)

$LogDir = Join-Path ([System.IO.Path]::GetTempPath()) ('obscura-release-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))

# ---------------------------------------------------------------- helpers

function Write-Stage([string] $Text) {
    Write-Host ''
    Write-Host "=== $Text" -ForegroundColor Cyan
}

function Stop-Release([string] $Message) {
    throw "RELEASE BUILD FAILED: $Message"
}

function Read-GradleProperties([string] $Path) {
    $props = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') {
            $props[$Matches[1]] = $Matches[2]
        }
    }
    return $props
}

function Get-JavaMajorVersion {
    $javaExe = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { $javaExe = $candidate }
    }
    if (-not $javaExe) {
        $cmd = Get-Command java -ErrorAction SilentlyContinue
        if ($cmd) { $javaExe = $cmd.Source }
    }
    if (-not $javaExe) { return $null }
    $first = (& $javaExe -version 2>&1 | Select-Object -First 1).ToString()
    if ($first -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }   # "1.8.0" style
        return $major
    }
    return $null
}

# Runs gradlew.bat in a project folder, streams the output live and into a log file, fails on a non-zero exit code.
function Invoke-Gradle {
    param(
        [Parameter(Mandatory)] $Project,
        [Parameter(Mandatory)][string] $Stage,
        [Parameter(Mandatory)][string[]] $GradleArgs
    )
    $projectDir = Join-Path $ModRoot $Project.Dir
    $allArgs = $GradleArgs + @('--console=plain')
    $display = "gradlew.bat $($allArgs -join ' ')"

    if ($DryRun) {
        Write-Host "  [dry-run] ($($Project.Dir)) $display" -ForegroundColor DarkYellow
        return $null
    }

    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    $logName = ($Project.Dir + '-' + ($Stage -replace '[^A-Za-z0-9]+', '-') + '.log')
    $logPath = Join-Path $LogDir $logName

    Write-Host "  ($($Project.Dir)) $display" -ForegroundColor DarkGray
    Push-Location -LiteralPath $projectDir
    try {
        & .\gradlew.bat @allArgs 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $logPath | Out-Host
        $exit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exit -ne 0) {
        Stop-Release "$($Project.Name): '$Stage' failed (Gradle exit code $exit). Full log: $logPath"
    }
    return $logPath
}

function Stop-GradleDaemons([string] $ProjectDirName) {
    if ($DryRun) { return }
    $gradlew = Join-Path $ModRoot "$ProjectDirName\gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { return }
    Push-Location -LiteralPath (Join-Path $ModRoot $ProjectDirName)
    try { & .\gradlew.bat --stop 2>&1 | Out-Null } catch { } finally { Pop-Location }
}

# Sums the JUnit XML reports of the Gradle test task. Returns $null when the project has no test results.
function Get-JUnitSummary([string] $ProjectDirName) {
    $resultsDir = Join-Path $ModRoot "$ProjectDirName\build\test-results\test"
    $files = @(Get-ChildItem -LiteralPath $resultsDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { return $null }
    $sum = [pscustomobject]@{ Tests = 0; Failed = 0; Skipped = 0 }
    foreach ($file in $files) {
        $xml = [xml](Get-Content -LiteralPath $file.FullName -Raw)
        $suite = $xml.DocumentElement
        $sum.Tests   += [int]$suite.GetAttribute('tests')
        $sum.Failed  += [int]$suite.GetAttribute('failures') + [int]$suite.GetAttribute('errors')
        $sum.Skipped += [int]$suite.GetAttribute('skipped')
    }
    return $sum
}

# Fabric: build\gametest\junit.xml written by the Fabric API GameTest runner.
function Get-FabricGameTestCount([string] $ProjectDirName) {
    $report = Join-Path $ModRoot "$ProjectDirName\build\gametest\junit.xml"
    if (-not (Test-Path -LiteralPath $report)) { return $null }
    $xml = [xml](Get-Content -LiteralPath $report -Raw)
    $total  = @($xml.SelectNodes('//testcase')).Count
    $failed = @($xml.SelectNodes('//testcase[failure or error]')).Count
    return [pscustomobject]@{ Total = $total; Failed = $failed }
}

# NeoForge / Forge: the vanilla GameTest server logs "All N required tests passed :)" before it exits. The text is
# in the Gradle output (fresh by definition); the server's own debug.log is the fallback (deleted before each run).
function Get-ServerGameTestCount([string[]] $LogPaths) {
    $found = $false
    foreach ($path in $LogPaths) {
        if (-not $path -or -not (Test-Path -LiteralPath $path)) { continue }
        $found = $true
        $text = Get-Content -LiteralPath $path -Raw
        if ($text -match 'All (\d+) required tests passed') {
            return [pscustomobject]@{ Total = [int]$Matches[1]; Failed = 0 }
        }
    }
    if (-not $found) { return $null }
    return [pscustomobject]@{ Total = 0; Failed = -1 }
}

# Checks the jar is a real release jar: loader metadata present, no GameTest classes or resources leaked in.
function Test-ReleaseJar {
    param([Parameter(Mandatory)] $Project, [Parameter(Mandatory)][string] $JarPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
    } finally {
        $zip.Dispose()
    }
    if ($names -notcontains $Project.MetaFile) {
        Stop-Release "$($Project.Name): jar $JarPath has no $($Project.MetaFile) (wrong or broken jar)."
    }
    $leaked = @($names | Where-Object { $_ -match '(?i)gametest' })
    if ($leaked.Count -gt 0) {
        Stop-Release "$($Project.Name): jar $JarPath contains $($leaked.Count) GameTest entries (first: $($leaked[0]))."
    }
}

# ---------------------------------------------------------------- preflight

Write-Stage 'Preflight'

if ($DryRun)    { Write-Host 'DRY RUN: nothing is built, tested or copied.' -ForegroundColor DarkYellow }
if ($SkipBuild) { Write-Host 'SkipBuild: no Gradle run, re-exporting jars that are already in build\libs.' -ForegroundColor DarkYellow }
elseif ($SkipTests) { Write-Host 'SkipTests: clean build without JUnit tests and without GameTests.' -ForegroundColor DarkYellow }

$releaseVersion = $null
foreach ($p in $Projects) {
    $projectDir = Join-Path $ModRoot $p.Dir
    foreach ($required in @($projectDir, (Join-Path $ModRoot $p.Common), (Join-Path $projectDir 'gradlew.bat'), (Join-Path $projectDir 'gradle.properties'))) {
        if (-not (Test-Path -LiteralPath $required)) { Stop-Release "Missing path: $required" }
    }
    $props = Read-GradleProperties (Join-Path $projectDir 'gradle.properties')
    foreach ($key in 'archives_base_name', 'mod_version') {
        if (-not $props.ContainsKey($key)) { Stop-Release "$($p.Dir)\gradle.properties has no $key." }
    }
    $jarName = "$($props['archives_base_name'])-$($props['mod_version']).jar"
    $p | Add-Member -NotePropertyName JarName -NotePropertyValue $jarName
    $p | Add-Member -NotePropertyName JarPath -NotePropertyValue (Join-Path $projectDir "build\libs\$jarName")
    if ($null -eq $releaseVersion) { $releaseVersion = $props['mod_version'] }
    elseif ($releaseVersion -ne $props['mod_version']) {
        Stop-Release "mod_version differs between projects ($releaseVersion vs $($props['mod_version']) in $($p.Dir))."
    }
}
Write-Host "Release version: $releaseVersion" -ForegroundColor Green

if (-not $SkipBuild) {
    $java = Get-JavaMajorVersion
    if ($null -eq $java) { Stop-Release 'No Java found. Install JDK 21 and set JAVA_HOME (or put java on PATH).' }
    if ($java -lt 21)    { Stop-Release "Gradle needs JDK 21 or newer, found Java $java. Point JAVA_HOME at a JDK 21." }
    Write-Host "Java $java for the Gradle wrapper (Forge 1.20.1 fetches its own Java 17 toolchain)." -ForegroundColor Green
    Write-Host "Gradle logs: $LogDir" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- build + test

$results = @()
$totalTimer = [System.Diagnostics.Stopwatch]::StartNew()

try {
    foreach ($p in $Projects) {
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $projectDir = Join-Path $ModRoot $p.Dir
        $testsText = 'not run'
        $gameTestText = 'not run'

        Write-Stage "$($p.Name)"

        if (-not $SkipBuild) {
            # --- 1. clean build (JUnit included unless -SkipTests)
            $buildArgs = @('clean', 'build')
            if ($SkipTests) { $buildArgs += @('-x', 'test') }
            $null = Invoke-Gradle -Project $p -Stage 'clean build' -GradleArgs $buildArgs

            if (-not $SkipTests -and -not $DryRun) {
                $junit = Get-JUnitSummary $p.Dir
                if ($null -eq $junit) {
                    $testsText = 'none'
                } elseif ($junit.Failed -gt 0) {
                    Stop-Release "$($p.Name): $($junit.Failed) JUnit test(s) failed."
                } else {
                    $testsText = "$($junit.Tests)"
                    if ($junit.Skipped -gt 0) { $testsText += " ($($junit.Skipped) skipped)" }
                }
                Write-Host "  JUnit: $testsText" -ForegroundColor Green
            }

            # --- 2. GameTests
            if (-not $SkipTests) {
                $serverLogDir = Join-Path $projectDir 'build\gametest\logs'
                if ($p.GameTestKind -eq 'server-log') {
                    Remove-Item -LiteralPath (Join-Path $serverLogDir 'debug.log'), (Join-Path $serverLogDir 'latest.log') -Force -ErrorAction SilentlyContinue
                }
                $gradleLog = Invoke-Gradle -Project $p -Stage 'gametests' -GradleArgs @($p.GameTestTask)

                if (-not $DryRun) {
                    $gt = if ($p.GameTestKind -eq 'junit-xml') {
                        Get-FabricGameTestCount $p.Dir
                    } else {
                        Get-ServerGameTestCount @($gradleLog, (Join-Path $serverLogDir 'debug.log'), (Join-Path $serverLogDir 'latest.log'))
                    }
                    if ($null -eq $gt) {
                        Stop-Release "$($p.Name): GameTests ran but left no result file to read ($($p.GameTestKind))."
                    }
                    if ($gt.Failed -ne 0 -or $gt.Total -eq 0) {
                        Stop-Release "$($p.Name): GameTests did not report a clean pass (total $($gt.Total), failed $($gt.Failed))."
                    }
                    $gameTestText = "$($gt.Total)"
                    Write-Host "  GameTests: $($gt.Total) passed" -ForegroundColor Green
                }

                # --- 3. Forge 1.20.1 only: same GameTests on NeoForge 1.20.1 (own build dir build-neo)
                if ($p.NeoGameTests) {
                    $neoLogDir = Join-Path $projectDir 'build-neo\gametest\logs'
                    Remove-Item -LiteralPath (Join-Path $neoLogDir 'debug.log'), (Join-Path $neoLogDir 'latest.log') -Force -ErrorAction SilentlyContinue
                    $neoGradleLog = Invoke-Gradle -Project $p -Stage 'gametests NeoForge 1.20.1' -GradleArgs @('-PneoForge', 'clean', 'runGameTestServer')

                    if (-not $DryRun) {
                        $gtNeo = Get-ServerGameTestCount @($neoGradleLog, (Join-Path $neoLogDir 'debug.log'), (Join-Path $neoLogDir 'latest.log'))
                        if ($null -eq $gtNeo) {
                            Stop-Release "$($p.Name): NeoForge 1.20.1 GameTests ran but left no result file to read."
                        }
                        if ($gtNeo.Failed -ne 0 -or $gtNeo.Total -eq 0) {
                            Stop-Release "$($p.Name): NeoForge 1.20.1 GameTests did not report a clean pass (total $($gtNeo.Total), failed $($gtNeo.Failed))."
                        }
                        $gameTestText += " + $($gtNeo.Total) (NeoForge)"
                        Write-Host "  GameTests on NeoForge 1.20.1: $($gtNeo.Total) passed" -ForegroundColor Green
                    }
                }
            }
        } else {
            $junit = Get-JUnitSummary $p.Dir
            if ($null -ne $junit) { $testsText = "$($junit.Tests) (earlier run)" }
        }

        # --- release jar check (does not copy anything yet)
        $jarSize = 0
        if ($DryRun) {
            $state = if (Test-Path -LiteralPath $p.JarPath) { 'exists now, would be rebuilt' } else { 'not built yet' }
            Write-Host "  [dry-run] expects build\libs\$($p.JarName) ($state)" -ForegroundColor DarkYellow
        } else {
            if (-not (Test-Path -LiteralPath $p.JarPath)) {
                Stop-Release "$($p.Name): release jar not found: $($p.JarPath)"
            }
            Test-ReleaseJar -Project $p -JarPath $p.JarPath
            $jarSize = (Get-Item -LiteralPath $p.JarPath).Length
            Write-Host "  Jar OK: $($p.JarName)" -ForegroundColor Green
        }

        $results += [pscustomobject]@{
            Project   = $p.Name
            Tests     = $testsText
            GameTests = $gameTestText
            Jar       = $p.JarName
            Size      = if ($jarSize -gt 0) { '{0:N2} MB' -f ($jarSize / 1MB) } else { '-' }
            Time      = '{0:mm\:ss}' -f $timer.Elapsed
        }

        if (-not $SkipBuild) { Stop-GradleDaemons $p.Dir }
    }
} catch {
    foreach ($p in $Projects) { Stop-GradleDaemons $p.Dir }
    Write-Host ''
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host 'Nothing was exported. Fix the failure above and run the script again.' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- export (only when everything passed)

Write-Stage 'Export'
if ($DryRun) {
    Write-Host "  [dry-run] would copy the 4 jars to $OutputDir (overwriting jars of the same name, leaving other versions alone)." -ForegroundColor DarkYellow
} else {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    foreach ($p in $Projects) {
        $target = Join-Path $OutputDir $p.JarName
        $existed = Test-Path -LiteralPath $target
        Copy-Item -LiteralPath $p.JarPath -Destination $target -Force
        $verb = if ($existed) { 'replaced' } else { 'added' }
        Write-Host "  $verb $($p.JarName)" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------- summary

Write-Host ''
Write-Host "Summary ($releaseVersion, total time $('{0:hh\:mm\:ss}' -f $totalTimer.Elapsed))" -ForegroundColor Cyan
($results | Format-Table -AutoSize | Out-String).TrimEnd() | Write-Host
Write-Host ''
if ($DryRun) {
    Write-Host 'Dry run finished. Nothing was built or copied.' -ForegroundColor DarkYellow
} else {
    Write-Host "Done. Jars are in: $OutputDir" -ForegroundColor Green
}
