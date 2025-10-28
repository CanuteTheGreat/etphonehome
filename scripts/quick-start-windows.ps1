# ET Phone Home - Quick Start Script for Windows
# Run this script as Administrator

param()

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "ET Phone Home - Quick Start" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

# Check if running as Administrator
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
$isAdmin = $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "ERROR: This script must be run as Administrator" -ForegroundColor Red
    Write-Host "Please right-click PowerShell and select 'Run as Administrator'" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Get script directory
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

# Check for dependencies
Write-Host "Checking dependencies..." -ForegroundColor Yellow

# Check for Rust
try {
    $null = Get-Command cargo -ErrorAction Stop
    Write-Host "✓ Rust found" -ForegroundColor Green
} catch {
    Write-Host "✗ Rust not found" -ForegroundColor Red
    Write-Host "Please install Rust from: https://rustup.rs/" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Check for ADB
try {
    $null = Get-Command adb -ErrorAction Stop
    Write-Host "✓ ADB found" -ForegroundColor Green
} catch {
    Write-Host "✗ ADB not found" -ForegroundColor Red
    Write-Host "Please install Android Platform Tools:" -ForegroundColor Yellow
    Write-Host "  https://developer.android.com/studio/releases/platform-tools" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Check for TAP-Windows adapter
$tapAdapters = Get-NetAdapter | Where-Object { $_.DriverDescription -like "*TAP-Windows*" }
if ($tapAdapters) {
    Write-Host "✓ TAP-Windows adapter found" -ForegroundColor Green
} else {
    Write-Host "⚠ TAP-Windows adapter not found" -ForegroundColor Yellow
    Write-Host "  Install OpenVPN to get TAP-Windows adapter" -ForegroundColor Yellow
    Write-Host "  or install TAP-Windows separately" -ForegroundColor Yellow
}

# Build the desktop client if not already built
$BinaryPath = Join-Path $ProjectRoot "desktop\target\release\etphonehome.exe"

if (-not (Test-Path $BinaryPath)) {
    Write-Host ""
    Write-Host "Building desktop client..." -ForegroundColor Yellow
    Push-Location (Join-Path $ProjectRoot "desktop")
    cargo build --release -p etphonehome-windows
    Pop-Location
    if (Test-Path $BinaryPath) {
        Write-Host "✓ Build complete" -ForegroundColor Green
    } else {
        Write-Host "✗ Build failed" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
} else {
    Write-Host "✓ Desktop client already built" -ForegroundColor Green
}

# Check for connected Android device
Write-Host ""
Write-Host "Checking for Android device..." -ForegroundColor Yellow

$adbDevices = adb devices | Select-String "device$"

if (-not $adbDevices) {
    Write-Host ""
    Write-Host "No Android device detected!" -ForegroundColor Red
    Write-Host "Please:" -ForegroundColor Yellow
    Write-Host "  1. Connect your Android device via USB"
    Write-Host "  2. Enable USB debugging in Developer Options"
    Write-Host "  3. Accept the USB debugging prompt on your device"
    Write-Host "  4. Run this script again"
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "✓ Android device connected" -ForegroundColor Green

# Set up ADB reverse forwarding
Write-Host ""
Write-Host "Setting up ADB port forwarding..." -ForegroundColor Yellow
adb reverse tcp:8888 tcp:8888
if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ Port forwarding configured" -ForegroundColor Green
} else {
    Write-Host "✗ Failed to set up port forwarding" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Check if Android app is installed
Write-Host ""
Write-Host "Checking if Android app is installed..." -ForegroundColor Yellow

$appInstalled = adb shell pm list packages | Select-String "com.etphonehome"

if ($appInstalled) {
    Write-Host "✓ Android app is installed" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "Android app not found!" -ForegroundColor Red
    $ApkPath = Join-Path $ProjectRoot "android\app\build\outputs\apk\debug\app-debug.apk"

    if (Test-Path $ApkPath) {
        Write-Host "Installing Android app..." -ForegroundColor Yellow
        adb install -r $ApkPath
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✓ App installed" -ForegroundColor Green
        } else {
            Write-Host "✗ Installation failed" -ForegroundColor Red
            Read-Host "Press Enter to exit"
            exit 1
        }
    } else {
        Write-Host "Please build and install the Android app first:" -ForegroundColor Yellow
        Write-Host "  cd $ProjectRoot\android"
        Write-Host "  .\gradlew.bat assembleDebug"
        Write-Host "  adb install app\build\outputs\apk\debug\app-debug.apk"
        Read-Host "Press Enter to exit"
        exit 1
    }
}

# Instructions for user
Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Setup complete! Follow these steps:" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. On your Android device:" -ForegroundColor Yellow
Write-Host "   - Open the 'ET Phone Home' app"
Write-Host "   - Tap 'Start Tethering'"
Write-Host "   - Grant VPN permission when prompted"
Write-Host ""
Write-Host "2. Once the app shows 'Connected', press ENTER here to continue..." -ForegroundColor Yellow
Read-Host

# Start the desktop client
Write-Host ""
Write-Host "Starting tethering client..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop" -ForegroundColor Yellow
Write-Host ""

& $BinaryPath usb --port 8888 --interface etph0

# Cleanup on exit
Write-Host ""
Write-Host "Cleaning up..." -ForegroundColor Yellow
adb reverse --remove tcp:8888
Write-Host "Done!" -ForegroundColor Green
