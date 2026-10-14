Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Measure-MixedSeatHold.ps1'
foreach ($file in @($runner, (Join-Path $PSScriptRoot 'Run-MixedSeatHoldComparison.ps1'))) {
    $tokens = $null; $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) { throw "Parse errors in $file" }
}
$oldProject = $env:COMPOSE_PROJECT_NAME; $oldPort = $env:ONTICKET_DB_PORT
$global:mixed224ApiCalls = 0
$global:mixed224MemoryKiB = 1024; $global:mixed224WriteCalls = 0
function Invoke-RestMethod {
    param([string]$Uri, [string]$Method='Get', [int]$TimeoutSec)
    $global:mixed224ApiCalls++
    if ($Method -ne 'Get') { $global:mixed224WriteCalls++; throw 'Preflight must not call a write API.' }
    if ($Uri -match '/actuator/health$') { return [pscustomobject]@{ status='UP' } }
    if ($Uri -match '/loadtest/fixture\?') { return [pscustomobject]@{ totalSeats=2000; concertId='LOAD-TEST-prepared224'; concertTimeId=1 } }
    throw 'Unexpected API call.'
}
function Get-CimInstance { [pscustomobject]@{ FreePhysicalMemory=$global:mixed224MemoryKiB } }
function docker { $global:LASTEXITCODE=0; '0' } # fixture absent from intended DB
try {
    $env:COMPOSE_PROJECT_NAME='not-the-measurement-db'; $env:ONTICKET_DB_PORT='3309'
    $rejected = $false
    try { & $runner -RunId preflight224 -FixtureRunId prepared224 } catch { $rejected = $_.Exception.Message -match 'dedicated' }
    if (!$rejected) { throw 'Wrong compose project was not rejected.' }
    $env:COMPOSE_PROJECT_NAME='ticketon-mixed224-test'
    $rejected = $false
    try { & $runner -RunId preflight224 -FixtureRunId prepared224 } catch {
        $rejected = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { $_.Exception.Message -match 'memory <2GiB' } else { $_.Exception.Message -match 'Windows host memory preflight' }
    }
    if (!$rejected -or $global:mixed224ApiCalls -ne 0) { throw 'Host preflight did not fail before API calls.' }
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $global:mixed224MemoryKiB=4*1024*1024
        $rejected=$false
        try { & $runner -RunId preflight224 -FixtureRunId prepared224 } catch { $rejected = $_.Exception.Message -match 'not in the dedicated measurement DB' }
        if (!$rejected -or $global:mixed224WriteCalls -ne 0 -or $global:mixed224ApiCalls -ne 2) { throw 'Wrong DB target did not fail with GET-only preflight.' }
    }
    $rejected=$false
    try { & (Join-Path $PSScriptRoot 'Run-MixedSeatHoldComparison.ps1') -BatchId empty224 -FixtureRunId prepared224 -LargeSizes @() }
    catch { $rejected = $_.Exception.Message -match 'LargeSizes' }
    if (!$rejected) { throw 'Empty LargeSizes was not rejected.' }
    Write-Output 'MIXED_PREFLIGHT_TESTS_PASSED wrong-project / host-resource / wrong-DB-GET-only-on-Windows / empty-bundle'
} finally {
    $env:COMPOSE_PROJECT_NAME=$oldProject; $env:ONTICKET_DB_PORT=$oldPort
    Remove-Variable mixed224ApiCalls,mixed224MemoryKiB,mixed224WriteCalls -Scope Global -ErrorAction SilentlyContinue
}
