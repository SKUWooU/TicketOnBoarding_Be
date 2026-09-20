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

function ConvertFrom-PrometheusHikariAcquireTiming {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $values = @{}
    foreach ($name in @('hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum', 'hikaricp_connections_timeout_total')) {
        $matches = @([regex]::Matches($Text, "(?m)^$([regex]::Escape($name))(?:\{[^}]*\})?\s+([0-9.eE+-]+)\s*$"))
        if ($matches.Count -ne 1) { throw "Required Hikari timing metric is missing or ambiguous: $name" }
        $values[$name] = [double]::Parse($matches[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    [pscustomobject]@{
        AcquireCount = [long]$values['hikaricp_connections_acquire_seconds_count']
        AcquireSeconds = [double]$values['hikaricp_connections_acquire_seconds_sum']
        TimeoutCount = [long]$values['hikaricp_connections_timeout_total']
    }
}

function New-HikariAcquireTimingDelta {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$Before, [Parameter(Mandatory = $true)][object]$After)

    foreach ($name in @('AcquireCount', 'AcquireSeconds', 'TimeoutCount')) {
        if ($After.$name -lt $Before.$name) { throw "Hikari acquire timing counter decreased: $name" }
    }
    $count = [long]$After.AcquireCount - [long]$Before.AcquireCount
    $seconds = [double]$After.AcquireSeconds - [double]$Before.AcquireSeconds
    [pscustomobject]@{
        AcquireCount = $count
        AcquireWaitMilliseconds = $seconds * 1000.0
        AverageAcquireWaitMilliseconds = if ($count -eq 0) { 0.0 } else { ($seconds * 1000.0) / $count }
        TimeoutCount = [long]$After.TimeoutCount - [long]$Before.TimeoutCount
    }
}

function ConvertFrom-PrometheusCheckoutHttpRequests {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $paths = @{
        '/main/detail/{concertId}/seat-holds' = 'seat_hold'
        '/main/detail/{concertId}/checkouts' = 'checkout_prepare'
        '/main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation' = 'checkout_verify'
    }
    $counts = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -notmatch '^http_server_requests_seconds_count\{(.+)\}\s+([0-9.eE+-]+)\s*$') { continue }
        $labels = @{}; foreach ($match in [regex]::Matches($Matches[1], '([A-Za-z_][A-Za-z0-9_]*)="([^"]*)"')) { $labels[$match.Groups[1].Value] = $match.Groups[2].Value }
        if ($labels.method -ne 'POST' -or -not $paths.ContainsKey($labels.uri)) { continue }
        $key = "$($paths[$labels.uri])|$($labels.status)"
        if ($counts.ContainsKey($key)) { throw "Ambiguous Checkout HTTP metric series: $key" }
        $counts[$key] = [double]::Parse($Matches[2], [Globalization.CultureInfo]::InvariantCulture)
    }
    $counts
}

function New-PrometheusCheckoutHttpRequestDelta {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][hashtable]$Before, [Parameter(Mandatory = $true)][hashtable]$After)

    $deltas = @{}
    foreach ($key in $Before.Keys) {
        if (-not $After.ContainsKey($key)) { throw "Checkout HTTP metric disappeared: $key" }
        if ($After[$key] -lt $Before[$key]) { throw "Checkout HTTP metric counter decreased: $key" }
    }
    foreach ($key in $After.Keys) { $beforeValue = if ($Before.ContainsKey($key)) { [double]$Before[$key] } else { 0.0 }; $deltas[$key] = [double]$After[$key] - $beforeValue }
    function Get-CheckoutHttpDelta([string]$Key) { if ($deltas.ContainsKey($Key)) { return [long]$deltas[$Key] }; return 0L }
    function Get-CheckoutHttpNonSuccess([string]$Endpoint) { $total = 0L; foreach ($item in $deltas.GetEnumerator()) { if ($item.Key -like "$Endpoint|*" -and $item.Key -ne "$Endpoint|200") { $total += [long]$item.Value } }; return $total }
    [pscustomobject]@{
        SeatHold = [pscustomobject]@{ Success = Get-CheckoutHttpDelta 'seat_hold|200'; NonSuccess = Get-CheckoutHttpNonSuccess 'seat_hold' }
        CheckoutPrepare = [pscustomobject]@{ Success = Get-CheckoutHttpDelta 'checkout_prepare|200'; NonSuccess = Get-CheckoutHttpNonSuccess 'checkout_prepare' }
        CheckoutVerify = [pscustomobject]@{ Success = Get-CheckoutHttpDelta 'checkout_verify|200'; NonSuccess = Get-CheckoutHttpNonSuccess 'checkout_verify' }
    }
}

