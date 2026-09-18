[CmdletBinding()]
param(
    [string]$BatchId = '',
    [ValidateRange(3, 10)][int]$Repeats = 3,
    [ValidateRange(1, 60)][int]$DurationSeconds = 10,
    [ValidateRange(1, 500)][int]$PreAllocatedVus = 100,
    [ValidateRange(1, 500)][int]$MaxVus = 200,
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'CheckoutSaturationAttribution.psm1') -Force
$measure = Join-Path $PSScriptRoot 'Measure-CheckoutContention.ps1'
if ($PreAllocatedVus -gt $MaxVus) { throw 'PreAllocatedVus must not exceed MaxVus.' }
if ([string]::IsNullOrWhiteSpace($BatchId)) { $BatchId = 'i134-' + (Get-Date).ToUniversalTime().ToString('MMddHHmmss') }
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,32}$') { throw 'BatchId must contain 1-32 letters, numbers, or hyphens.' }
$output = Join-Path $root "load-test\results\$BatchId"
if (Test-Path -LiteralPath $output) { throw "Refusing to reuse an attribution batch: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null

$records = New-Object 'Collections.Generic.List[object]'
foreach ($stage in @(New-CheckoutSaturationAttributionPlan -Repeats $Repeats)) {
    $label = if ($stage.Warmup) { 'warmup' } else { "r$($stage.Repeat)" }
    $runId = "$BatchId-$($stage.Rate)-$label"
    Write-Output "CHECKOUT_SATURATION_START runId=$runId rate=$($stage.Rate) warmup=$($stage.Warmup)"
    & $measure -Scenario distributed -Rate $stage.Rate -DurationSeconds $DurationSeconds `
        -PreAllocatedVus $PreAllocatedVus -MaxVus $MaxVus -RunId $runId `
        -BaseUrl $BaseUrl -ManagementBaseUrl $ManagementBaseUrl -OutputDirectory $output `
        -DisablePerformanceThresholds
    $summaryPath = Join-Path $output "$runId-summary.json"
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-CheckoutSaturationSummary -Summary $summary -RunId $runId -Rate $stage.Rate -DurationSeconds $DurationSeconds | Out-Null
    $records.Add([pscustomobject]@{ Sequence=$stage.Sequence; Rate=$stage.Rate; Repeat=$stage.Repeat; Warmup=$stage.Warmup; RunId=$runId; SummaryFile=[IO.Path]::GetFileName($summaryPath); Summary=$summary })
}
$aggregate = @(New-CheckoutSaturationAttributionAggregate -Records $records.ToArray())
$manifest = [ordered]@{ SchemaVersion=1; BatchId=$BatchId; Rates=@(50,75,100); Repeats=$Repeats; WarmupExcluded=$true; Records=$records.ToArray(); Aggregate=$aggregate }
$manifestPath = Join-Path $output 'checkout-saturation-attribution-manifest.json'
$aggregatePath = Join-Path $output 'checkout-saturation-attribution-aggregate.json'
$manifest | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$aggregate | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $aggregatePath -Encoding UTF8
Write-Output "CHECKOUT_SATURATION_COMPLETE rates=50,75,100 repeats=$Repeats aggregate=$aggregatePath"
