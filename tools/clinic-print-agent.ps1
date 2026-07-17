param(
    [int]$Port = 18848,
    [string]$ConfigPath = ""
)

$ErrorActionPreference = "Stop"
$agentVersion = "1.0.0"
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $PSScriptRoot "clinic-print-agent.json"
}

function Read-AgentConfig {
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        return [ordered]@{ terminalId = ""; terminalName = ""; printerName = "" }
    }
    try {
        return Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        return [ordered]@{ terminalId = ""; terminalName = ""; printerName = "" }
    }
}

function Write-AgentConfig($Config) {
    $directory = Split-Path -Parent $ConfigPath
    if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    $Config | ConvertTo-Json | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
}

function Get-Printers {
    try {
        return @(Get-CimInstance Win32_Printer | Sort-Object Name | ForEach-Object Name)
    } catch {
        return @()
    }
}

function Send-Json($Context, [int]$StatusCode, $Body) {
    $json = $Body | ConvertTo-Json -Depth 8 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $origin = [string]$Context.Request.Headers["Origin"]
    $Context.Response.StatusCode = $StatusCode
    $Context.Response.ContentType = "application/json; charset=utf-8"
    $Context.Response.ContentLength64 = $bytes.Length
    if ($origin) {
        $Context.Response.Headers["Access-Control-Allow-Origin"] = $origin
        $Context.Response.Headers["Vary"] = "Origin"
    }
    $Context.Response.Headers["Access-Control-Allow-Headers"] = "Content-Type, Access-Control-Request-Private-Network"
    $Context.Response.Headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS"
    $Context.Response.Headers["Access-Control-Allow-Private-Network"] = "true"
    $Context.Response.Headers["Cache-Control"] = "no-store"
    $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Context.Response.Close()
}

function Escape-Html([string]$Value) {
    return [System.Net.WebUtility]::HtmlEncode($Value)
}

