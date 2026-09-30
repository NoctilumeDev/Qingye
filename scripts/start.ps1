param([switch]$Redis, [switch]$Mq, [switch]$SkipBuild, [switch]$RealWechat)
$ErrorActionPreference = 'Stop'
$qingyeDirectory = Split-Path -Parent $PSScriptRoot
$qingyeLocalConfig = Join-Path $qingyeDirectory 'local.ps1'
if (Test-Path -LiteralPath $qingyeLocalConfig) { . $qingyeLocalConfig }
if ($RealWechat) { $env:QINGYE_DEMO = 'false' } else { $env:QINGYE_DEMO = 'true' }
$env:QINGYE_REDIS = $Redis.IsPresent.ToString().ToLowerInvariant()
$env:QINGYE_MQ = $Mq.IsPresent.ToString().ToLowerInvariant()
Push-Location (Join-Path $qingyeDirectory 'backend')
try {
    if (-not $SkipBuild) {
        & mvn -o -q '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Offline Maven build failed; check installed dependency versions.' }
    }
    & java -jar target/qingye-0.1.0.jar
} finally { Pop-Location }
