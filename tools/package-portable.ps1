param(
    [string]$OutputDir = "release\clinic-portable",
    [string]$JdkZip = "",
    [string]$MysqlZip = "",
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MysqlHome = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$backendDir = Join-Path $root "coshare_patientrecord_sys_backend"
$frontendDir = Join-Path $root "coshare_patientrecord_sys_frontend\Geeker-Admin"
$releaseDir = Join-Path $root $OutputDir
$appDir = Join-Path $releaseDir "app"
$runtimeDir = Join-Path $releaseDir "runtime"
$configDir = Join-Path $releaseDir "config"
$dataDir = Join-Path $releaseDir "data"
$logsDir = Join-Path $releaseDir "logs"
$toolsDir = Join-Path $releaseDir "tools"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Find-InstalledMysqlHome {
    if ($MysqlHome -and (Test-Path -LiteralPath (Join-Path $MysqlHome "bin\mysqld.exe"))) {
        return (Resolve-Path -LiteralPath $MysqlHome).Path
    }
    $mysqld = Get-Command mysqld.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($mysqld) {
        return Split-Path -Parent (Split-Path -Parent $mysqld.Source)
    }
    foreach ($candidate in @(
        "C:\Program Files\MySQL\MySQL Server 9.5",
        "C:\Program Files\MySQL\MySQL Server 8.0"
    )) {
        if (Test-Path -LiteralPath (Join-Path $candidate "bin\mysqld.exe")) {
            return $candidate
        }
    }
    return ""
}

function Resolve-RuntimeRoot {
    param([string]$Path, [string]$Marker)
    if (Test-Path -LiteralPath (Join-Path $Path $Marker)) {
        return (Resolve-Path -LiteralPath $Path).Path
    }
    $match = Get-ChildItem -LiteralPath $Path -Directory -Recurse -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName $Marker) } |
        Select-Object -First 1
    if (-not $match) {
        throw "Runtime marker '$Marker' was not found under $Path"
    }
    return $match.FullName
}