function Assert-CheckoutHttpIterationAgreement {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$HttpRequests, [Parameter(Mandatory = $true)][object]$Result)
    $confirmed = [long]$Result.checkoutConfirmed
    foreach ($endpoint in @($HttpRequests.SeatHold, $HttpRequests.CheckoutPrepare, $HttpRequests.CheckoutVerify)) {
        if ([long]$endpoint.Success -ne $confirmed) { throw 'Checkout successful iteration count does not match the corresponding HTTP 200 delta.' }
    }
    $true
}

function ConvertFrom-MariaDbStatementDigestSnapshot {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string[]]$Lines)

    $rows = New-Object 'Collections.Generic.List[object]'
    foreach ($line in $Lines) {
        $parts = [string]$line -split "`t", 5
        if ($parts.Count -ne 5) { throw 'MariaDB statement digest row must contain five tab-separated fields.' }
        $rows.Add([pscustomobject]@{ Digest=$parts[0]; DigestText=$parts[1]; Count=[long]$parts[2]; TimerWaitPicoseconds=[double]$parts[3]; LockTimePicoseconds=[double]$parts[4] })
    }
    $rows.ToArray()
}

function New-MariaDbStatementDigestDelta {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Before, [Parameter(Mandatory = $true)][object[]]$After)

    $beforeByDigest = @{}
    foreach ($row in $Before) { $beforeByDigest[$row.Digest] = $row }
    $afterByDigest = @{}
    foreach ($row in $After) { $afterByDigest[$row.Digest] = $row }
    foreach ($beforeRow in $Before) {
        if (-not $afterByDigest.ContainsKey($beforeRow.Digest)) { throw "MariaDB statement digest disappeared between snapshots: $($beforeRow.Digest)" }
    }
    $deltas = New-Object 'Collections.Generic.List[object]'
    foreach ($afterRow in $After) {
        $beforeRow = $beforeByDigest[$afterRow.Digest]
        $beforeCount = if ($null -eq $beforeRow) { 0 } else { [long]$beforeRow.Count }
        $beforeWait = if ($null -eq $beforeRow) { 0.0 } else { [double]$beforeRow.TimerWaitPicoseconds }
        $beforeLock = if ($null -eq $beforeRow) { 0.0 } else { [double]$beforeRow.LockTimePicoseconds }
        if ($afterRow.Count -lt $beforeCount -or $afterRow.TimerWaitPicoseconds -lt $beforeWait -or $afterRow.LockTimePicoseconds -lt $beforeLock) { throw "MariaDB statement digest counter decreased: $($afterRow.Digest)" }
        $count = [long]$afterRow.Count - $beforeCount
        if ($count -gt 0) { $deltas.Add([pscustomobject]@{ Digest=$afterRow.Digest; DigestText=$afterRow.DigestText; Count=$count; ExecutionMilliseconds=([double]$afterRow.TimerWaitPicoseconds-$beforeWait)/1000000000.0; LockMilliseconds=([double]$afterRow.LockTimePicoseconds-$beforeLock)/1000000000.0 }) }
    }
    $items = @($deltas.ToArray())
    $observerItems = @($items | Where-Object { $_.DigestText -match '(?i)\bperformance_schema\b' })
    $businessItems = @($items | Where-Object { $_.DigestText -notmatch '(?i)\bperformance_schema\b' })
    $businessStatementCount = 0L; $businessExecutionMilliseconds = 0.0; $businessLockMilliseconds = 0.0
    foreach ($item in $businessItems) { $businessStatementCount += [long]$item.Count; $businessExecutionMilliseconds += [double]$item.ExecutionMilliseconds; $businessLockMilliseconds += [double]$item.LockMilliseconds }
    $observerStatementCount = 0L; $observerExecutionMilliseconds = 0.0; $observerLockMilliseconds = 0.0
    foreach ($item in $observerItems) { $observerStatementCount += [long]$item.Count; $observerExecutionMilliseconds += [double]$item.ExecutionMilliseconds; $observerLockMilliseconds += [double]$item.LockMilliseconds }
    [pscustomobject]@{
        StatementCount = $businessStatementCount
        ExecutionMilliseconds = $businessExecutionMilliseconds
        LockMilliseconds = $businessLockMilliseconds
        TopStatements = @($businessItems | Sort-Object ExecutionMilliseconds -Descending | Select-Object -First 10)
        ExcludedObserverStatementCount = $observerStatementCount
        ExcludedObserverExecutionMilliseconds = $observerExecutionMilliseconds
        ExcludedObserverLockMilliseconds = $observerLockMilliseconds
    }
}

