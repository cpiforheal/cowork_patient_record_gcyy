param(
    [string]$ConfigPath = "",
    [string]$BackendUrl = "",
    [string]$MysqlHost = "",
    [int]$MysqlPort = 0,
    [int]$ServerPort = 0,
    [string]$AttachmentDir = ""
)

$ErrorActionPreference = "Continue"
$script:PassCount = 0
$script:FailCount = 0
$script:WarnCount = 0
$script:Suggestions = New-Object System.Collections.Generic.List[string]

function Import-RuntimeEnv {
    param([string]$Path)

    if (-not $Path) {
        return
    }
    if (-not (Test-Path $Path)) {
        $script:Suggestions.Add("Config file was not found: $Path")
        return
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

function Get-MysqlHostFromUrl {
    param([string]$Url)
    if ($Url -match "jdbc:mysql://([^:/?]+)") {
        return $Matches[1]
    }
    return "localhost"
}

function Get-MysqlPortFromUrl {
    param([string]$Url)
    if ($Url -match "jdbc:mysql://[^:/?]+:(\d+)") {
        return [int]$Matches[1]
    }
    return 3306
}

function Write-Check {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Detail,
        [string]$Suggestion = ""
    )

    $color = "Gray"
    if ($Status -eq "PASS") {
        $script:PassCount++
        $color = "Green"
    }
    elseif ($Status -eq "WARN") {
        $script:WarnCount++
        $color = "Yellow"
    }
    else {
        $script:FailCount++
        $color = "Red"
    }

    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
    if ($Suggestion -and $Status -ne "PASS") {
        $script:Suggestions.Add($Suggestion)
    }
}

function Test-HttpJson {
    param([string]$Url)
    Invoke-RestMethod -Uri $Url -TimeoutSec 5
}

Import-RuntimeEnv -Path $ConfigPath

if (-not $ServerPort) { $ServerPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 } }
if (-not $BackendUrl) { $BackendUrl = "http://localhost:$ServerPort" }
if (-not $MysqlHost) { $MysqlHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { Get-MysqlHostFromUrl $env:MYSQL_URL } }
if (-not $MysqlPort) { $MysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { Get-MysqlPortFromUrl $env:MYSQL_URL } }
if (-not $AttachmentDir) { $AttachmentDir = if ($env:CLINIC_ATTACHMENT_DIR) { $env:CLINIC_ATTACHMENT_DIR } else { "D:\hos_patient_record_runtime\attachments" } }

Write-Host "Clinic pilot host check" -ForegroundColor Cyan
Write-Host "BackendUrl: $BackendUrl"
Write-Host "MySQL:      $MysqlHost`:$MysqlPort"
Write-Host "Files:      $AttachmentDir"
Write-Host ""

try {
    $java = & java -version 2>&1
    $javaText = ($java -join " ")
    $javaOk = $LASTEXITCODE -eq 0 -and $javaText -match 'version "1[7-9]\.|version "2[0-9]\.'
    Write-Check "Java 17+" $(if ($javaOk) { "PASS" } else { "FAIL" }) $javaText "Install JDK 17 and make sure java is available in PATH."
}
catch {
    Write-Check "Java 17+" "FAIL" $_.Exception.Message "Install JDK 17 and reopen the command window."
}

try {
    $mysqlTcp = Test-NetConnection -ComputerName $MysqlHost -Port $MysqlPort -InformationLevel Quiet
    Write-Check "MySQL TCP" $(if ($mysqlTcp) { "PASS" } else { "FAIL" }) "$MysqlHost`:$MysqlPort" "Start MySQL and check host, port, and firewall."
}
catch {
    Write-Check "MySQL TCP" "FAIL" $_.Exception.Message "Start MySQL and run this check again."
}

try {
    $serverTcp = Test-NetConnection -ComputerName "localhost" -Port $ServerPort -InformationLevel Quiet
    if ($serverTcp) {
        Write-Check "App port" "PASS" "Port $ServerPort is listening."
    }
    else {
        Write-Check "App port" "WARN" "Port $ServerPort is not listening. This is normal before startup."
    }
}
catch {
    Write-Check "App port" "FAIL" $_.Exception.Message "Check whether port $ServerPort is blocked."
}

try {
    if (-not (Test-Path $AttachmentDir)) {
        New-Item -ItemType Directory -Path $AttachmentDir -Force | Out-Null
    }
    $probe = Join-Path $AttachmentDir ".write-test"
    "ok" | Set-Content -Path $probe -Encoding ASCII
    Remove-Item -LiteralPath $probe -Force
    $drive = Get-Item $AttachmentDir
    $freeGb = [math]::Round(($drive.PSDrive.Free / 1GB), 2)
    $status = if ($freeGb -ge 10) { "PASS" } else { "WARN" }
    Write-Check "Attachment dir" $status "$AttachmentDir; free about $freeGb GB" "Use a writable large disk folder and include it in backups."
}
catch {
    Write-Check "Attachment dir" "FAIL" $_.Exception.Message "Use a writable folder, for example D:\hos_patient_record_runtime\attachments."
}

try {
    $health = Test-HttpJson "$BackendUrl/health"
    Write-Check "Backend health" $(if ($health.status -eq "ok") { "PASS" } else { "FAIL" }) ($health | ConvertTo-Json -Compress) "Run 02-start-system.bat or check logs\backend.err.log."
}
catch {
    Write-Check "Backend health" "FAIL" $_.Exception.Message "Start the system first. If already started, check logs\backend.err.log."
}

try {
    $dbHealth = Test-HttpJson "$BackendUrl/health/db"
    $dbOk = $dbHealth.status -eq "ok"
    Write-Check "Database health" $(if ($dbOk) { "PASS" } else { "FAIL" }) ($dbHealth | ConvertTo-Json -Compress) "Check MYSQL_URL, MYSQL_USERNAME, and MYSQL_PASSWORD in config\runtime.env."
}
catch {
    Write-Check "Database health" "FAIL" $_.Exception.Message "Backend is not running or database is unavailable."
}

try {
    $maintenance = Test-HttpJson "$BackendUrl/clinic-api/maintenance/status"
    $storage = $maintenance.data.storage
    $missingOk = [int64]$storage.missingFileCount -eq 0
    $freeGb = [math]::Round(([double]$storage.usableSpaceBytes / 1GB), 2)
    Write-Check "Attachment references" $(if ($missingOk) { "PASS" } else { "FAIL" }) ("missing={0}; referenced={1}" -f $storage.missingFileCount, $storage.referencedFileCount) "Some referenced files are missing. Check whether the attachment directory was moved or cleaned."
    Write-Check "Attachment free space" $(if ($freeGb -ge 10) { "PASS" } else { "WARN" }) ("free={0}GB; dir={1}" -f $freeGb, $storage.attachmentDir) "Free space below 10GB should be handled before pilot use."
}
catch {
    Write-Check "Maintenance status" "WARN" $_.Exception.Message "Run this check again after system startup."
}

Write-Host ""
Write-Host ("Summary: PASS {0}, WARN {1}, FAIL {2}." -f $script:PassCount, $script:WarnCount, $script:FailCount) -ForegroundColor Cyan

if ($script:Suggestions.Count -gt 0) {
    Write-Host ""
    Write-Host "Suggestions:" -ForegroundColor Cyan
    $script:Suggestions | Select-Object -Unique | ForEach-Object {
        Write-Host "- $_"
    }
}

if ($script:FailCount -gt 0) {
    exit 1
}
