[CmdletBinding()]
param([switch]$ConnectOnly, [switch]$Stop)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if ($ConnectOnly -or $Stop) {
    $mode = if ($Stop) { '-Stop' } else { '-ConnectOnly' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Connect-DevDatabaseTunnel.ps1') $mode
    exit $LASTEXITCODE
}
if (-not (Test-Path -LiteralPath (Join-Path $backendRoot '.runtime/mysql-dev/application.properties'))) {
    throw 'Protected development configuration is missing. See backend/DEBUGGING.md.'
}
try {
    Push-Location -LiteralPath $backendRoot
    $env:MAVEN_USER_HOME = Join-Path $backendRoot '.m2'
    & (Join-Path $backendRoot 'mvnw.cmd') -s .mvn/settings.xml -B -ntp spring-boot:run `
        '-Dspring-boot.run.main-class=cn.huimeng.HuiMengApplication' `
        '-Dspring-boot.run.arguments=--spring.profiles.active=local'
    if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed; inspect the startup error.' }
} finally { Pop-Location }
