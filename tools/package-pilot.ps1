param(
    [string]$OutputDir = "release\clinic-pilot-package",
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$backendDir = Join-Path $root "coshare_patientrecord_sys_backend"
$frontendDir = Join-Path $root "coshare_patientrecord_sys_frontend\Geeker-Admin"
$releaseDir = Join-Path $root $OutputDir
$backendReleaseDir = Join-Path $releaseDir "backend"
$frontendReleaseDir = Join-Path $releaseDir "frontend"
$configReleaseDir = Join-Path $releaseDir "config"
$docsReleaseDir = Join-Path $releaseDir "docs"
$logsReleaseDir = Join-Path $releaseDir "logs"
$runtimeReleaseDir = Join-Path $releaseDir "runtime"
$toolsReleaseDir = Join-Path $releaseDir "tools"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Utf8File {
    param(
        [string]$Path,
        [string]$Content
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

Write-Step "Create release directory: $releaseDir"
if (Test-Path $releaseDir) {
    Remove-Item -LiteralPath $releaseDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path @(
    $backendReleaseDir,
    $frontendReleaseDir,
    $configReleaseDir,
    $docsReleaseDir,
    $logsReleaseDir,
    $runtimeReleaseDir,
    $toolsReleaseDir
) | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $runtimeReleaseDir "attachments") | Out-Null

if (-not $SkipBuild) {
    Write-Step "Build backend jar"
    Push-Location $backendDir
    try {
        .\mvnw.cmd clean package -DskipTests
    }
    finally {
        Pop-Location
    }

    Write-Step "Build frontend static files"
    Push-Location $frontendDir
    try {
        pnpm build:pro
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Step "Skip build and reuse existing target/dist outputs"
}

$jar = Get-ChildItem -Path (Join-Path $backendDir "target") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*plain*" -and $_.Name -notlike "*sources*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "Backend jar was not found. Install JDK 17 and run this script again."
}

$frontendDist = Join-Path $frontendDir "dist"
if (-not (Test-Path (Join-Path $frontendDist "index.html"))) {
    throw "Frontend dist\index.html was not found. Install Node/pnpm and run this script again."
}

Write-Step "Copy runtime files"
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $backendReleaseDir "app.jar")
Copy-Item -Path (Join-Path $frontendDist "*") -Destination $frontendReleaseDir -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "check-pilot-host.ps1") -Destination (Join-Path $toolsReleaseDir "check-pilot-host.ps1")

$runtimeEnv = @'
# Runtime config for the production single-node host.
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=mysql,prod
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_URL=jdbc:mysql://db.internal.example:3306/hos_refactor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY
MYSQL_RUNTIME_USERNAME=clinic_app
MYSQL_RUNTIME_PASSWORD=CHANGE_ME_RUNTIME_PASSWORD
MYSQL_MIGRATION_URL=jdbc:mysql://db.internal.example:3306/hos_refactor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY
MYSQL_MIGRATION_USERNAME=clinic_migrator
MYSQL_MIGRATION_PASSWORD=CHANGE_ME_MIGRATION_PASSWORD
# Empty database only. Remove this value after the first administrator changes it.
CLINIC_BOOTSTRAP_ADMIN_PASSWORD=
AI_CONFIG_SECRET=
CLINIC_ATTACHMENT_DIR=D:\hos_patient_record_runtime\attachments
CLINIC_ATTACHMENT_MAX_SIZE_BYTES=52428800
'@
Write-Utf8File -Path (Join-Path $configReleaseDir "runtime.env") -Content $runtimeEnv

$startPs1 = @'
param(
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$configPath = Join-Path $packageRoot "config\runtime.env"
$runtimeDir = Join-Path $packageRoot "runtime"
$logsDir = Join-Path $packageRoot "logs"
$pidPath = Join-Path $runtimeDir "backend.pid"
$jarPath = Join-Path $packageRoot "backend\app.jar"
$frontendDir = Join-Path $packageRoot "frontend"

function Read-RuntimeEnv {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        throw "Config file not found: $Path"
    }
    Get-Content -Path $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
        }
    }
}

function Test-ProcessAlive {
    param([int]$ProcessId)
    try {
        return $null -ne (Get-Process -Id $ProcessId -ErrorAction Stop)
    }
    catch {
        return $false
    }
}

New-Item -ItemType Directory -Force -Path $runtimeDir, $logsDir | Out-Null
Read-RuntimeEnv -Path $configPath

if (-not $env:SERVER_PORT) { $env:SERVER_PORT = "8080" }
if (-not $env:MYSQL_RUNTIME_PASSWORD -or $env:MYSQL_RUNTIME_PASSWORD -like "CHANGE_ME*") {
    throw "MYSQL_RUNTIME_PASSWORD in config\runtime.env must be set before startup."
}
if (-not $env:MYSQL_MIGRATION_PASSWORD -or $env:MYSQL_MIGRATION_PASSWORD -like "CHANGE_ME*") {
    throw "MYSQL_MIGRATION_PASSWORD in config\runtime.env must be set before startup."
}
if ($env:MYSQL_RUNTIME_USERNAME -eq $env:MYSQL_MIGRATION_USERNAME) {
    throw "Runtime and migration database users must be different."
}
if (-not $env:AI_CONFIG_SECRET) {
    throw "AI_CONFIG_SECRET in config\runtime.env must be set before startup."
}
if (-not $env:CLINIC_ATTACHMENT_DIR) { $env:CLINIC_ATTACHMENT_DIR = Join-Path $runtimeDir "attachments" }
$env:CLINIC_FRONTEND_DIR = $frontendDir
if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE = "mysql,prod" }

