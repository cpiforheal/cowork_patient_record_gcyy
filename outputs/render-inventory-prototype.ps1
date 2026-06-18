Add-Type -AssemblyName System.Drawing

$OutputPath = "E:\CodeRESPOTORITY\hos_refactor\outputs\inventory-executive-prototype.png"
$W = 1732
$H = 924

function C($hex) {
  return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function New-Brush($hex) {
  return New-Object System.Drawing.SolidBrush (C $hex)
}

function New-Pen($hex, $width = 1) {
  return New-Object System.Drawing.Pen (C $hex), $width
}

function RoundedPath($x, $y, $w, $h, $r) {
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r * 2
  if ($r -le 0) {
    $path.AddRectangle([System.Drawing.RectangleF]::new($x, $y, $w, $h))
    return $path
  }
  $path.AddArc($x, $y, $d, $d, 180, 90)
  $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
  $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
  $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
  $path.CloseFigure()
  return $path
}

function Box($g, $x, $y, $w, $h, $fill = "#ffffff", $stroke = "#e5e7eb", $r = 8) {
  $path = RoundedPath $x $y $w $h $r
  $brush = New-Brush $fill
  $pen = New-Object System.Drawing.Pen (C $stroke), 1
  $g.FillPath($brush, $path)
  $g.DrawPath($pen, $path)
  $brush.Dispose()
  $pen.Dispose()
  $path.Dispose()
}

function Label($g, $s, $x, $y, $size = 14, $color = "#111827", $style = [System.Drawing.FontStyle]::Regular, $align = "Near") {
  $font = [System.Drawing.Font]::new("Microsoft YaHei", [single]$size, $style, [System.Drawing.GraphicsUnit]::Pixel)
  $brush = New-Brush $color
  $fmt = New-Object System.Drawing.StringFormat
  $fmt.Alignment = [System.Drawing.StringAlignment]::$align
  $fmt.LineAlignment = [System.Drawing.StringAlignment]::Near
  $g.DrawString($s, $font, $brush, [System.Drawing.PointF]::new($x, $y), $fmt)
  $fmt.Dispose()
  $brush.Dispose()
  $font.Dispose()
}

function CenterLabel($g, $s, $x, $y, $w, $h, $size = 13, $color = "#111827", $style = [System.Drawing.FontStyle]::Bold) {
  $font = [System.Drawing.Font]::new("Microsoft YaHei", [single]$size, $style, [System.Drawing.GraphicsUnit]::Pixel)
  $brush = New-Brush $color
  $fmt = New-Object System.Drawing.StringFormat
  $fmt.Alignment = [System.Drawing.StringAlignment]::Center
  $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
  $g.DrawString($s, $font, $brush, [System.Drawing.RectangleF]::new($x, $y, $w, $h), $fmt)
  $fmt.Dispose()
  $brush.Dispose()
  $font.Dispose()
}

function Pill($g, $s, $x, $y, $w, $fill, $color, $stroke = $fill) {
  Box $g $x $y $w 24 $fill $stroke 12
  CenterLabel $g $s $x $y $w 24 12 $color ([System.Drawing.FontStyle]::Bold)
}

function Line($g, $x1, $y1, $x2, $y2, $color = "#e5e7eb") {
  $pen = New-Object System.Drawing.Pen (C $color), 1
  $g.DrawLine($pen, $x1, $y1, $x2, $y2)
  $pen.Dispose()
}

function Circle($g, $x, $y, $r, $fill, $stroke = $fill, $strokeWidth = 1) {
  $brush = New-Brush $fill
  $pen = New-Object System.Drawing.Pen (C $stroke), $strokeWidth
  $g.FillEllipse($brush, $x - $r, $y - $r, $r * 2, $r * 2)
  $g.DrawEllipse($pen, $x - $r, $y - $r, $r * 2, $r * 2)
  $brush.Dispose()
  $pen.Dispose()
}

$bmp = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear((C "#f6f8fa"))

# Left navigation
Box $g 0 0 64 $H "#ffffff" "#e5e7eb" 0
Box $g 12 12 40 40 "#006d68" "#006d68" 10
CenterLabel $g "协" 12 12 40 40 18 "#ffffff" ([System.Drawing.FontStyle]::Bold)
$navY = @(78,130,182,234,286,338)
for ($i = 0; $i -lt $navY.Count; $i++) {
  $active = $i -eq 2
  Box $g 10 $navY[$i] 44 40 ($(if ($active) {"#e7f3f1"} else {"#ffffff"})) ($(if ($active) {"#bfdfdc"} else {"#ffffff"})) 8
  Circle $g 32 ($navY[$i] + 20) ($(if ($active) {8} else {6})) ($(if ($active) {"#006d68"} else {"#98a2b3"}))
}

# Top bar
Box $g 64 0 ($W - 64) 48 "#ffffff" "#e5e7eb" 0
Label $g "首页 / 物资管理 / 进销存管理" 86 16 13 "#667085"
$tabs = @("概览","患者病历","会诊协同","进销存管理","统计分析","系统设置")
for ($i = 0; $i -lt $tabs.Count; $i++) {
  $x = 320 + $i * 96
  $active = $i -eq 3
  Label $g $tabs[$i] $x 16 13 ($(if ($active) {"#006d68"} else {"#475467"})) ($(if ($active) {[System.Drawing.FontStyle]::Bold} else {[System.Drawing.FontStyle]::Regular}))
  if ($active) { Line $g $x 47 ($x + 72) 47 "#006d68" }
}
Circle $g 1600 24 14 "#edf2f7"
CenterLabel $g "管" 1586 10 28 28 12 "#344054" ([System.Drawing.FontStyle]::Bold)
Label $g "管理员" 1624 16 13 "#344054" ([System.Drawing.FontStyle]::Bold)

# Header
Box $g 86 66 1624 98
Label $g "进销存管理 / 领导驾驶舱" 104 86 13 "#006d68" ([System.Drawing.FontStyle]::Bold)
Label $g "今天物资运行是否安全" 104 111 24 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "聚焦库存、申领、签字、风险闭环四类关键状态，保留原业务入口与操作路径。" 104 141 14 "#667085"
Box $g 1376 92 92 32 "#ffffff" "#d1d5db" 6
CenterLabel $g "导出" 1376 92 92 32 13 "#344054" ([System.Drawing.FontStyle]::Bold)
Box $g 1478 92 96 32 "#006d68" "#006d68" 6
CenterLabel $g "新增单据" 1478 92 96 32 13 "#ffffff" ([System.Drawing.FontStyle]::Bold)
Box $g 1584 92 104 32 "#ffffff" "#d1d5db" 6
CenterLabel $g "刷新数据" 1584 92 104 32 13 "#344054" ([System.Drawing.FontStyle]::Bold)

# Filter bar
Box $g 86 178 1624 54
Pill $g "全部" 106 193 56 "#006d68" "#ffffff"
Pill $g "库存预警" 170 193 86 "#ffffff" "#475467" "#d1d5db"
Pill $g "科室申领" 264 193 86 "#ffffff" "#475467" "#d1d5db"
Pill $g "出入库" 358 193 72 "#ffffff" "#475467" "#d1d5db"
Pill $g "待签字" 438 193 72 "#ffffff" "#475467" "#d1d5db"
Label $g "统计范围" 1222 199 12 "#667085" ([System.Drawing.FontStyle]::Bold)
Box $g 1290 189 132 30 "#f9fafb" "#d1d5db" 6
CenterLabel $g "今日 00:00 至今" 1290 189 132 30 13 "#344054" ([System.Drawing.FontStyle]::Bold)
Box $g 1434 189 118 30 "#f9fafb" "#d1d5db" 6
CenterLabel $g "全院科室" 1434 189 118 30 13 "#344054" ([System.Drawing.FontStyle]::Bold)
Box $g 1564 189 124 30 "#f9fafb" "#d1d5db" 6
CenterLabel $g "低库存优先" 1564 189 124 30 13 "#344054" ([System.Drawing.FontStyle]::Bold)

# KPI cards
Box $g 86 246 392 154
Label $g "今日红绿灯" 106 269 14 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "基于库存下限、申领时效、签字积压综合判断" 106 293 13 "#667085"
Circle $g 140 346 30 "#eaf6ef" "#c8ead5"
Circle $g 140 346 14 "#2f855a"
Label $g "安全" 188 326 20 "#2f855a" ([System.Drawing.FontStyle]::Bold)
Label $g "仅 2 项需要当班处理" 188 354 13 "#667085"
Pill $g "闭环率 94%" 106 374 88 "#eaf6ef" "#2f855a"
Pill $g "待签字 12" 204 374 86 "#fff7e6" "#b7791f"

Box $g 494 246 392 154
Label $g "紧急风险" 514 269 14 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "影响手术、抢救、基础护理的关键耗材" 514 293 13 "#667085"
Label $g "3" 514 318 52 "#c92a2a" ([System.Drawing.FontStyle]::Bold)
Label $g "项" 582 336 16 "#c92a2a" ([System.Drawing.FontStyle]::Bold)
Label $g "留置针、止血纱布、急救包库存接近下限" 514 376 13 "#667085"
Pill $g "立即处理" 760 362 86 "#fff1f1" "#c92a2a"

Box $g 902 246 392 154
Label $g "关注事项" 922 269 14 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "需要科室或库房协同跟进的单据" 922 293 13 "#667085"
Label $g "27" 922 318 52 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "单" 995 336 16 "#475467" ([System.Drawing.FontStyle]::Bold)
Label $g "其中 8 单超过 30 分钟未更新状态" 922 376 13 "#667085"
Pill $g "待确认" 1168 362 76 "#fff7e6" "#b7791f"

Box $g 1310 246 400 154
Label $g "关键指标" 1330 269 14 "#111827" ([System.Drawing.FontStyle]::Bold)
$metrics = @(
  @("库存周转","8.6 天","#006d68"),
  @("申领完成","96.2%","#2f855a"),
  @("异常单据","5 单","#c92a2a")
)
for ($i = 0; $i -lt $metrics.Count; $i++) {
  $x = 1330 + $i * 122
  Label $g $metrics[$i][0] $x 312 12 "#667085" ([System.Drawing.FontStyle]::Bold)
  Label $g $metrics[$i][1] $x 338 25 $metrics[$i][2] ([System.Drawing.FontStyle]::Bold)
  Line $g $x 372 ($x + 86) 372
}

# Consumption chart
Box $g 86 414 754 236
Label $g "科室消耗 TOP" 106 437 15 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "按今日出库金额与高频耗材综合排序" 106 459 13 "#667085"
$bars = @(
  @("急诊科",86,"#006d68"),
  @("ICU",74,"#2f855a"),
  @("骨科一病区",63,"#2563eb"),
  @("手术室",52,"#b7791f"),
  @("儿科",41,"#667085")
)
for ($i = 0; $i -lt $bars.Count; $i++) {
  $y = 497 + $i * 28
  Label $g $bars[$i][0] 106 ($y - 6) 13 "#344054" ([System.Drawing.FontStyle]::Bold)
  Box $g 220 ($y - 2) 500 10 "#f1f5f9" "#f1f5f9" 5
  Box $g 220 ($y - 2) ([int]$bars[$i][1] * 5) 10 $bars[$i][2] $bars[$i][2] 5
  Label $g "$($bars[$i][1])%" 742 ($y - 8) 13 "#667085" ([System.Drawing.FontStyle]::Bold)
}

# Table
Box $g 856 414 854 236
Label $g "物资库存明细" 876 437 15 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "保留原表格字段，增强风险状态可读性" 876 459 13 "#667085"
Line $g 876 486 1688 486
$headers = @("物资名称","当前库存","安全库存","状态")
for ($i = 0; $i -lt $headers.Count; $i++) {
  Label $g $headers[$i] (892 + $i * 190) 501 12 "#667085" ([System.Drawing.FontStyle]::Bold)
}
$rows = @(
  @("酒精棉片","35 盒","21 盒","偏高","#fff7e6","#b7791f"),
  @("一次性注射器","184 支","92 支","正常","#eaf6ef","#2f855a"),
  @("医用纱布块","76 包","68 包","正常","#eaf6ef","#2f855a"),
  @("留置针","19 个","43 个","偏低","#fff1f1","#c92a2a")
)
for ($i = 0; $i -lt $rows.Count; $i++) {
  $y = 532 + $i * 32
  if ($i -gt 0) { Line $g 876 ($y - 13) 1688 ($y - 13) }
  Label $g $rows[$i][0] 892 ($y - 9) 13 "#111827" ([System.Drawing.FontStyle]::Bold)
  Label $g $rows[$i][1] 1082 ($y - 9) 13 "#344054"
  Label $g $rows[$i][2] 1272 ($y - 9) 13 "#344054"
  Pill $g $rows[$i][3] 1462 ($y - 16) 54 $rows[$i][4] $rows[$i][5]
}

# Approval list
Box $g 86 664 754 220
Label $g "待签字事项" 106 687 15 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "按超时风险排序，减少领导视角下的信息噪声" 106 709 13 "#667085"
$approvals = @(
  @("骨科一病区","高值耗材领用单","待护士长签字","18 分钟"),
  @("急诊科","补货申请单","待库管复核","42 分钟"),
  @("ICU","科室退库单","待财务确认","1 小时")
)
for ($i = 0; $i -lt $approvals.Count; $i++) {
  $y = 744 + $i * 42
  if ($i -gt 0) { Line $g 106 ($y - 18) 820 ($y - 18) }
  Label $g $approvals[$i][0] 106 ($y - 10) 13 "#111827" ([System.Drawing.FontStyle]::Bold)
  Label $g $approvals[$i][1] 224 ($y - 10) 13 "#344054"
  Label $g $approvals[$i][2] 436 ($y - 10) 13 "#b7791f" ([System.Drawing.FontStyle]::Bold)
  Label $g $approvals[$i][3] 720 ($y - 10) 13 "#667085" ([System.Drawing.FontStyle]::Bold)
}
Box $g 106 842 120 30 "#006d68" "#006d68" 6
CenterLabel $g "进入签字中心" 106 842 120 30 13 "#ffffff" ([System.Drawing.FontStyle]::Bold)

# Risk timeline
Box $g 856 664 854 220
Label $g "风险闭环" 876 687 15 "#111827" ([System.Drawing.FontStyle]::Bold)
Label $g "把原来的分散卡片收敛成可追踪的处理链路" 876 709 13 "#667085"
$steps = @(
  @("发现","低库存预警 3 项","#c92a2a"),
  @("派发","已通知库房与责任科室","#b7791f"),
  @("处理","2 项补货中，1 项待审批","#2563eb"),
  @("复核","预计 16:30 前闭环","#2f855a")
)
for ($i = 0; $i -lt $steps.Count; $i++) {
  $x = 900 + $i * 196
  if ($i -lt 3) { Line $g ($x + 22) 778 ($x + 174) 778 "#d1d5db" }
  Circle $g $x 778 18 "#ffffff" $steps[$i][2] 3
  CenterLabel $g ([string]($i + 1)) ($x - 18) 760 36 36 13 $steps[$i][2] ([System.Drawing.FontStyle]::Bold)
  Label $g $steps[$i][0] ($x - 30) 812 13 "#111827" ([System.Drawing.FontStyle]::Bold)
  Label $g $steps[$i][1] ($x - 30) 836 12 "#667085" ([System.Drawing.FontStyle]::Bold)
}

$bmp.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output $OutputPath
