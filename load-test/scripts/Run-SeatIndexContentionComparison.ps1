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

$issue57ScriptDirectory = $PSScriptRoot
$issue57RepositoryRoot = (Resolve-Path (Join-Path $issue57ScriptDirectory '..\..')).Path
$issue57ComparisonModule = Join-Path $issue57ScriptDirectory 'SeatIndexContentionComparison.psm1'
$issue57IndexModule = Join-Path $issue57ScriptDirectory 'SeatIndexExperiment.psm1'
$issue57MeasureScript = Join-Path $issue57ScriptDirectory 'Measure-Contention.ps1'
$issue57ComposeFile = Join-Path $issue57RepositoryRoot 'compose.yml'
Import-Module $issue57ComparisonModule -Force
Import-Module $issue57IndexModule -Force

if ($PreAllocatedVus -gt $MaxVus) {
    throw 'PreAllocatedVus must not exceed MaxVus.'
}
if ([string]::IsNullOrWhiteSpace($BatchId)) {
    $BatchId = 'ab57-' + (Get-Date).ToUniversalTime().ToString('MMddHHmm')
}
if ($BatchId -notmatch '^[A-Za-z0-9-]{1,16}$') {
    throw 'BatchId must contain 1-16 letters, numbers, or hyphens.'
}

$issue57OutputDirectory = Join-Path $issue57RepositoryRoot "load-test\results\$BatchId"
$issue57ManifestPath = Join-Path $issue57OutputDirectory 'seat-index-ab-manifest.json'
$issue57AggregatePath = Join-Path $issue57OutputDirectory 'seat-index-ab-aggregate.json'
if (Test-Path -LiteralPath $issue57OutputDirectory) {
    throw "Refusing to reuse an existing A/B batch: $issue57OutputDirectory"
}
New-Item -ItemType Directory -Path $issue57OutputDirectory -Force | Out-Null

$issue57Plan = @(New-SeatIndexContentionComparisonPlan -DurationSeconds $DurationSeconds -Repeats $Repeats -TotalSeats 2000)
$issue57Records = New-Object 'Collections.Generic.List[object]'
$issue57SchemaRestored = $false
$issue57FinalCleanupCompleted = $false
$issue57AllRunsCompleted = $false
$issue57BatchCompleted = $false
$issue57ValidBatch = $false

function Invoke-Issue57RunnerRootQuery {
    param([Parameter(Mandatory = $true)][string]$Query)

    $issue57Output = & docker compose -f $issue57ComposeFile exec -T mariadb mariadb `
        "-u$DatabaseRootUser" `
        "-p$DatabaseRootPassword" `
        -N `
        -B `
        $DatabaseName `
        -e $Query 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB seat index A/B query failed with exit code $LASTEXITCODE."
    }
    @($issue57Output)
}

$issue57RootQueryExecutor = {
    param([string]$Query)
    Invoke-Issue57RunnerRootQuery -Query $Query
}

function Test-Issue57BalancedRecords {
    $issue57Measured = @($issue57Records.ToArray() | Where-Object { -not $_.Warmup })
    if ($issue57Measured.Count -eq 0) {
        return $false
    }
    foreach ($issue57Rate in @($issue57Measured.Rate | Sort-Object -Unique)) {
        $issue57CurrentCount = @($issue57Measured | Where-Object { $_.Rate -eq $issue57Rate -and $_.Variant -eq 'current' }).Count
        $issue57CompositeCount = @($issue57Measured | Where-Object { $_.Rate -eq $issue57Rate -and $_.Variant -eq 'composite' }).Count
        if ($issue57CurrentCount -eq 0 -or $issue57CurrentCount -ne $issue57CompositeCount) {
            return $false
        }
    }
    $true
}

function Write-Issue57ComparisonFiles {
    $issue57Manifest = [ordered]@{
        SchemaVersion = 1
        BatchId = $BatchId
        Scenario = 'distributed'
        DurationSeconds = $DurationSeconds
        Repeats = $Repeats
        PreAllocatedVus = $PreAllocatedVus
        MaxVus = $MaxVus
        PerformanceSchemaRequired = $true
        WarmupExcluded = $true
        VariantOrderAlternated = $true
        ExpectedRecordCount = $issue57Plan.Count
        BatchCompleted = $issue57BatchCompleted
        ValidBatch = $issue57ValidBatch
        CurrentSchemaRestored = $issue57SchemaRestored
        FinalCleanupCompleted = $issue57FinalCleanupCompleted
        Records = $issue57Records.ToArray()
    }
    $issue57Manifest | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $issue57ManifestPath -Encoding UTF8
    if ($issue57ValidBatch -and (Test-Issue57BalancedRecords)) {
        New-SeatIndexContentionComparisonAggregate -Records $issue57Records.ToArray() |
            ConvertTo-Json -Depth 16 |
            Set-Content -LiteralPath $issue57AggregatePath -Encoding UTF8
    }
}

