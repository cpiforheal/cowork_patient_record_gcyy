$ErrorActionPreference = "Stop"
$trace = "e:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\scripts\start-backend-9848.trace.log"
"=== launcher start $(Get-Date -Format o) ===" | Set-Content -LiteralPath $trace -Encoding UTF8
try {
  $root = "E:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\release\clinic-portable-test"
  "root ok" | Add-Content -LiteralPath $trace

  foreach ($f in @("$root\config\runtime.env", "$root\config\ai-secrets.local.env")) {
    if (-not (Test-Path -LiteralPath $f)) { "missing env file: $f" | Add-Content -LiteralPath $trace; continue }
    Get-Content -LiteralPath $f -Encoding UTF8 | ForEach-Object {
      $line = $_.Trim()
      if (-not $line -or $line.StartsWith("#")) { return }
      $parts = $line.Split("=", 2)
      if ($parts.Count -eq 2) {
        $v = $parts[1].Trim().Replace('${PACKAGE_ROOT}', $root)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $v, "Process")
      }
    }
    "loaded env: $f, AI_CONFIG_SECRET set: $([bool]$env:AI_CONFIG_SECRET)" | Add-Content -LiteralPath $trace
  }

  $java = Join-Path $root "runtime\jdk\bin\java.exe"
  "java exists: $(Test-Path -LiteralPath $java)" | Add-Content -LiteralPath $trace
  $argList = @(
    "-jar", (Join-Path $root "app\backend.jar"),
    "--server.port=9848",
    "--spring.profiles.active=mysql",
    "--spring.datasource.url=$env:MYSQL_URL",
    "--clinic.attachment-dir=$env:CLINIC_ATTACHMENT_DIR",
    "--clinic.frontend-dir=$env:CLINIC_FRONTEND_DIR",
    "--clinic.backup.mysqldump-path=$env:CLINIC_BACKUP_MYSQLDUMP_PATH",
    "--clinic.mysql-data-dir=$env:CLINIC_MYSQL_DATA_DIR",
    "--clinic.backup.cron=$env:CLINIC_BACKUP_CRON"
  )
  $quoted = $argList | ForEach-Object {
    if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
  }
  $p = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root "logs\backend.out.log") -RedirectStandardError (Join-Path $root "logs\backend.err.log")
  "started java pid: $($p.Id)" | Add-Content -LiteralPath $trace
  $p.Id | Set-Content -LiteralPath (Join-Path $root "data\backend.pid") -Encoding ASCII
  "pid file written" | Add-Content -LiteralPath $trace
} catch {
  "FATAL: $($_.Exception.Message)" | Add-Content -LiteralPath $trace
  "AT: $($_.InvocationInfo.PositionMessage)" | Add-Content -LiteralPath $trace
}