function Copy-DirectoryContent {
    param([string]$Source, [string]$Destination)
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Copy-Runtime {
    param(
        [string]$ZipPath,
        [string]$SourceHome,
        [string]$Marker,
        [string]$Destination,
        [string]$Name
    )
    $extractDir = Join-Path $releaseDir ".extract-$Name"
    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
    if ($ZipPath) {
        if (-not (Test-Path -LiteralPath $ZipPath)) {
            throw "$Name zip was not found: $ZipPath"
        }
        if (Test-Path -LiteralPath $extractDir) {
            Remove-Item -LiteralPath $extractDir -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $extractDir -Force
        $runtimeRoot = Resolve-RuntimeRoot -Path $extractDir -Marker $Marker
        Copy-DirectoryContent -Source $runtimeRoot -Destination $Destination
        Remove-Item -LiteralPath $extractDir -Recurse -Force
        return
    }
    if (-not $SourceHome -or -not (Test-Path -LiteralPath (Join-Path $SourceHome $Marker))) {
        throw "$Name runtime was not found. Pass -${Name}Zip or -${Name}Home."
    }
    Copy-DirectoryContent -Source (Resolve-Path -LiteralPath $SourceHome).Path -Destination $Destination
}

if (-not $MysqlHome) {
    $MysqlHome = Find-InstalledMysqlHome
}

Write-Step "Create portable release directory"
if (Test-Path -LiteralPath $releaseDir) {
    Remove-Item -LiteralPath $releaseDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path @(
    $appDir,
    (Join-Path $appDir "frontend"),
    (Join-Path $runtimeDir "jdk"),
    (Join-Path $runtimeDir "mysql"),
    (Join-Path $dataDir "mysql"),
    (Join-Path $dataDir "attachments"),
    $configDir,
    $logsDir,
    $toolsDir
) | Out-Null

if (-not $SkipBuild) {
    Write-Step "Build backend jar"
    Push-Location $backendDir
    try {
        .\mvnw.cmd -DskipTests package
    } finally {
        Pop-Location
    }

    Write-Step "Build frontend static files"
    Push-Location $frontendDir
    try {
        pnpm exec vite build --mode production
    } finally {
        Pop-Location
    }
} else {
    Write-Step "Skip build and reuse existing target/dist outputs"
}

$jar = Get-ChildItem -Path (Join-Path $backendDir "target") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*plain*" -and $_.Name -notlike "*sources*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Backend jar was not found."
}

$frontendDist = Join-Path $frontendDir "dist"
if (-not (Test-Path -LiteralPath (Join-Path $frontendDist "index.html"))) {
    throw "Frontend dist\index.html was not found."
}

Write-Step "Copy application files"
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $appDir "backend.jar")
Copy-Item -Path (Join-Path $frontendDist "*") -Destination (Join-Path $appDir "frontend") -Recurse -Force

Write-Step "Copy portable JDK"
Copy-Runtime -ZipPath $JdkZip -SourceHome $JdkHome -Marker "bin\java.exe" -Destination (Join-Path $runtimeDir "jdk") -Name "Jdk"

Write-Step "Copy portable MySQL"
Copy-Runtime -ZipPath $MysqlZip -SourceHome $MysqlHome -Marker "bin\mysqld.exe" -Destination (Join-Path $runtimeDir "mysql") -Name "Mysql"

$runtimeEnv = @'
SERVER_PORT=8848
MYSQL_PORT=3307
MYSQL_DATABASE=hos_refactor
MYSQL_USERNAME=cowork
MYSQL_PASSWORD=Cowork_2026!
MYSQL_URL=jdbc:mysql://127.0.0.1:3307/hos_refactor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
ADMIN_USERNAME=admin
ADMIN_PASSWORD=Init@Coshare2026!
CLINIC_ATTACHMENT_DIR=${PACKAGE_ROOT}\data\attachments
CLINIC_FRONTEND_DIR=${PACKAGE_ROOT}\app\frontend
CLINIC_BACKUP_MYSQLDUMP_PATH=${PACKAGE_ROOT}\runtime\mysql\bin\mysqldump.exe
CLINIC_MYSQL_DATA_DIR=${PACKAGE_ROOT}\data\mysql
CLINIC_BACKUP_CRON=0 0 2 * * *
'@
Write-Utf8File -Path (Join-Path $configDir "runtime.env") -Content $runtimeEnv

$aiSecretsExample = @'
# AI runtime config template.
# Copy this file to ai-secrets.local.env and fill private API keys on the deployment machine.
# Keep real API keys out of git and release archives shared outside the hospital.
AI_BASE_URL=
AI_API_KEY=
AI_MODEL=gpt-5.5
CLINIC_AI_DOUBAO_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
CLINIC_AI_DOUBAO_API_KEY=
CLINIC_AI_DOUBAO_MODEL=doubao-seed-2.0-mini
CLINIC_AI_DOUBAO_TTS_BASE_URL=https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse
CLINIC_AI_DOUBAO_TTS_API_KEY=
CLINIC_AI_DOUBAO_TTS_MODEL=seed-tts-2.0-standard
CLINIC_AI_DOUBAO_TTS_RESOURCE_ID=seed-tts-2.0
CLINIC_AI_DOUBAO_TTS_VOICE_TYPE=zh_male_tiancaitongsheng_mars_bigtts
CLINIC_AI_DOUBAO_TTS_SPEED_RATIO=1.0
'@
Write-Utf8File -Path (Join-Path $configDir "ai-secrets.local.env.example") -Content $aiSecretsExample

$startPs1 = @'
$ErrorActionPreference = "Stop"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$configPath = Join-Path $packageRoot "config\runtime.env"
$aiSecretsPath = Join-Path $packageRoot "config\ai-secrets.local.env"
$runtimeDir = Join-Path $packageRoot "runtime"
$dataDir = Join-Path $packageRoot "data"
$logsDir = Join-Path $packageRoot "logs"
$java = Join-Path $runtimeDir "jdk\bin\java.exe"
$mysqld = Join-Path $runtimeDir "mysql\bin\mysqld.exe"
$mysql = Join-Path $runtimeDir "mysql\bin\mysql.exe"
$mysqlDataDir = Join-Path $dataDir "mysql"
$mysqlConfig = Join-Path $dataDir "mysql-portable.ini"
$backendPidPath = Join-Path $dataDir "backend.pid"
$mysqlPidPath = Join-Path $dataDir "mysql.pid"
$mysqlSubstPath = Join-Path $dataDir "mysql-subst-drive.txt"
$mysqlPackageRoot = $packageRoot.Path
$adminPasswordHash = '$2a$10$8KJFlcFrqcIzXgrVGOzvzesgS1ehE.DQhr4YC9e8Xl4b21MX6qCfu'

function Read-EnvFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            $value = $parts[1].Trim().Replace('${PACKAGE_ROOT}', $packageRoot.Path)
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, "Process")
        }
    }
}

