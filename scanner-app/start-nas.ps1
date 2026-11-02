param(
    [int]$Port = 18080,
    [string]$BindAddress = '127.0.0.1'
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$jarPath = Join-Path $projectDir 'target-nas\scanner-app-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build target-nas/scanner-app-1.0.0.jar first. See README-NAS.md.' }
$javaExe = (Get-Command java -ErrorAction Stop).Source
$pythonExe = Join-Path $projectDir '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonExe)) { throw 'Install the OCR Python environment first. See README-OCR.md.' }
$env:SCANNER_PYTHON = $pythonExe
$portCheck = [Net.Sockets.TcpClient]::new()
try {
    $connection = $portCheck.ConnectAsync('127.0.0.1', $Port)
    try { $null = $connection.Wait(1000) } catch {}
    if ($portCheck.Connected) { throw "Port $Port is already in use. No process was stopped." }
} finally { $portCheck.Dispose() }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $projectDir "nas-$stamp.log"
$stderr = Join-Path $projectDir "nas-$stamp-error.log"
$javaArgs = @('-jar', ('"{0}"' -f $jarPath), '--spring.profiles.active=nas', "--server.port=$Port", "--server.address=$BindAddress")
$appProcess = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $projectDir -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
@{ pid = $appProcess.Id; started = $appProcess.StartTime.ToString('o'); port = $Port; address = $BindAddress; jar = $jarPath } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectDir '.nas-process.json') -Encoding UTF8
Write-Output "Scanner NAS starting (PID $($appProcess.Id)) on ${BindAddress}:$Port."
if ($BindAddress -eq '0.0.0.0') {
    Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Write-Output "LAN: http://$($_.IPAddress):$Port/" }
} else {
    Write-Output "Open http://localhost:$Port/ after startup."
}
Write-Output "Log: $stdout"
