# Mahi Remote Control — Cloud Backend Deployment Script
param(
    [string]$ComposeFile = "infrastructure/deployment/docker-compose.yml"
)

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Mahi Remote: Cloud Backend Deployment" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Warning "Docker CLI not detected in local path. Ensure Docker & Compose are installed on target host."
    Write-Host "Manual steps to deploy:" -ForegroundColor Yellow
    Write-Host "1. cd infrastructure/deployment"
    Write-Host "2. docker compose up -d"
    exit 0
}

Write-Host "Deploying stack via Docker Compose: $ComposeFile..." -ForegroundColor Yellow
docker compose -f $ComposeFile pull
docker compose -f $ComposeFile up -d --build

Write-Host "Deployment completed! Services running:" -ForegroundColor Green
docker compose -f $ComposeFile ps
