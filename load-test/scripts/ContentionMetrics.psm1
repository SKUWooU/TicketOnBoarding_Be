Set-StrictMode -Version Latest

function ConvertFrom-PrometheusHikari {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $issue51MetricNames = @(
        'hikaricp_connections_active',
        'hikaricp_connections_pending',
        'hikaricp_connections_idle',
        'hikaricp_connections_max'
    )
    $issue51Totals = @{}
    $issue51Counts = @{}
    foreach ($issue51MetricName in $issue51MetricNames) {
        $issue51Totals[$issue51MetricName] = 0.0
        $issue51Counts[$issue51MetricName] = 0
    }

    foreach ($issue51Line in ($Text -split "`r?`n")) {
        foreach ($issue51MetricName in $issue51MetricNames) {
            $issue51EscapedName = [regex]::Escape($issue51MetricName)
            if ($issue51Line -match "^$issue51EscapedName(?:\{[^}]*\})?\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$") {
                $issue51Value = [double]::Parse(
                    $Matches[1],
                    [Globalization.CultureInfo]::InvariantCulture
                )
                $issue51Totals[$issue51MetricName] += $issue51Value
                $issue51Counts[$issue51MetricName] += 1
            }
        }
    }

    foreach ($issue51MetricName in $issue51MetricNames) {
        if ($issue51Counts[$issue51MetricName] -eq 0) {
            throw "Required Hikari metric is missing: $issue51MetricName"
        }
    }

    [pscustomobject]@{
        Active  = $issue51Totals['hikaricp_connections_active']
        Pending = $issue51Totals['hikaricp_connections_pending']
        Idle    = $issue51Totals['hikaricp_connections_idle']
        Max     = $issue51Totals['hikaricp_connections_max']
    }
}

function ConvertFrom-MariaDbStatus {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    $issue51RequiredNames = @(
        'Innodb_deadlocks',
        'Innodb_row_lock_current_waits',
        'Innodb_row_lock_time',
        'Innodb_row_lock_waits',
        'Threads_connected',
        'Threads_running'
    )
    $issue51Values = @{}
    foreach ($issue51Line in (($Lines -join "`n") -split "`r?`n")) {
        if ($issue51Line -match '^([A-Za-z0-9_]+)\s+([0-9]+)\s*$') {
            $issue51Values[$Matches[1]] = [long]$Matches[2]
        }
    }

    foreach ($issue51RequiredName in $issue51RequiredNames) {
        if (-not $issue51Values.ContainsKey($issue51RequiredName)) {
            throw "Required MariaDB status is missing: $issue51RequiredName"
        }
    }

    [pscustomobject]@{
        Deadlocks           = $issue51Values['Innodb_deadlocks']
        RowLockCurrentWaits = $issue51Values['Innodb_row_lock_current_waits']
        RowLockTimeMs       = $issue51Values['Innodb_row_lock_time']
        RowLockWaits        = $issue51Values['Innodb_row_lock_waits']
        ThreadsConnected    = $issue51Values['Threads_connected']
        ThreadsRunning      = $issue51Values['Threads_running']
    }
}

function Assert-ContentionRunId {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunId
    )

    if ($RunId -notmatch '^[A-Za-z0-9-]{1,32}$') {
        throw 'RUN_ID must contain 1-32 letters, numbers, or hyphens.'
    }
    $RunId
}

