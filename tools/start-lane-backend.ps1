$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Docker is not installed or is not on PATH." -ForegroundColor Red
    Write-Host "Install/start Docker Desktop, then run this script again."
    exit 1
}

try {
    docker info | Out-Null
} catch {
    Write-Host "Docker Desktop is installed but the Docker engine is not running." -ForegroundColor Red
    Write-Host "Start Docker Desktop, then run this script again."
    exit 1
}

Write-Host "Starting Lane GPS PostGIS + API..." -ForegroundColor Cyan
docker compose up -d --build
if ($LASTEXITCODE -ne 0) {
    throw "docker compose failed"
}

Write-Host "Checking API..." -ForegroundColor Cyan
$healthy = $false
for ($i = 0; $i -lt 20; $i++) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/health" -TimeoutSec 2
        if ($health.ok -eq $true) {
            $healthy = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $healthy) {
    Write-Host "Containers started, but the API health check did not answer yet." -ForegroundColor Yellow
    Write-Host "Run: docker compose logs api"
    exit 1
}

$lanIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.IPAddress -notmatch '^127\.' -and
        $_.IPAddress -notmatch '^169\.254\.' -and
        ($_.IPAddress -match '^10\.' -or $_.IPAddress -match '^192\.168\.' -or $_.IPAddress -match '^172\.(1[6-9]|2[0-9]|3[0-1])\.')
    } |
    Select-Object -ExpandProperty IPAddress -Unique

Write-Host "" 
Write-Host "Lane GPS backend is READY." -ForegroundColor Green
Write-Host "Local PC test: http://127.0.0.1:8080"

if ($lanIps) {
    Write-Host "" 
    Write-Host "On your phone, enter one of these under Lane API:" -ForegroundColor Green
    foreach ($ip in $lanIps) {
        Write-Host "  http://${ip}:8080"
    }
} else {
    Write-Host "Could not automatically find a private LAN IPv4 address." -ForegroundColor Yellow
    Write-Host "Run ipconfig and use your Wi-Fi IPv4 address with port 8080."
}

Write-Host "" 
Write-Host "The app can now request nearby OSM lanes automatically when a drive test is active."
