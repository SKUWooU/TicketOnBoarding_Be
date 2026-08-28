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

Export-ModuleMember -Function @(
    'ConvertFrom-PrometheusHikari',
    'ConvertFrom-MariaDbStatus',
    'Assert-ContentionRunId',
    'New-ContentionMetricsSummary'
)
