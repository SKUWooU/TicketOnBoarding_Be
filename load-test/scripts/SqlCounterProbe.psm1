Set-StrictMode -Version Latest

function Get-SqlCounterProbePlan {
    # Six finite observations, not a load or capacity benchmark.
    foreach ($index in 0..5) {
        [pscustomobject]@{
            Sequence = $index + 1
            Mode = @('serial', 'parallel', 'parallel', 'serial', 'serial', 'parallel')[$index]
            Workers = 4
            CyclesPerWorker = 25
            ExpectedTransactions = 100
        }
    }
}

function Get-SqlCounterProbeWorkload {
    param([Parameter(Mandatory = $true)][ValidateRange(1, 4)][int]$Worker)
    (1..25 | ForEach-Object {
        "START TRANSACTION; SELECT s.counter FROM seat s WHERE s.id=$Worker FOR UPDATE; " +
        "UPDATE seat SET counter=counter+1 WHERE id=$Worker; COMMIT; SELECT 'cycle-complete';"
    }) -join "`n"
}

function Assert-SqlCounterProbeCompletion {
    param([Parameter(Mandatory = $true)][string]$Output,
          [Parameter(Mandatory = $true)][int]$ExitCode)
    $lines = @($Output -split '\r?\n' | Where-Object { $_ -ne '' })
    if ($ExitCode -ne 0 -or $lines.Count -ne 50 -or
        @($lines | Where-Object { $_ -ceq 'cycle-complete' }).Count -ne 25 -or
        @($lines | Where-Object { $_ -cne 'cycle-complete' -and $_ -notmatch '^\d+$' }).Count -gt 0) {
        throw 'Independent SQL probe client did not complete its fixed workload.'
    }
    25
}
Export-ModuleMember -Function 'Get-SqlCounterProbePlan', 'Get-SqlCounterProbeWorkload', 'Assert-SqlCounterProbeCompletion'