function Enable-MysqlPackageRoot {
    $rootPath = $packageRoot.Path
    if ($rootPath -cmatch '^[\x00-\x7F]+$') {
        return $rootPath
    }
    $substExe = Join-Path $env:SystemRoot "System32\subst.exe"
    if (Test-Path -LiteralPath $mysqlSubstPath) {
        $storedDrive = (Get-Content -LiteralPath $mysqlSubstPath -Raw).Trim()
        if ($storedDrive -and (Test-Path -LiteralPath ($storedDrive + "\"))) {
            return ($storedDrive + "\")
        }
        Remove-Item -LiteralPath $mysqlSubstPath -Force -ErrorAction SilentlyContinue
    }
    foreach ($letter in @("Z","Y","X","W","V","U","T","S","R","Q","P")) {
        $drive = "${letter}:"
        if (-not (Test-Path -LiteralPath ($drive + "\"))) {
            & $substExe $drive $rootPath | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to create drive alias $drive for MySQL."
            }
            $drive | Set-Content -LiteralPath $mysqlSubstPath -Encoding ASCII
            return ($drive + "\")
        }
    }
    throw "No free drive letter is available for MySQL path alias."
}

function Set-MysqlPaths {
    $script:mysqlPackageRoot = Enable-MysqlPackageRoot
    $script:mysqld = Join-Path $script:mysqlPackageRoot "runtime\mysql\bin\mysqld.exe"
    $script:mysql = Join-Path $script:mysqlPackageRoot "runtime\mysql\bin\mysql.exe"
    $script:mysqlDataDir = Join-Path $script:mysqlPackageRoot "data\mysql"
    $script:mysqlConfig = Join-Path $script:mysqlPackageRoot "data\mysql-portable.ini"
    $script:mysqlPidPath = Join-Path $script:mysqlPackageRoot "data\mysql.pid"
}

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Port([int]$Port, [string]$Name, [int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not start on port $Port within $TimeoutSeconds seconds."
}

function Test-PidFileProcess([string]$Path, [string]$ProcessName) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    $pidText = (Get-Content -LiteralPath $Path -Raw).Trim()
    $processId = 0
    if (-not [int]::TryParse($pidText, [ref]$processId)) {
        return $false
    }
    try {
        $process = Get-Process -Id $processId -ErrorAction Stop
        return ($process.ProcessName -ieq [System.IO.Path]::GetFileNameWithoutExtension($ProcessName))
    } catch {
        return $false
    }
}

function Test-BackendReady {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$env:SERVER_PORT/health" -Method Get -TimeoutSec 3
        return ($health.status -in @("ok", "degraded") -and $health.service -eq "coshare-patientrecord-backend")
    } catch {
        return $false
    }
}

function Wait-Backend([int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-BackendReady) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Backend did not become healthy on port $env:SERVER_PORT within $TimeoutSeconds seconds."
}

function Invoke-Mysql([string]$User, [string]$Password, [string]$Sql, [string]$Database = "") {
    $args = @("--host=127.0.0.1", "--port=$env:MYSQL_PORT", "--user=$User", "--default-character-set=utf8mb4", "--batch", "--skip-column-names")
    if ($Password) { $args += "--password=$Password" }
    if ($Database) { $args += "--database=$Database" }

    $sqlPath = Join-Path $mysqlPackageRoot ("data\mysql-command-" + [guid]::NewGuid().ToString("N") + ".sql")
    $outPath = Join-Path $logsDir "mysql-command.out.log"
    $errPath = Join-Path $logsDir "mysql-command.err.log"
    [System.IO.File]::WriteAllText($sqlPath, $Sql, [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $outPath, $errPath -Force -ErrorAction SilentlyContinue

    try {
        $process = Start-Process -FilePath $mysql -ArgumentList $args -WorkingDirectory $mysqlPackageRoot -Wait -PassThru -NoNewWindow -RedirectStandardInput $sqlPath -RedirectStandardOutput $outPath -RedirectStandardError $errPath
        if ($process.ExitCode -ne 0) {
            throw "MySQL command failed with exit code $($process.ExitCode). See $errPath"
        }
        if (Test-Path -LiteralPath $outPath) {
            Get-Content -LiteralPath $outPath
        }
    } finally {
        Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
    }
}

function Quote-ProcessArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Start-Mysql {
    $mysqlBasePath = (Join-Path $mysqlPackageRoot "runtime\mysql").Replace("\", "/")
    $mysqlDataPath = (Join-Path $mysqlPackageRoot "data\mysql").Replace("\", "/")
    $mysqlLogPath = (Join-Path $mysqlPackageRoot "logs\mysql.err.log").Replace("\", "/")
    $mysqlPid = (Join-Path $mysqlPackageRoot "data\mysql.pid").Replace("\", "/")
    $mysqlConfigContent = @"
[mysqld]
basedir=$mysqlBasePath
datadir=$mysqlDataPath
port=$env:MYSQL_PORT
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
mysqlx=0
log-error=$mysqlLogPath
pid-file=$mysqlPid
"@
    [System.IO.File]::WriteAllText($mysqlConfig, $mysqlConfigContent, [System.Text.UTF8Encoding]::new($false))

    if (Test-Port ([int]$env:MYSQL_PORT)) {
        if (Test-PidFileProcess $mysqlPidPath "mysqld.exe") {
            Write-Host "[mysql] already running on port $env:MYSQL_PORT"
            return
        }
        throw "Port $env:MYSQL_PORT is already in use. Stop the existing service or change MYSQL_PORT in config\runtime.env."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $mysqlDataDir "auto.cnf"))) {
        Write-Host "[mysql] initializing data directory..."
        $init = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=$mysqlConfig", "--initialize-insecure", "--console") -WorkingDirectory $mysqlPackageRoot -Wait -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $logsDir "mysql-init.out.log") -RedirectStandardError (Join-Path $logsDir "mysql-init.err.log")
        if ($init.ExitCode -ne 0) { throw "MySQL initialization failed. See logs\mysql-init.err.log" }
    }
    Write-Host "[mysql] starting on port $env:MYSQL_PORT..."
    $process = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=$mysqlConfig") -WorkingDirectory $mysqlPackageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir "mysql.out.log") -RedirectStandardError (Join-Path $logsDir "mysql.start.err.log")
    $process.Id | Set-Content -LiteralPath $mysqlPidPath -Encoding ASCII
    Wait-Port ([int]$env:MYSQL_PORT) "MySQL" 120
}

function Ensure-Database {
    $db = $env:MYSQL_DATABASE
    $user = $env:MYSQL_USERNAME
    $password = $env:MYSQL_PASSWORD
    $setupSql = @"
CREATE DATABASE IF NOT EXISTS $db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$user'@'127.0.0.1' IDENTIFIED BY '$password';
ALTER USER '$user'@'127.0.0.1' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON $db.* TO '$user'@'127.0.0.1';
GRANT RELOAD, FLUSH_TABLES ON *.* TO '$user'@'127.0.0.1';
CREATE USER IF NOT EXISTS '$user'@'localhost' IDENTIFIED BY '$password';
ALTER USER '$user'@'localhost' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON $db.* TO '$user'@'localhost';
GRANT RELOAD, FLUSH_TABLES ON *.* TO '$user'@'localhost';
FLUSH PRIVILEGES;
"@
    try {
        Invoke-Mysql "root" "" $setupSql | Out-Null
    } catch {
        Invoke-Mysql $user $password "SELECT 1;" $db | Out-Null
    }
    Write-Host "[mysql] database and app user are ready"
}

function Start-Backend {
    if (Test-Port ([int]$env:SERVER_PORT)) {
        if (Test-BackendReady) {
            Write-Host "[backend] already running on port $env:SERVER_PORT"
            return
        }
        throw "Port $env:SERVER_PORT is already in use by another service. Stop it or change SERVER_PORT in config\runtime.env."
    }
    $args = @(
        "-jar", (Join-Path $packageRoot "app\backend.jar"),
        "--server.port=$env:SERVER_PORT",
        "--spring.profiles.active=mysql",
        "--spring.datasource.url=$env:MYSQL_URL",
        "--spring.datasource.username=$env:MYSQL_USERNAME",
        "--spring.datasource.password=$env:MYSQL_PASSWORD",
        "--clinic.attachment-dir=$env:CLINIC_ATTACHMENT_DIR",
        "--clinic.frontend-dir=$env:CLINIC_FRONTEND_DIR",
        "--clinic.backup.mysqldump-path=$env:CLINIC_BACKUP_MYSQLDUMP_PATH",
        "--clinic.mysql-data-dir=$env:CLINIC_MYSQL_DATA_DIR",
        "--clinic.backup.cron=$env:CLINIC_BACKUP_CRON"
    )
    Write-Host "[backend] starting on port $env:SERVER_PORT..."
    $quotedArgs = $args | ForEach-Object { Quote-ProcessArgument $_ }
    $process = Start-Process -FilePath $java -ArgumentList $quotedArgs -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir "backend.out.log") -RedirectStandardError (Join-Path $logsDir "backend.err.log")
    $process.Id | Set-Content -LiteralPath $backendPidPath -Encoding ASCII
    Wait-Backend 120
}

function Ensure-AdminAccount {
    $adminName = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("566h55CG5ZGY"))
    $department = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("5L+h5oGvL+mZouWKng=="))
    $enabled = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("5ZCv55So"))
    $account = [ordered]@{
        id = "admin";
        username = $env:ADMIN_USERNAME;
        name = $adminName;
        role = "admin";
        roleLabel = $adminName;
        department = $department;
        scope = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("57O757uf5YWo5bGA6YWN572u"));
        status = $enabled;
        createdAt = "2026-06-10 08:00:00";
        updatedAt = "2026-06-10 08:00:00";
        passwordHash = $adminPasswordHash;
        currentPassword = $env:ADMIN_PASSWORD;
    }
    $json = ($account | ConvertTo-Json -Compress).Replace("'", "''")
    $sql = @"
SET NAMES utf8mb4;
INSERT INTO clinic_accounts (id, username, role, status, raw_json)
VALUES ('admin', '$($env:ADMIN_USERNAME)', 'admin', '$enabled', CAST('$json' AS JSON))
ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role), status = VALUES(status);
"@
    Invoke-Mysql $env:MYSQL_USERNAME $env:MYSQL_PASSWORD $sql $env:MYSQL_DATABASE | Out-Null
    Write-Host "[mysql] admin account ready: $env:ADMIN_USERNAME / $env:ADMIN_PASSWORD"
}

Read-EnvFile $configPath
Read-EnvFile $aiSecretsPath
Set-MysqlPaths
New-Item -ItemType Directory -Force -Path $mysqlDataDir, $env:CLINIC_ATTACHMENT_DIR, $logsDir | Out-Null
Start-Mysql
Ensure-Database
Start-Backend
Ensure-AdminAccount

Write-Host ""
Write-Host "System is ready." -ForegroundColor Green
Write-Host "Open from this host: http://localhost:$env:SERVER_PORT/"
Write-Host "Open from LAN:       http://HOST_IP:$env:SERVER_PORT/"
Write-Host "Login:               $env:ADMIN_USERNAME / $env:ADMIN_PASSWORD"
Write-Host "Logs:                $logsDir"
'@
Write-Utf8File -Path (Join-Path $toolsDir "start-clinic.ps1") -Content $startPs1

$stopPs1 = @'
$ErrorActionPreference = "Continue"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$dataDir = Join-Path $packageRoot "data"
$backendPidPath = Join-Path $dataDir "backend.pid"
$mysqlPidPath = Join-Path $dataDir "mysql.pid"
$mysqlSubstPath = Join-Path $dataDir "mysql-subst-drive.txt"

function Stop-PidFile([string]$Path, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host "[$Name] no pid file"
        return
    }
    $pidText = (Get-Content -LiteralPath $Path -Raw).Trim()
    $processId = 0
    if ([int]::TryParse($pidText, [ref]$processId)) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Write-Host "[$Name] stopped pid $processId"
    }
    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
}

