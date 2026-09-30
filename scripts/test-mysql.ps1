$ErrorActionPreference = 'Stop'
$qingyeDirectory = Split-Path -Parent $PSScriptRoot
$qingyeLocalConfig = Join-Path $qingyeDirectory 'local.ps1'
if (Test-Path -LiteralPath $qingyeLocalConfig) { . $qingyeLocalConfig }
$env:QINGYE_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/qingye_test?serverTimezone=Asia/Shanghai'
$env:QINGYE_TEST_USER = $env:QINGYE_DB_USER
$env:QINGYE_TEST_PASSWORD = $env:QINGYE_DB_PASSWORD
Push-Location (Join-Path $qingyeDirectory 'backend')
try { & mvn -o test; if ($LASTEXITCODE -ne 0) { throw 'MySQL verification failed.' } }
finally { Pop-Location }
