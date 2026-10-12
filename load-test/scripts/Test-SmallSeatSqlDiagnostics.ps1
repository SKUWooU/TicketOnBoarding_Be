Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SmallSeatSqlDiagnostics.psm1') -Force
$assertions = 0
function Assert-True([bool]$condition) { $script:assertions++; if (-not $condition) { throw 'Expected true.' } }
function Assert-Throws([scriptblock]$action) { $script:assertions++; try { & $action | Out-Null } catch { return }; throw 'Expected error.' }
function Make-Row([string]$sql, [string]$values) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql)) + "`t" + $values.Replace(',', "`t")
}
$before = ConvertFrom-SmallSeatSqlCounters @( (Make-Row 'SELECT * FROM seat WHERE id=? FOR UPDATE' '2,2000000000,2,0,0'), (Make-Row 'UPDATE seat SET held_by=? WHERE id=?' '1,1000000000,1,1,0') )
$after = ConvertFrom-SmallSeatSqlCounters @( (Make-Row 'SELECT * FROM seat WHERE id=? FOR UPDATE' '5,8000000000,5,0,0'), (Make-Row 'UPDATE seat SET held_by=? WHERE id=?' '3,7000000000,3,3,0'), (Make-Row 'SELECT * FROM site_user WHERE username=?' '100,100,100,0,0') )
$delta = @(New-SmallSeatSqlDelta $before $after)
Assert-True ($delta.Count -eq 2 -and $delta[0].Executions -eq 3 -and $delta[0].StatementTotalMs -eq 6 -and $delta[0].StatementAverageMs -eq 2)
Assert-True ($delta[1].Executions -eq 2 -and $delta[1].RowsAffected -eq 2)
Assert-True (-not ($delta | ConvertTo-Json).Contains('username'))
Assert-True ((Get-SmallSeatSqlCoverage -Delta $delta -Iterations 2 -HoldSuccess 1 -ReleaseSuccess 1).Status -eq 'COUNTERS_MATCH_WORKLOAD')
$missing = @($delta | ForEach-Object { $_.PSObject.Copy() })
$missing[0].Executions = 2
Assert-True ((Get-SmallSeatSqlCoverage -Delta $missing -Iterations 2 -HoldSuccess 1 -ReleaseSuccess 1).Status -eq 'SQL_COUNTER_COVERAGE_MISMATCH')
$extra = @($delta | ForEach-Object { $_.PSObject.Copy() })
$extra[1].Executions = 3
Assert-True ((Get-SmallSeatSqlCoverage -Delta $extra -Iterations 2 -HoldSuccess 1 -ReleaseSuccess 1).Status -eq 'SQL_COUNTER_COVERAGE_MISMATCH')
Assert-Throws { Get-SmallSeatSqlCoverage -Delta $delta -Iterations 2 -HoldSuccess 1 -ReleaseSuccess 0 }
Assert-Throws { ConvertFrom-SmallSeatSqlCounters @('broken') }
Assert-Throws { ConvertFrom-SmallSeatSqlCounters @((Make-Row 'UPDATE seat SET held_by=?' '-1,0,0,0,0')) }
Assert-Throws { New-SmallSeatSqlDelta $after $before }
Assert-Throws { New-SmallSeatSqlDelta $before @{} }
Assert-Throws { & (Join-Path $PSScriptRoot 'Run-ControlledSmallSeatHoldRamp.ps1') -RunId 'invalidsql' -ComposeProject 'ticketon-controlled172-i208' -DiagnoseSql }
Write-Output "SMALL_SEAT_SQL_DIAGNOSTICS_TESTS_PASSED assertions=$assertions"
