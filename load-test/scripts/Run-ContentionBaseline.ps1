[CmdletBinding()]
param(
    [string]$BatchId = '',

    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 10,

    [ValidateRange(1, 10)]
    [int]$Repeats = 3,

    [ValidateRange(250, 10000)]
    [int]$SampleIntervalMilliseconds = 1000,

    [ValidateRange(1, 500)]
    [int]$PreAllocatedVus = 200,

    [ValidateRange(1, 500)]
    [int]$MaxVus = 200,

    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$ManagementBaseUrl = 'http://127.0.0.1:18081',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue53ScriptDirectory = $PSScriptRoot
$issue53RepositoryRoot = (Resolve-Path (Join-Path $issue53ScriptDirectory '..\..')).Path
$issue53ModulePath = Join-Path $issue53ScriptDirectory 'ContentionBaseline.psm1'
$issue53MeasureScript = Join-Path $issue53ScriptDirectory 'Measure-Contention.ps1'
Import-Module $issue53ModulePath -Force

if ($PreAllocatedVus -gt $MaxVus) {
    throw 'PreAllocatedVus must not exceed MaxVus.'
}

if ([string]::IsNullOrWhiteSpace($BatchId)) {
    $BatchId = 'b53-' + (Get-Date).ToUniversalTime().ToString('MMddHHmmss')
}
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,16}$') {
    throw 'BatchId must contain 1-16 letters, numbers, or hyphens.'
}

$issue53OutputDirectory = Join-Path $issue53RepositoryRoot "load-test\results\$BatchId"
$issue53ManifestPath = Join-Path $issue53OutputDirectory 'baseline-manifest.json'
$issue53AggregatePath = Join-Path $issue53OutputDirectory 'baseline-aggregate.json'
if (Test-Path -LiteralPath $issue53OutputDirectory) {
    throw "Refusing to reuse an existing baseline batch: $issue53OutputDirectory"
}
New-Item -ItemType Directory -Path $issue53OutputDirectory -Force | Out-Null

$issue53Plan = @(New-ContentionBaselinePlan -DurationSeconds $DurationSeconds -Repeats $Repeats -TotalSeats 2000)
$issue53Records = New-Object 'Collections.Generic.List[object]'
$issue53Skipped = New-Object 'Collections.Generic.List[object]'
$issue53StoppedScenarios = @{}
$issue53ScenarioCodes = @{
    'distributed' = 'dis'
    'hot-section' = 'sec'
    'hot-seat' = 'seat'
}

function Write-Issue53BatchFiles {
    $issue53Manifest = [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        DurationSeconds = $DurationSeconds
        Repeats = $Repeats
        PreAllocatedVus = $PreAllocatedVus
        MaxVus = $MaxVus
        WarmupExcluded = $true
        Records = $issue53Records.ToArray()
        Skipped = $issue53Skipped.ToArray()
        StoppedScenarios = $issue53StoppedScenarios
    }
    $issue53Manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $issue53ManifestPath -Encoding UTF8
    $issue53MeasuredRecords = @($issue53Records.ToArray() | Where-Object { -not $_.Warmup })
    if ($issue53MeasuredRecords.Count -gt 0) {
        New-ContentionBaselineAggregate -Records $issue53MeasuredRecords |
            ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath $issue53AggregatePath -Encoding UTF8
    }
}