function New-ContentionMetricsSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Samples
    )

    if ($Samples.Count -lt 2) {
        throw 'At least two metric samples are required.'
    }

    $issue51First = $Samples[0]
    $issue51Last = $Samples[$Samples.Count - 1]

    $issue51Intervals = New-Object 'Collections.Generic.List[long]'
    for ($issue51Index = 1; $issue51Index -lt $Samples.Count; $issue51Index += 1) {
        foreach ($issue51CounterName in @('DbRowLockWaits', 'DbRowLockTimeMs', 'DbDeadlocks')) {
            $issue51PreviousCounter = [long]$Samples[$issue51Index - 1].$issue51CounterName
            $issue51CurrentCounter = [long]$Samples[$issue51Index].$issue51CounterName
            if ($issue51CurrentCounter -lt $issue51PreviousCounter) {
                throw "MariaDB counter decreased between metric samples: $issue51CounterName"
            }
        }

        $issue51Interval = [long]$Samples[$issue51Index].ElapsedMilliseconds - [long]$Samples[$issue51Index - 1].ElapsedMilliseconds
        if ($issue51Interval -le 0) {
            throw 'Metric sample timestamps must increase.'
        }
        $issue51Intervals.Add($issue51Interval)
    }

    [pscustomobject]@{
        SampleCount = $Samples.Count
        Sampling = [pscustomobject]@{
            MinimumIntervalMs = [long](($issue51Intervals | Measure-Object -Minimum).Minimum)
            AverageIntervalMs = [double](($issue51Intervals | Measure-Object -Average).Average)
            MaximumIntervalMs = [long](($issue51Intervals | Measure-Object -Maximum).Maximum)
        }
        Peaks = [pscustomobject]@{
            HikariActive         = [double](($Samples | Measure-Object -Property HikariActive -Maximum).Maximum)
            HikariPending        = [double](($Samples | Measure-Object -Property HikariPending -Maximum).Maximum)
            HikariIdle           = [double](($Samples | Measure-Object -Property HikariIdle -Maximum).Maximum)
            HikariMax            = [double](($Samples | Measure-Object -Property HikariMax -Maximum).Maximum)
            DbRowLockCurrentWaits = [long](($Samples | Measure-Object -Property DbRowLockCurrentWaits -Maximum).Maximum)
            DbThreadsConnected   = [long](($Samples | Measure-Object -Property DbThreadsConnected -Maximum).Maximum)
            DbThreadsRunning     = [long](($Samples | Measure-Object -Property DbThreadsRunning -Maximum).Maximum)
        }
        Deltas = [pscustomobject]@{
            DbRowLockWaits  = [long]$issue51Last.DbRowLockWaits - [long]$issue51First.DbRowLockWaits
            DbRowLockTimeMs = [long]$issue51Last.DbRowLockTimeMs - [long]$issue51First.DbRowLockTimeMs
            DbDeadlocks     = [long]$issue51Last.DbDeadlocks - [long]$issue51First.DbDeadlocks
        }
        ObserverEffects = [pscustomobject]@{
            DbCliConnectionIncludedInThreadGauges = $true
            ConnectionsCounterExcluded            = $true
            ComposeHealthcheckMayOpenConnections   = $true
        }
    }
}

function ConvertFrom-K6ContentionResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $issue53Prefix = 'LOADTEST_RESULT '
    $issue53ResultLines = @(
        ($Text -split "`r?`n") |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.StartsWith($issue53Prefix, [StringComparison]::Ordinal) }
    )
    if ($issue53ResultLines.Count -ne 1) {
        throw "Expected exactly one structured k6 result, found $($issue53ResultLines.Count)."
    }

    try {
        $issue53Result = $issue53ResultLines[0].Substring($issue53Prefix.Length) | ConvertFrom-Json
    } catch {
        throw "Structured k6 result is not valid JSON: $($_.Exception.Message)"
    }

    foreach ($issue53RequiredProperty in @(
        'schemaVersion',
        'scenario',
        'targetRatePerSecond',
        'duration',
        'thresholdsEnforced',
        'iterations',
        'droppedIterations',
        'reservationSuccess',
        'expectedContention',
        'unexpectedNonSuccessful',
        'unexpectedFailureRate',
        'reservationDurationMs',
        'maxObservedVus',
        'maxAllocatedVus',
        'preAllocatedVus',
        'configuredMaxVus'
    )) {
        if ($issue53RequiredProperty -notin $issue53Result.PSObject.Properties.Name) {
            throw "Structured k6 result is missing: $issue53RequiredProperty"
        }
    }
    foreach ($issue53DurationProperty in @('average', 'median', 'p95', 'maximum')) {
        if ($issue53DurationProperty -notin $issue53Result.reservationDurationMs.PSObject.Properties.Name) {
            throw "Structured k6 duration is missing: $issue53DurationProperty"
        }
    }
    if ([int]$issue53Result.schemaVersion -ne 1) {
        throw "Unsupported structured k6 result schema: $($issue53Result.schemaVersion)"
    }

    $issue53Result
}

function ConvertFrom-K6FinalSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $issue53SnapshotMatches = [regex]::Matches(
        $Text,
        'LOADTEST_FINAL_SNAPSHOT\s+(\{[^{}]*\})'
    )
    if ($issue53SnapshotMatches.Count -ne 1) {
        throw "Expected exactly one final inventory snapshot, found $($issue53SnapshotMatches.Count)."
    }
    try {
        $issue53Snapshot = $issue53SnapshotMatches[0].Groups[1].Value | ConvertFrom-Json
    } catch {
        throw "Final inventory snapshot is not valid JSON: $($_.Exception.Message)"
    }
    foreach ($issue53RequiredProperty in @(
        'expectedTotalSeats',
        'actualSeatCount',
        'remainingSeats',
        'reservedSeats',
        'reservations',
        'bookings',
        'payments',
        'invariantSatisfied'
    )) {
        if ($issue53RequiredProperty -notin $issue53Snapshot.PSObject.Properties.Name) {
            throw "Final inventory snapshot is missing: $issue53RequiredProperty"
        }
    }

    $issue53Snapshot
}

