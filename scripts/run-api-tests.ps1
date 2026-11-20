$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot

if (-not (Test-Path -LiteralPath ".env")) {
    Copy-Item -LiteralPath ".env.example" -Destination ".env"
}

# A small deterministic bucket keeps the 429 contract test fast. Other transaction
# tests use a unique X-Forwarded-For value and therefore have isolated buckets.
$env:TRANSACTIONS_RATE_LIMIT_CAPACITY = "3"
$env:TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND = "1"

$composeFiles = @("-f", "docker-compose.yaml", "-f", "docker-compose.api-tests.yaml")
docker compose @composeFiles up -d --build
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$ready = $false
for ($attempt = 1; $attempt -le 90; $attempt++) {
    try {
        $gateway = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/health" -TimeoutSec 3
        $wiremock = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:18089/__admin/mappings" -TimeoutSec 3
        $contractGateway = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:18080/actuator/health" -TimeoutSec 3
        $contractAuth = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:18083/actuator/health" -TimeoutSec 3
        if ($gateway.StatusCode -eq 200 -and $wiremock.StatusCode -eq 200 `
                -and $contractGateway.StatusCode -eq 200 -and $contractAuth.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 5
    }
}

if (-not $ready) {
    docker compose @composeFiles ps
    throw "API test stack did not become ready in time"
}

$runs = if ($env:API_TEST_RUNS) { [int]$env:API_TEST_RUNS } else { 1 }
for ($run = 1; $run -le $runs; $run++) {
    Write-Host "Running API suite ($run/$runs)"
    mvn -f services/pom.xml -Papi-tests -pl api-tests -am test "-DskipApiTests=false"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
