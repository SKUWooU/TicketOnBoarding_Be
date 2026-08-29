Set-StrictMode -Version Latest

$script:Issue57CompositeIndexName = 'uk_seat_concert_time_number'
$script:Issue57RestoreIndexName = 'idx_seat_concert_time_ab_restore'

function Invoke-Issue57Query {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$QueryExecutor,

        [Parameter(Mandatory = $true)]
        [string]$Query
    )

    @(& $QueryExecutor $Query)
}

function ConvertFrom-Issue57Scalar {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $issue57Values = @($Lines | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($issue57Values.Count -ne 1) {
        throw "$Name query must return exactly one row."
    }
    $issue57Value = 0L
    if (-not [long]::TryParse($issue57Values[0].Trim(), [ref]$issue57Value) -or $issue57Value -lt 0) {
        throw "$Name query returned an invalid non-negative integer: $($issue57Values[0])"
    }
    $issue57Value
}

function ConvertFrom-Issue57IndexRows {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Lines)

    $issue57Rows = New-Object 'Collections.Generic.List[object]'
    foreach ($issue57LineValue in $Lines) {
        $issue57Line = [string]$issue57LineValue
        if ([string]::IsNullOrWhiteSpace($issue57Line)) {
            continue
        }
        $issue57Parts = $issue57Line.Split([char[]]@([char]9))
        if ($issue57Parts.Count -ne 4) {
            throw "Seat index row must contain 4 tab-separated columns: $issue57Line"
        }
        $issue57NonUnique = 0
        $issue57Sequence = 0
        if (-not [int]::TryParse($issue57Parts[1], [ref]$issue57NonUnique) -or
            -not [int]::TryParse($issue57Parts[2], [ref]$issue57Sequence) -or
            $issue57NonUnique -notin @(0, 1) -or
            $issue57Sequence -le 0) {
            throw "Seat index row contains invalid metadata: $issue57Line"
        }
        $issue57Rows.Add([pscustomobject]@{
            IndexName = $issue57Parts[0]
            NonUnique = $issue57NonUnique
            Sequence = $issue57Sequence
            ColumnName = $issue57Parts[3]
        })
    }
    $issue57Rows.ToArray()
}

function ConvertFrom-Issue57ExplainRow {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$Lines)

    $issue57Values = @($Lines | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($issue57Values.Count -ne 1) {
        throw 'Seat lock EXPLAIN must return exactly one row.'
    }
    $issue57Parts = $issue57Values[0].Split([char[]]@([char]9))
    if ($issue57Parts.Count -lt 10) {
        throw "Seat lock EXPLAIN row must contain at least 10 tab-separated columns: $($issue57Values[0])"
    }
    $issue57Rows = 0L
    if (-not [long]::TryParse($issue57Parts[8], [ref]$issue57Rows) -or $issue57Rows -le 0) {
        throw "Seat lock EXPLAIN returned an invalid row estimate: $($issue57Parts[8])"
    }
    [pscustomobject]@{
        AccessType = $issue57Parts[3]
        Key = if ($issue57Parts[5] -eq 'NULL') { $null } else { $issue57Parts[5] }
        EstimatedRows = $issue57Rows
        Extra = if ($issue57Parts[9] -eq 'NULL') { '' } else { $issue57Parts[9] }
    }
}

