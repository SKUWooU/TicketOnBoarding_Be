Set-StrictMode -Version Latest

function ConvertFrom-SmallSeatSqlCounters {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Lines)
    $rows = @{}
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        $parts = ([string]$line).Split([char]9)
        if ($parts.Count -ne 6) { throw 'Invalid SQL diagnostic counter row.' }
        $sql = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[0]))
        $operation = if ($sql -match '(?is)^SELECT\b.*\bFROM\s+`?seat`?\b.*\bFOR\s+UPDATE\b') { 'seat-lock-select' } `
            elseif ($sql -match '(?is)^UPDATE\s+`?seat`?\b') { 'seat-update' } else { $null }
        if ($null -eq $operation) { continue }
        $values = @(1..5 | ForEach-Object { [decimal]$parts[$_] })
        if (@($values | Where-Object { $_ -lt 0 -or $_ -ne [decimal]::Truncate($_) }).Count -gt 0) {
            throw 'SQL counters must be nonnegative integers.'
        }
        if (-not $rows.ContainsKey($operation)) { $rows[$operation] = @(0D, 0D, 0D, 0D, 0D) }
        foreach ($index in 0..4) { $rows[$operation][$index] += $values[$index] }
    }
    $rows
}

function New-SmallSeatSqlDelta {
    param([Parameter(Mandatory = $true)][hashtable]$Before,
          [Parameter(Mandatory = $true)][hashtable]$After)
    $result = @()
    foreach ($operation in @('seat-lock-select', 'seat-update')) {
        if (-not $After.ContainsKey($operation)) { throw 'Required seat SQL diagnostics are missing.' }
        $previous = if ($Before.ContainsKey($operation)) { $Before[$operation] } else { @(0D, 0D, 0D, 0D, 0D) }
        $delta = @(0..4 | ForEach-Object { [decimal]$After[$operation][$_] - [decimal]$previous[$_] })
        if (@($delta | Where-Object { $_ -lt 0 }).Count -gt 0 -or $delta[0] -le 0) {
            throw 'SQL counters decreased or did not advance.'
        }
        $result += [pscustomobject]@{
            Operation = $operation
            Executions = [long]$delta[0]
            StatementTotalMs = [double]($delta[1] / 1000000000)
            StatementAverageMs = [double]($delta[1] / $delta[0] / 1000000000)
            RowsExamined = [long]$delta[2]
            RowsAffected = [long]$delta[3]
            SqlErrors = [long]$delta[4]
        }
    }
    $result
}
function Get-SmallSeatSqlCoverage {
    param([Parameter(Mandatory = $true)][object[]]$Delta,
          [Parameter(Mandatory = $true)][long]$Iterations,
          [Parameter(Mandatory = $true)][long]$HoldSuccess,
          [Parameter(Mandatory = $true)][long]$ReleaseSuccess)
    if ($Iterations -le 0 -or $HoldSuccess -le 0 -or $HoldSuccess -gt $Iterations -or
        $ReleaseSuccess -ne $HoldSuccess -or $Delta.Count -ne 2 -or
        $Delta[0].Operation -cne 'seat-lock-select' -or $Delta[1].Operation -cne 'seat-update') {
        throw 'Invalid completed one-seat Hold/Release workload for SQL coverage.'
    }
    $matched = $Delta[0].Executions -eq ($Iterations + $ReleaseSuccess) -and
        $Delta[1].Executions -eq (2 * $HoldSuccess) -and
        @($Delta | Where-Object SqlErrors -ne 0).Count -eq 0
    [pscustomobject]@{
        Status = if ($matched) { 'COUNTERS_MATCH_WORKLOAD' } else { 'SQL_COUNTER_COVERAGE_MISMATCH' }
        ExpectedLockSelects = $Iterations + $ReleaseSuccess
        ObservedLockSelects = $Delta[0].Executions
        ExpectedSeatUpdates = 2 * $HoldSuccess
        ObservedSeatUpdates = $Delta[1].Executions
    }
}
Export-ModuleMember -Function 'ConvertFrom-SmallSeatSqlCounters', 'New-SmallSeatSqlDelta', 'Get-SmallSeatSqlCoverage'
