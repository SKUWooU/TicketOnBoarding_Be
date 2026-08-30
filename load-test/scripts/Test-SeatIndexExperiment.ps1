[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'SeatIndexExperiment.psm1') -Force
$issue57Assertions = 0

function Assert-Issue57Equal {
    param($Actual, $Expected, [string]$Message)
    $script:issue57Assertions += 1
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Issue57Throws {
    param([scriptblock]$Action, [string]$Message)
    $script:issue57Assertions += 1
    try {
        & $Action
    } catch {
        return
    }
    throw $Message
}

function Join-Issue57Tsv {
    param([object[]]$Values)
    [string]::Join([char]9, [string[]]$Values)
}

$script:issue57FakeComposite = $false
$script:issue57FakeSupport = $true
$script:issue57FakeDuplicateGroups = 0
$script:issue57FakePhysicalSeatRows = 2000
$issue57FakeExecutor = {
    param([string]$Query)

    if ($Query -match 'duplicate_seats') {
        return [string]$script:issue57FakeDuplicateGroups
    }
    if ($Query -match '^\s*DELETE FROM reservation_payment') {
        $script:issue57FakePhysicalSeatRows = 0
        $script:issue57FakeDuplicateGroups = 0
        return
    }
    if ($Query.Trim() -eq 'SELECT COUNT(*) FROM seat;') {
        return [string]$script:issue57FakePhysicalSeatRows
    }
    if ($Query -match 'information_schema\.STATISTICS') {
        $issue57Rows = New-Object 'Collections.Generic.List[string]'
        $issue57Rows.Add((Join-Issue57Tsv -Values @('PRIMARY', 0, 1, 'id')))
        if ($script:issue57FakeSupport) {
            $issue57Rows.Add((Join-Issue57Tsv -Values @('fk_seat_concert_time', 1, 1, 'concert_time_id')))
        }
        if ($script:issue57FakeComposite) {
            $issue57Rows.Add((Join-Issue57Tsv -Values @('uk_seat_concert_time_number', 0, 1, 'concert_time_id')))
            $issue57Rows.Add((Join-Issue57Tsv -Values @('uk_seat_concert_time_number', 0, 2, 'seat_number')))
        }
        return $issue57Rows.ToArray()
    }
    if ($Query -match '^\s*CREATE UNIQUE INDEX') {
        $script:issue57FakeComposite = $true
        $script:issue57FakeSupport = $false
        return
    }
    if ($Query -match '^\s*CREATE INDEX idx_seat_concert_time_ab_restore') {
        $script:issue57FakeSupport = $true
        return
    }
    if ($Query -match '^\s*DROP INDEX') {
        if (-not $script:issue57FakeSupport) {
            throw 'Foreign key support index is required before dropping the composite index.'
        }
        $script:issue57FakeComposite = $false
        return
    }
    if ($Query -match '^\s*EXPLAIN') {
        if ($script:issue57FakeComposite) {
            return (Join-Issue57Tsv -Values @(1, 'SIMPLE', 'seat', 'const', 'uk_seat_concert_time_number', 'uk_seat_concert_time_number', 1022, 'const,const', 1, ''))
        }
        return (Join-Issue57Tsv -Values @(1, 'SIMPLE', 'seat', 'ref', 'fk_seat_concert_time', 'fk_seat_concert_time', 9, 'const', 2000, 'Using where'))
    }
    throw "Unexpected fake query: $Query"
}

$issue57CompositeTransition = Set-Issue57SeatIndexVariant `
    -Variant composite `
    -DatabaseName onticket_local `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue57CompositeTransition.Changed $true 'Creating the composite index must report a transition.'
Assert-Issue57Equal $script:issue57FakeComposite $true 'The composite transition must create the index.'
$issue57CompositeEvidence = Get-Issue57SeatIndexEvidence `
    -Variant composite `
    -DatabaseName onticket_local `
    -ConcertTimeId 1 `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue57CompositeEvidence.DuplicateGroupCount 0 'Composite evidence must retain the duplicate precondition.'
Assert-Issue57Equal $issue57CompositeEvidence.CompositeIndexPresent $true 'Composite evidence must report the index.'
Assert-Issue57Equal ($issue57CompositeEvidence.CompositeColumns -join ',') 'concert_time_id,seat_number' 'Composite evidence must preserve column order.'
Assert-Issue57Equal $issue57CompositeEvidence.CompositeUnique $true 'Composite evidence must report uniqueness.'
Assert-Issue57Equal $issue57CompositeEvidence.QueryPlan.Key 'uk_seat_concert_time_number' 'Composite EXPLAIN must use the target index.'
Assert-Issue57Equal $issue57CompositeEvidence.QueryPlan.EstimatedRows 1 'Composite EXPLAIN must estimate one row.'
Assert-Issue57Equal $issue57CompositeEvidence.SqlTextIncluded $false 'Persisted evidence must exclude SQL text.'

$issue57CurrentTransition = Set-Issue57SeatIndexVariant `
    -Variant current `
    -DatabaseName onticket_local `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue57CurrentTransition.Changed $true 'Removing the composite index must report a transition.'
Assert-Issue57Equal $issue57CurrentTransition.ForeignKeySupportIndexCreated $true 'Removing a reused index must restore foreign key support.'
Assert-Issue57Equal $script:issue57FakeComposite $false 'The current transition must remove the composite index.'
Assert-Issue57Equal $script:issue57FakeSupport $true 'The current transition must retain foreign key support.'
$issue57CurrentEvidence = Get-Issue57SeatIndexEvidence `
    -Variant current `
    -DatabaseName onticket_local `
    -ConcertTimeId 2 `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue57CurrentEvidence.CompositeIndexPresent $false 'Current evidence must not report the composite index.'
Assert-Issue57Equal $issue57CurrentEvidence.QueryPlan.Key 'fk_seat_concert_time' 'Current EXPLAIN must use the single-column support index.'
Assert-Issue57Equal $issue57CurrentEvidence.QueryPlan.EstimatedRows 2000 'Current EXPLAIN must retain the broad lookup estimate.'

$issue57NoOpTransition = Set-Issue57SeatIndexVariant `
    -Variant current `
    -DatabaseName onticket_local `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue57NoOpTransition.Changed $false 'An already-current schema must be a no-op.'

Assert-Issue57Equal (Get-Issue57PhysicalSeatRowCount -QueryExecutor $issue57FakeExecutor) 2000 'Physical row evidence must expose total seat table size.'
$script:issue57FakeDuplicateGroups = 1
Assert-Issue57Throws {
    Set-Issue57SeatIndexVariant -Variant composite -DatabaseName onticket_local -QueryExecutor $issue57FakeExecutor
} 'A duplicate current-stage fixture must fail before permanent finalization.'
$issue59Finalization = Restore-Issue59PermanentSeatSchema `
    -DatabaseName onticket_local `
    -QueryExecutor $issue57FakeExecutor
Assert-Issue57Equal $issue59Finalization.FinalCleanupCompleted $true 'Permanent schema finalization must complete fixture cleanup.'
Assert-Issue57Equal $issue59Finalization.Cleanup.SeatRowsAfterCleanup 0 'Permanent schema finalization must leave an empty exclusive seat table.'
Assert-Issue57Equal $issue59Finalization.PermanentCompositeSchemaRestored $true 'Permanent schema finalization must restore the composite index.'
Assert-Issue57Equal $script:issue57FakeComposite $true 'A failed current-stage fixture must finish with the composite index present.'
Assert-Issue57Equal $script:issue57FakeDuplicateGroups 0 'Fixture cleanup must remove duplicate load-test seat groups before index restoration.'
Assert-Issue57Equal (Assert-Issue57MeasurementHealth -UnexpectedNonSuccessful 0 -DeadlocksDelta 0) $true 'A healthy A/B measurement must pass.'
Assert-Issue57Throws {
    Assert-Issue57MeasurementHealth -UnexpectedNonSuccessful 1 -DeadlocksDelta 0
} 'An unexpected response must invalidate the A/B measurement.'
Assert-Issue57Throws {
    Assert-Issue57MeasurementHealth -UnexpectedNonSuccessful 0 -DeadlocksDelta 1
} 'A database deadlock must invalidate the A/B measurement.'

$script:issue57FakeDuplicateGroups = 1
Assert-Issue57Throws {
    Set-Issue57SeatIndexVariant -Variant composite -DatabaseName onticket_local -QueryExecutor $issue57FakeExecutor
} 'Duplicate seat groups must block composite index creation.'
$script:issue57FakeDuplicateGroups = 0

Assert-Issue57Throws {
    ConvertFrom-Issue57IndexRows -Lines @('invalid row')
} 'Malformed index metadata must fail.'
Assert-Issue57Throws {
    ConvertFrom-Issue57ExplainRow -Lines @((Join-Issue57Tsv -Values @(1, 'SIMPLE', 'seat')))
} 'Malformed EXPLAIN output must fail.'
Assert-Issue57Throws {
    ConvertFrom-Issue57Scalar -Lines @('-1') -Name 'negative'
} 'Negative scalar output must fail.'

Write-Output "SeatIndexExperiment checks passed: $issue57Assertions assertions."
