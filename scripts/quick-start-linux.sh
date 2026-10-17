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

# Check for ADB
if ! command -v adb &> /dev/null; then
    echo "Error: ADB not found"
    echo "Install ADB:"
    echo "  Ubuntu/Debian: sudo apt-get install android-tools-adb"
    echo "  Fedora: sudo dnf install android-tools"
    exit 1
fi
echo "✓ ADB found"

# Check for TUN support. Many kernels build tun in (so it never shows up in
# lsmod); the device node is what matters. Only try modprobe if it's missing.
if [ ! -c /dev/net/tun ]; then
    echo "Loading TUN module..."
    modprobe tun || true
fi
if [ ! -c /dev/net/tun ]; then
    echo "Error: /dev/net/tun is not available (sudo modprobe tun)"
    exit 1
fi
echo "✓ TUN device available"

# Build the desktop client if not already built
BINARY_PATH="$PROJECT_ROOT/desktop/target/release/etphonehome"

if [ ! -f "$BINARY_PATH" ]; then
    # Rust is only needed when the client hasn't been built yet.
    if ! command -v cargo &> /dev/null; then
        echo "Error: $BINARY_PATH not found and Rust/Cargo is not installed"
        echo "Install Rust from: https://rustup.rs/ (or copy a release build there)"
        exit 1
    fi
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

# Forward this computer's localhost:8888 to the app's server on the phone
echo ""
echo "Setting up ADB port forwarding..."
adb forward tcp:8888 tcp:8888
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
adb forward --remove tcp:8888
echo "Done!"
