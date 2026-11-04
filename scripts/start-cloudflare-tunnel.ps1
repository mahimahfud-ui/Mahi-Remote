<#
.SYNOPSIS
    Starts Mahi Remote Control with an automatic zero-config Cloudflare Quick Tunnel.

.DESCRIPTION
    Launches the Node.js backend locally and runs 'cloudflared tunnel --url http://localhost:8080'.
    Extracts and displays the temporary https://*.trycloudflare.com URL.
    Enables remote browser access and WebSocket signaling over HTTPS/WSS without domain purchase
    or router port forwarding.

.PARAMETER Port
    Local TCP port for the backend server. Defaults to 8080.

.EXAMPLE
    .\scripts\start-cloudflare-tunnel.ps1 -Port 8080
#>

param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   MAHI REMOTE CONTROL - CLOUDFLARE QUICK TUNNEL LAUNCHER " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check cloudflared CLI presence
$cloudflaredCmd = Get-Command "cloudflared" -ErrorAction SilentlyContinue
if (-not $cloudflaredCmd) {
    Write-Host "[!] 'cloudflared' was not found in your PATH." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "To install cloudflared on Windows:" -ForegroundColor White
    Write-Host "  Using Winget:  winget install --id Cloudflare.cloudflared" -ForegroundColor Green
    Write-Host "  Or download:   https://github.com/cloudflare/cloudflared/releases/latest" -ForegroundColor Green
    Write-Host "  On Linux:      sudo apt-get install cloudflared  (or curl binary)" -ForegroundColor Green
    Write-Host ""
    Write-Error "Please install cloudflared and rerun this script."
    exit 1
}

Write-Host "[OK] Detected cloudflared at: $($cloudflaredCmd.Source)" -ForegroundColor Green

# 2. Check Node.js and build backend
$backendDir = Join-Path $PSScriptRoot "..\backend"
Push-Location $backendDir

if (-not (Test-Path "node_modules")) {
    Write-Host "[*] Installing backend dependencies..." -ForegroundColor Yellow
    npm install
}

if (-not (Test-Path "dist\index.js")) {
    Write-Host "[*] Building backend..." -ForegroundColor Yellow
    npm run build
}

# 3. Start local Node backend
Write-Host "[*] Starting local backend server on 127.0.0.1:$Port..." -ForegroundColor Yellow

$env:PORT = $Port.ToString()
$env:HOST = "127.0.0.1"

$nodeProcess = Start-Process -FilePath "node" -ArgumentList "dist/index.js" -WorkingDirectory $backendDir -PassThru -NoNewWindow
Start-Sleep -Seconds 2

if ($nodeProcess.HasExited) {
    Write-Error "Backend server failed to start or exited immediately. Check error logs."
    Pop-Location
    exit 1
}

Write-Host "[OK] Backend server is running (PID: $($nodeProcess.Id))" -ForegroundColor Green

# 4. Launch Cloudflare Tunnel and monitor output for public URL
Write-Host "[*] Requesting temporary Cloudflare Tunnel URL..." -ForegroundColor Yellow

$tunnelLog = [System.IO.Path]::GetTempFileName()
$tunnelProcess = Start-Process -FilePath "cloudflared" `
    -ArgumentList "tunnel --url http://localhost:$Port --no-autoupdate" `
    -RedirectStandardError $tunnelLog `
    -PassThru -NoNewWindow

$tunnelUrl = $null
$timeout = 25
$elapsed = 0

Write-Host "[*] Waiting for tunnel connection..." -NoNewline
while ($elapsed -lt $timeout -and (-not $tunnelUrl)) {
    Start-Sleep -Seconds 1
    Write-Host "." -NoNewline
    $elapsed++

    if (Test-Path $tunnelLog) {
        $content = Get-Content $tunnelLog -Raw -ErrorAction SilentlyContinue
        if ($content -match '(https://[a-zA-Z0-9-]+\.trycloudflare\.com)') {
            $tunnelUrl = $matches[1]
            break
        }
    }
}
Write-Host ""

if ($tunnelUrl) {
    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  TUNNEL ACTIVE! NO DOMAIN REQUIRED." -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host " [BROWSER CONTROLLER URL]:" -ForegroundColor White
    Write-Host "   $tunnelUrl" -ForegroundColor Cyan
    Write-Host ""
    Write-Host " [ANDROID AGENT SERVER URL]:" -ForegroundColor White
    Write-Host "   $tunnelUrl" -ForegroundColor Yellow
    Write-Host ""
    Write-Host " [ANDROID AGENT WEBSOCKET URL]:" -ForegroundColor White
    $wsUrl = $tunnelUrl.Replace("https://", "wss://") + "/ws"
    Write-Host "   $wsUrl" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "----------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host " Note: The tunnel URL will remain active until you stop this script." -ForegroundColor Gray
    Write-Host " Press Ctrl+C to terminate the tunnel and local backend." -ForegroundColor Gray
    Write-Host "----------------------------------------------------------" -ForegroundColor DarkCyan
} else {
    Write-Host "[!] Could not automatically capture the tunnel URL from logs." -ForegroundColor Yellow
    Write-Host "Check output below or examine log file at: $tunnelLog" -ForegroundColor Yellow
    if (Test-Path $tunnelLog) {
        Get-Content $tunnelLog -Tail 15
    }
}

try {
    # Keep running until user terminates
    while ((-not $tunnelProcess.HasExited) -and (-not $nodeProcess.HasExited)) {
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Host "`n[*] Shutting down tunnel and backend..." -ForegroundColor Yellow
    if (-not $tunnelProcess.HasExited) {
        Stop-Process -Id $tunnelProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if (-not $nodeProcess.HasExited) {
        Stop-Process -Id $nodeProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $tunnelLog) {
        Remove-Item $tunnelLog -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
    Write-Host "[OK] Clean shutdown complete." -ForegroundColor Green
}
