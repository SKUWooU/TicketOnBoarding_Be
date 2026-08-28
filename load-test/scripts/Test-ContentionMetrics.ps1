[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$issue51ModulePath = Join-Path $PSScriptRoot 'ContentionMetrics.psm1'
Import-Module $issue51ModulePath -Force
$issue51Assertions = 0

function Assert-Issue51Equal {
    param(
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:issue51Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue51Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:issue51Assertions += 1
    $issue51Thrown = $false
    try {
        & $Action
    } catch {
        $issue51Thrown = $true
    }
    if (-not $issue51Thrown) {
        throw $Message
    }
}

$issue51PrometheusFixture = @'
# TYPE hikaricp_connections_active gauge
hikaricp_connections_active{application="onticket-loadtest",pool="HikariPool-1",} 2.0
hikaricp_connections_active{application="onticket-loadtest",pool="HikariPool-2",} 1.0
hikaricp_connections_pending{application="onticket-loadtest",pool="HikariPool-1",} 1.0
hikaricp_connections_pending{application="onticket-loadtest",pool="HikariPool-2",} 0.0
hikaricp_connections_idle{application="onticket-loadtest",pool="HikariPool-1",} 7.0
hikaricp_connections_idle{application="onticket-loadtest",pool="HikariPool-2",} 4.0
hikaricp_connections_max{application="onticket-loadtest",pool="HikariPool-1",} 10.0
hikaricp_connections_max{application="onticket-loadtest",pool="HikariPool-2",} 5.0
'@
$issue51Hikari = ConvertFrom-PrometheusHikari -Text $issue51PrometheusFixture
Assert-Issue51Equal $issue51Hikari.Active 3.0 'Hikari active values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Pending 1.0 'Hikari pending values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Idle 11.0 'Hikari idle values must be summed across pools.'
Assert-Issue51Equal $issue51Hikari.Max 15.0 'Hikari max values must be summed across pools.'
Assert-Issue51Throws { ConvertFrom-PrometheusHikari -Text 'hikaricp_connections_active 1.0' } 'Missing Hikari metrics must fail parsing.'

$issue51BeforeDb = ConvertFrom-MariaDbStatus -Lines @'
Innodb_deadlocks	2
Innodb_row_lock_current_waits	0
Innodb_row_lock_time	100
Innodb_row_lock_waits	4
Threads_connected	11
Threads_running	1
'@
$issue51AfterDb = ConvertFrom-MariaDbStatus -Lines @'
Innodb_deadlocks 3
Innodb_row_lock_current_waits 2
Innodb_row_lock_time 370
Innodb_row_lock_waits 9
Threads_connected 12
Threads_running 5
'@
Assert-Issue51Equal $issue51BeforeDb.RowLockWaits 4 'MariaDB row lock waits must be parsed.'
Assert-Issue51Equal $issue51AfterDb.RowLockTimeMs 370 'MariaDB row lock time must be parsed.'
Assert-Issue51Throws { ConvertFrom-MariaDbStatus -Lines 'Threads_running 1' } 'Missing MariaDB status must fail parsing.'

$issue51Samples = @(
    [pscustomobject]@{
        ElapsedMilliseconds = 0
        HikariActive = 0; HikariPending = 0; HikariIdle = 10; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 4; DbRowLockTimeMs = 100
        DbDeadlocks = 2; DbThreadsConnected = 11; DbThreadsRunning = 1
    },
    [pscustomobject]@{
        ElapsedMilliseconds = 1000
        HikariActive = 8; HikariPending = 3; HikariIdle = 2; HikariMax = 10
        DbRowLockCurrentWaits = 2; DbRowLockWaits = 7; DbRowLockTimeMs = 220
        DbDeadlocks = 2; DbThreadsConnected = 12; DbThreadsRunning = 5
    },
    [pscustomobject]@{
        ElapsedMilliseconds = 2200
        HikariActive = 1; HikariPending = 0; HikariIdle = 9; HikariMax = 10
        DbRowLockCurrentWaits = 0; DbRowLockWaits = 9; DbRowLockTimeMs = 370
        DbDeadlocks = 3; DbThreadsConnected = 11; DbThreadsRunning = 1
    }
)
$issue51Summary = New-ContentionMetricsSummary -Samples $issue51Samples
Assert-Issue51Equal $issue51Summary.SampleCount 3 'All samples must be counted.'
Assert-Issue51Equal $issue51Summary.Sampling.MinimumIntervalMs 1000 'Minimum sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Sampling.AverageIntervalMs 1100 'Average sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Sampling.MaximumIntervalMs 1200 'Maximum sample interval must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.HikariActive 8.0 'Hikari active peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.HikariPending 3.0 'Hikari pending peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Peaks.DbRowLockCurrentWaits 2 'DB current wait peak must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbRowLockWaits 5 'DB row lock wait delta must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbRowLockTimeMs 270 'DB row lock time delta must be calculated.'
Assert-Issue51Equal $issue51Summary.Deltas.DbDeadlocks 1 'DB deadlock delta must be calculated.'
Assert-Issue51Equal $issue51Summary.ObserverEffects.ConnectionsCounterExcluded $true 'Connections counter exclusion must be explicit.'

Assert-Issue51Equal (Assert-ContentionRunId -RunId 'issue51-smoke-01') 'issue51-smoke-01' 'Valid run IDs must be preserved.'
Assert-Issue51Throws { Assert-ContentionRunId -RunId 'invalid run id' } 'Invalid run IDs must fail.'

$issue51CounterResetSamples = @($issue51Samples[2], $issue51Samples[0])
Assert-Issue51Throws { New-ContentionMetricsSummary -Samples $issue51CounterResetSamples } 'Counter resets must invalidate the measurement.'

Write-Output "ContentionMetrics checks passed: $issue51Assertions assertions."
