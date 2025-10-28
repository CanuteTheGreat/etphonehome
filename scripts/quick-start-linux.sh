#!/bin/bash

# ET Phone Home - Quick Start Script for Linux
# This script helps you quickly set up and connect via USB tethering

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "========================================="
echo "ET Phone Home - Quick Start"
echo "========================================="
echo ""

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo "This script must be run as root (for TUN interface creation)"
   echo "Please run: sudo $0"
   exit 1
fi

# Check for dependencies
echo "Checking dependencies..."

# Check for Rust
if ! command -v cargo &> /dev/null; then
    echo "Error: Rust/Cargo not found"
    echo "Install Rust from: https://rustup.rs/"
    exit 1
fi
echo "✓ Rust found"

# Check for ADB
if ! command -v adb &> /dev/null; then
    echo "Error: ADB not found"
    echo "Install ADB:"
    echo "  Ubuntu/Debian: sudo apt-get install android-tools-adb"
    echo "  Fedora: sudo dnf install android-tools"
    exit 1
fi
echo "✓ ADB found"

# Check for TUN module
if ! lsmod | grep -q "^tun"; then
    echo "Loading TUN module..."
    modprobe tun
fi
echo "✓ TUN module loaded"

# Build the desktop client if not already built
BINARY_PATH="$PROJECT_ROOT/desktop/target/release/etphonehome"

if [ ! -f "$BINARY_PATH" ]; then
    echo ""
    echo "Building desktop client..."
    cd "$PROJECT_ROOT/desktop"
    cargo build --release -p etphonehome-linux
    echo "✓ Build complete"
else
    echo "✓ Desktop client already built"
fi

# Check for connected Android device
echo ""
echo "Checking for Android device..."
adb devices -l

DEVICE_COUNT=$(adb devices | grep -c "device$" || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo ""
    echo "No Android device detected!"
    echo "Please:"
    echo "  1. Connect your Android device via USB"
    echo "  2. Enable USB debugging in Developer Options"
    echo "  3. Accept the USB debugging prompt on your device"
    echo "  4. Run this script again"
    exit 1
fi

echo "✓ Android device connected"

# Set up ADB reverse forwarding
echo ""
echo "Setting up ADB port forwarding..."
adb reverse tcp:8888 tcp:8888
echo "✓ Port forwarding configured"

# Check if Android app is installed
echo ""
echo "Checking if Android app is installed..."
if adb shell pm list packages | grep -q "com.etphonehome"; then
    echo "✓ Android app is installed"
else
    echo ""
    echo "Android app not found!"

    APK_PATH="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

    if [ -f "$APK_PATH" ]; then
        echo "Installing Android app..."
        adb install -r "$APK_PATH"
        echo "✓ App installed"
    else
        echo "Please build and install the Android app first:"
        echo "  cd $PROJECT_ROOT/android"
        echo "  ./gradlew assembleDebug"
        echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
        exit 1
    fi
fi

# Instructions for user
echo ""
echo "========================================="
echo "Setup complete! Follow these steps:"
echo "========================================="
echo ""
echo "1. On your Android device:"
echo "   - Open the 'ET Phone Home' app"
echo "   - Tap 'Start Tethering'"
echo "   - Grant VPN permission when prompted"
echo ""
echo "2. Once the app shows 'Connected', press ENTER here to continue..."
read -r

# Start the desktop client
echo ""
echo "Starting tethering client..."
echo "Press Ctrl+C to stop"
echo ""

"$BINARY_PATH" usb --port 8888 --interface etph0

# Cleanup on exit
echo ""
echo "Cleaning up..."
adb reverse --remove tcp:8888
echo "Done!"
