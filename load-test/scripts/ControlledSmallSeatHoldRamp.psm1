Set-StrictMode -Version Latest

function Get-ControlledSmallSeatHoldRampPlan {
    @(
        [pscustomobject]@{ Rate = 5; DurationSeconds = 10; HotSeatCount = 1; HoldDwellMilliseconds = 500 },
        [pscustomobject]@{ Rate = 10; DurationSeconds = 10; HotSeatCount = 1; HoldDwellMilliseconds = 500 },
        [pscustomobject]@{ Rate = 20; DurationSeconds = 10; HotSeatCount = 1; HoldDwellMilliseconds = 500 }
    )
}

function Get-ControlledSmallSeatHoldProbe30Plan {
    @(
        [pscustomobject]@{ Sequence = 1; Round = 1; Rate = 20; DurationSeconds = 10; HotSeatCount = 1; HoldDwellMilliseconds = 500 },
        [pscustomobject]@{ Sequence = 2; Round = 1; Rate = 30; DurationSeconds = 10; HotSeatCount = 1; HoldDwellMilliseconds = 500 }
    )
}

function Assert-ControlledSmallSeatProbeMemory {
    param([Parameter(Mandatory = $true)][long]$FreePhysicalMemoryKb)
    if ($FreePhysicalMemoryKb -lt 2097152) {
        throw 'Probe30 stopped: less than 2 GiB host physical memory free.'
    }
    $true
}

function Assert-ControlledSmallSeatProbeBaseline {
    param([Parameter(Mandatory = $true)][object]$WaitSummary)
    if ([long]$WaitSummary.HikariPendingPeak -ne 0 -or
        [long]$WaitSummary.HikariActivePeak -ge [long]$WaitSummary.HikariMax -or
        [long]$WaitSummary.HikariTimeoutDelta -ne 0 -or
        [long]$WaitSummary.DbDeadlocksDelta -ne 0) {
        throw 'Probe30 stopped: 20 RPS baseline already showed pool pressure, timeout or deadlock.'
    }
    $true
}

function ConvertFrom-ControlledK6ConsoleSnapshot {
    param([Parameter(Mandatory = $true)][string]$StandardError)
    $lines = @($StandardError -split "`r?`n" | Where-Object { $_.Contains('SEAT_HOLD_FINAL_SNAPSHOT ') })
    if ($lines.Count -ne 1) { throw "Expected one k6 final snapshot log, found $($lines.Count)." }
    $match = [regex]::Match($lines[0], 'msg="((?:\\.|[^"\\])*)"')
    if (-not $match.Success) { throw 'Could not decode the k6 console message.' }
    $message = ('"' + $match.Groups[1].Value + '"') | ConvertFrom-Json
    if (-not $message.StartsWith('SEAT_HOLD_FINAL_SNAPSHOT ', [StringComparison]::Ordinal)) {
        throw 'k6 console message is not the final snapshot.'
    }
    $json = $message.Substring('SEAT_HOLD_FINAL_SNAPSHOT '.Length)
    $json | ConvertFrom-Json
}

function Assert-ControlledSmallSeatHoldStage {
    param(
        [Parameter(Mandatory = $true)][object]$Plan,
        [Parameter(Mandatory = $true)][object]$Summary,
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][object]$FreshSnapshot,
        [Parameter(Mandatory = $true)][object[]]$DatabaseCounts,
        [Parameter(Mandatory = $true)][long]$DeadlockDelta
    )
    if ([int]$Plan.Rate -notin @(5, 10, 20, 30) -or [int]$Plan.DurationSeconds -ne 10 -or
        [int]$Plan.HotSeatCount -ne 1 -or [int]$Plan.HoldDwellMilliseconds -ne 500 -or
        [string]$Summary.Scenario -ne 'weighted-hotspot-churn' -or
        [int]$Summary.TargetRatePerSecond -ne [int]$Plan.Rate -or
        [int]$Summary.DurationSeconds -ne 10 -or
        [long]$Summary.Iterations -lt ([int]$Plan.Rate * 10 * 0.95) -or
        [long]$Summary.DroppedIterations -ne 0 -or
        [long]$Summary.HoldSuccess -le 0 -or
        [long]$Summary.HoldSuccess -ne [long]$Summary.ReleaseSuccess -or
        ([long]$Summary.HoldSuccess + [long]$Summary.ExpectedContention) -ne [long]$Summary.Iterations -or
        [long]$Summary.UnexpectedNonSuccessful -ne 0 -or [long]$Summary.UnexpectedRelease -ne 0 -or
        [int]$Summary.WeightedHotspot.HotSeatCount -ne 1 -or
        [int]$Summary.WeightedHotspot.HotRequestPercent -ne 70 -or
        [int]$Summary.HoldDwellMilliseconds -ne 500 -or
        -not [bool]$Snapshot.invariantSatisfied -or
        [long]$Snapshot.activeHeldSeats -ne 0 -or [long]$Snapshot.holdRows -ne 0 -or
        -not [bool]$FreshSnapshot.invariantSatisfied -or
        [long]$FreshSnapshot.expectedTotalSeats -ne 20 -or
        [long]$FreshSnapshot.actualSeatCount -ne 20 -or
        [long]$FreshSnapshot.remainingSeats -ne 20 -or
        [long]$FreshSnapshot.activeHeldSeats -ne 0 -or [long]$FreshSnapshot.holdRows -ne 0 -or
        [long]$FreshSnapshot.reservations -ne 0 -or [long]$FreshSnapshot.bookings -ne 0 -or
        [long]$FreshSnapshot.payments -ne 0 -or
        $DatabaseCounts.Count -ne 5 -or
        (@($DatabaseCounts | ForEach-Object { [long]$_ }) -join ',') -ne '20,0,0,20,0' -or
        $DeadlockDelta -ne 0) {
        throw 'Controlled small-seat Hold ramp stage failed its completion or inventory gate.'
    }
    $true
}

Export-ModuleMember -Function 'Get-ControlledSmallSeatHoldRampPlan', 'Get-ControlledSmallSeatHoldProbe30Plan',
    'Assert-ControlledSmallSeatProbeMemory', 'Assert-ControlledSmallSeatProbeBaseline',
    'ConvertFrom-ControlledK6ConsoleSnapshot', 'Assert-ControlledSmallSeatHoldStage'
