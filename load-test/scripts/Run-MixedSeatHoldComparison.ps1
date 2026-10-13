[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9-]{1,12}$')][string]$BatchId,
    [ValidateSet(20,200)][int[]]$LargeSizes = @(20)
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$batchPath = Join-Path $root "load-test/results/$BatchId-comparison.json"
if (Test-Path -LiteralPath $batchPath) { throw 'Batch already exists.' }
New-Item -ItemType Directory -Path (Split-Path $batchPath) -Force | Out-Null
$records = New-Object 'Collections.Generic.List[object]'
$complete = $false; $failure = $null
if (@($LargeSizes | Select-Object -Unique).Count -ne $LargeSizes.Count) { throw 'LargeSizes must not contain duplicates.' }
$conditions = @([pscustomobject]@{ Mode='small-only'; Size=20; Code='s' })
foreach ($size in $LargeSizes) {
    $conditions += [pscustomobject]@{ Mode='separate'; Size=$size; Code="d$size" }
    $conditions += [pscustomobject]@{ Mode='overlap'; Size=$size; Code="o$size" }
}
function Run-Condition($condition, $label, $duration, $warmup) {
    $id = "$BatchId-$($condition.Code)-$label"
    try { & (Join-Path $PSScriptRoot 'Measure-MixedSeatHold.ps1') -RunId $id -Mode $condition.Mode -LargeSeats $condition.Size -DurationSeconds $duration }
    catch { throw "Measurement $id failed: $($_.Exception.Message)" }
    $summary = Get-Content -Raw -Encoding UTF8 (Join-Path $root "load-test/results/$id/summary.json") | ConvertFrom-Json
    $records.Add([pscustomobject]@{ RunId=$id; Warmup=$warmup; Summary=$summary })
    if ($summary.Metrics.Peaks.SystemCpuUsage -ge 0.9) { throw "Run $id host CPU peak >=90%; stop bounded batch for resource review, not a DB bottleneck conclusion." }
    if ($summary.Metrics.Deltas.DbDeadlocks -ne 0) { throw 'SQL deadlock observed; stop batch.' }
}
try {
    foreach ($condition in $conditions) { Run-Condition $condition 'w' 5 $true }
    for ($repeat=1; $repeat -le 3; $repeat++) {
        $order = @($conditions)
        if ($repeat -eq 2) { [Array]::Reverse($order) }
        foreach ($condition in $order) { Run-Condition $condition "r$repeat" 10 $false }
    }
    $complete = $true
} catch { $failure = $_.Exception.Message; throw }
finally {
    [ordered]@{ SchemaVersion=1; Complete=$complete; Failure=$failure; SmallIterationsPerSecond=10; LargeIterationsPerSecond=2; SmallSeatCount=1; SmallSeatPool=40; DwellMilliseconds=50; LargeSizes=$LargeSizes; MeasuredSeconds=10; Repeats=3; WarmupExcluded=$true; Records=$records.ToArray() } |
        ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $batchPath -Encoding UTF8
}