function Print-QueueTicket($Payload, $Config) {
    if (-not $Config.printerName) { throw "尚未绑定 Windows 打印机" }
    $installed = Get-Printers
    if ($installed -notcontains $Config.printerName) { throw "绑定的打印机不存在或已离线：$($Config.printerName)" }
    if ($Payload.terminalId -ne $Config.terminalId) { throw "打印任务与本机终端不匹配" }
    if (-not $Payload.executionToken) { throw "打印任务缺少执行令牌" }

    $template = $Payload.template
    if (-not $template) { throw "打印任务缺少票据模板" }
    Add-Type -AssemblyName System.Drawing
    $paperWidth = if ([int]$template.paperWidth -eq 80) { 80 } else { 58 }
    $numberSize = [Math]::Max(30, [Math]::Min(64, [int]$template.numberFontSize))
    $compact = [bool]$template.compact
    $lines = [Collections.Generic.List[object]]::new()
    $lines.Add(@{ text = [string]$template.institutionName; size = 12; bold = $true; align = "center" })
    $lines.Add(@{ text = [string]$template.title; size = 9; bold = $false; align = "center" })
    if ($Payload.reprint) { $lines.Add(@{ text = "【补打】"; size = 9; bold = $true; align = "center" }) }
    elseif ($Payload.test) { $lines.Add(@{ text = "【测试票】"; size = 9; bold = $true; align = "center" }) }
    $lines.Add(@{ text = [string]$Payload.publicNo; size = $numberSize; bold = $true; align = "center"; separator = $true })
    if ($template.showMaskedName) { $lines.Add(@{ text = "患者：$($Payload.maskedName)"; size = 9; bold = $false; align = "left" }) }
    if ($template.showVisitType) { $lines.Add(@{ text = "类型：$($Payload.visitType)"; size = 9; bold = $false; align = "left" }) }
    if ($template.showFirstStage) { $lines.Add(@{ text = "请前往：$($Payload.firstStage)"; size = 9; bold = $false; align = "left" }) }
    if ($template.showIssuedAt) { $lines.Add(@{ text = "取号：$($Payload.issuedAt)"; size = 8; bold = $false; align = "left" }) }
    if ($template.showNotice) {
        if ($template.notice) { $lines.Add(@{ text = [string]$template.notice; size = 8; bold = $false; align = "center"; separatorTop = $true }) }
        if ($template.secondaryNotice) { $lines.Add(@{ text = [string]$template.secondaryNotice; size = 8; bold = $false; align = "center" }) }
    }

    $document = [Drawing.Printing.PrintDocument]::new()
    $document.PrinterSettings.PrinterName = [string]$Config.printerName
    if (-not $document.PrinterSettings.IsValid) { throw "打印机不可用：$($Config.printerName)" }
    $document.PrintController = [Drawing.Printing.StandardPrintController]::new()
    $widthHundredths = [int][Math]::Round($paperWidth / 25.4 * 100)
    $document.DefaultPageSettings.PaperSize = [Drawing.Printing.PaperSize]::new("ClinicTicket", $widthHundredths, 1200)
    $document.DefaultPageSettings.Margins = [Drawing.Printing.Margins]::new(8, 8, 4, 4)
    $lineGap = if ($compact) { 1.0 } else { 3.0 }
    $handler = [Drawing.Printing.PrintPageEventHandler]{
        param($sender, $eventArgs)
        $graphics = $eventArgs.Graphics
        $bounds = $eventArgs.MarginBounds
        $y = [single]$bounds.Top
        foreach ($line in $lines) {
            $style = if ($line.bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
            $font = [Drawing.Font]::new("Microsoft YaHei", [single]$line.size, $style, [Drawing.GraphicsUnit]::Point)
            try {
                $format = [Drawing.StringFormat]::new()
                $format.Alignment = if ($line.align -eq "center") { [Drawing.StringAlignment]::Center } else { [Drawing.StringAlignment]::Near }
                $format.LineAlignment = [Drawing.StringAlignment]::Near
                if ($line.separatorTop) {
                    $graphics.DrawLine([Drawing.Pens]::Black, $bounds.Left, $y, $bounds.Right, $y)
                    $y += 4
                }
                $measured = $graphics.MeasureString([string]$line.text, $font, $bounds.Width)
                if ($line.separator) {
                    $graphics.DrawLine([Drawing.Pens]::Black, $bounds.Left, $y, $bounds.Right, $y)
                    $y += 3
                }
                $rect = [Drawing.RectangleF]::new([single]$bounds.Left, $y, [single]$bounds.Width, [single]($measured.Height + 2))
                $graphics.DrawString([string]$line.text, $font, [Drawing.Brushes]::Black, $rect, $format)
                $y += [single]$measured.Height + [single]$lineGap
                if ($line.separator) {
                    $graphics.DrawLine([Drawing.Pens]::Black, $bounds.Left, $y, $bounds.Right, $y)
                    $y += 3
                }
                $format.Dispose()
            } finally {
                $font.Dispose()
            }
        }
        $eventArgs.HasMorePages = $false
    }
    $document.add_PrintPage($handler)
    try {
        $document.Print()
    } finally {
        $document.remove_PrintPage($handler)
        $document.Dispose()
    }
}

$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Host "Clinic print agent listening on http://127.0.0.1:$Port/"

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            if ($context.Request.HttpMethod -eq "OPTIONS") {
                Send-Json $context 200 @{ status = "ok" }
                continue
            }
            $config = Read-AgentConfig
            $path = $context.Request.Url.AbsolutePath
            if ($context.Request.HttpMethod -eq "GET" -and $path -eq "/health") {
                Send-Json $context 200 @{
                    status = "ok"; terminalId = [string]$config.terminalId; terminalName = [string]$config.terminalName;
                    printerName = [string]$config.printerName; version = $agentVersion; printers = @(Get-Printers)
                }
                continue
            }
            $memory = [IO.MemoryStream]::new()
            $context.Request.InputStream.CopyTo($memory)
            $bodyBytes = $memory.ToArray()
            $memory.Dispose()
            $body = [Text.Encoding]::UTF8.GetString($bodyBytes)
            try {
                $payload = if ($body) { $body | ConvertFrom-Json -ErrorAction Stop } else { [pscustomobject]@{} }
            } catch {
                throw "请求 JSON 解析失败：$($_.Exception.Message)"
            }
            if ($context.Request.HttpMethod -eq "POST" -and $path -eq "/configure") {
                if (-not $payload.terminalId -or -not $payload.terminalName -or -not $payload.printerName) { throw "终端编号、名称和打印机不能为空" }
                if ((Get-Printers) -notcontains $payload.printerName) { throw "所选打印机不存在" }
                $config = [ordered]@{ terminalId = [string]$payload.terminalId; terminalName = [string]$payload.terminalName; printerName = [string]$payload.printerName }
                Write-AgentConfig $config
                Send-Json $context 200 @{ status = "ok"; terminalId = $config.terminalId; terminalName = $config.terminalName; printerName = $config.printerName; version = $agentVersion; printers = @(Get-Printers) }
                continue
            }
            if ($context.Request.HttpMethod -eq "POST" -and $path -eq "/print/queue-ticket") {
                Print-QueueTicket $payload $config
                Send-Json $context 200 @{ status = "SUCCESS"; printerName = [string]$config.printerName }
                continue
            }
            Send-Json $context 404 @{ status = "error"; message = "接口不存在" }
        } catch {
            if ($context.Response.OutputStream.CanWrite) {
                Send-Json $context 500 @{ status = "FAILED"; message = $_.Exception.Message; errorMessage = $_.Exception.Message }
            }
        }
    }
} finally {
    $listener.Stop()
    $listener.Close()
}
