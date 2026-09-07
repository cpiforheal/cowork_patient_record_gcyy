$ErrorActionPreference = "Stop"
# ASCII-only deploy: frontend dist -> live frontend; backend jar already replaced separately.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$app = Join-Path $repoRoot "release\clinic-portable\app"
$dist = Join-Path $PSScriptRoot "dist"
$deployed = Join-Path $app "frontend"
$backup = Join-Path $app ("frontend.backup-" + (Get-Date -Format "yyyyMMdd-HHmmss"))

$rb1 = Start-Process robocopy -ArgumentList @("`"$deployed`"", "`"$backup`"", "/E", "/NFL", "/NDL", "/NJH", "/NJS") -Wait -PassThru -NoNewWindow
Write-Output "BACKUP exit=$($rb1.ExitCode)"
if ($rb1.ExitCode -ge 8) { throw "backup failed" }

$rb2 = Start-Process robocopy -ArgumentList @("`"$dist`"", "`"$deployed`"", "/E", "/NFL", "/NDL", "/NJH", "/NJS") -Wait -PassThru -NoNewWindow
Write-Output "DEPLOY exit=$($rb2.ExitCode)"
if ($rb2.ExitCode -ge 8) { throw "deploy failed" }

$distHtml = [System.IO.File]::ReadAllText((Join-Path $dist "index.html"))
$dm = [regex]::Match($distHtml, 'assets/js/index-([A-Za-z0-9_-]+)\.js')
$html = [System.IO.File]::ReadAllText((Join-Path $deployed "index.html"))
$m = [regex]::Match($html, 'assets/js/index-([A-Za-z0-9_-]+)\.js')
Write-Output "BUNDLE dist=$($dm.Value) deployed=$($m.Value)"
if ($m.Value -ne $dm.Value) { throw "bundle mismatch" }
Write-Output "FRONTEND_DEPLOY_OK"
