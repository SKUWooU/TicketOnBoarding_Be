[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'StatementDigestDiagnostics.psm1') -Force
$issue55Assertions = 0

function Assert-Issue55Equal {
    param($Actual, $Expected, [string]$Message)
    $script:issue55Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue55Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue55Assertions += 1
    try {
        & $Action
    } catch {
        return
    }
    throw $Message
}

function New-Issue55DigestLine {
    param(
        [string]$Digest,
        [long]$Count,
        [decimal]$TotalPicoseconds,
        [decimal]$AveragePicoseconds,
        [decimal]$MaximumPicoseconds,
        [long]$Errors = 0,
        [long]$Warnings = 0,
        [long]$RowsAffected = 0
    )
    $issue55Encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Digest))
    @($issue55Encoded, $Count, $TotalPicoseconds, $AveragePicoseconds, $MaximumPicoseconds, $Errors, $Warnings, $RowsAffected) -join "`t"
}

$issue55Lines = @(
    (New-Issue55DigestLine -Digest 'SELECT s.id FROM seat s WHERE s.concert_time_id = ? AND s.seat_number = ? FOR UPDATE' -Count 100 -TotalPicoseconds 100000000000 -AveragePicoseconds 1000000000 -MaximumPicoseconds 3000000000)
    (New-Issue55DigestLine -Digest 'UPDATE concert_time SET seat_amount = seat_amount - ? WHERE id = ? AND seat_amount >= ?' -Count 60 -TotalPicoseconds 600000000000 -AveragePicoseconds 10000000000 -MaximumPicoseconds 30000000000 -RowsAffected 60)
    (New-Issue55DigestLine -Digest 'UPDATE `concert_time` SET `seat_amount` = ( `seat_amount` - ? ) WHERE `id` = ? AND `seat_amount` >= ?' -Count 40 -TotalPicoseconds 800000000000 -AveragePicoseconds 20000000000 -MaximumPicoseconds 50000000000 -RowsAffected 40)
    (New-Issue55DigestLine -Digest 'INSERT INTO reservation VALUES (?)' -Count 100 -TotalPicoseconds 1 -AveragePicoseconds 1 -MaximumPicoseconds 1)
)
$issue55Summary = New-ContentionStatementDigestSummary -Lines $issue55Lines
Assert-Issue55Equal $issue55Summary.SqlTextIncluded $false 'The persisted summary must state that SQL text is excluded.'
Assert-Issue55Equal $issue55Summary.SeatLockSelect.Count 100 'Seat lock statements must be classified.'
Assert-Issue55Equal $issue55Summary.SeatLockSelect.AverageMilliseconds 1 'Seat lock average must be converted from picoseconds to milliseconds.'
Assert-Issue55Equal $issue55Summary.SeatLockSelect.MaximumMilliseconds 3 'Seat lock maximum must be converted to milliseconds.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.DigestCount 2 'Equivalent counter update digests must be aggregated.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.Count 100 'Counter update counts must be summed.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.TotalMilliseconds 1400 'Counter update total duration must be summed.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.AverageMilliseconds 14 'Counter update average must be weighted by execution count.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.MaximumMilliseconds 50 'Counter update maximum must use the largest digest maximum.'
Assert-Issue55Equal $issue55Summary.ConcertTimeDecrement.RowsAffected 100 'Affected rows must be aggregated.'
Assert-Issue55Equal (Assert-ContentionStatementDigestCounts -Summary $issue55Summary -ExpectedSuccessfulReservations 100) $true 'Matching statement and success counts must pass.'
Assert-Issue55Throws { Assert-ContentionStatementDigestCounts -Summary $issue55Summary -ExpectedSuccessfulReservations 99 } 'A statement count mismatch must fail.'
Assert-Issue55Throws { New-ContentionStatementDigestSummary -Lines @($issue55Lines[0]) } 'A missing concert time update digest must fail.'
Assert-Issue55Throws { ConvertFrom-MariaDbStatementDigest -Lines @('not-tab-separated') } 'Malformed statement output must fail.'
Assert-Issue55Throws { ConvertFrom-MariaDbStatementDigest -Lines @("%%%`t1`t1`t1`t1`t0`t0`t0") } 'Invalid Base64 SQL text must fail.'
Assert-Issue55Throws { ConvertFrom-MariaDbStatementDigest -Lines @((New-Issue55DigestLine -Digest 'SELECT * FROM seat FOR UPDATE' -Count -1 -TotalPicoseconds 1 -AveragePicoseconds 1 -MaximumPicoseconds 1)) } 'Negative counters must fail.'

Write-Output "StatementDigestDiagnostics checks passed: $issue55Assertions assertions."
