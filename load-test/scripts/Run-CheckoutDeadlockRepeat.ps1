[CmdletBinding()]
param(
    [string]$BatchId = '',
    [ValidateRange(3, 10)][int]$Repeats = 3,
    [ValidateRange(1, 60)][int]$DurationSeconds = 10,
    [ValidateRange(1, 1000)][int]$Rate = 100,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 100,
    [ValidateRange(1, 500)][int]$MaxVus = 200,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'CheckoutDeadlockRepeat.psm1') -Force
$measure = Join-Path $PSScriptRoot 'Measure-CheckoutContention.ps1'
if ($PreAllocatedVus -gt $MaxVus) { throw 'PreAllocatedVus must not exceed MaxVus.' }
if ($Rate -ne 100 -or $DurationSeconds -ne 10) { throw 'Issue #132 fixes the repeat contract at distributed 100 RPS for 10 seconds.' }
if ([string]::IsNullOrWhiteSpace($BatchId)) { $BatchId = 'i132-' + (Get-Date).ToUniversalTime().ToString('MMddHHmmss') }
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,32}$') { throw 'BatchId must contain 1-32 letters, numbers, or hyphens.' }
$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to reuse a repeat batch: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null

$records = New-Object 'Collections.Generic.List[object]'
foreach ($stage in @(New-CheckoutDeadlockRepeatPlan -Repeats $Repeats)) {
    $label = if ($stage.Warmup) { 'warmup' } else { "r$($stage.Repeat)" }
    $runId = "$BatchId-$label"
    Write-Output "CHECKOUT_DEADLOCK_REPEAT_START runId=$runId warmup=$($stage.Warmup)"
    & $measure -Scenario distributed -Rate $Rate -DurationSeconds $DurationSeconds `
        -PreAllocatedVus $PreAllocatedVus -MaxVus $MaxVus -RunId $runId `
        -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl -OutputDirectory $output `
        -DisablePerformanceThresholds
    $summaryPath = Join-Path $output "$runId-summary.json"
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-CheckoutDeadlockRepeatSummary -Summary $summary -RunId $runId -Rate $Rate -DurationSeconds $DurationSeconds | Out-Null
    $records.Add([pscustomobject]@{ Sequence=$stage.Sequence; Repeat=$stage.Repeat; Warmup=$stage.Warmup; RunId=$runId; SummaryFile=[IO.Path]::GetFileName($summaryPath); Summary=$summary })
}

$aggregate = New-CheckoutDeadlockRepeatAggregate -Records $records.ToArray()
$manifest = [ordered]@{ SchemaVersion=1; BatchId=$BatchId; Repeats=$Repeats; WarmupExcluded=$true; Records=$records.ToArray(); Aggregate=$aggregate }
$manifestPath = Join-Path $output 'checkout-deadlock-repeat-manifest.json'
$aggregatePath = Join-Path $output 'checkout-deadlock-repeat-aggregate.json'
$manifest | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$aggregate | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $aggregatePath -Encoding UTF8
Write-Output "CHECKOUT_DEADLOCK_REPEAT_COMPLETE repeats=$Repeats gatePassed=$($aggregate.Gate.AllDeadlocksZero -and $aggregate.Gate.AllUnexpectedFailuresZero -and $aggregate.Gate.AllInventoryInvariantsSatisfied) aggregate=$aggregatePath"
