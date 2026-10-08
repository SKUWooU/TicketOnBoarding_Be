Set-StrictMode -Version Latest

function Assert-ControlledSmallFixtureConfig {
    param([Parameter(Mandatory = $true)][object]$Config)
    if ([int]$Config.rows -ne 2 -or [int]$Config.seatsPerRow -ne 10 -or
        [int]$Config.totalSeats -ne 20) {
        throw 'The loadtest Backend must be configured for exactly 2 rows and 10 seats per row.'
    }
    $true
}

function Assert-ControlledSmallFixtureState {
    param(
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][object]$Fixture,
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][object]$Holds,
        [Parameter(Mandatory = $true)][object[]]$DatabaseCounts
    )
    if ($Fixture.runId -cne $RunId -or $Fixture.concertId -cne "LOAD-TEST-$RunId" -or
        [int]$Fixture.rows -ne 2 -or [int]$Fixture.seatsPerRow -ne 10 -or
        [int]$Fixture.totalSeats -ne 20 -or [long]$Fixture.concertTimeId -le 0 -or
        [int]$Snapshot.expectedTotalSeats -ne 20 -or [long]$Snapshot.actualSeatCount -ne 20 -or
        [int]$Snapshot.remainingSeats -ne 20 -or [long]$Snapshot.reservedSeats -ne 0 -or
        [long]$Snapshot.reservations -ne 0 -or [long]$Snapshot.bookings -ne 0 -or
        [long]$Snapshot.payments -ne 0 -or -not [bool]$Snapshot.invariantSatisfied -or
        [int]$Holds.expectedTotalSeats -ne 20 -or [long]$Holds.actualSeatCount -ne 20 -or
        [long]$Holds.remainingSeats -ne 20 -or [long]$Holds.activeHeldSeats -ne 0 -or
        [long]$Holds.holdRows -ne 0 -or [long]$Holds.partialHoldStates -ne 0 -or
        -not [bool]$Holds.invariantSatisfied -or $DatabaseCounts.Count -ne 8 -or
        (@($DatabaseCounts | ForEach-Object { [long]$_ }) -join ',') -ne '1,1,20,0,0,0,0,0') {
        throw 'The small fixture did not converge to the expected 20-seat inventory.'
    }
    $true
}

Export-ModuleMember -Function 'Assert-ControlledSmallFixtureConfig', 'Assert-ControlledSmallFixtureState'
