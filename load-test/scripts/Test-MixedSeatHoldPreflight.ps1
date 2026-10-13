Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Measure-MixedSeatHold.ps1'
foreach ($file in @($runner, (Join-Path $PSScriptRoot 'Run-MixedSeatHoldComparison.ps1'))) {
    $tokens = $null; $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) { throw "Parse errors in $file" }
}
$oldProject = $env:COMPOSE_PROJECT_NAME; $oldPort = $env:ONTICKET_DB_PORT
$script:apiCalls = 0
function Invoke-RestMethod { $script:apiCalls++; throw 'Preflight must not call Backend.' }
function Get-CimInstance { [pscustomobject]@{ FreePhysicalMemory=1024 } }
try {
    $env:COMPOSE_PROJECT_NAME='not-the-measurement-db'; $env:ONTICKET_DB_PORT='3309'
    $rejected = $false
    try { & $runner -RunId preflight224 } catch { $rejected = $_.Exception.Message -match 'dedicated' }
    if (!$rejected) { throw 'Wrong compose project was not rejected.' }
    $env:COMPOSE_PROJECT_NAME='ticketon-mixed224-test'
    $rejected = $false
    try { & $runner -RunId preflight224 } catch {
        $rejected = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { $_.Exception.Message -match 'memory <2GiB' } else { $_.Exception.Message -match 'Windows host memory preflight' }
    }
    if (!$rejected -or $script:apiCalls -ne 0) { throw 'Host preflight did not fail before API calls.' }
    Write-Output 'MIXED_PREFLIGHT_TESTS_PASSED wrong-project / low-memory-or-unsupported-host / zero-API-calls'
} finally {
    $env:COMPOSE_PROJECT_NAME=$oldProject; $env:ONTICKET_DB_PORT=$oldPort
}