function Get-Issue57IndexRows {
    param(
        [scriptblock]$QueryExecutor,
        [string]$DatabaseName
    )

    ConvertFrom-Issue57IndexRows -Lines @(Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @"
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = '$DatabaseName'
  AND TABLE_NAME = 'seat'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
"@)
}

function Get-Issue57DuplicateGroupCount {
    param([scriptblock]$QueryExecutor)

    ConvertFrom-Issue57Scalar -Name 'Duplicate seat group count' -Lines @(Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @'
SELECT COUNT(*)
FROM (
  SELECT concert_time_id, seat_number
  FROM seat
  GROUP BY concert_time_id, seat_number
  HAVING COUNT(*) > 1
) duplicate_seats;
'@)
}

function Clear-Issue57LoadTestFixtures {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][scriptblock]$QueryExecutor)

    Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @'
DELETE FROM reservation_payment
WHERE provider_payment_id LIKE 'LT:load-user-%';
DELETE FROM reservation
WHERE concert_id LIKE 'LOAD-TEST-%';
DELETE FROM reservation_booking
WHERE idempotency_key LIKE 'lt-%';
DELETE FROM review
WHERE concert_id LIKE 'LOAD-TEST-%';
DELETE seat
FROM seat
JOIN concert_time ON concert_time.id = seat.concert_time_id
WHERE concert_time.concert_id LIKE 'LOAD-TEST-%';
DELETE FROM concert_time
WHERE concert_id LIKE 'LOAD-TEST-%';
DELETE FROM concert_detail
WHERE concert_id LIKE 'LOAD-TEST-%';
DELETE FROM concert
WHERE concert_id LIKE 'LOAD-TEST-%';
'@ | Out-Null

    $issue57RemainingSeatRows = ConvertFrom-Issue57Scalar `
        -Name 'Seat table row count after load-test cleanup' `
        -Lines @(Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query 'SELECT COUNT(*) FROM seat;')
    if ($issue57RemainingSeatRows -ne 0) {
        throw "Seat index A/B requires an exclusive diagnostic database with zero seat rows before fixture creation: actual=$issue57RemainingSeatRows"
    }
    [pscustomobject]@{
        LoadTestFixturesRemoved = $true
        SeatRowsAfterCleanup = $issue57RemainingSeatRows
    }
}

function Get-Issue57PhysicalSeatRowCount {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][scriptblock]$QueryExecutor)

    ConvertFrom-Issue57Scalar `
        -Name 'Physical seat table row count' `
        -Lines @(Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query 'SELECT COUNT(*) FROM seat;')
}

function Assert-Issue57MeasurementHealth {
    [CmdletBinding()]
    param(
        [ValidateRange(0, 9223372036854775807)]
        [long]$UnexpectedNonSuccessful,

        [ValidateRange(0, 9223372036854775807)]
        [long]$DeadlocksDelta
    )

    if ($UnexpectedNonSuccessful -ne 0) {
        throw "Seat index A/B requires zero unexpected non-successful responses: actual=$UnexpectedNonSuccessful"
    }
    if ($DeadlocksDelta -ne 0) {
        throw "Seat index A/B requires zero database deadlocks: actual=$DeadlocksDelta"
    }
    $true
}

function Set-Issue57SeatIndexVariant {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('current', 'composite')]
        [string]$Variant,

        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z0-9_]+$')]
        [string]$DatabaseName,

        [Parameter(Mandatory = $true)]
        [scriptblock]$QueryExecutor
    )

    $issue57DuplicateGroups = Get-Issue57DuplicateGroupCount -QueryExecutor $QueryExecutor
    if ($Variant -eq 'composite' -and $issue57DuplicateGroups -ne 0) {
        throw "Composite seat index transition requires zero duplicate seat groups: actual=$issue57DuplicateGroups"
    }

    $issue57IndexRows = @(Get-Issue57IndexRows -QueryExecutor $QueryExecutor -DatabaseName $DatabaseName)
    $issue57CompositePresent = @($issue57IndexRows | Where-Object { $_.IndexName -eq $script:Issue57CompositeIndexName }).Count -gt 0
    $issue57Changed = $false
    $issue57SupportIndexCreated = $false

    if ($Variant -eq 'composite' -and -not $issue57CompositePresent) {
        Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @"
CREATE UNIQUE INDEX $($script:Issue57CompositeIndexName)
ON seat (concert_time_id, seat_number);
"@ | Out-Null
        $issue57Changed = $true
    }
    if ($Variant -eq 'current' -and $issue57CompositePresent) {
        $issue57HasForeignKeySupport = @($issue57IndexRows | Where-Object {
            $_.IndexName -ne $script:Issue57CompositeIndexName -and
            $_.Sequence -eq 1 -and
            $_.ColumnName -eq 'concert_time_id'
        }).Count -gt 0
        if (-not $issue57HasForeignKeySupport) {
            Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @"
CREATE INDEX $($script:Issue57RestoreIndexName)
ON seat (concert_time_id);
"@ | Out-Null
            $issue57SupportIndexCreated = $true
        }
        Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query "DROP INDEX $($script:Issue57CompositeIndexName) ON seat;" | Out-Null
        $issue57Changed = $true
    }

    [pscustomobject]@{
        Variant = $Variant
        DuplicateGroupCount = $issue57DuplicateGroups
        Changed = $issue57Changed
        ForeignKeySupportIndexCreated = $issue57SupportIndexCreated
    }
}