try {
    foreach ($issue57Stage in $issue57Plan) {
        $issue57RepeatLabel = if ($issue57Stage.Warmup) { 'warm' } else { "r$($issue57Stage.Repeat)" }
        $issue57VariantLabel = if ($issue57Stage.Variant -eq 'current') { 'cur' } else { 'idx' }
        $issue57RunId = "$BatchId-$issue57VariantLabel-$($issue57Stage.Rate)-$issue57RepeatLabel"
        Write-Output "SEAT_INDEX_AB_RUN_START sequence=$($issue57Stage.Sequence) runId=$issue57RunId variant=$($issue57Stage.Variant) rate=$($issue57Stage.Rate) warmup=$($issue57Stage.Warmup)"

        $issue57CleanupStopwatch = [Diagnostics.Stopwatch]::StartNew()
        $issue57Cleanup = Clear-Issue57LoadTestFixtures -QueryExecutor $issue57RootQueryExecutor
        $issue57CleanupStopwatch.Stop()
        $issue57Cleanup | Add-Member -NotePropertyName DurationMilliseconds -NotePropertyValue $issue57CleanupStopwatch.ElapsedMilliseconds
        $issue57Cleanup | Add-Member -NotePropertyName ExcludedFromMetricSamples -NotePropertyValue $true

        & $issue57MeasureScript `
            -Scenario distributed `
            -Rate $issue57Stage.Rate `
            -DurationSeconds $DurationSeconds `
            -SampleIntervalMilliseconds $SampleIntervalMilliseconds `
            -PreAllocatedVus $PreAllocatedVus `
            -MaxVus $MaxVus `
            -RunId $issue57RunId `
            -BaseUrl $BaseUrl `
            -ManagementBaseUrl $ManagementBaseUrl `
            -OutputDirectory $issue57OutputDirectory `
            -DatabaseName $DatabaseName `
            -DatabaseUser $DatabaseUser `
            -DatabasePassword $DatabasePassword `
            -DatabaseRootUser $DatabaseRootUser `
            -DatabaseRootPassword $DatabaseRootPassword `
            -SeatIndexVariant $issue57Stage.Variant `
            -CollectStatementDigests `
            -DisablePerformanceThresholds

        $issue57SummaryPath = Join-Path $issue57OutputDirectory "$issue57RunId-summary.json"
        $issue57Summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $issue57SummaryPath | ConvertFrom-Json
        if (-not [bool]$issue57Summary.ValidMeasurement -or
            -not [bool]$issue57Summary.K6.InventoryInvariantSatisfied -or
            $null -eq $issue57Summary.DatabaseStatementDigests -or
            $null -eq $issue57Summary.SeatIndexExperiment -or
            $issue57Summary.SeatIndexExperiment.Variant -ne $issue57Stage.Variant) {
            throw "Seat index A/B run is not valid: $issue57RunId"
        }
        $issue57Records.Add([pscustomobject]@{
            Sequence = $issue57Stage.Sequence
            RunId = $issue57RunId
            Rate = $issue57Stage.Rate
            Repeat = $issue57Stage.Repeat
            Warmup = $issue57Stage.Warmup
            Variant = $issue57Stage.Variant
            FixtureIsolation = $issue57Cleanup
            SummaryFile = [IO.Path]::GetFileName($issue57SummaryPath)
            Summary = $issue57Summary
        })
        Write-Issue57ComparisonFiles
        Write-Output "SEAT_INDEX_AB_RUN_COMPLETE runId=$issue57RunId variant=$($issue57Stage.Variant) p95=$($issue57Summary.K6.Result.ReservationDurationMs.P95) seatAvgMs=$($issue57Summary.DatabaseStatementDigests.SeatLockSelect.AverageMilliseconds)"
    }
    $issue57AllRunsCompleted = $true
} finally {
    try {
        Set-Issue57SeatIndexVariant `
            -Variant current `
            -DatabaseName $DatabaseName `
            -QueryExecutor $issue57RootQueryExecutor | Out-Null
        $issue57SchemaRestored = $true
    } finally {
        try {
            Clear-Issue57LoadTestFixtures -QueryExecutor $issue57RootQueryExecutor | Out-Null
            $issue57FinalCleanupCompleted = $true
        } finally {
            $issue57BatchCompleted = $issue57AllRunsCompleted -and (Test-SeatIndexContentionBatchComplete -Records $issue57Records.ToArray() -Plan $issue57Plan)
            $issue57ValidBatch = $issue57BatchCompleted -and $issue57SchemaRestored -and $issue57FinalCleanupCompleted
            Write-Issue57ComparisonFiles
        }
    }
}

Write-Output "SEAT_INDEX_AB_BATCH_COMPLETE batchId=$BatchId records=$($issue57Records.Count)"
Write-Output "SEAT_INDEX_AB_AGGREGATE_PATH $issue57AggregatePath"