function New-K6CompletedIterationStatementDiagnostics {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Diagnostics,
        [Parameter(Mandatory = $true)][object]$Result
    )

    foreach ($property in @('iterations', 'droppedIterations', 'checkoutConfirmed', 'expectedContention', 'unexpectedNonSuccessful')) {
        if ($property -notin $Result.PSObject.Properties.Name) { throw "k6 result is missing iteration normalization field: $property" }
    }
    $completed = [long]$Result.iterations
    $dropped = [long]$Result.droppedIterations
    $classified = [long]$Result.checkoutConfirmed + [long]$Result.expectedContention + [long]$Result.unexpectedNonSuccessful
    if ($completed -le 0 -or $dropped -lt 0 -or $classified -ne $completed) { throw 'k6 completed iteration normalization is invalid.' }
    $scheduled = $completed + $dropped
    [pscustomobject]@{
        StatementCount = [long]$Diagnostics.StatementCount
        ExecutionMilliseconds = [double]$Diagnostics.ExecutionMilliseconds
        LockMilliseconds = [double]$Diagnostics.LockMilliseconds
        TopStatements = @($Diagnostics.TopStatements)
        ExcludedObserverStatementCount = [long]$Diagnostics.ExcludedObserverStatementCount
        ExcludedObserverExecutionMilliseconds = [double]$Diagnostics.ExcludedObserverExecutionMilliseconds
        ExcludedObserverLockMilliseconds = [double]$Diagnostics.ExcludedObserverLockMilliseconds
        CompletedIterations = $completed
        DroppedIterations = $dropped
        ScheduledIterations = $scheduled
        CompletionAttainmentRate = [double]$completed / $scheduled
        StatementsPerCompletedIteration = [double]$Diagnostics.StatementCount / $completed
        ExecutionMillisecondsPerCompletedIteration = [double]$Diagnostics.ExecutionMilliseconds / $completed
        LockMillisecondsPerCompletedIteration = [double]$Diagnostics.LockMilliseconds / $completed
    }
}

function ConvertFrom-PrometheusRuntimeMetrics {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $issue112ProcessCpu = $null
    $issue112SystemCpu = $null
    $issue112HeapUsed = 0.0
    $issue112HeapSamples = 0
    foreach ($issue112Line in ($Text -split "`r?`n")) {
        if ($issue112Line -match '^process_cpu_usage(?:\{[^}]*\})?\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$') {
            $issue112ProcessCpu = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        } elseif ($issue112Line -match '^system_cpu_usage(?:\{[^}]*\})?\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$') {
            $issue112SystemCpu = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        } elseif ($issue112Line -match '^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\}\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*$') {
            $issue112HeapUsed += [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
            $issue112HeapSamples += 1
        }
    }
    if ($null -eq $issue112ProcessCpu -or $null -eq $issue112SystemCpu -or $issue112HeapSamples -eq 0) {
        throw 'Required JVM/process Prometheus metrics are missing.'
    }
    [pscustomobject]@{
        ProcessCpuUsage = $issue112ProcessCpu
        SystemCpuUsage = $issue112SystemCpu
        HeapUsedBytes = $issue112HeapUsed
    }
}

function ConvertFrom-PrometheusJvmContentionMetrics {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Text)

    $threads = $null; $gcSeconds = 0.0; $gcCount = 0.0; $gcSecondsFound = $false; $gcCountFound = $false
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^jvm_threads_live_threads(?:\{[^}]*\})?\s+([0-9.eE+-]+)\s*$') { $threads = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) }
        elseif ($line -match '^jvm_gc_pause_seconds_sum\{[^}]*\}\s+([0-9.eE+-]+)\s*$') { $gcSeconds += [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture); $gcSecondsFound = $true }
        elseif ($line -match '^jvm_gc_pause_seconds_count\{[^}]*\}\s+([0-9.eE+-]+)\s*$') { $gcCount += [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture); $gcCountFound = $true }
    }
    if ($null -eq $threads -or -not $gcSecondsFound -or -not $gcCountFound) { throw 'Required JVM contention metrics are missing.' }
    [pscustomobject]@{ JvmThreadsLive = $threads; JvmGcPauseSeconds = $gcSeconds; JvmGcPauseCount = $gcCount }
}

