[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateRange(1, 100)][int]$PoolSize,
    [string]$BatchId = '',
    [ValidateRange(1, 10)][int]$Repeats = 3,
    [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
    [ValidateRange(1, 10000)][int]$Rate = 200,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 150,
    [ValidateRange(1, 500)][int]$MaxVus = 500,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'HikariPoolMatrix.psm1') -Force
$measure = Join-Path $PSScriptRoot 'Measure-SeatHoldContention.ps1'
if ([string]::IsNullOrWhiteSpace($BatchId)) { $BatchId = "h114-p$PoolSize-" + (Get-Date).ToUniversalTime().ToString('MMddHHmmss') }
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,20}$') { throw 'BatchId must contain 1-20 letters, numbers, or hyphens.' }
$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to reuse a matrix batch: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$fixtureRunId = "$BatchId-fixture"
$plan = @(New-HikariPoolMatrixPlan -PoolSizes @($PoolSize) -Repeats $Repeats)
$records = New-Object 'Collections.Generic.List[object]'

foreach ($stage in $plan) {
    $label = if ($stage.Warmup) { 'warm' } else { "r$($stage.Repeat)" }
    $runId = "$BatchId-$label"
    Write-Output "HIKARI_MATRIX_RUN_START pool=$PoolSize runId=$runId warmup=$($stage.Warmup)"
    & $measure -Scenario distributed-churn -Rate $Rate -DurationSeconds $DurationSeconds `
        -PreAllocatedVus $PreAllocatedVus -MaxVus $MaxVus -RunId $runId -FixtureRunId $fixtureRunId `
        -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl -OutputDirectory $output `
        -ExpectedHikariMax $PoolSize -DisablePerformanceThresholds
    $summaryPath = Join-Path $output "$runId-summary.json"
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $records.Add([pscustomobject]@{ PoolSize=$PoolSize; Repeat=$stage.Repeat; Warmup=$stage.Warmup; RunId=$runId; SummaryFile=[IO.Path]::GetFileName($summaryPath); Summary=$summary })
}

$manifest = [ordered]@{ SchemaVersion=1; BatchId=$BatchId; PoolSize=$PoolSize; Repeats=$Repeats; WarmupExcluded=$true; Records=$records.ToArray() }
$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'hikari-pool-matrix-manifest.json') -Encoding UTF8
$aggregate = New-HikariPoolMatrixAggregate -Records $records.ToArray()
$aggregate | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'hikari-pool-matrix-aggregate.json') -Encoding UTF8
Write-Output "HIKARI_MATRIX_COMPLETE pool=$PoolSize records=$($records.Count) aggregate=$output\hikari-pool-matrix-aggregate.json"
