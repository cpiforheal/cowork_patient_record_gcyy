param(
    [string]$JavaHome = "C:\Users\Administrator\.jdks\ms-17.0.18",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$frontendDir = Join-Path $root "coshare_patientrecord_sys_frontend\Geeker-Admin"
$backendDir = Join-Path $root "coshare_patientrecord_sys_backend"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "== $Message ==" -ForegroundColor Cyan
}

function Assert-Text([string]$Path, [string]$Pattern, [string]$Message) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -notmatch $Pattern) {
        throw $Message
    }
    Write-Host "PASS $Message" -ForegroundColor Green
}

function Assert-NoText([string]$Path, [string]$Pattern, [string]$Message) {
    $content = Get-Content -Raw -LiteralPath $Path
    if ($content -match $Pattern) {
        throw $Message
    }
    Write-Host "PASS $Message" -ForegroundColor Green
}

function Invoke-CheckedCommand([scriptblock]$Command, [string]$Message) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Message failed with exit code $LASTEXITCODE"
    }
}

Write-Step "Static checks"
Assert-Text `
    -Path (Join-Path $frontendDir ".env.development") `
    -Pattern '\["/auth","http://localhost:8080",true\]' `
    -Message "development proxy includes /auth"
Assert-Text `
    -Path (Join-Path $frontendDir ".env.development") `
    -Pattern '\["/clinic-api","http://localhost:8080",true\]' `
    -Message "development proxy includes /clinic-api"
Assert-Text `
    -Path (Join-Path $frontendDir ".env.development") `
    -Pattern '\["/inventory-api","http://localhost:8080",true\]' `
    -Message "development proxy includes /inventory-api"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'finally\s*\{' `
    -Message "logout always clears local session"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'await router\.replace\(LOGIN_URL\)' `
    -Message "logout waits for navigation before clearing layout state"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'userStore\.setToken\(""\)[\s\S]*await router\.replace\(LOGIN_URL\)[\s\S]*authStore\.\$reset\(\)' `
    -Message "logout clears token before login navigation and resets layout after navigation"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'authStore\.\$reset\(\)' `
    -Message "logout resets dynamic auth menus"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'tabsStore\.\$reset\(\)' `
    -Message "logout resets opened tabs"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Avatar.vue") `
    -Pattern 'keepAliveStore\.\$reset\(\)' `
    -Message "logout resets page cache"
Assert-Text `
    -Path (Join-Path $frontendDir "src\api\modules\login.ts") `
    -Pattern 'readJsonResult' `
    -Message "auth fetch responses use safe JSON parsing"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Breadcrumb.vue") `
    -Pattern 'if \(!breadcrumbData\.length\) return \[\]' `
    -Message "breadcrumb tolerates empty auth menu state"
Assert-Text `
    -Path (Join-Path $frontendDir "src\layouts\components\Header\components\Breadcrumb.vue") `
    -Pattern 'if \(!currentRouteRecord\) return \[\]' `
    -Message "breadcrumb tolerates transient empty route match state"
Assert-NoText `
    -Path (Join-Path $frontendDir "src\views\login\components\LoginForm.vue") `
    -Pattern ':disabled="!loginForm\.department"' `
    -Message "login account selector is not blocked by missing department"
Assert-NoText `
    -Path (Join-Path $frontendDir "src\views\login\components\LoginForm.vue") `
    -Pattern 'department:\s*\[\{\s*required:\s*true' `
    -Message "login department selector is optional"
Assert-Text `
    -Path (Join-Path $frontendDir "src\routers\index.ts") `
    -Pattern '!userStore\.token\)[\s\S]*LOGIN_URL' `
    -Message "router guard redirects anonymous users to login"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthApiController.java") `
    -Pattern '@PostMapping\("/auth/logout"\)' `
    -Message "backend exposes /auth/logout"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern 'passwordEncoder\.matches' `
    -Message "login verifies BCrypt password hash"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern 'isBcrypt\(storedPassword\)' `
    -Message "login rejects legacy plaintext password values"
Assert-NoText `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern '123456' `
    -Message "login has no default password fallback"
Assert-NoText `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern 'return new LoginOptions\(departments, List\.of\(\)\)' `
    -Message "login options include fallback account list"
Assert-NoText `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern 'normalizedDepartment\.isBlank\(\)\)\s*\{\s*return new LoginAccountOptions\(List\.of\(\)\)' `
    -Message "blank department returns all enabled accounts"
Assert-NoText `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\AuthSessionService.java") `
    -Pattern 'ORDER BY department, name, username' `
    -Message "login account query only orders by real table columns"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\SecurityConfig.java") `
    -Pattern '"/health/db", "/auth/logout", "/clinic-api/\*\*", "/inventory-api/\*\*"\)\.authenticated\(\)' `
    -Message "business APIs and db health require authentication"
Assert-NoText `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\DatabaseHealthService.java") `
    -Pattern 'response\.put\("username"' `
    -Message "db health response does not expose username"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\ClinicApiController.java") `
    -Pattern 'requireClinicWriter\(payload\)' `
    -Message "clinic write APIs enforce role checks"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\ClinicApiController.java") `
    -Pattern 'prepareWritePayload' `
    -Message "clinic writes sanitize operator-owned fields"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\ClinicApiController.java") `
    -Pattern 'readDbForUser' `
    -Message "clinic reads are scoped by current user"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryApiController.java") `
    -Pattern 'requests/(approve|issue|receive|reject|cancel|void)' `
    -Message "inventory request lifecycle endpoints exist"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryApiController.java") `
    -Pattern 'require(Staff|Manager)\(\)' `
    -Message "inventory write APIs enforce server-side roles"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'inventory_request_lines' `
    -Message "inventory requests support multiple line items"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'FOR UPDATE' `
    -Message "inventory issuing uses row locks"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'chooseBatchesForIssue' `
    -Message "inventory issuing can allocate across batches"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'partially_issued' `
    -Message "inventory requests support partial issue"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'rejected[\s\S]*cancelled[\s\S]*void' `
    -Message "inventory requests support reject cancel and void states"
Assert-Text `
    -Path (Join-Path $backendDir "src\main\java\com\example\coshare_patientrecord_sys\InventoryDatabaseService.java") `
    -Pattern 'batchRequired[\s\S]*expiryRequired' `
    -Message "inventory inbound honors batch and expiry requirements"
Assert-Text `
    -Path (Join-Path $frontendDir "src\api\modules\inventory.ts") `
    -Pattern 'createInventoryRequestApi[\s\S]*approveInventoryRequestApi[\s\S]*issueInventoryRequestApi[\s\S]*receiveInventoryRequestApi[\s\S]*rejectInventoryRequestApi[\s\S]*cancelInventoryRequestApi[\s\S]*voidInventoryRequestApi' `
    -Message "frontend exposes complete inventory lifecycle API calls"

if (-not $SkipBuild) {
    Write-Step "Frontend build"
    Push-Location $frontendDir
    try {
        Invoke-CheckedCommand { pnpm build:dev } "Frontend build"
    } finally {
        Pop-Location
    }

    Write-Step "Backend package"
    Push-Location $backendDir
    try {
        if (Test-Path -LiteralPath $JavaHome) {
            $env:JAVA_HOME = $JavaHome
            $env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path
        }
        Invoke-CheckedCommand { .\mvnw.cmd -DskipTests package } "Backend package"
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "Pilot source check passed." -ForegroundColor Green