New-Item -ItemType Directory -Force -Path $env:CLINIC_ATTACHMENT_DIR | Out-Null

if (Test-Path $pidPath) {
    $oldPid = [int](Get-Content -Path $pidPath -Raw)
    if (Test-ProcessAlive -ProcessId $oldPid) {
        Write-Host "System is already running. PID=$oldPid" -ForegroundColor Yellow
        Write-Host "Open: http://localhost:$env:SERVER_PORT/"
        if (-not $NoBrowser) {
            Start-Process "http://localhost:$env:SERVER_PORT/"
        }
        exit 0
    }
}

$javaVersion = (& java -version 2>&1) -join " "
if ($LASTEXITCODE -ne 0) {
    throw "Java was not found. Install JDK 17 and try again."
}
if ($javaVersion -notmatch 'version "1[7-9]\.|version "2[0-9]\.') {
    Write-Host "Warning: Java version may be lower than 17: $javaVersion" -ForegroundColor Yellow
}

$outLog = Join-Path $logsDir "backend.out.log"
$errLog = Join-Path $logsDir "backend.err.log"
$process = Start-Process -FilePath "java" -ArgumentList @("-jar", $jarPath) -WorkingDirectory $packageRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
$process.Id | Set-Content -Path $pidPath -Encoding ASCII

Write-Host "System started. PID=$($process.Id)" -ForegroundColor Green
Write-Host "Local: http://localhost:$env:SERVER_PORT/"
Write-Host "LAN:   http://HOST_IP:$env:SERVER_PORT/"
Write-Host "Logs:  $logsDir"

Start-Sleep -Seconds 3
if (-not $NoBrowser) {
    Start-Process "http://localhost:$env:SERVER_PORT/"
}
'@
Write-Utf8File -Path (Join-Path $toolsReleaseDir "start-clinic.ps1") -Content $startPs1

$stopPs1 = @'
$ErrorActionPreference = "Continue"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$pidPath = Join-Path $packageRoot "runtime\backend.pid"

if (-not (Test-Path $pidPath)) {
    Write-Host "No running process record was found." -ForegroundColor Yellow
    exit 0
}

$pidText = Get-Content -Path $pidPath -Raw
$processId = 0
if (-not [int]::TryParse($pidText.Trim(), [ref]$processId)) {
    Remove-Item -LiteralPath $pidPath -Force
    Write-Host "Invalid process record was removed."
    exit 0
}

try {
    Stop-Process -Id $processId -Force -ErrorAction Stop
    Remove-Item -LiteralPath $pidPath -Force
    Write-Host "System stopped. PID=$processId" -ForegroundColor Green
}
catch {
    Remove-Item -LiteralPath $pidPath -Force
    Write-Host "Process was not found. Stale record was removed." -ForegroundColor Yellow
}
'@
Write-Utf8File -Path (Join-Path $toolsReleaseDir "stop-clinic.ps1") -Content $stopPs1

$checkBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\check-pilot-host.ps1" -ConfigPath ".\config\runtime.env"
echo.
pause
'@
Set-Content -Path (Join-Path $releaseDir "01-check-host.bat") -Value $checkBat -Encoding ASCII

$startBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\start-clinic.ps1"
echo.
pause
'@
Set-Content -Path (Join-Path $releaseDir "02-start-system.bat") -Value $startBat -Encoding ASCII

$stopBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\stop-clinic.ps1"
echo.
pause
'@
Set-Content -Path (Join-Path $releaseDir "03-stop-system.bat") -Value $stopBat -Encoding ASCII

$readmeTemplate = Join-Path $root "docs\production-single-node-runbook.md"
if (Test-Path $readmeTemplate) {
    Copy-Item -LiteralPath $readmeTemplate -Destination (Join-Path $releaseDir "README.md")
    Copy-Item -LiteralPath $readmeTemplate -Destination (Join-Path $docsReleaseDir "production-single-node-runbook.md")
}
if (Test-Path (Join-Path $root "docs\inventory-operation-guide.md")) {
    Copy-Item -LiteralPath (Join-Path $root "docs\inventory-operation-guide.md") -Destination (Join-Path $docsReleaseDir "inventory-operation-guide.md")
}
if (Test-Path (Join-Path $root "docs\delivery")) {
    Copy-Item -LiteralPath (Join-Path $root "docs\delivery") -Destination (Join-Path $docsReleaseDir "delivery") -Recurse
}

Write-Step "Pilot package is ready"
Write-Host "Directory: $releaseDir" -ForegroundColor Green
Write-Host "Run order: 01-check-host.bat, 02-start-system.bat, 03-stop-system.bat"
Write-Host "Config: config\runtime.env"
