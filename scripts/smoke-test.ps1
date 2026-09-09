# =========================================================================
# smoke-test.ps1 - Автоматическая приёмка для ревью (Windows PowerShell)
# =========================================================================
# Запуск:
#   powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
# =========================================================================

$ErrorActionPreference = "Stop"
$Gateway = "http://localhost:8080"

function Pass($msg) { Write-Host "ok $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "err $msg" -ForegroundColor Red; exit 1 }
function Info($msg) { Write-Host "info $msg" -ForegroundColor Yellow }

Write-Host "=========================================="
Write-Host "  Smoke Test - Automated Acceptance"
Write-Host "  СМП Processing Simulator"
Write-Host "=========================================="
Write-Host ""

# -------------------------------------------------------------------
# 1. Health-checks всех 11 сервисов + RabbitMQ
# -------------------------------------------------------------------
Info "1. Checking health-checks..."

function Check-Health($name, $url) {
    try {
        $response = Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing
        if ($response.StatusCode -eq 200) { Pass "$name ($url) - HTTP 200" }
        else { Fail "$name ($url) - HTTP $($response.StatusCode) (expected 200)" }
    } catch {
        Fail "$name ($url) - UNREACHABLE"
    }
}

Check-Health "Gateway"            "$Gateway/health"
Check-Health "Card Management"    "http://localhost:8081/health"
Check-Health "Switch"             "http://localhost:8082/health"
Check-Health "Authorization"      "http://localhost:8083/health"
Check-Health "Terminal Simulator" "http://localhost:8085/health"
Check-Health "Merchant Simulator" "http://localhost:8084/health"
Check-Health "Transaction Logger" "http://localhost:8088/health"
Check-Health "Bin Lookup"         "http://localhost:8096/actuator/health"
Check-Health "Notification Service" "http://localhost:8097/actuator/health"

# RabbitMQ Management UI — проверяем доступность
try {
    $rmq = Invoke-WebRequest -Uri "http://localhost:15672" -TimeoutSec 5 -UseBasicParsing
    Pass "RabbitMQ Management UI (http://localhost:15672) - HTTP $($rmq.StatusCode)"
} catch {
    Fail "RabbitMQ Management UI (http://localhost:15672) - UNREACHABLE"
}

try {
    $db = Invoke-WebRequest -Uri "http://localhost:3000" -TimeoutSec 5 -UseBasicParsing
    Pass "Web Dashboard (http://localhost:3000) - HTTP $($db.StatusCode)"
} catch {
    Fail "Web Dashboard (http://localhost:3000) - UNREACHABLE"
}

Write-Host ""

# -------------------------------------------------------------------
# 2. Генерация тестовых карт
# -------------------------------------------------------------------
Info "2. Generating test cards..."

$cardsBody = @{ count = 500; bins = @("400000","400001","400002","400003","400004") } | ConvertTo-Json -Compress
try {
    $cardsResponse = Invoke-RestMethod -Uri "$Gateway/api/cards/generate" -Method Post -Body $cardsBody -ContentType "application/json" -TimeoutSec 10
    if ($cardsResponse.generated -ge 500) {
        Pass "Cards generated: $($cardsResponse.generated)"
        $PAN = $cardsResponse.cards[0].pan
        Pass "First test PAN: $($PAN.Substring(0,4))****$($PAN.Substring(12,4))"
    } else {
        Fail "Card generation returned $($cardsResponse.generated) cards (expected >= 20)"
    }
} catch {
    Fail "Card generation FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 3. Одиночная тестовая транзакция
# -------------------------------------------------------------------
Info "3. Testing single transaction..."

$txBody = @{
    mti = "0100"
    stan = "000001"
    pan = $PAN
    processingCode = "000000"
    amount = 150000
    currencyCode = "643"
    transmissionDateTime = "2026-06-01T10:30:00Z"
    terminalId = "TERM0001"
    merchantId = "MERCH0000000001"
    mcc = "5411"
    acquirerId = "ACQ001"
} | ConvertTo-Json -Compress

try {
    $txResponse = Invoke-RestMethod -Uri "$Gateway/api/transactions" -Method Post -Body $txBody -ContentType "application/json" -TimeoutSec 15
    if ($txResponse.status -eq "APPROVED") {
        Pass "Single transaction: APPROVED (processingTimeMs: $($txResponse.processingTimeMs)ms)"
    } elseif ($txResponse.status -eq "DECLINED") {
        Info "Single transaction: DECLINED (reason: $($txResponse.declineReason))"
        Info "This may be OK if card has insufficient balance"
    } else {
        Fail "Single transaction: unexpected status '$($txResponse.status)'"
    }
} catch {
    Fail "Single transaction FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 3a. Проверка bin-lookup endpoint
# -------------------------------------------------------------------
Info "3a. Testing bin-lookup endpoint..."

try {
    $binResponse = Invoke-RestMethod -Uri "http://localhost:8096/api/bin/400000" -TimeoutSec 5
    if ($binResponse.issuerId -eq "ISS001") {
        Pass "Bin Lookup: GET /api/bin/400000 - issuerId=$($binResponse.issuerId)"
    } else {
        Fail "Bin Lookup: GET /api/bin/400000 - expected issuerId=ISS001, got $($binResponse.issuerId)"
    }
} catch {
    Fail "Bin Lookup endpoint FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 3b. Проверка notification-service endpoint
# -------------------------------------------------------------------
Info "3b. Testing notification-service endpoint..."

try {
    $nsResponse = Invoke-WebRequest -Uri "http://localhost:8097/api/notifications" -TimeoutSec 5 -UseBasicParsing
    if ($nsResponse.StatusCode -eq 200) {
        Pass "Notification Service: GET /api/notifications - HTTP 200"
    } else {
        Fail "Notification Service: GET /api/notifications - HTTP $($nsResponse.StatusCode) (expected 200)"
    }
} catch {
    Fail "Notification Service endpoint FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 4. Симулятор: 50 транзакций (mixed-сценарий)
# -------------------------------------------------------------------
Info "4. Running terminal simulator (50 TX, mixed scenario)..."

$simBody = @{ count = 50; scenario = "mixed"; tps = 50 } | ConvertTo-Json -Compress
try {
    $simResponse = Invoke-RestMethod -Uri "$Gateway/api/simulator/terminal/run" -Method Post -Body $simBody -ContentType "application/json" -TimeoutSec 60
    if ($simResponse.totalSubmitted -eq 50) {
        Pass "Simulator: $($simResponse.totalSubmitted) submitted, $($simResponse.approved) approved, $($simResponse.declined) declined"
    } else {
        Fail "Simulator: expected 50 submitted, got $($simResponse.totalSubmitted)"
    }
} catch {
    Fail "Simulator FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 5. Поиск транзакций
# -------------------------------------------------------------------
Info "5. Testing transaction search..."

try {
    $searchResponse = Invoke-RestMethod -Uri "$Gateway/api/transactions/search?limit=5" -TimeoutSec 10
    if ($searchResponse.total -gt 0) {
        Pass "Search: $($searchResponse.total) transactions found"
    } else {
        Fail "Search: no transactions found"
    }
} catch {
    Fail "Search FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 6. Dashboard статистика
# -------------------------------------------------------------------
Info "6. Testing dashboard stats..."

try {
    $statsResponse = Invoke-RestMethod -Uri "$Gateway/api/dashboard/stats" -TimeoutSec 10
    if ($statsResponse.totalTransactions -gt 0) {
        Pass "Dashboard stats: $($statsResponse.totalTransactions) total TX, approval rate: $($statsResponse.approvalRate)"
    } else {
        Fail "Dashboard stats: totalTransactions is 0"
    }
} catch {
    Fail "Dashboard stats FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# 7. Dashboard recent transactions
# -------------------------------------------------------------------
Info "7. Testing dashboard recent..."

try {
    $recentResponse = Invoke-RestMethod -Uri "$Gateway/api/dashboard/recent?limit=5" -TimeoutSec 10
    if ($recentResponse.Count -gt 0) {
        Pass "Recent transactions: $($recentResponse.Count) entries"
    } else {
        Fail "Recent transactions: empty array"
    }
} catch {
    Fail "Recent transactions FAILED: $_"
}

Write-Host ""

# -------------------------------------------------------------------
# Итог
# -------------------------------------------------------------------
Write-Host "=========================================="
Write-Host "🎉 ALL CHECKS PASSED" -ForegroundColor Green
Write-Host "=========================================="
