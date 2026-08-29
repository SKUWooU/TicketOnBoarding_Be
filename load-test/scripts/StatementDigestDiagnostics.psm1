Set-StrictMode -Version Latest

function Get-Issue55DigestOperation {
    param([string]$DigestText)

    if ($DigestText -match '(?is)^\s*SELECT\b.*\bFROM\s+`?seat`?\b.*\bFOR\s+UPDATE\b') {
        return 'seat-lock-select'
    }
    if ($DigestText -match '(?is)^\s*UPDATE\s+`?concert_time`?\b.*\bseat_amount\b') {
        return 'concert-time-decrement'
    }
    $null
}

function ConvertFrom-MariaDbStatementDigest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Lines
    )

    $issue55Rows = New-Object 'Collections.Generic.List[object]'
    foreach ($issue55LineValue in $Lines) {
        $issue55Line = [string]$issue55LineValue
        if ([string]::IsNullOrWhiteSpace($issue55Line)) {
            continue
        }
        $issue55Parts = $issue55Line.Split([char[]]@([char]9))
        if ($issue55Parts.Count -ne 8) {
            throw "Statement digest row must contain 8 tab-separated columns: $issue55Line"
        }
        try {
            $issue55DigestText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($issue55Parts[0]))
            $issue55Count = [long]$issue55Parts[1]
            $issue55SumTimer = [decimal]$issue55Parts[2]
            $issue55AverageTimer = [decimal]$issue55Parts[3]
            $issue55MaximumTimer = [decimal]$issue55Parts[4]
            $issue55Errors = [long]$issue55Parts[5]
            $issue55Warnings = [long]$issue55Parts[6]
            $issue55RowsAffected = [long]$issue55Parts[7]
        } catch {
            throw "Statement digest row contains an invalid value: $($_.Exception.Message)"
        }
        foreach ($issue55NonNegative in @(
            $issue55Count,
            $issue55SumTimer,
            $issue55AverageTimer,
            $issue55MaximumTimer,
            $issue55Errors,
            $issue55Warnings,
            $issue55RowsAffected
        )) {
            if ($issue55NonNegative -lt 0) {
                throw 'Statement digest counters and timers must not be negative.'
            }
        }
        if ($issue55Count -eq 0) {
            continue
        }

        $issue55Operation = Get-Issue55DigestOperation -DigestText $issue55DigestText
        if ($null -eq $issue55Operation) {
            continue
        }
        $issue55Rows.Add([pscustomobject]@{
            Operation = $issue55Operation
            Count = $issue55Count
            SumTimerPicoseconds = $issue55SumTimer
            AverageTimerPicoseconds = $issue55AverageTimer
            MaximumTimerPicoseconds = $issue55MaximumTimer
            Errors = $issue55Errors
            Warnings = $issue55Warnings
            RowsAffected = $issue55RowsAffected
        })
    }
    $issue55Rows.ToArray()
}

function New-Issue55OperationSummary {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Rows,

        [Parameter(Mandatory = $true)]
        [string]$Operation
    )

    $issue55Matches = @($Rows | Where-Object { $_.Operation -eq $Operation })
    if ($issue55Matches.Count -eq 0) {
        throw "Required statement digest operation is missing: $Operation"
    }
    $issue55Count = [long](($issue55Matches | Measure-Object -Property Count -Sum).Sum)
    $issue55SumTimer = [decimal](($issue55Matches | Measure-Object -Property SumTimerPicoseconds -Sum).Sum)
    $issue55MaximumTimer = [decimal](($issue55Matches | Measure-Object -Property MaximumTimerPicoseconds -Maximum).Maximum)
    [pscustomobject]@{
        Operation = $Operation
        DigestCount = $issue55Matches.Count
        Count = $issue55Count
        TotalMilliseconds = [double]($issue55SumTimer / 1000000000)
        AverageMilliseconds = [double](($issue55SumTimer / $issue55Count) / 1000000000)
        MaximumMilliseconds = [double]($issue55MaximumTimer / 1000000000)
        Errors = [long](($issue55Matches | Measure-Object -Property Errors -Sum).Sum)
        Warnings = [long](($issue55Matches | Measure-Object -Property Warnings -Sum).Sum)
        RowsAffected = [long](($issue55Matches | Measure-Object -Property RowsAffected -Sum).Sum)
    }
}

