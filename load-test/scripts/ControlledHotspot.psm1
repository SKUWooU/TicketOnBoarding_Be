Set-StrictMode -Version Latest

function Assert-ControlledHotspotProject {
    param(
        [string]$ExpectedProject,
        [string]$ActualProject,
        [string]$ContainerProject
    )
    if ($ExpectedProject -ne 'ticketon-controlled166' -or
        $ActualProject -ne $ExpectedProject -or
        $ContainerProject -ne $ExpectedProject) {
        throw 'Controlled hotspot cleanup is restricted to the ticketon-controlled166 Compose project.'
    }
    $true
}

function Assert-ControlledHotspotPreCleanup {
    param(
        [long]$TotalSeatRows,
        [long]$FixtureSeatRows,
        [long]$OtherConcertRows,
        [long]$TotalReservations,
        [long]$FixtureReservations,
        [long]$TotalBookings,
        [long]$FixtureBookings,
        [long]$TotalPayments,
        [long]$FixturePayments,
        [long]$CheckoutRows,
        [long]$ReviewRows
    )
    if ($TotalSeatRows -lt 0 -or $FixtureSeatRows -lt 0 -or
        $TotalSeatRows -ne $FixtureSeatRows -or $OtherConcertRows -ne 0 -or
        $TotalReservations -ne $FixtureReservations -or
        $TotalBookings -ne $FixtureBookings -or
        $TotalPayments -ne $FixturePayments -or $CheckoutRows -ne 0 -or $ReviewRows -ne 0) {
        throw "Diagnostic database contains non-fixture data: seats=$TotalSeatRows/$FixtureSeatRows otherConcerts=$OtherConcertRows reservations=$TotalReservations/$FixtureReservations bookings=$TotalBookings/$FixtureBookings payments=$TotalPayments/$FixturePayments checkouts=$CheckoutRows reviews=$ReviewRows"
    }
    $true
}

function New-ControlledHotspotPlan {
    param(
        [ValidateRange(1, 10)][int]$Repeats = 2,
        [ValidateRange(1, 3600)][int]$DurationSeconds = 10,
        [ValidateRange(1, 10000)][int]$Rate = 50
    )
    if ($Rate * $DurationSeconds -gt 2000) {
        throw 'A run may schedule more successful reservations than the 2,000-seat fixture.'
    }
    $plan = New-Object 'Collections.Generic.List[object]'
    $sequence = 0
    for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        $order = if ($repeat % 2 -eq 1) { @(20, 40, 200) } else { @(200, 40, 20) }
        foreach ($hotSeats in $order) {
            $sequence++
            $plan.Add([pscustomobject]@{
                Sequence = $sequence
                Repeat = $repeat
                HotSeatCount = $hotSeats
                Rate = $Rate
                DurationSeconds = $DurationSeconds
            })
        }
    }
    $plan.ToArray()
}

function Assert-ControlledHotspotResult {
    param([Parameter(Mandatory = $true)][object]$Summary)
    if (-not [bool]$Summary.ValidMeasurement -or
        -not [bool]$Summary.K6.InventoryInvariantSatisfied -or
        [long]$Summary.K6.Result.DroppedIterations -ne 0 -or
        [long]$Summary.K6.Result.UnexpectedNonSuccessful -ne 0 -or
        [long]$Summary.Metrics.Deltas.DbDeadlocks -ne 0 -or
        $null -eq $Summary.DatabaseStatementDigests -or
        $null -eq $Summary.K6.Result.ReservationSuccessDurationMs -or
        $null -eq $Summary.K6.Result.ReservationSeatContentionDurationMs -or
        $null -eq $Summary.FixturePreparation.FreshFixture -or
        [long]$Summary.FixturePreparation.FreshFixture.PhysicalSeatRows -ne 2000 -or
        [long]$Summary.FixturePreparation.FreshFixture.RemainingSeats -ne 2000) {
        throw 'Controlled hotspot run failed the comparison gate.'
    }
    $true
}

Export-ModuleMember -Function @(
    'Assert-ControlledHotspotProject',
    'Assert-ControlledHotspotPreCleanup',
    'New-ControlledHotspotPlan',
    'Assert-ControlledHotspotResult'
)
