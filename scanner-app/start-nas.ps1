param(
    [int]$Port = 18080,
    [string]$BindAddress = '',
    [string]$PythonExe = '',
    [switch]$Lan
)
# -Lan là cách ngắn, an toàn để mở cho điện thoại: không phải gõ địa chỉ IP
# và tránh lỗi dấu gạch Unicode khi copy lệnh -BindAddress 0.0.0.0.
if ($Lan -and -not $BindAddress) { $BindAddress = '0.0.0.0' }
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$jarPath = Join-Path $projectDir 'target-nas\scanner-app-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Chua build jar. Chay build truoc (xem README-NAS.md). Duong dan can co: $jarPath"
}
$javaExe = (Get-Command java -ErrorAction Stop).Source
$pythonExe = if ($PythonExe) { $PythonExe } else { Join-Path $projectDir '.venv\Scripts\python.exe' }
if (-not (Test-Path -LiteralPath $pythonExe)) { throw "OCR Python not found: $pythonExe. See README-OCR.md or pass -PythonExe." }
$pythonExe = (Resolve-Path -LiteralPath $pythonExe).Path
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
$javaArgs = @('-jar', ('"{0}"' -f $jarPath), '--spring.profiles.active=nas', "--server.port=$Port")
if ($BindAddress) { $javaArgs += "--server.address=$BindAddress" }
$appProcess = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $projectDir -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
@{ pid = $appProcess.Id; started = $appProcess.StartTime.ToString('o'); port = $Port; address = $BindAddress; jar = $jarPath } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectDir '.nas-process.json') -Encoding UTF8
if ($BindAddress) {
    Write-Output "Scanner NAS starting (PID $($appProcess.Id)) on ${BindAddress}:$Port."
} else {
    Write-Output "Scanner NAS starting (PID $($appProcess.Id)) port $Port (dia chi lay theo application-nas.properties)."
}
if ($BindAddress -eq '0.0.0.0') {
    Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Write-Output "LAN: http://$($_.IPAddress):$Port/" }
} else {
    Write-Output "Open http://localhost:$Port/ after startup."
}
Write-Output "Log: $stdout"
