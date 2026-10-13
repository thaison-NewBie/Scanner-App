$ErrorActionPreference = 'Stop'
$statePath = Join-Path $PSScriptRoot '.nas-process.json'
if (-not (Test-Path -LiteralPath $statePath)) { throw 'No Scanner NAS process record found.' }
$record = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$appProcess = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
if ($appProcess) {
    $sameStart = $appProcess.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.started).ToUniversalTime().Ticks
    if ($appProcess.ProcessName -ne 'java' -or -not $sameStart) {
        throw 'Process no longer matches Scanner NAS. Nothing was stopped.'
    }
    Stop-Process -Id $appProcess.Id
}
Remove-Item -LiteralPath $statePath
Write-Output 'Scanner NAS stopped.'
