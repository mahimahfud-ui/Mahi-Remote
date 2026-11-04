<#
.SYNOPSIS
    Starts the Mahi Remote Control backend with public IP binding and automatic public IPv4 discovery.

.DESCRIPTION
    Launches the Node.js signaling and Web Browser Controller server on 0.0.0.0.
    Queries the host's public IP address via public echo services and prints the exact
    browser controller and Android Agent connection URLs. No domain purchase or DNS required.

.PARAMETER Port
    The TCP port to bind the HTTP and WebSocket server. Defaults to 8080.

.EXAMPLE
    .\scripts\start-public-access.ps1 -Port 8080
#>

param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "    MAHI REMOTE CONTROL - PUBLIC IP DEPLOYMENT LAUNCHER   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check Node.js runtime
try {
    $nodeVersion = node -v
    Write-Host "[OK] Node.js detected: $nodeVersion" -ForegroundColor Green
} catch {
    Write-Error "Node.js is not found in PATH. Please install Node.js 18+ (https://nodejs.org)."
    exit 1
}

# 2. Check Backend build status
$backendDir = Join-Path $PSScriptRoot "..\backend"
if (-not (Test-Path $backendDir)) {
    Write-Error "Backend directory not found at $backendDir"
    exit 1
}

Push-Location $backendDir

if (-not (Test-Path "node_modules")) {
    Write-Host "[*] Installing backend dependencies..." -ForegroundColor Yellow
    npm install
}

if (-not (Test-Path "dist\index.js")) {
    Write-Host "[*] Compiling TypeScript backend..." -ForegroundColor Yellow
    npm run build
}

# 3. Detect Public and Local IP Addresses
Write-Host "[*] Discovering network addresses..." -ForegroundColor Yellow

$publicIp = "UNKNOWN"
$ipProviders = @(
    "https://api.ipify.org",
    "https://icanhazip.com",
    "https://ifconfig.me/ip"
)

foreach ($provider in $ipProviders) {
    try {
        $response = Invoke-RestMethod -Uri $provider -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($response -and $response.Trim() -match '^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$') {
            $publicIp = $response.Trim()
            break
        }
    } catch {
        # Try next provider
    }
}

$localIp = "127.0.0.1"
try {
    $localAddresses = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi*", "Ethernet*" -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "169.254*" -and $_.IPAddress -notlike "127.*" }
    if ($localAddresses) {
        $localIp = $localAddresses[0].IPAddress
    }
} catch {
    # Fallback to localhost
}

# 4. Display Access Details
Write-Host ""
Write-Host "----------------------------------------------------------" -ForegroundColor DarkCyan
Write-Host " [ACCESS URLS] - Connect with any modern web browser:" -ForegroundColor White
Write-Host "   Local Machine : http://localhost:$Port" -ForegroundColor Cyan
Write-Host "   LAN / Wi-Fi   : http://${localIp}:$Port" -ForegroundColor Cyan
if ($publicIp -ne "UNKNOWN") {
    Write-Host "   Public WAN IP : http://${publicIp}:$Port" -ForegroundColor Green
} else {
    Write-Host "   Public WAN IP : [Could not auto-detect public IPv4]" -ForegroundColor Yellow
}
Write-Host ""
Write-Host " [ANDROID AGENT CONFIGURATION]:" -ForegroundColor White
if ($publicIp -ne "UNKNOWN") {
    Write-Host "   Server URL    : http://${publicIp}:$Port" -ForegroundColor Yellow
    Write-Host "   WebSocket URL : ws://${publicIp}:$Port/ws" -ForegroundColor Yellow
} else {
    Write-Host "   Server URL    : http://${localIp}:$Port" -ForegroundColor Yellow
    Write-Host "   WebSocket URL : ws://${localIp}:$Port/ws" -ForegroundColor Yellow
}
Write-Host "----------------------------------------------------------" -ForegroundColor DarkCyan
Write-Host ""
Write-Host "[!] FIREWALL REMINDER: Ensure TCP port $Port is allowed on your OS and router/VPS firewall." -ForegroundColor Magenta
Write-Host "[!] Press Ctrl+C in this console to terminate the server." -ForegroundColor Gray
Write-Host ""

# 5. Start Backend Server
$env:PORT = $Port.ToString()
$env:HOST = "0.0.0.0"

try {
    node dist/index.js
} finally {
    Pop-Location
}