function Remove-MysqlPathAlias {
    if (-not (Test-Path -LiteralPath $mysqlSubstPath)) {
        return
    }
    $drive = (Get-Content -LiteralPath $mysqlSubstPath -Raw).Trim()
    if ($drive -match '^[A-Z]:$') {
        & (Join-Path $env:SystemRoot "System32\subst.exe") $drive /D 2>$null
        Write-Host "[mysql] released path alias $drive"
    }
    Remove-Item -LiteralPath $mysqlSubstPath -Force -ErrorAction SilentlyContinue
}

Stop-PidFile $backendPidPath "backend"
Stop-PidFile $mysqlPidPath "mysql"
Remove-MysqlPathAlias
'@
Write-Utf8File -Path (Join-Path $toolsDir "stop-clinic.ps1") -Content $stopPs1

$backupPs1 = @'
$ErrorActionPreference = "Stop"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$configPath = Join-Path $packageRoot "config\runtime.env"
Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        $value = $parts[1].Trim().Replace('${PACKAGE_ROOT}', $packageRoot.Path)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, "Process")
    }
}
$baseUrl = "http://127.0.0.1:$env:SERVER_PORT"
$loginBody = @{ username = $env:ADMIN_USERNAME; password = $env:ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$baseUrl/auth/login" -Method Post -ContentType "application/json" -Body $loginBody
$token = $login.data.access_token
if (-not $token) { throw "Login failed; no access token returned." }
$result = Invoke-RestMethod -Uri "$baseUrl/clinic-api/maintenance/backup/run" -Method Post -Headers @{ Authorization = "Bearer $token" }
Write-Host "Backup completed:" -ForegroundColor Green
Write-Host $result.data.backupFile
'@
Write-Utf8File -Path (Join-Path $toolsDir "backup-now.ps1") -Content $backupPs1

$restorePs1 = @'
param([string]$BackupZip = "")
$ErrorActionPreference = "Stop"
$packageRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$configPath = Join-Path $packageRoot "config\runtime.env"
$runtimeDir = Join-Path $packageRoot "runtime"
$dataDir = Join-Path $packageRoot "data"
$logsDir = Join-Path $packageRoot "logs"
$mysql = Join-Path $runtimeDir "mysql\bin\mysql.exe"
$mysqld = Join-Path $runtimeDir "mysql\bin\mysqld.exe"
$mysqlDataDir = Join-Path $dataDir "mysql"
$mysqlConfig = Join-Path $dataDir "mysql-portable.ini"
$mysqlPidPath = Join-Path $dataDir "mysql.pid"
$mysqlSubstPath = Join-Path $dataDir "mysql-subst-drive.txt"
$mysqlPackageRoot = $packageRoot.Path

if (-not $BackupZip) {
    $BackupZip = Read-Host "Input backup zip path"
}
if (-not (Test-Path -LiteralPath $BackupZip)) {
    throw "Backup zip was not found: $BackupZip"
}
$confirm = Read-Host "This will replace current database and attachments. Type RESTORE to continue"
if ($confirm -ne "RESTORE") {
    Write-Host "Restore cancelled."
    exit 0
}

Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        $value = $parts[1].Trim().Replace('${PACKAGE_ROOT}', $packageRoot.Path)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, "Process")
    }
}

