param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$MysqlHost = "localhost",
    [int]$MysqlPort = 3306,
    [int]$ServerPort = 8080,
    [string]$AttachmentDir = "D:\hos_patient_record_runtime\attachments"
)

$ErrorActionPreference = "Continue"

function Write-Check {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Detail
    )

    $status = if ($Ok) { "OK" } else { "FAIL" }
    $color = if ($Ok) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail) -ForegroundColor $color
}

Write-Host "Clinic pilot host check"
Write-Host "BackendUrl=$BackendUrl"
Write-Host ""

$java = $null
try {
    $java = & java -version 2>&1
    $javaText = ($java -join " ")
    Write-Check "Java" ($LASTEXITCODE -eq 0 -and $javaText -match "17\.|18\.|19\.|20\.|21\.|22\.") $javaText
}
catch {
    Write-Check "Java" $false $_.Exception.Message
}

try {
    $mysqlTcp = Test-NetConnection -ComputerName $MysqlHost -Port $MysqlPort -InformationLevel Quiet
    Write-Check "MySQL TCP" $mysqlTcp "$MysqlHost`:$MysqlPort"
}
catch {
    Write-Check "MySQL TCP" $false $_.Exception.Message
}

try {
    $serverTcp = Test-NetConnection -ComputerName "localhost" -Port $ServerPort -InformationLevel Quiet
    $detail = if ($serverTcp) { "Port $ServerPort is already listening" } else { "Port $ServerPort is free before backend starts" }
    Write-Check "Backend port" $true $detail
}
catch {
    Write-Check "Backend port" $false $_.Exception.Message
}

try {
    if (-not (Test-Path $AttachmentDir)) {
        New-Item -ItemType Directory -Path $AttachmentDir -Force | Out-Null
    }
    $probe = Join-Path $AttachmentDir ".write-test"
    "ok" | Set-Content -Path $probe -Encoding ASCII
    Remove-Item -LiteralPath $probe -Force
    Write-Check "Attachment dir" $true $AttachmentDir
}
catch {
    Write-Check "Attachment dir" $false $_.Exception.Message
}

try {
    $health = Invoke-RestMethod -Uri "$BackendUrl/health" -TimeoutSec 5
    Write-Check "Backend health" ($health.status -eq "ok") ($health | ConvertTo-Json -Compress)
}
catch {
    Write-Check "Backend health" $false $_.Exception.Message
}

try {
    $dbHealth = Invoke-RestMethod -Uri "$BackendUrl/health/db" -TimeoutSec 5
    Write-Check "Database health" ($dbHealth.status -eq "ok") ($dbHealth | ConvertTo-Json -Compress)
}
catch {
    Write-Check "Database health" $false $_.Exception.Message
}
