param(
  [int]$MysqlPort = 3307,
  [int]$BackendPort = 8080,
  [int]$FrontendPort = 8848,
  [string]$RuntimeRoot = "$env:USERPROFILE\hos_cowork_runtime"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectRoot "coshare_patientrecord_sys_backend"
$FrontendDir = Join-Path $ProjectRoot "coshare_patientrecord_sys_frontend\Geeker-Admin"
$BackendJar = Join-Path $BackendDir "target\coshare_patientrecord_sys-0.0.1-SNAPSHOT.jar"
$MysqlDataDir = Join-Path $RuntimeRoot "mysql-data"
$MysqlConfig = Join-Path $RuntimeRoot "mysql3307.ini"
$AttachmentDir = Join-Path $RuntimeRoot "clinic-attachments"
$LogDir = Join-Path $RuntimeRoot "logs"
$DbName = "hos_refactor"
$DbUser = "cowork"
$DbPassword = "Cowork_2026!"
$AdminUsername = "admin"
$AdminPassword = "Init@Coshare2026!"
$AdminPasswordHash = '$2a$10$YkQ1vqK4O8t/MhybAJoCiO4ZNF4ySvh0WiZ3IGCu5GNT2LDnJd9hy'
$AiSecretsPath = Join-Path $ProjectRoot "config\ai-secrets.local.env"

function New-UnicodeText([int[]]$CodePoints) {
  return -join ($CodePoints | ForEach-Object { [char]$_ })
}

function New-Directory($Path) {
  if (-not (Test-Path -LiteralPath $Path)) {
    New-Item -ItemType Directory -Path $Path | Out-Null
  }
}

function Write-Utf8NoBomFile($Path, $Content) {
  [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Read-EnvFile($Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return }
  Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
      [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
    }
  }
}

function Convert-ToMysqlPath($Path) {
  return (Resolve-Path -LiteralPath $Path).Path.Replace("\", "/")
}

function Test-Port($Port) {
  $client = [System.Net.Sockets.TcpClient]::new()
  try {
    $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne(400)) { return $false }
    $client.EndConnect($async)
    return $true
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}

function Wait-Port($Port, $Name, $TimeoutSeconds = 60) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $Port) { return }
    Start-Sleep -Milliseconds 500
  }
  throw "$Name did not start on port $Port within $TimeoutSeconds seconds."
}

function Find-Executable($Name, [string[]]$Candidates) {
  foreach ($candidate in $Candidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
  }
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd) { return $cmd.Source }
  throw "Cannot find $Name. Install it or add it to PATH."
}

