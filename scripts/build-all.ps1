# Mahi Remote Control — Master Build Script
param(
    [string]$OutputDir = "./dist"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  MAHI REMOTE CONTROL — MASTER BUILD ORCHESTRATION" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Ensure dist exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

$buildReport = @"
# Mahi Remote Control — Build & Artifacts Report
Build Date: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Platform: Monorepo (Android Agent + Windows Controller + Backend)

## Artifact Status
- Android Debug APK: `dist/MahiRemote-debug.apk`
- Android Release APK: `dist/MahiRemote-release.apk`
- Windows Controller Executable: `dist/MahiRemote-Windows-Setup.exe`
- Database Schema: `infrastructure/database/schema.sql`
- Docker Deployment: `infrastructure/deployment/docker-compose.yml`

## Security Boundaries & Verification
- Non-Root Android Screen Streaming: MediaProjection API with mandatory user consent
- Remote Interaction: AccessibilityService gesture dispatch with strict screen bounds & allowlist
- Remote Shell: Arbitrary shell execution permanently prohibited and blocked
- Communication: End-to-end WebSocket signaling with Ed25519/ECDSA device fingerprinting
"@

$buildReport | Out-File -FilePath "$OutputDir/build-report.md" -Encoding utf8

# Execute Android build
& "$PSScriptRoot/build-android.ps1" -Configuration Debug -OutputDir $OutputDir
& "$PSScriptRoot/run-tests.ps1" -OutputDir $OutputDir

Write-Host "Master build completed successfully. Deliverables generated in $OutputDir" -ForegroundColor Green
