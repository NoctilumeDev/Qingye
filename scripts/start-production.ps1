param()
$ErrorActionPreference = 'Stop'

function Stop-QingyeStartup([string]$Message) {
    Write-Error -Message $Message -ErrorAction Continue
    exit 1
}

# This entry point belongs to the candidate package. Never source local.ps1.
$qingyePackage = Split-Path -Parent $PSScriptRoot
$qingyeJar = Join-Path $qingyePackage 'backend/qingye-0.1.0.jar'
$qingyeRequired = @('QINGYE_DB_URL', 'QINGYE_DB_USER', 'QINGYE_DB_PASSWORD',
    'QINGYE_TOKEN_SECRET', 'QINGYE_WX_APPID', 'QINGYE_WX_SECRET')
foreach ($qingyeName in $qingyeRequired) {
    $qingyeValue = [Environment]::GetEnvironmentVariable($qingyeName)
    if ([string]::IsNullOrWhiteSpace($qingyeValue) -or $qingyeValue -match 'REPLACE_WITH_|CHANGE_ME|^<.*>$') {
        Stop-QingyeStartup "Set $qingyeName to the deployment value; placeholders cannot start Qingye."
    }
}
if ($env:QINGYE_TOKEN_SECRET.Length -lt 32) {
    Stop-QingyeStartup 'QINGYE_TOKEN_SECRET must contain at least 32 characters.'
}
if ($env:QINGYE_TOKEN_SECRET -eq $env:QINGYE_WX_SECRET) {
    Stop-QingyeStartup 'Use an independent QINGYE_TOKEN_SECRET, not the WeChat secret.'
}
if ($env:QINGYE_DB_URL -notmatch '^jdbc:mysql://[^/?\s]+/[^/?#\s]+(?:\?[^\s]*)?$') {
    Stop-QingyeStartup 'QINGYE_DB_URL must name a MySQL database.'
}
if (-not (Test-Path -LiteralPath $qingyeJar -PathType Leaf)) {
    Stop-QingyeStartup 'The package backend/qingye-0.1.0.jar is missing.'
}
$env:QINGYE_DEMO = 'false'
$env:QINGYE_REDIS = 'false'
$env:QINGYE_MQ = 'false'
if ([string]::IsNullOrWhiteSpace($env:QINGYE_BIND)) { $env:QINGYE_BIND = '127.0.0.1' }

$qingyeJavaExit = 1
Push-Location -LiteralPath $qingyePackage
try {
    # Keep the database task worker: it delivers notifications without RabbitMQ.
    & java -jar $qingyeJar '--qingye.demo-enabled=false' '--qingye.redis-enabled=false' '--qingye.mq-enabled=false' '--qingye.worker-enabled=true' '--spring.config.location=classpath:/application.yml'
    $qingyeJavaExit = $LASTEXITCODE
} catch {
    Write-Error -Message 'Java could not start Qingye; check Java 17 and the package.' -ErrorAction Continue
} finally {
    Pop-Location
}
exit $qingyeJavaExit
