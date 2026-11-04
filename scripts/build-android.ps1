# Mahi Remote Control — Android Build Script
param(
    [string]$Configuration = "Debug",
    [string]$OutputDir = "./dist"
)

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Mahi Remote: Android Agent Build Engine" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Environment and SDK validation
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Warning "ANDROID_HOME / ANDROID_SDK_ROOT not explicitly defined. Using system default Gradle environment."
}

# 2. Ensure dist directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

# 3. Compile Android Application
Write-Host "Compiling Android app (:app:assemble$Configuration)..." -ForegroundColor Yellow
if ($Configuration -eq "Release") {
    gradle :app:assembleRelease
    if ($LASTEXITCODE -eq 0) {
        $sourceApk = "app/build/outputs/apk/release/app-release-unsigned.apk"
        if (-not (Test-Path $sourceApk)) {
            $sourceApk = "app/build/outputs/apk/release/app-release.apk"
        }
        Copy-Item -Path $sourceApk -Destination "$OutputDir/MahiRemote-release.apk" -Force
        Write-Host "Android Release APK copied to $OutputDir/MahiRemote-release.apk" -ForegroundColor Green
    } else {
        Write-Error "Android Release compilation failed."
        exit 1
    }
} else {
    gradle :app:assembleDebug
    if ($LASTEXITCODE -eq 0) {
        Copy-Item -Path "app/build/outputs/apk/debug/app-debug.apk" -Destination "$OutputDir/MahiRemote-debug.apk" -Force
        Write-Host "Android Debug APK copied to $OutputDir/MahiRemote-debug.apk" -ForegroundColor Green
    } else {
        Write-Error "Android Debug compilation failed."
        exit 1
    }
}

Write-Host "Android Agent build finished successfully!" -ForegroundColor Green
