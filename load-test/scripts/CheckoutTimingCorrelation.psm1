Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'CheckoutSaturationAttribution.psm1') -Force

function Get-CheckoutTimingMedian {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    $sorted = @($Values | Sort-Object); $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}
function New-CheckoutTimingRange { param([Parameter(Mandatory = $true)][double[]]$Values) [pscustomobject]@{ Median=(Get-CheckoutTimingMedian $Values); Minimum=[double](($Values|Measure-Object -Minimum).Minimum); Maximum=[double](($Values|Measure-Object -Maximum).Maximum) } }

function Assert-CheckoutTimingCorrelationSummary {
    param([Parameter(Mandatory = $true)][object]$Summary, [Parameter(Mandatory = $true)][string]$RunId, [Parameter(Mandatory = $true)][int]$Rate)
    Assert-CheckoutSaturationSummary -Summary $Summary -RunId $RunId -Rate $Rate | Out-Null
    if ($null -eq $Summary.HikariAcquire -or $null -eq $Summary.StatementDiagnostics) { throw "Timing diagnostics are missing: $RunId" }
    foreach ($value in @($Summary.HikariAcquire.AcquireCount, $Summary.HikariAcquire.AcquireWaitMilliseconds, $Summary.HikariAcquire.TimeoutCount, $Summary.StatementDiagnostics.StatementCount, $Summary.StatementDiagnostics.ExecutionMilliseconds, $Summary.StatementDiagnostics.LockMilliseconds, $Summary.StatementDiagnostics.CompletedIterations, $Summary.StatementDiagnostics.DroppedIterations, $Summary.StatementDiagnostics.ScheduledIterations, $Summary.StatementDiagnostics.StatementsPerCompletedIteration, $Summary.StatementDiagnostics.ExecutionMillisecondsPerCompletedIteration, $Summary.StatementDiagnostics.LockMillisecondsPerCompletedIteration)) { if ([double]$value -lt 0) { throw "Negative timing diagnostic: $RunId" } }
    if ([long]$Summary.StatementDiagnostics.CompletedIterations -le 0 -or [long]$Summary.StatementDiagnostics.CompletedIterations + [long]$Summary.StatementDiagnostics.DroppedIterations -ne [long]$Summary.StatementDiagnostics.ScheduledIterations) { throw "Invalid completed iteration normalization: $RunId" }
    $true
}

function New-CheckoutTimingCorrelationAggregate {
    param([Parameter(Mandatory = $true)][object[]]$Records)
    $out = New-Object 'Collections.Generic.List[object]'
    foreach ($group in ($Records | Where-Object { -not $_.Warmup } | Group-Object Rate | Sort-Object { [int]$_.Name })) {
        $items=@($group.Group); if ($items.Count -lt 3) { throw "Rate $($group.Name) needs three measurements." }
        foreach ($item in $items) { Assert-CheckoutTimingCorrelationSummary -Summary $item.Summary -RunId $item.RunId -Rate ([int]$group.Name) | Out-Null }
        $summaries=@($items|ForEach-Object {$_.Summary})
        $out.Add([pscustomobject]@{ RatePerSecond=[int]$group.Name; MeasuredRepeatCount=$items.Count; WarmupExcluded=$true; Metrics=[pscustomobject]@{
            HikariAcquireWaitMilliseconds=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.HikariAcquire.AcquireWaitMilliseconds})
            HikariAcquireAverageMilliseconds=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.HikariAcquire.AverageAcquireWaitMilliseconds})
            HikariTimeouts=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.HikariAcquire.TimeoutCount})
            DbStatementExecutionMilliseconds=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.ExecutionMilliseconds})
            DbStatementLockMilliseconds=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.LockMilliseconds})
            DbStatementCount=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.StatementCount})
            DbStatementExecutionMillisecondsPerCompletedIteration=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.ExecutionMillisecondsPerCompletedIteration})
            DbStatementLockMillisecondsPerCompletedIteration=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.LockMillisecondsPerCompletedIteration})
            DbStatementsPerCompletedIteration=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.StatementsPerCompletedIteration})
            CompletedIterations=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.CompletedIterations})
            DroppedIterations=New-CheckoutTimingRange @($summaries|ForEach-Object {[double]$_.StatementDiagnostics.DroppedIterations})
        } })
    }
    @($out.ToArray() | Sort-Object RatePerSecond)
}
Export-ModuleMember -Function @('Assert-CheckoutTimingCorrelationSummary','New-CheckoutTimingCorrelationAggregate')
