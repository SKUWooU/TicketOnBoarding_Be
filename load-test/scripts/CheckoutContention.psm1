Set-StrictMode -Version Latest

function ConvertFrom-CheckoutK6Result {
    param([Parameter(Mandatory = $true)][string]$Text)
    $lines = @((($Text -split "`r?`n") | ForEach-Object { $_.Trim() } | Where-Object { $_.StartsWith('CHECKOUT_RESULT ') }))
    if ($lines.Count -ne 1) { throw "Expected exactly one Checkout result, found $($lines.Count)." }
    try { $result = $lines[0].Substring(16) | ConvertFrom-Json } catch { throw 'Checkout result is not valid JSON.' }
    foreach ($property in @('schemaVersion','scenario','targetRatePerSecond','duration','iterations','droppedIterations','checkoutConfirmed','expectedContention','unexpectedNonSuccessful','unexpectedFailureRate','checkoutDurationMs')) {
        if ($property -notin $result.PSObject.Properties.Name) { throw "Checkout result is missing: $property" }
    }
    if ([int]$result.schemaVersion -ne 1) { throw 'Unsupported Checkout result schema.' }
    $result
}

function ConvertFrom-CheckoutFinalSnapshot {
    param([Parameter(Mandatory = $true)][string]$Text)
    $matches = [regex]::Matches($Text, 'CHECKOUT_FINAL_SNAPSHOT\s+(\{[^{}]*\})')
    if ($matches.Count -ne 1) { throw "Expected exactly one Checkout final snapshot, found $($matches.Count)." }
    try { $snapshot = ($matches[0].Groups[1].Value -replace '\\"', '"') | ConvertFrom-Json } catch { throw 'Checkout final snapshot is not valid JSON.' }
    foreach ($property in @('reservedSeats','reservations','bookings','payments','invariantSatisfied')) {
        if ($property -notin $snapshot.PSObject.Properties.Name) { throw "Checkout final snapshot is missing: $property" }
    }
    $snapshot
}

function ConvertFrom-CheckoutTransitionDelta {
    param([Parameter(Mandatory = $true)][string]$Text)
    $matches = [regex]::Matches($Text, 'CHECKOUT_TRANSITION_DELTA\s+(\{[^{}]*\})')
    if ($matches.Count -ne 1) { throw "Expected exactly one Checkout transition delta, found $($matches.Count)." }
    try { $delta = ($matches[0].Groups[1].Value -replace '\\"', '"') | ConvertFrom-Json } catch { throw 'Checkout transition delta is not valid JSON.' }
    foreach ($property in @('reservationConfirmed','verificationClaimed')) {
        if ($property -notin $delta.PSObject.Properties.Name) { throw "Checkout transition delta is missing: $property" }
    }
    $delta
}

function Assert-CheckoutContentionGate {
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][object]$TransitionDelta
    )
    $confirmed = [long]$Result.checkoutConfirmed
    if ([long]$Result.iterations -ne ($confirmed + [long]$Result.expectedContention + [long]$Result.unexpectedNonSuccessful)) { throw 'Checkout k6 counters do not match iterations.' }
    if ([double]$Result.unexpectedFailureRate -ne 0 -or [long]$Result.unexpectedNonSuccessful -ne 0) { throw 'Checkout run contains unexpected failures.' }
    if (-not [bool]$Snapshot.invariantSatisfied) { throw 'Checkout inventory invariant is false.' }
    foreach ($property in @('reservedSeats','reservations','bookings','payments')) {
        if ([long]$Snapshot.$property -ne $confirmed) { throw "Checkout final $property does not match confirmed responses." }
    }
    if ([long]$TransitionDelta.reservationConfirmed -ne $confirmed -or [long]$TransitionDelta.verificationClaimed -ne $confirmed) { throw 'Checkout committed transition delta does not match confirmed responses.' }
    $true
}