function Invoke-Mysql($MysqlExe, $User, $Password, $Sql, $Database = "") {
  New-Directory $RuntimeRoot
  New-Directory $LogDir

  $args = @(
    "--host=127.0.0.1",
    "--port=$MysqlPort",
    "--user=$User",
    "--default-character-set=utf8mb4",
    "--batch",
    "--skip-column-names"
  )
  if ($Password) { $args += "--password=$Password" }
  if ($Database) { $args += "--database=$Database" }

  $sqlPath = Join-Path $RuntimeRoot ("mysql-command-" + [guid]::NewGuid().ToString("N") + ".sql")
  $outPath = Join-Path $LogDir "mysql-command.out.log"
  $errPath = Join-Path $LogDir "mysql-command.err.log"
  [System.IO.File]::WriteAllText($sqlPath, $Sql, [System.Text.UTF8Encoding]::new($false))
  Remove-Item -LiteralPath $outPath, $errPath -Force -ErrorAction SilentlyContinue

  try {
    $process = Start-Process -FilePath $MysqlExe -ArgumentList $args -Wait -PassThru -NoNewWindow `
      -RedirectStandardInput $sqlPath `
      -RedirectStandardOutput $outPath `
      -RedirectStandardError $errPath
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

function Start-LocalMysql($MysqldExe) {
  New-Directory $RuntimeRoot
  New-Directory $MysqlDataDir
  New-Directory $LogDir

  $mysqlBaseDir = Split-Path -Parent (Split-Path -Parent $MysqldExe)
  $mysqlBasePath = $mysqlBaseDir.Replace("\", "/")
  $mysqlDataPath = (Resolve-Path -LiteralPath $MysqlDataDir).Path.Replace("\", "/")
  $mysqlLogPath = (Join-Path $LogDir "mysql.err.log").Replace("\", "/")
  $mysqlPidPath = (Join-Path $RuntimeRoot "mysql.pid").Replace("\", "/")

  $mysqlConfigContent = @"
[mysqld]
basedir=$mysqlBasePath
datadir=$mysqlDataPath
port=$MysqlPort
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
mysqlx=0
log-error=$mysqlLogPath
pid-file=$mysqlPidPath
"@
  Write-Utf8NoBomFile $MysqlConfig $mysqlConfigContent

  if (-not (Test-Path -LiteralPath (Join-Path $MysqlDataDir "auto.cnf"))) {
    Write-Host "[mysql] initializing local data directory..."
    $initArgs = @("--defaults-file=$MysqlConfig", "--initialize-insecure", "--console")
    $init = Start-Process -FilePath $MysqldExe -ArgumentList $initArgs -WorkingDirectory $RuntimeRoot -Wait -PassThru -NoNewWindow `
      -RedirectStandardOutput (Join-Path $LogDir "mysql-init.out.log") `
      -RedirectStandardError (Join-Path $LogDir "mysql-init.err.log")
    if ($init.ExitCode -ne 0) { throw "MySQL initialization failed. See $LogDir\mysql-init.err.log" }
  }

  if (Test-Port $MysqlPort) {
    Write-Host "[mysql] already running on port $MysqlPort"
    return
  }

  Write-Host "[mysql] starting on port $MysqlPort..."
  Start-Process -FilePath $MysqldExe -ArgumentList @("--defaults-file=$MysqlConfig") -WorkingDirectory $RuntimeRoot -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogDir "mysql.out.log") `
    -RedirectStandardError (Join-Path $LogDir "mysql.start.err.log") | Out-Null
  Wait-Port $MysqlPort "MySQL" 90
}

function Ensure-Database($MysqlExe) {
  $setupSql = @"
CREATE DATABASE IF NOT EXISTS $DbName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DbUser'@'127.0.0.1' IDENTIFIED BY '$DbPassword';
ALTER USER '$DbUser'@'127.0.0.1' IDENTIFIED BY '$DbPassword';
GRANT ALL PRIVILEGES ON $DbName.* TO '$DbUser'@'127.0.0.1';
CREATE USER IF NOT EXISTS '$DbUser'@'localhost' IDENTIFIED BY '$DbPassword';
ALTER USER '$DbUser'@'localhost' IDENTIFIED BY '$DbPassword';
GRANT ALL PRIVILEGES ON $DbName.* TO '$DbUser'@'localhost';
FLUSH PRIVILEGES;
"@

  try {
    Invoke-Mysql $MysqlExe "root" "" $setupSql | Out-Null
    Write-Host "[mysql] database and user are ready"
  } catch {
    Invoke-Mysql $MysqlExe $DbUser $DbPassword "SELECT 1;" $DbName | Out-Null
    Write-Host "[mysql] using existing database user"
  }
}

function Get-LatestBackendSourceTime {
  $latest = [DateTime]::MinValue
  $sourceDir = Join-Path $BackendDir "src"
  if (Test-Path -LiteralPath $sourceDir) {
    Get-ChildItem -LiteralPath $sourceDir -Recurse -File | ForEach-Object {
      if ($_.LastWriteTimeUtc -gt $latest) { $latest = $_.LastWriteTimeUtc }
    }
  }
  @("pom.xml", "mvnw.cmd") | ForEach-Object {
    $path = Join-Path $BackendDir $_
    if (Test-Path -LiteralPath $path) {
      $item = Get-Item -LiteralPath $path
      if ($item.LastWriteTimeUtc -gt $latest) { $latest = $item.LastWriteTimeUtc }
    }
  }
  return $latest
}

function Ensure-BackendJar {
  $needsBuild = -not (Test-Path -LiteralPath $BackendJar)
  if (-not $needsBuild) {
    $jarTime = (Get-Item -LiteralPath $BackendJar).LastWriteTimeUtc
    $sourceTime = Get-LatestBackendSourceTime
    $needsBuild = $sourceTime -gt $jarTime
  }
  if (-not $needsBuild) { return }

  $maven = Join-Path $BackendDir "mvnw.cmd"
  if (-not (Test-Path -LiteralPath $maven)) {
    $maven = Find-Executable "mvn.cmd" @()
  }
  Write-Host "[backend] building jar..."
  Push-Location $BackendDir
  try {
    & $maven -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE." }
  } finally {
    Pop-Location
  }
}

function Start-Backend {
  Ensure-BackendJar
  New-Directory $AttachmentDir
  New-Directory $LogDir

  if (Test-Port $BackendPort) {
    Write-Host "[backend] already running on port $BackendPort"
    return
  }

  $java = Find-Executable "java.exe" @()
  $jdbcUrl = "jdbc:mysql://127.0.0.1:$MysqlPort/$DbName`?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
  $args = @(
    "-jar", $BackendJar,
    "--server.port=$BackendPort",
    "--spring.profiles.active=mysql",
    "--spring.datasource.url=$jdbcUrl",
    "--spring.datasource.username=$DbUser",
    "--spring.datasource.password=$DbPassword",
    "--clinic.attachment-dir=$AttachmentDir"
  )

  Write-Host "[backend] starting on port $BackendPort..."
  Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $BackendDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogDir "backend.out.log") `
    -RedirectStandardError (Join-Path $LogDir "backend.err.log") | Out-Null
  Wait-Port $BackendPort "Backend" 120
}

function Ensure-AdminAccount($MysqlExe) {
  $textAdmin = New-UnicodeText @(0x7BA1, 0x7406, 0x5458)
  $textDepartment = New-UnicodeText @(0x4FE1, 0x606F, 0x2F, 0x9662, 0x529E)
  $textScope = New-UnicodeText @(0x7CFB, 0x7EDF, 0x5168, 0x5C40, 0x914D, 0x7F6E)
  $textEnabled = New-UnicodeText @(0x542F, 0x7528)
  $account = [ordered]@{
    id = "admin";
    username = $AdminUsername;
    name = $textAdmin;
    role = "admin";
    roleLabel = $textAdmin;
    department = $textDepartment;
    scope = $textScope;
    status = $textEnabled;
    createdAt = "2026-06-10 08:00:00";
    updatedAt = "2026-06-10 08:00:00";
    passwordHash = $AdminPasswordHash;
    currentPassword = $AdminPassword;
  }
  $json = ($account | ConvertTo-Json -Compress).Replace("'", "''")
  $seedSql = @"
SET NAMES utf8mb4;
INSERT INTO clinic_accounts (id, username, role, status, raw_json)
VALUES ('admin', '$AdminUsername', 'admin', '$textEnabled', CAST('$json' AS JSON))
ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role), status = VALUES(status);
"@
  Invoke-Mysql $MysqlExe $DbUser $DbPassword $seedSql $DbName | Out-Null
  Write-Host "[mysql] admin account ready: $AdminUsername / $AdminPassword"
}

function Start-Frontend {
  if (Test-Port $FrontendPort) {
    Write-Host "[frontend] already running on port $FrontendPort"
    return
  }

  $pnpm = Find-Executable "pnpm" @()
  if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir "node_modules"))) {
    Write-Host "[frontend] installing dependencies..."
    Push-Location $FrontendDir
    try {
      & $pnpm install
    } finally {
      Pop-Location
    }
  }

  Write-Host "[frontend] starting on port $FrontendPort..."
  Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "cd '$FrontendDir'; & '$pnpm' dev -- --host 0.0.0.0 --port $FrontendPort"
  ) -WorkingDirectory $FrontendDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogDir "frontend.out.log") `
    -RedirectStandardError (Join-Path $LogDir "frontend.err.log") | Out-Null
  Wait-Port $FrontendPort "Frontend" 90
}

$mysqld = Find-Executable "mysqld.exe" @(
  "C:\Program Files\MySQL\MySQL Server 9.5\bin\mysqld.exe",
  "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe"
)
$mysql = Find-Executable "mysql.exe" @(
  "C:\Program Files\MySQL\MySQL Server 9.5\bin\mysql.exe",
  "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
)

Read-EnvFile $AiSecretsPath
Start-LocalMysql $mysqld
Ensure-Database $mysql
Start-Backend
Ensure-AdminAccount $mysql
Start-Frontend

Write-Host ""
Write-Host "System is ready."
Write-Host "Frontend: http://localhost:$FrontendPort/"
Write-Host "Backend:  http://localhost:$BackendPort/"
Write-Host "Login:    $AdminUsername / $AdminPassword"
Write-Host "Runtime:  $RuntimeRoot"