& (Join-Path $PSScriptRoot "stop-clinic.ps1")

function Enable-MysqlPackageRoot {
    $rootPath = $packageRoot.Path
    if ($rootPath -cmatch '^[\x00-\x7F]+$') {
        return $rootPath
    }
    $substExe = Join-Path $env:SystemRoot "System32\subst.exe"
    if (Test-Path -LiteralPath $mysqlSubstPath) {
        $storedDrive = (Get-Content -LiteralPath $mysqlSubstPath -Raw).Trim()
        if ($storedDrive -and (Test-Path -LiteralPath ($storedDrive + "\"))) {
            return ($storedDrive + "\")
        }
        Remove-Item -LiteralPath $mysqlSubstPath -Force -ErrorAction SilentlyContinue
    }
    foreach ($letter in @("Z","Y","X","W","V","U","T","S","R","Q","P")) {
        $drive = "${letter}:"
        if (-not (Test-Path -LiteralPath ($drive + "\"))) {
            & $substExe $drive $rootPath | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to create drive alias $drive for MySQL."
            }
            $drive | Set-Content -LiteralPath $mysqlSubstPath -Encoding ASCII
            return ($drive + "\")
        }
    }
    throw "No free drive letter is available for MySQL path alias."
}

function Set-MysqlPaths {
    $script:mysqlPackageRoot = Enable-MysqlPackageRoot
    $script:mysqld = Join-Path $script:mysqlPackageRoot "runtime\mysql\bin\mysqld.exe"
    $script:mysql = Join-Path $script:mysqlPackageRoot "runtime\mysql\bin\mysql.exe"
    $script:mysqlDataDir = Join-Path $script:mysqlPackageRoot "data\mysql"
    $script:mysqlConfig = Join-Path $script:mysqlPackageRoot "data\mysql-portable.ini"
    $script:mysqlPidPath = Join-Path $script:mysqlPackageRoot "data\mysql.pid"
}

Set-MysqlPaths

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Port([int]$Port) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "MySQL did not start."
}

$mysqlBasePath = (Join-Path $mysqlPackageRoot "runtime\mysql").Replace("\", "/")
$mysqlDataPath = (Join-Path $mysqlPackageRoot "data\mysql").Replace("\", "/")
$mysqlLogPath = (Join-Path $mysqlPackageRoot "logs\mysql.err.log").Replace("\", "/")
$mysqlPid = (Join-Path $mysqlPackageRoot "data\mysql.pid").Replace("\", "/")
$mysqlConfigContent = @"
[mysqld]
basedir=$mysqlBasePath
datadir=$mysqlDataPath
port=$env:MYSQL_PORT
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
mysqlx=0
log-error=$mysqlLogPath
pid-file=$mysqlPid
"@
[System.IO.File]::WriteAllText($mysqlConfig, $mysqlConfigContent, [System.Text.UTF8Encoding]::new($false))

$process = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=$mysqlConfig") -WorkingDirectory $mysqlPackageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsDir "mysql.restore.out.log") -RedirectStandardError (Join-Path $logsDir "mysql.restore.err.log")
$process.Id | Set-Content -LiteralPath $mysqlPidPath -Encoding ASCII
Wait-Port ([int]$env:MYSQL_PORT)

$extractDir = Join-Path $mysqlPackageRoot ("data\restore-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
Expand-Archive -LiteralPath $BackupZip -DestinationPath $extractDir -Force
$sqlPath = Join-Path $extractDir "database.sql"
if (-not (Test-Path -LiteralPath $sqlPath)) {
    throw "database.sql was not found in the backup zip."
}
$restoreArgs = @("--host=127.0.0.1", "--port=$env:MYSQL_PORT", "--user=$env:MYSQL_USERNAME", "--password=$env:MYSQL_PASSWORD", "--database=$env:MYSQL_DATABASE", "--default-character-set=utf8mb4")
$restore = Start-Process -FilePath $mysql -ArgumentList $restoreArgs -WorkingDirectory $mysqlPackageRoot -Wait -PassThru -NoNewWindow -RedirectStandardInput $sqlPath -RedirectStandardOutput (Join-Path $logsDir "mysql-restore-command.out.log") -RedirectStandardError (Join-Path $logsDir "mysql-restore-command.err.log")
if ($restore.ExitCode -ne 0) { throw "Database restore failed. See logs\mysql-restore-command.err.log" }

$attachmentSource = Join-Path $extractDir "attachments"
if (Test-Path -LiteralPath $attachmentSource) {
    $currentAttachments = $env:CLINIC_ATTACHMENT_DIR
    $oldAttachments = "$currentAttachments.before-restore-$(Get-Date -Format yyyyMMddHHmmss)"
    if (Test-Path -LiteralPath $currentAttachments) {
        Move-Item -LiteralPath $currentAttachments -Destination $oldAttachments
    }
    New-Item -ItemType Directory -Force -Path $currentAttachments | Out-Null
    Copy-Item -Path (Join-Path $attachmentSource "*") -Destination $currentAttachments -Recurse -Force
}
Remove-Item -LiteralPath $extractDir -Recurse -Force
Write-Host "Restore completed. Run start.bat to start the system." -ForegroundColor Green
'@
Write-Utf8File -Path (Join-Path $toolsDir "restore-from-backup.ps1") -Content $restorePs1

$startBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\start-clinic.ps1"
echo.
pause
'@
Set-Content -LiteralPath (Join-Path $releaseDir "start.bat") -Value $startBat -Encoding ASCII

$stopBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\stop-clinic.ps1"
echo.
pause
'@
Set-Content -LiteralPath (Join-Path $releaseDir "stop.bat") -Value $stopBat -Encoding ASCII

$backupBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\backup-now.ps1"
echo.
pause
'@
Set-Content -LiteralPath (Join-Path $releaseDir "backup-now.bat") -Value $backupBat -Encoding ASCII

$restoreBat = @'
@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\tools\restore-from-backup.ps1"
echo.
pause
'@
Set-Content -LiteralPath (Join-Path $releaseDir "restore-from-backup.bat") -Value $restoreBat -Encoding ASCII

$readme = @'
# 协和患者病历协同系统便携包

## 启动

在目标主机双击 `start.bat`。启动完成后，同一内网电脑访问：

```text
http://目标主机IP:8848/
```

初始管理员：

```text
admin / Init@Coshare2026!
```

目标主机不需要安装 Java、MySQL、Node 或 pnpm；运行时已放在 `runtime` 目录。

## 数据目录

```text
data\mysql        MySQL 数据文件
data\attachments  系统附件
logs              运行日志
config\runtime.env 端口、数据库和路径配置
```

## 备份

管理员登录后，在首页“物理备份”填写服务器本机可写路径，例如：

```text
D:\clinic-backup
\\192.168.1.10\clinic-backup
```

保存后系统每天 02:00 自动备份，也可以点“立即备份”。备份保留策略为：最近 7 天、最近 4 周每周 1 份、最近 12 月每月 1 份。

也可以在目标主机双击 `backup-now.bat` 主动触发一次备份。该脚本要求系统已经启动，且已在管理员页面配置过备份路径。

## 恢复

先确认当前数据已另行保留，然后双击 `restore-from-backup.bat`，输入备份 zip 路径，并按提示输入 `RESTORE`。恢复完成后再双击 `start.bat`。
'@
'@
$readmeBase64 = @'
IyDljY/lkozmgqPogIXnl4XljobljY/lkIzns7vnu5/kvr/mkLrljIUKCiMjIOWQr+WKqAoK5Zyo55uu5qCH5Li75py65Y+M5Ye7IHN0YXJ0LmJhdOOAguWQr+WKqOWujOaIkOWQju+8jOWQjOS4gOWGhee9keeUteiEkeiuv+mXru+8mgoKYAlleHQKaHR0cDovL+ebruagh+S4u+acuklQOjg4NDgvCmAKCuWIneWni+euoeeQhuWRmO+8mgoKYAlleHQKYWRtaW4gLyBJbml0QENvc2hhcmUyMDI2IQpgCgrnm67moIfkuLvmnLrkuI3pnIDopoHlronoo4UgSmF2YeOAgU15U1FM44CBTm9kZSDmiJYgcG5wbe+8m+i/kOihjOaXtuW3suaUvuWcqCANdW50aW1lIOebruW9leOAggoKIyMg5pWw5o2u55uu5b2VCgpgCWV4dApkYXRhXG15c3FsICAgICAgICAgTXlTUUwg5pWw5o2u5paH5Lu2CmRhdGFcYXR0YWNobWVudHMgICDns7vnu5/pmYTku7YKbG9ncyAgICAgICAgICAgICAgIOi/kOihjOaXpeW/lwpjb25maWdccnVudGltZS5lbnYg56uv5Y+j44CB5pWw5o2u5bqT5ZKM6Lev5b6E6YWN572uCmAKCiMjIOWkh+S7vQoK566h55CG5ZGY55m75b2V5ZCO77yM5Zyo6aaW6aG1IOeJqeeQhuWkh+S7veWhq+WGmeacjeWKoeWZqOacrOacuuWPr+WGmei3r+W+hO+8jOS+i+Wmgu+8mgoKYAlleHQKRDpcY2xpbmljLWJhY2t1cApcXDE5Mi4xNjguMS4xMFxjbGluaWMtYmFja3VwCmAKCuS/neWtmOWQjuezu+e7n+avj+WkqSAwMjowMCDoh6rliqjlpIfku73vvIzkuZ/lj6/ku6Xngrnlh7vnq4vljbPlpIfku73jgILlpIfku73kv53nlZnnrZbnlaXkuLrvvJrmnIDov5EgNyDlpKnlhajpg6jkv53nlZnjgIHmnIDov5EgNCDlkajmr4/lkaggMSDku73jgIHmnIDov5EgMTIg5pyI5q+P5pyIIDEg5Lu944CCCgrkuZ/lj6/ku6XlnKjnm67moIfkuLvmnLrlj4zlh7sgCGFja3VwLW5vdy5iYXQg5Li75Yqo6Kem5Y+R5LiA5qyh5aSH5Lu944CC6K+l6ISa5pys6KaB5rGC57O757uf5bey57uP5ZCv5Yqo77yM5LiU5bey5Zyo566h55CG5ZGY6aG16Z2i6YWN572u6L+H5aSH5Lu96Lev5b6E44CCCgojIyDmgaLlpI0KCuWFiOehruiupOW9k+WJjeaVsOaNruW3suWPpuihjOS/neeVme+8jOeEtuWQjuWPjOWHuyANZXN0b3JlLWZyb20tYmFja3VwLmJhdO+8jOi+k+WFpeWkh+S7vSB6aXAg6Lev5b6E77yM5bm25oyJ5o+Q56S66L6T5YWlIFJFU1RPUkXjgILmgaLlpI3lrozmiJDlkI7lho3lj4zlh7sgc3RhcnQuYmF044CC
'@
$readme = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($readmeBase64 -replace "\s", "")))
Write-Utf8File -Path (Join-Path $releaseDir "README.md") -Content $readme

Write-Step "Portable package is ready"
Write-Host "Directory: $releaseDir" -ForegroundColor Green
Write-Host "Start:     start.bat"
Write-Host "Stop:      stop.bat"
Write-Host "Backup:    backup-now.bat"
