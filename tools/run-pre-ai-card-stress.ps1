$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$outerRoot = Split-Path -Parent $projectRoot
$releaseRoot = Join-Path $outerRoot "release\clinic-portable"
$runtimeEnv = @{}
Get-Content -LiteralPath (Join-Path $releaseRoot "config\runtime.env") -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        $runtimeEnv[$parts[0].Trim()] = $parts[1].Trim().Replace('${PACKAGE_ROOT}', $releaseRoot)
    }
}

$mysql = Join-Path $releaseRoot "runtime\mysql\bin\mysql.exe"
$query = "SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.currentPassword')) FROM clinic_accounts WHERE username = 'admin' LIMIT 1;"
$password = & $mysql `
    "--host=127.0.0.1" `
    "--port=$($runtimeEnv.MYSQL_PORT)" `
    "--user=$($runtimeEnv.MYSQL_USERNAME)" `
    "--password=$($runtimeEnv.MYSQL_PASSWORD)" `
    "--database=$($runtimeEnv.MYSQL_DATABASE)" `
    "--default-character-set=utf8mb4" `
    "--batch" `
    "--skip-column-names" `
    "--execute=$query"

if ($LASTEXITCODE -ne 0 -or -not $password) {
    throw "Unable to load the local admin credential for the deployment-only stress test."
}

$env:PRE_AI_TEST_PASSWORD = ($password | Select-Object -First 1).Trim()
& pnpm dlx playwright@1.55.0 test tools/pre-ai-card-stress.spec.js --workers=1 --reporter=line
exit $LASTEXITCODE
