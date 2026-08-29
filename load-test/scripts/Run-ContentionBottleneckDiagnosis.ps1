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
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$DatabaseName = 'onticket_local',
    [string]$DatabaseUser = 'onticket',
    [string]$DatabasePassword = 'onticket',
    [string]$DatabaseRootUser = 'root',
    [string]$DatabaseRootPassword = 'onticket-root'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue55ScriptDirectory = $PSScriptRoot
$issue55RepositoryRoot = (Resolve-Path (Join-Path $issue55ScriptDirectory '..\..')).Path
$issue55ModulePath = Join-Path $issue55ScriptDirectory 'ContentionBottleneckDiagnosis.psm1'
$issue55MeasureScript = Join-Path $issue55ScriptDirectory 'Measure-Contention.ps1'
Import-Module $issue55ModulePath -Force

if ($PreAllocatedVus -gt $MaxVus) {
    throw 'PreAllocatedVus must not exceed MaxVus.'
}
if ([string]::IsNullOrWhiteSpace($BatchId)) {
    $BatchId = 'd55-' + (Get-Date).ToUniversalTime().ToString('MMddHHmmss')
}
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,16}$') {
    throw 'BatchId must contain 1-16 letters, numbers, or hyphens.'
}

$issue55OutputDirectory = Join-Path $issue55RepositoryRoot "load-test\results\$BatchId"
$issue55ManifestPath = Join-Path $issue55OutputDirectory 'diagnosis-manifest.json'
$issue55AggregatePath = Join-Path $issue55OutputDirectory 'diagnosis-aggregate.json'
if (Test-Path -LiteralPath $issue55OutputDirectory) {
    throw "Refusing to reuse an existing diagnosis batch: $issue55OutputDirectory"
}
New-Item -ItemType Directory -Path $issue55OutputDirectory -Force | Out-Null

$issue55Plan = @(New-ContentionBottleneckDiagnosisPlan -DurationSeconds $DurationSeconds -Repeats $Repeats -TotalSeats 2000)
$issue55Records = New-Object 'Collections.Generic.List[object]'

function Write-Issue55DiagnosisFiles {
    $issue55Manifest = [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        Scenario = 'distributed'
        DurationSeconds = $DurationSeconds
        Repeats = $Repeats
        PreAllocatedVus = $PreAllocatedVus
        MaxVus = $MaxVus
        PerformanceSchemaRequired = $true
        WarmupExcluded = $true
        Records = $issue55Records.ToArray()
    }
    $issue55Manifest | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $issue55ManifestPath -Encoding UTF8
    $issue55MeasuredRecords = @($issue55Records.ToArray() | Where-Object { -not $_.Warmup })
    if ($issue55MeasuredRecords.Count -gt 0) {
        New-ContentionBottleneckDiagnosisAggregate -Records $issue55MeasuredRecords |
            ConvertTo-Json -Depth 14 |
            Set-Content -LiteralPath $issue55AggregatePath -Encoding UTF8
    }
}

try {
    foreach ($issue55Stage in $issue55Plan) {
        $issue55RepeatLabel = if ($issue55Stage.Warmup) { 'warm' } else { "r$($issue55Stage.Repeat)" }
        $issue55RunId = "$BatchId-dis-$($issue55Stage.Rate)-$issue55RepeatLabel"
        Write-Output "DIAGNOSIS_RUN_START sequence=$($issue55Stage.Sequence) runId=$issue55RunId rate=$($issue55Stage.Rate) warmup=$($issue55Stage.Warmup)"

        & $issue55MeasureScript `
            -Scenario distributed `
            -Rate $issue55Stage.Rate `
            -DurationSeconds $DurationSeconds `
            -SampleIntervalMilliseconds $SampleIntervalMilliseconds `
            -PreAllocatedVus $PreAllocatedVus `
            -MaxVus $MaxVus `
            -RunId $issue55RunId `
            -BaseUrl $BaseUrl `
            -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $issue55OutputDirectory `
            -DatabaseName $DatabaseName `
            -DatabaseUser $DatabaseUser `
            -DatabasePassword $DatabasePassword `
            -DatabaseRootUser $DatabaseRootUser `
            -DatabaseRootPassword $DatabaseRootPassword `
            -CollectStatementDigests `
            -DisablePerformanceThresholds

        $issue55SummaryPath = Join-Path $issue55OutputDirectory "$issue55RunId-summary.json"
        $issue55Summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $issue55SummaryPath | ConvertFrom-Json
        if (-not [bool]$issue55Summary.ValidMeasurement -or
            -not [bool]$issue55Summary.K6.InventoryInvariantSatisfied -or
            $null -eq $issue55Summary.DatabaseStatementDigests) {
            throw "Diagnosis run is not valid: $issue55RunId"
        }
        $issue55Records.Add([pscustomobject]@{
            RunId = $issue55RunId
            Rate = $issue55Stage.Rate
            Repeat = $issue55Stage.Repeat
            Warmup = $issue55Stage.Warmup
            SummaryFile = [IO.Path]::GetFileName($issue55SummaryPath)
            Summary = $issue55Summary
        })
        Write-Issue55DiagnosisFiles
        Write-Output "DIAGNOSIS_RUN_COMPLETE runId=$issue55RunId p95=$($issue55Summary.K6.Result.ReservationDurationMs.P95) counterAvgMs=$($issue55Summary.DatabaseStatementDigests.ConcertTimeDecrement.AverageMilliseconds)"
    }
    Write-Issue55DiagnosisFiles
    Write-Output "DIAGNOSIS_BATCH_COMPLETE batchId=$BatchId records=$($issue55Records.Count)"
    Write-Output "DIAGNOSIS_AGGREGATE_PATH $issue55AggregatePath"
} catch {
    Write-Issue55DiagnosisFiles
    throw
}
