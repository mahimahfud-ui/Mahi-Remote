# Mahi Remote Control — Windows Controller Build Script
param(
    [string]$Configuration = "Release",
    [string]$OutputDir = "./dist"
)

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Mahi Remote: Windows Controller Build" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Validate .NET SDK
$dotnetVersion = dotnet --version
if ($LASTEXITCODE -ne 0) {
    Write-Error ".NET SDK is required to build Windows Controller. Please install .NET 10 or later."
    exit 1
}
Write-Host "Detected .NET SDK: $dotnetVersion" -ForegroundColor Gray

# 2. Ensure dist directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

# 3. Restore and Publish
Write-Host "Restoring NuGet dependencies for MahiRemote.sln..." -ForegroundColor Yellow
dotnet restore windows-controller/MahiRemote.sln

Write-Host "Building and publishing MahiRemote.App..." -ForegroundColor Yellow
dotnet publish windows-controller/MahiRemote.App/MahiRemote.App.csproj `
    -c $Configuration `
    -r win-x64 `
    --self-contained true `
    -p:PublishSingleFile=true `
    -o "$OutputDir/windows"

if ($LASTEXITCODE -eq 0) {
    Copy-Item "$OutputDir/windows/MahiRemote.App.exe" "$OutputDir/MahiRemote-Windows-Setup.exe" -Force
    Write-Host "Windows Controller standalone binary created at $OutputDir/MahiRemote-Windows-Setup.exe" -ForegroundColor Green
} else {
    Write-Error "Windows controller build failed."
    exit 1
}
