<#
  SnapWrite local build script (Windows PowerShell 5.1 / PowerShell 7)

  Usage (from anywhere, paths are resolved from this script's folder):
    powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
        Full build: compile all Java sources, rebuild the jar, rebuild the exe,
        patch the Chinese version info + icon with rcedit, verify the result.
    powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -JarOnly
        Fast path: recompile only if src/*.java changed, then rebuild the jar.
        The exe is a "dontWrapJar" launcher, so it always reads the newest jar.
        Use this while editing web/index.html or web/selftest.js.
    powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -SelfTest
        Full build + --selftest
    powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -AllTests
        Full build + selftest / bosstest / keytest / gesttest
    powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 -Shot
        Full build + bubble screenshot (ui_hybrid.png)

  This file is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less
  UTF-8 scripts as ANSI (GB2312 on this machine), which would garble Chinese
  string literals. All Chinese values are read from build/launch4j.xml (UTF-8).
  The launch4j config stores absolute paths, so a temporary copy with the
  current folder is generated at build time - the repo can be moved freely.
#>
[CmdletBinding()]
param(
    [switch]$JarOnly,
    [switch]$SelfTest,
    [switch]$AllTests,
    [switch]$Shot
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

function Say($msg) { Write-Host "[snapwrite] $msg" }

function Invoke-Step($exe, [string[]]$argv, $label) {
    Say $label
    $output = & $exe @argv 2>&1
    $code = $LASTEXITCODE
    if ($output) { $output | ForEach-Object { Write-Host "  $_" } }
    if ($code -ne 0) { throw "$label failed (exit $code)" }
    return $output
}

function Test-NeedCompile {
    # Compare each .java with its OWN top-level .class. Comparing against the
    # oldest class in out/ is wrong: orphaned inner classes from older builds
    # (e.g. FxApp$Bridge$2.class) are never rewritten by javac, so they would
    # force a recompile forever.
    $srcs = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Filter '*.java' -File)
    foreach ($s in $srcs) {
        $cn = Join-Path $root ('out/' + [IO.Path]::GetFileNameWithoutExtension($s.Name) + '.class')
        if (-not (Test-Path -LiteralPath $cn)) { return $true }
        $ci = Get-Item -LiteralPath $cn
        if ($s.LastWriteTime -gt $ci.LastWriteTime) { return $true }
    }
    return $false
}

function Invoke-App($flag, $label) {
    $jargs = @(
        '--module-path', (Join-Path $root 'jfx'),
        '--add-modules', 'javafx.web,javafx.controls,javafx.swing',
        '--add-opens', 'javafx.web/com.sun.javafx.webkit=ALL-UNNAMED',
        '-cp', 'FlashWriteFx.jar;lib/*', 'FxMain', $flag
    )
    Say $label
    $output = & $java @jargs 2>&1
    $lines = @($output | ForEach-Object { "$_" })
    $passes = @($lines | Where-Object { $_ -match '^PASS' })
    $fails = @($lines | Where-Object { $_ -match 'FAIL' })
    $tail = @($lines | Select-Object -Last 6)
    $tail | ForEach-Object { Write-Host "  $_" }
    if ($passes.Count -gt 0) { Say ("$label : PASS=" + $passes.Count + " FAIL=" + $fails.Count) }
    if ($fails.Count -gt 0) { throw "$label has FAIL lines" }
    return $lines
}

$jdk = Join-Path $root 'tools/jdk-21.0.12.1+1'
$javac = Join-Path $jdk 'bin/javac.exe'
$jar = Join-Path $jdk 'bin/jar.exe'
$java = Join-Path $jdk 'bin/java.exe'
foreach ($tool in @($javac, $jar, $java)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "missing JDK tool: $tool" }
}

$cfgPath = Join-Path $root 'build/launch4j.xml'
[xml]$cfg = Get-Content -LiteralPath $cfgPath -Raw -Encoding UTF8
$exeName = [IO.Path]::GetFileName([string]$cfg.launch4jConfig.outfile)
# Chinese product description comes from the UTF-8 XML, never from this script.
$desc = ([string]$cfg.launch4jConfig.errTitle) + ' ' + ([string]$cfg.launch4jConfig.versionInfo.productName)
$fileVer = [string]$cfg.launch4jConfig.versionInfo.txtFileVersion
$prodVer = [string]$cfg.launch4jConfig.versionInfo.txtProductVersion

# ---- 1. compile Java (never single-file: FxApp references FxMain fields) ----
$needCompile = (-not $JarOnly) -or (Test-NeedCompile)
if ($needCompile) {
    if (-not (Test-Path -LiteralPath (Join-Path $root 'out'))) {
        New-Item -ItemType Directory -Path (Join-Path $root 'out') | Out-Null
    }
    $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Filter '*.java' -File |
                   ForEach-Object { 'src/' + $_.Name })
    $javacArgs = @(
        '--release', '21',
        '--module-path', (Join-Path $root 'jfx'),
        '--add-modules', 'javafx.web,javafx.controls,javafx.swing',
        '-cp', 'lib/*', '-d', 'out'
    ) + $javaFiles
    Invoke-Step $javac $javacArgs 'javac (all src/*.java)' | Out-Null
} else {
    Say 'skip javac (no Java source changes)'
}

# ---- 2. jar: Java classes + web resources ----
$jarArgs = @('--create', '--file', 'FlashWriteFx.jar',
             '--manifest', 'build/manifest.txt', '-C', 'out', '.', '-C', 'web', '.')
Invoke-Step $jar $jarArgs 'jar (classes + web/)' | Out-Null

if (-not $JarOnly) {
    # ---- 3. launch4j: temp config with paths pointing at this checkout ----
    # Patch ONLY the two absolute-path lines as text. Re-serializing the whole
    # XmlDocument ($cfg.Save) rewrites empty elements such as <maxVersion></...>
    # into self-closing tags, which launch4j rejects ("Invalid data: Maximum
    # JRE version..."). Byte-preserving text replacement keeps it valid.
    $tmpCfg = Join-Path $root 'build/_launch4j.local.xml'
    $rawCfg = [IO.File]::ReadAllText($cfgPath, [Text.Encoding]::UTF8)
    $outAbs = ((Join-Path $root $exeName) -replace '\\', '/')
    $iconAbs = ((Join-Path $root 'build/icon.ico') -replace '\\', '/')
    $rawCfg = [regex]::Replace($rawCfg, '<outfile>[^<]*</outfile>', ('<outfile>' + $outAbs + '</outfile>'))
    $rawCfg = [regex]::Replace($rawCfg, '<icon>[^<]*</icon>', ('<icon>' + $iconAbs + '</icon>'))
    try {
        [IO.File]::WriteAllText($tmpCfg, $rawCfg, (New-Object System.Text.UTF8Encoding($false)))
        $l4j = Join-Path $root 'tools/launch4j/launch4jc.exe'
        Invoke-Step $l4j @($tmpCfg) 'launch4j (exe)' | Out-Null

        # ---- 4. rcedit: Chinese version strings + icon (windres mangles UTF-8) ----
        $rcedit = Join-Path $root 'tools/bin/rcedit.exe'
        $rcArgs = @(
            $exeName,
            '--set-version-string', 'FileDescription', $desc,
            '--set-version-string', 'ProductName', $desc,
            '--set-version-string', 'FileVersion', $fileVer,
            '--set-version-string', 'ProductVersion', $prodVer,
            '--set-icon', 'build/icon.ico'
        )
        Invoke-Step $rcedit $rcArgs 'rcedit (version info + icon)' | Out-Null
    } finally {
        Remove-Item -LiteralPath $tmpCfg -Force -ErrorAction SilentlyContinue
    }

    # ---- 5. verify the exe really carries the Chinese description ----
    $vi = (Get-Item -LiteralPath (Join-Path $root $exeName)).VersionInfo
    Say ("exe: " + $vi.FileDescription + " / " + $vi.FileVersion)
}

# ---- 6. optional regressions ----
if ($SelfTest -or $AllTests) { Invoke-App '--selftest' 'selftest' | Out-Null }
if ($AllTests) {
    Invoke-App '--bosstest' 'bosstest' | Out-Null
    Invoke-App '--keytest' 'keytest' | Out-Null
    Invoke-App '--gesttest' 'gesttest' | Out-Null
}
if ($Shot) { Invoke-App '--shot-bubble' 'shot-bubble' | Out-Null }

Say ("BUILD OK  v" + $fileVer + "  ->  " + $exeName + " + FlashWriteFx.jar")