function New-K6ContentionRunSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Result,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 3600)]
        [int]$DurationSeconds
    )

    $issue53Iterations = [long]$Result.iterations
    $issue53Dropped = [long]$Result.droppedIterations
    $issue53Success = [long]$Result.reservationSuccess
    $issue53ExpectedContention = [long]$Result.expectedContention
    $issue53Unexpected = [long]$Result.unexpectedNonSuccessful
    foreach ($issue53NonNegative in @(
        $issue53Iterations,
        $issue53Dropped,
        $issue53Success,
        $issue53ExpectedContention,
        $issue53Unexpected
    )) {
        if ($issue53NonNegative -lt 0) {
            throw 'Structured k6 counters must not be negative.'
        }
    }
    if ($issue53Iterations -le 0) {
        throw 'Structured k6 result must contain at least one completed iteration.'
    }
    if ($issue53Iterations -ne ($issue53Success + $issue53ExpectedContention + $issue53Unexpected)) {
        throw 'Structured k6 reservation counters do not match completed iterations.'
    }

    $issue53Scheduled = $issue53Iterations + $issue53Dropped
    [pscustomobject]@{
        Scenario = [string]$Result.scenario
        TargetRatePerSecond = [int]$Result.targetRatePerSecond
        DurationSeconds = $DurationSeconds
        ThresholdsEnforced = [bool]$Result.thresholdsEnforced
        Iterations = $issue53Iterations
        DroppedIterations = $issue53Dropped
        ScheduledIterationAttainmentRate = if ($issue53Scheduled -eq 0) { 0.0 } else { [double]$issue53Iterations / $issue53Scheduled }
        CompletedIterationsPerScheduledSecond = [double]$issue53Iterations / $DurationSeconds
        ReservationSuccess = $issue53Success
        ExpectedContention = $issue53ExpectedContention
        UnexpectedNonSuccessful = $issue53Unexpected
        UnexpectedFailureRate = [double]$Result.unexpectedFailureRate
        ReservationDurationMs = [pscustomobject]@{
            Average = [double]$Result.reservationDurationMs.average
            Median = [double]$Result.reservationDurationMs.median
            P95 = [double]$Result.reservationDurationMs.p95
            Maximum = [double]$Result.reservationDurationMs.maximum
        }
        MaxObservedVus = [int]$Result.maxObservedVus
        MaxAllocatedVus = [int]$Result.maxAllocatedVus
        PreAllocatedVus = [int]$Result.preAllocatedVus
        ConfiguredMaxVus = [int]$Result.configuredMaxVus
    }
}

function Assert-K6ContentionRunIdentity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Result,

        [Parameter(Mandatory = $true)]
        [string]$Scenario,

        [Parameter(Mandatory = $true)]
        [int]$Rate,

        [Parameter(Mandatory = $true)]
        [int]$DurationSeconds,

        [Parameter(Mandatory = $true)]
        [bool]$ThresholdsEnforced
    )

    $issue53ExpectedDuration = "$($DurationSeconds)s"
    if ([string]$Result.scenario -ne $Scenario) {
        throw "Structured k6 scenario does not match the requested scenario: expected=$Scenario actual=$($Result.scenario)"
    }
    if ([int]$Result.targetRatePerSecond -ne $Rate) {
        throw "Structured k6 target rate does not match the requested rate: expected=$Rate actual=$($Result.targetRatePerSecond)"
    }
    if ([string]$Result.duration -ne $issue53ExpectedDuration) {
        throw "Structured k6 duration does not match the requested duration: expected=$issue53ExpectedDuration actual=$($Result.duration)"
    }
    if ([bool]$Result.thresholdsEnforced -ne $ThresholdsEnforced) {
        throw "Structured k6 threshold mode does not match the requested mode: expected=$ThresholdsEnforced actual=$($Result.thresholdsEnforced)"
    }

    $true
}

Export-ModuleMember -Function @(
    'ConvertFrom-PrometheusHikari',
    'ConvertFrom-MariaDbStatus',
    'Assert-ContentionRunId',
    'New-ContentionMetricsSummary',
    'ConvertFrom-K6ContentionResult',
    'ConvertFrom-K6FinalSnapshot',
    'New-K6ContentionRunSummary',
    'Assert-K6ContentionRunIdentity'
)
