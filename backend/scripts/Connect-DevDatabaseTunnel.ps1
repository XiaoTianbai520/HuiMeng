[CmdletBinding()]
param(
    [switch]$ConnectOnly,
    [switch]$Stop,
    [switch]$Json,
    [int]$ExpectedProcessId = 0,
    [string]$ExpectedStartTicks = ''
)

# Load protected, ignored development configuration; never pass credentials on argv.
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $backendRoot '.runtime/mysql-dev'
$connectionFile = Join-Path $runtimeRoot 'connection.json'
$stateFile = Join-Path $runtimeRoot 'tunnel.json'

function Read-OwnedTunnel {
    if (-not (Test-Path -LiteralPath $stateFile)) { return $null }
    $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    $process = Get-Process -Id ([int]$state.processId) -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    if ($process.ProcessName -ne 'ssh' -or $process.StartTime.ToUniversalTime().Ticks.ToString() -ne $state.startTicks) {
        throw 'Recorded tunnel process identity changed; inspect it manually.'
    }
    return $process
}

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false }
    finally { $client.Dispose() }
}

function Stop-OwnedTunnel {
    $owned = Read-OwnedTunnel
    if ($null -ne $owned) { Stop-Process -Id $owned.Id -ErrorAction Stop }
}

if ($Stop) {
    if ($ExpectedProcessId -gt 0) {
        if (-not (Test-Path -LiteralPath $stateFile)) { exit 0 }
        $record = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        if ([int]$record.processId -ne $ExpectedProcessId -or $record.startTicks -ne $ExpectedStartTicks) {
            exit 0
        }
    }
    Stop-OwnedTunnel
    Write-Host 'Development database tunnel stopped.'
    exit 0
}
if (-not (Test-Path -LiteralPath $connectionFile) -or
    -not (Test-Path -LiteralPath (Join-Path $runtimeRoot 'application.properties'))) {
    throw 'Protected MySQL development configuration is missing. See backend/DEBUGGING.md.'
}
$connection = Get-Content -Raw -LiteralPath $connectionFile | ConvertFrom-Json
if ($connection.sshTarget -notmatch '^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+$' -or
    $connection.remoteHost -ne '127.0.0.1' -or [int]$connection.remotePort -ne 3306 -or
    [int]$connection.localPort -lt 1024 -or [int]$connection.localPort -gt 65535) {
    throw 'Invalid development tunnel configuration.'
}
$localPort = [int]$connection.localPort
$createdTunnel = $false
$createdTunnelProcessId = $null
$createdTunnelStartTicks = $null
$owned = Read-OwnedTunnel
if (Test-LocalPort $localPort) {
    if ($null -eq $owned) { throw 'Local port is occupied by an unowned process; no changes made.' }
} else {
    if ($null -ne $owned) { throw 'Owned SSH process exists but its port is unavailable; inspect before restarting.' }
    $ssh = (Get-Command ssh -ErrorAction Stop).Source
    $forward = '127.0.0.1:{0}:127.0.0.1:3306' -f $localPort
    $arguments = @('-N', '-T', '-n', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ExitOnForwardFailure=yes', '-o', 'ConnectTimeout=10',
        '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=3',
        '-L', $forward, [string]$connection.sshTarget)
    $process = Start-Process -FilePath $ssh -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardError (Join-Path $runtimeRoot 'tunnel.stderr.log') `
        -RedirectStandardOutput (Join-Path $runtimeRoot 'tunnel.stdout.log')
    try {
        @{ processId = $process.Id; startTicks = $process.StartTime.ToUniversalTime().Ticks.ToString() } |
            ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
        $createdTunnel = $true
        $createdTunnelProcessId = $process.Id
        $createdTunnelStartTicks = $process.StartTime.ToUniversalTime().Ticks.ToString()
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            if (Test-LocalPort $localPort) { $ready = $true; break }
            if ($process.HasExited) { break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'SSH tunnel did not become ready; inspect the protected tunnel log.' }
    } catch {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id }
        throw
    }
}
if ($Json) {
    if (-not $ConnectOnly) { throw 'JSON mode requires ConnectOnly.' }
    $lease = Read-OwnedTunnel
    @{ processId = $lease.Id; startTicks = $lease.StartTime.ToUniversalTime().Ticks.ToString(); created = $createdTunnel } |
        ConvertTo-Json -Compress | Write-Output
} else {
    Write-Host ('Development database tunnel ready on loopback port {0}.' -f $localPort)
}
if ($ConnectOnly) { exit 0 }