function ConvertFrom-DockerContainerStats {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Json)
    try { $stats = $Json | ConvertFrom-Json } catch { throw 'Docker container stats are not valid JSON.' }
    if ([string]::IsNullOrWhiteSpace([string]$stats.CPUPerc) -or [string]::IsNullOrWhiteSpace([string]$stats.MemUsage)) { throw 'Docker container stats are missing CPU or memory.' }
    if ([string]$stats.CPUPerc -notmatch '^\s*([0-9]+(?:[.,][0-9]+)?)%\s*$') { throw 'Docker container CPU percentage is invalid.' }
    $cpu = [double]::Parse($Matches[1].Replace(',', '.'), [Globalization.CultureInfo]::InvariantCulture)
    if ([string]$stats.MemUsage -notmatch '^\s*([0-9]+(?:[.,][0-9]+)?)\s*(B|KiB|MiB|GiB)\s*/') { throw 'Docker container memory usage is invalid.' }
    $memory = [double]::Parse($Matches[1].Replace(',', '.'), [Globalization.CultureInfo]::InvariantCulture)
    $multipliers = @{ B = 1; KiB = 1KB; MiB = 1MB; GiB = 1GB }
    [pscustomobject]@{ ContainerCpuPercent = $cpu; ContainerMemoryBytes = [double]($memory * $multipliers[$Matches[2]]); ContainerName = [string]$stats.Name }
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
    $issue116ContainerSamples = @($Samples | Where-Object {
            $null -ne $_.MariaDbContainerCpuPercent -and $null -ne $_.MariaDbContainerMemoryBytes
        })
    if ($issue116ContainerSamples.Count -ne 0 -and $issue116ContainerSamples.Count -ne $Samples.Count) {
        throw 'MariaDB container stats must be present for every sample or omitted for every sample.'
    }

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
            ProcessCpuUsage       = [double](($Samples | Measure-Object -Property ProcessCpuUsage -Maximum).Maximum)
            SystemCpuUsage        = [double](($Samples | Measure-Object -Property SystemCpuUsage -Maximum).Maximum)
            HeapUsedBytes         = [double](($Samples | Measure-Object -Property HeapUsedBytes -Maximum).Maximum)
            JvmThreadsLive        = [double](($Samples | Measure-Object -Property JvmThreadsLive -Maximum).Maximum)
            DbRowLockCurrentWaits = [long](($Samples | Measure-Object -Property DbRowLockCurrentWaits -Maximum).Maximum)
            DbThreadsConnected   = [long](($Samples | Measure-Object -Property DbThreadsConnected -Maximum).Maximum)
            DbThreadsRunning     = [long](($Samples | Measure-Object -Property DbThreadsRunning -Maximum).Maximum)
        }
        Deltas = [pscustomobject]@{
            DbRowLockWaits  = [long]$issue51Last.DbRowLockWaits - [long]$issue51First.DbRowLockWaits
            DbRowLockTimeMs = [long]$issue51Last.DbRowLockTimeMs - [long]$issue51First.DbRowLockTimeMs
            DbDeadlocks     = [long]$issue51Last.DbDeadlocks - [long]$issue51First.DbDeadlocks
            JvmGcPauseSeconds = [double]$issue51Last.JvmGcPauseSeconds - [double]$issue51First.JvmGcPauseSeconds
            JvmGcPauseCount = [long]$issue51Last.JvmGcPauseCount - [long]$issue51First.JvmGcPauseCount
        }
        MariaDbContainer = if ($issue116ContainerSamples.Count -eq 0) { $null } else { [pscustomobject]@{
                CpuPercentPeak = [double](($issue116ContainerSamples | Measure-Object -Property MariaDbContainerCpuPercent -Maximum).Maximum)
                MemoryBytesPeak = [double](($issue116ContainerSamples | Measure-Object -Property MariaDbContainerMemoryBytes -Maximum).Maximum)
            } }
        ObserverEffects = [pscustomobject]@{
            DbCliConnectionIncludedInThreadGauges = $true
            ConnectionsCounterExcluded            = $true
            ComposeHealthcheckMayOpenConnections   = $true
            DockerStatsCollected                    = ($issue116ContainerSamples.Count -gt 0)
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
    'ConvertFrom-PrometheusHikariAcquireTiming',
    'New-HikariAcquireTimingDelta',
    'ConvertFrom-PrometheusCheckoutHttpRequests',
    'New-PrometheusCheckoutHttpRequestDelta',
    'Assert-CheckoutHttpIterationAgreement',
    'ConvertFrom-MariaDbStatementDigestSnapshot',
    'New-MariaDbStatementDigestDelta',
    'New-K6CompletedIterationStatementDiagnostics',
    'ConvertFrom-PrometheusRuntimeMetrics',
    'ConvertFrom-PrometheusJvmContentionMetrics',
    'ConvertFrom-DockerContainerStats',
    'ConvertFrom-MariaDbStatus',
    'Assert-ContentionRunId',
    'New-ContentionMetricsSummary',
    'ConvertFrom-K6ContentionResult',
    'ConvertFrom-K6FinalSnapshot',
    'New-K6ContentionRunSummary',
    'Assert-K6ContentionRunIdentity'
)
