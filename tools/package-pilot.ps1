param(
    [string]$OutputDir = "release\pilot",
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$backendDir = Join-Path $root "coshare_patientrecord_sys_backend"
$frontendDir = Join-Path $root "coshare_patientrecord_sys_frontend\Geeker-Admin"
$releaseDir = Join-Path $root $OutputDir
$backendReleaseDir = Join-Path $releaseDir "backend"
$frontendReleaseDir = Join-Path $releaseDir "frontend"
$runtimeDir = Join-Path $releaseDir "runtime"
$toolsReleaseDir = Join-Path $releaseDir "tools"

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

Write-Host "==> Cleaning release directory: $releaseDir"
if (Test-Path $releaseDir) {
    Remove-Item -LiteralPath $releaseDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $backendReleaseDir, $frontendReleaseDir, $runtimeDir, $toolsReleaseDir | Out-Null

Write-Host "==> Building backend"
Push-Location $backendDir
try {
    .\mvnw.cmd clean package -DskipTests
}
finally {
    Pop-Location
}

$jar = Get-ChildItem -Path (Join-Path $backendDir "target") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*plain*" -and $_.Name -notlike "*sources*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "Backend jar was not generated."
}

Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $backendReleaseDir "app.jar")

Write-Host "==> Building frontend"
Push-Location $frontendDir
try {
    pnpm build:pro
}
finally {
    Pop-Location
}

Copy-Item -Path (Join-Path $frontendDir "dist\*") -Destination $frontendReleaseDir -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "check-pilot-host.ps1") -Destination (Join-Path $toolsReleaseDir "check-pilot-host.ps1")

$startScript = @'
@echo off
setlocal

if "%SERVER_PORT%"=="" set SERVER_PORT=8080
if "%MYSQL_URL%"=="" set MYSQL_URL=jdbc:mysql://localhost:3306/hos_refactor?useUnicode=true^&characterEncoding=utf8^&serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true
if "%MYSQL_USERNAME%"=="" set MYSQL_USERNAME=root
if "%MYSQL_PASSWORD%"=="" set MYSQL_PASSWORD=123456
if "%CLINIC_ATTACHMENT_DIR%"=="" set CLINIC_ATTACHMENT_DIR=%~dp0runtime\attachments

if not exist "%CLINIC_ATTACHMENT_DIR%" mkdir "%CLINIC_ATTACHMENT_DIR%"

java -jar "%~dp0backend\app.jar"
'@

$startScript | Set-Content -Path (Join-Path $releaseDir "start-backend.cmd") -Encoding ASCII

$envSample = @'
SERVER_PORT=8080
MYSQL_URL=jdbc:mysql://localhost:3306/hos_refactor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=123456
CLINIC_ATTACHMENT_DIR=D:\hos_patient_record_runtime\attachments
'@

$envSample | Set-Content -Path (Join-Path $releaseDir "env.example.txt") -Encoding ASCII

Write-Host "==> Pilot package ready: $releaseDir"
Write-Host "    backend:  backend\app.jar"
Write-Host "    frontend: frontend\index.html"
Write-Host "    start:    start-backend.cmd"
Write-Host "    check:    tools\check-pilot-host.ps1"