function Get-Issue57SeatIndexEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('current', 'composite')]
        [string]$Variant,

        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z0-9_]+$')]
        [string]$DatabaseName,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 9223372036854775807)]
        [long]$ConcertTimeId,

        [Parameter(Mandatory = $true)]
        [scriptblock]$QueryExecutor
    )

    $issue57DuplicateGroups = Get-Issue57DuplicateGroupCount -QueryExecutor $QueryExecutor
    $issue57IndexRows = @(Get-Issue57IndexRows -QueryExecutor $QueryExecutor -DatabaseName $DatabaseName)
    $issue57CompositeRows = @($issue57IndexRows |
        Where-Object { $_.IndexName -eq $script:Issue57CompositeIndexName } |
        Sort-Object -Property Sequence)
    $issue57Plan = ConvertFrom-Issue57ExplainRow -Lines @(Invoke-Issue57Query -QueryExecutor $QueryExecutor -Query @"
EXPLAIN SELECT *
FROM seat
WHERE concert_time_id = $ConcertTimeId
  AND seat_number = 'R001-S001'
FOR UPDATE;
"@)

    if ($issue57DuplicateGroups -ne 0) {
        throw "Seat index evidence contains duplicate seat groups: actual=$issue57DuplicateGroups"
    }
    if ($Variant -eq 'current') {
        if ($issue57CompositeRows.Count -ne 0) {
            throw 'Current index variant must not contain the composite unique index.'
        }
        if ($issue57Plan.Key -eq $script:Issue57CompositeIndexName -or $issue57Plan.EstimatedRows -le 1) {
            throw 'Current index variant must retain the broad seat lookup plan.'
        }
    } else {
        $issue57Columns = @($issue57CompositeRows | ForEach-Object { $_.ColumnName })
        if ($issue57CompositeRows.Count -ne 2 -or
            ($issue57Columns -join ',') -ne 'concert_time_id,seat_number' -or
            @($issue57CompositeRows | Where-Object { $_.NonUnique -ne 0 }).Count -ne 0) {
            throw 'Composite index variant must contain the expected unique column order.'
        }
        if ($issue57Plan.Key -ne $script:Issue57CompositeIndexName -or $issue57Plan.EstimatedRows -ne 1) {
            throw 'Composite index variant must use the composite index with a one-row estimate.'
        }
    }

    [pscustomobject]@{
        Variant = $Variant
        DuplicateGroupCount = $issue57DuplicateGroups
        CompositeIndexPresent = $issue57CompositeRows.Count -gt 0
        CompositeIndexName = if ($issue57CompositeRows.Count -gt 0) { $script:Issue57CompositeIndexName } else { $null }
        CompositeColumns = @($issue57CompositeRows | ForEach-Object { $_.ColumnName })
        CompositeUnique = $issue57CompositeRows.Count -gt 0 -and @($issue57CompositeRows | Where-Object { $_.NonUnique -ne 0 }).Count -eq 0
        QueryPlan = $issue57Plan
        SqlTextIncluded = $false
    }
}

Export-ModuleMember -Function @(
    'ConvertFrom-Issue57Scalar',
    'ConvertFrom-Issue57IndexRows',
    'ConvertFrom-Issue57ExplainRow',
    'Clear-Issue57LoadTestFixtures',
    'Get-Issue57PhysicalSeatRowCount',
    'Assert-Issue57MeasurementHealth',
    'Set-Issue57SeatIndexVariant',
    'Get-Issue57SeatIndexEvidence'
)