function New-ContentionStatementDigestSummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Lines
    )

    $issue55Rows = @(ConvertFrom-MariaDbStatementDigest -Lines $Lines)
    [pscustomobject]@{
        Enabled = $true
        Source = 'performance_schema.events_statements_summary_by_digest'
        SqlTextIncluded = $false
        SeatLockSelect = New-Issue55OperationSummary -Rows $issue55Rows -Operation 'seat-lock-select'
        ConcertTimeDecrement = New-Issue55OperationSummary -Rows $issue55Rows -Operation 'concert-time-decrement'
    }
}

function Assert-ContentionStatementDigestCounts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 9223372036854775807)]
        [long]$ExpectedSuccessfulReservations,

        [ValidateRange(0.01, 1.0)]
        [double]$MinimumCoverageRate = 1.0
    )

    $issue55Coverage = New-ContentionStatementDigestCoverage `
        -Summary $Summary `
        -ExpectedSuccessfulReservations $ExpectedSuccessfulReservations
    if ([long]$Summary.SeatLockSelect.Count -gt $ExpectedSuccessfulReservations) {
        throw "Seat lock statement count exceeds successful reservations: expected=$ExpectedSuccessfulReservations actual=$($Summary.SeatLockSelect.Count)"
    }
    if ([long]$Summary.ConcertTimeDecrement.Count -gt $ExpectedSuccessfulReservations) {
        throw "Concert time decrement count exceeds successful reservations: expected=$ExpectedSuccessfulReservations actual=$($Summary.ConcertTimeDecrement.Count)"
    }
    if ($issue55Coverage.SeatLockSelectRate -lt $MinimumCoverageRate) {
        throw "Seat lock statement coverage is below the required rate: required=$MinimumCoverageRate actual=$($issue55Coverage.SeatLockSelectRate)"
    }
    if ($issue55Coverage.ConcertTimeDecrementRate -lt $MinimumCoverageRate) {
        throw "Concert time decrement coverage is below the required rate: required=$MinimumCoverageRate actual=$($issue55Coverage.ConcertTimeDecrementRate)"
    }
    if ([long]$Summary.SeatLockSelect.Errors -ne 0 -or [long]$Summary.ConcertTimeDecrement.Errors -ne 0) {
        throw 'Required statement digest operations contain SQL errors.'
    }
    $true
}

function New-ContentionStatementDigestCoverage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 9223372036854775807)]
        [long]$ExpectedSuccessfulReservations
    )

    $issue55SeatRate = [double]$Summary.SeatLockSelect.Count / $ExpectedSuccessfulReservations
    $issue55CounterRate = [double]$Summary.ConcertTimeDecrement.Count / $ExpectedSuccessfulReservations
    [pscustomobject]@{
        ExpectedSuccessfulReservations = $ExpectedSuccessfulReservations
        SeatLockSelectCount = [long]$Summary.SeatLockSelect.Count
        SeatLockSelectRate = $issue55SeatRate
        ConcertTimeDecrementCount = [long]$Summary.ConcertTimeDecrement.Count
        ConcertTimeDecrementRate = $issue55CounterRate
        MinimumObservedRate = [math]::Min($issue55SeatRate, $issue55CounterRate)
        ExactCountMatch = $issue55SeatRate -eq 1.0 -and $issue55CounterRate -eq 1.0
    }
}

Export-ModuleMember -Function @(
    'ConvertFrom-MariaDbStatementDigest',
    'New-ContentionStatementDigestSummary',
    'New-ContentionStatementDigestCoverage',
    'Assert-ContentionStatementDigestCounts'
)
