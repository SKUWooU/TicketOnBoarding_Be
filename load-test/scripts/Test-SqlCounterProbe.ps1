Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SqlCounterProbe.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'SmallSeatSqlDiagnostics.psm1') -Force
$assertions = 0
function Assert-True([bool]$value) { $script:assertions++; if (-not $value) { throw 'Expected true.' } }
function Assert-Throws([scriptblock]$action) { $script:assertions++; try { & $action | Out-Null } catch { return }; throw 'Expected error.' }
$plan = @(Get-SqlCounterProbePlan)
Assert-True ($plan.Count -eq 6 -and ($plan.Mode -join ',') -eq 'serial,parallel,parallel,serial,serial,parallel')
Assert-True (($plan.ExpectedTransactions | Measure-Object -Sum).Sum -eq 600)
$sql = Get-SqlCounterProbeWorkload -Worker 1
Assert-True (([regex]::Matches($sql, 'COMMIT;')).Count -eq 25)
Assert-True (([regex]::Matches($sql, 'FOR UPDATE;')).Count -eq 25)
Assert-Throws { Get-SqlCounterProbeWorkload -Worker 5 }
$good = ((1..25 | ForEach-Object { "0`ncycle-complete" }) -join "`n")
Assert-True ((Assert-SqlCounterProbeCompletion -Output $good -ExitCode 0) -eq 25)
Assert-Throws { Assert-SqlCounterProbeCompletion -Output $good -ExitCode 1 }
Assert-Throws { Assert-SqlCounterProbeCompletion -Output '0' -ExitCode 0 }
Assert-Throws { Assert-SqlCounterProbeCompletion -Output $good.Replace('cycle-complete','wrong') -ExitCode 0 }
function Row([string]$sql) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql)) + "`t1`t100`t1`t0`t0" }
$parsed = ConvertFrom-SmallSeatSqlCounters -Lines @(
    (Row 'SELECT `s1_0` . `counter` FROM `seat` `s1_0` WHERE `s1_0` . `id` = ? FOR UPDATE'),
    (Row 'UPDATE `seat` SET `counter` = `counter` + ? WHERE ID = ?'),
    (Row 'SELECT * FROM `reservation_checkout_seat_assignment` FOR UPDATE')
)
Assert-True ($parsed.Count -eq 2 -and $parsed['seat-lock-select'][0] -eq 1 -and $parsed['seat-update'][0] -eq 1)
$checked = & (Join-Path $PSScriptRoot 'Run-SqlCounterProbe.ps1') -RunId 'check210' -ComposeProject 'ticketon-controlled172-check210' -CheckOnly | ConvertFrom-Json
Assert-True ($checked.Count -eq 6)
Write-Output "SQL_COUNTER_PROBE_TESTS_PASSED assertions=$assertions"