try {
    foreach ($issue53Stage in $issue53Plan) {
        if (-not $issue53Stage.Warmup -and $issue53StoppedScenarios.ContainsKey($issue53Stage.Scenario)) {
            $issue53Skipped.Add([pscustomobject]@{
                Scenario = $issue53Stage.Scenario
                Rate = $issue53Stage.Rate
                Repeat = $issue53Stage.Repeat
                Reason = $issue53StoppedScenarios[$issue53Stage.Scenario]
            })
            continue
        }

        $issue53RepeatLabel = if ($issue53Stage.Warmup) { 'warm' } else { "r$($issue53Stage.Repeat)" }
        $issue53RunId = "$BatchId-$($issue53ScenarioCodes[$issue53Stage.Scenario])-$($issue53Stage.Rate)-$issue53RepeatLabel"
        Write-Output "BASELINE_RUN_START sequence=$($issue53Stage.Sequence) runId=$issue53RunId scenario=$($issue53Stage.Scenario) rate=$($issue53Stage.Rate) warmup=$($issue53Stage.Warmup)"

        & $issue53MeasureScript `
            -Scenario $issue53Stage.Scenario `
            -Rate $issue53Stage.Rate `
            -DurationSeconds $DurationSeconds `
            -SampleIntervalMilliseconds $SampleIntervalMilliseconds `
            -PreAllocatedVus $PreAllocatedVus `
            -MaxVus $MaxVus `
            -RunId $issue53RunId `
            -BaseUrl $BaseUrl `
            -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $issue53OutputDirectory `
            -DatabaseUser $DatabaseUser `
            -DatabasePassword $DatabasePassword `
            -DisablePerformanceThresholds

        $issue53SummaryPath = Join-Path $issue53OutputDirectory "$issue53RunId-summary.json"
        $issue53Summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $issue53SummaryPath | ConvertFrom-Json
        if (-not [bool]$issue53Summary.ValidMeasurement -or -not [bool]$issue53Summary.K6.InventoryInvariantSatisfied) {
            throw "Baseline run is not valid: $issue53RunId"
        }
        $issue53Records.Add([pscustomobject]@{
            RunId = $issue53RunId
            Scenario = $issue53Stage.Scenario
            Rate = $issue53Stage.Rate
            Repeat = $issue53Stage.Repeat
            Warmup = $issue53Stage.Warmup
            SummaryFile = [IO.Path]::GetFileName($issue53SummaryPath)
            Summary = $issue53Summary
        })
        Write-Issue53BatchFiles
        Write-Output "BASELINE_RUN_COMPLETE runId=$issue53RunId p95=$($issue53Summary.K6.Result.ReservationDurationMs.P95) dropped=$($issue53Summary.K6.Result.DroppedIterations)"

        if (-not $issue53Stage.Warmup -and $issue53Stage.Repeat -eq $Repeats) {
            $issue53StageRecords = @(
                $issue53Records.ToArray() |
                    Where-Object {
                        -not $_.Warmup -and
                        $_.Scenario -eq $issue53Stage.Scenario -and
                        $_.Rate -eq $issue53Stage.Rate
                    }
            )
            $issue53ReasonCounts = @{}
            foreach ($issue53StageRecord in $issue53StageRecords) {
                foreach ($issue53Reason in @(Get-ContentionBaselineStopReasons -Summary $issue53StageRecord.Summary)) {
                    if (-not $issue53ReasonCounts.ContainsKey($issue53Reason)) {
                        $issue53ReasonCounts[$issue53Reason] = 0
                    }
                    $issue53ReasonCounts[$issue53Reason] += 1
                }
            }
            $issue53RepeatedReasons = @(
                $issue53ReasonCounts.Keys |
                    Where-Object { $issue53ReasonCounts[$_] -ge 2 } |
                    Sort-Object
            )
            if ($issue53RepeatedReasons.Count -gt 0) {
                $issue53StoppedScenarios[$issue53Stage.Scenario] = $issue53RepeatedReasons -join ','
                Write-Output "BASELINE_SCENARIO_STOP scenario=$($issue53Stage.Scenario) rate=$($issue53Stage.Rate) reasons=$($issue53StoppedScenarios[$issue53Stage.Scenario])"
            }
        }
    }
    Write-Issue53BatchFiles
    Write-Output "BASELINE_BATCH_COMPLETE batchId=$BatchId records=$($issue53Records.Count) skipped=$($issue53Skipped.Count)"
    Write-Output "BASELINE_AGGREGATE_PATH $issue53AggregatePath"
} catch {
    Write-Issue53BatchFiles
    throw
}
