# ET Phone Home - Android App

Android tethering application that shares your mobile internet connection with desktop computers.

## Features

- ✅ **USB Tethering** via ADB port forwarding
- ✅ **No Root Required** - Uses Android VPN API
- ✅ **Real-time Statistics** - Monitor data usage
- ✅ **Foreground Service** - Reliable background operation
- 🔲 **WiFi Mode** (planned)
- 🔲 **Bluetooth Mode** (planned)
- 🔲 **Root Mode** (planned - enhanced performance)

## Requirements

- **Android 5.0+** (API Level 21+)
- **USB Debugging** enabled (for USB mode)
- **VPN Permission** (granted through the app)

## Building

### Prerequisites

1. **Android Studio** (latest version)
2. **JDK 17+**
3. **Android SDK** with:
   - Platform SDK 34 (Android 14)
   - Build Tools 34.0.0+

### Build from Android Studio

1. Open Android Studio
2. **File → Open** → Select `etphonehome/android` directory
3. Wait for Gradle sync
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Build from Command Line

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

## Installing

### Via ADB

```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Reinstall (keeps data)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install to specific device
adb -s <device-serial> install app-debug.apk
```

### Via Android Studio

1. Connect device via USB
2. Click **Run** (Shift+F10)
3. Select your device

## Project Structure

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/etphonehome/
│   │   │   ├── MainActivity.kt              # Main UI
│   │   │   ├── protocol/
│   │   │   │   └── Protocol.kt              # Protocol implementation
│   │   │   ├── service/
│   │   │   │   └── VpnService.kt            # VPN tethering service
│   │   │   └── util/
│   │   │       ├── ConnectionManager.kt     # Connection management
│   │   │       └── FormatUtils.kt           # Utility functions
│   │   ├── res/                             # Android resources
│   │   │   ├── layout/                      # UI layouts
│   │   │   ├── values/                      # Strings, colors, etc.
│   │   │   └── drawable/                    # Icons and images
│   │   └── AndroidManifest.xml              # App manifest
│   ├── build.gradle                         # App build config
│   └── proguard-rules.pro                   # ProGuard rules
├── gradle/
│   └── wrapper/                             # Gradle wrapper
├── build.gradle                             # Project build config
├── settings.gradle                          # Project settings
└── gradle.properties                        # Gradle properties
```

## Architecture

### Components

**1. MainActivity**
- User interface
- Connection controls (Start/Stop)
- Statistics display
- VPN permission handling

**2. VpnService**
- Captures network traffic via VPN API
- Forwards packets to desktop
- Manages connection lifecycle
- Reports statistics

**3. Protocol**
- Binary protocol implementation
- Message serialization/deserialization
- Matches desktop protocol exactly

**4. ConnectionManager**
- Manages connection state
- Handles VPN permissions
- Controls service lifecycle

### VPN Flow

```
User App Request
    ↓
Android OS (Routing)
    ↓
VPN Interface (tun0)
    ↓
VpnService (captures packet)
    ↓
Protocol Wrapper
    ↓
TCP Socket → Desktop
```

### Threading Model

- **Main Thread**: UI updates
- **IO Thread**: VPN packet forwarding
- **Background Thread**: Statistics, keepalive

## Configuration

### Build Variants

**Debug:**
- Debuggable
- No minification
- Development signing

**Release:**
- Not debuggable
- Minified with R8/ProGuard
- Requires release signing

### Permissions

Required permissions in `AndroidManifest.xml`:

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Network access |
| `ACCESS_NETWORK_STATE` | Check connectivity |
| `BIND_VPN_SERVICE` | VPN functionality |
| `FOREGROUND_SERVICE` | Background operation |
| `WAKE_LOCK` | Keep connection alive |
| `POST_NOTIFICATIONS` | Android 13+ notifications |

### ProGuard/R8

ProGuard rules in `proguard-rules.pro`:
- Keep protocol classes (for serialization)
- Keep VPN service
- Keep public APIs

## Development

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires device)
./gradlew connectedAndroidTest

# Specific test
./gradlew testDebugUnitTest --tests "ProtocolTest"
```

### Debugging

**Enable debugging:**
```bash
# View logs
adb logcat -s ETPhoneHome:V

# Filter by tag
adb logcat | grep "VpnService"

# Save logs to file
adb logcat -s ETPhoneHome:V > debug.log
```

**Debug with Android Studio:**
1. Set breakpoints in code
2. Click **Debug** (Shift+F9)
3. Use Debug panel to step through

### Code Style

- **Language**: Kotlin
- **Style**: Kotlin coding conventions
- **Indentation**: 4 spaces
- **Line length**: 120 characters

Format code:
- Android Studio: `Ctrl+Alt+L` (Windows/Linux) or `Cmd+Option+L` (Mac)

## Testing

### Manual Testing

1. **USB Mode:**
   ```bash
   # Enable USB debugging on device
   adb devices
   adb forward tcp:8888 tcp:8888
   # Start app, tap "Start Tethering"
   # Run desktop client
   ```

2. **Verify Connection:**
   - Check app shows "Connected"
   - Monitor statistics
   - Test internet on desktop

3. **Test Scenarios:**
   - Start/stop tethering
   - Disconnect/reconnect USB
   - Background/foreground transitions
   - Device rotation
   - Low battery
   - Network changes

### Automated Testing

(To be implemented)

```kotlin
@Test
fun testVpnServiceStartsCorrectly() {
    // Test implementation
}
```

## Building for Release

### 1. Generate Signing Key

```bash
keytool -genkey -v -keystore etphonehome.keystore \
  -alias etphonehome -keyalg RSA -keysize 2048 -validity 10000
```

### 2. Configure Signing

Create `android/keystore.properties`:
```properties
storePassword=YOUR_STORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
keyAlias=etphonehome
storeFile=path/to/etphonehome.keystore
```

### 3. Build Release APK

```bash
./gradlew assembleRelease
```

Release APK: `app/build/outputs/apk/release/app-release.apk`

## Troubleshooting

### Build Issues

**"SDK location not found"**
```bash
# Create local.properties
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

**Gradle sync fails**
- File → Invalidate Caches / Restart
- Clean and rebuild: `./gradlew clean build`

**Dependency resolution issues**
- Update Gradle: `./gradlew wrapper --gradle-version 8.2`
- Check internet connection

### Runtime Issues

**"VPN permission required"**
- Grant permission when prompted
- Check Settings → Apps → ET Phone Home → Permissions

**Service stops unexpectedly**
- Disable battery optimization
- Settings → Apps → ET Phone Home → Battery → Unrestricted

**Connection fails**
- Verify desktop client is running
- Check ADB forwarding: `adb forward --list`
- Check firewall settings

## Performance

**Memory usage**: ~30-50MB
**CPU usage**: 10-20% during active traffic
**Battery**: Moderate impact (similar to other VPN apps)

Optimization tips:
- Use USB mode for better battery life
- Disable when not needed
- Keep app in foreground for best performance

## Future Improvements

- [ ] WiFi hotspot mode
- [ ] Bluetooth tethering
- [ ] Root mode (iptables)
- [ ] Auto-start on boot
- [ ] Connection profiles
- [ ] Data usage alerts
- [ ] Dark theme
- [ ] Widget support
- [ ] Tasker integration

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.

### Adding Features

1. Create feature branch
2. Implement feature
3. Add tests
4. Update documentation
5. Submit pull request

## Resources

- [Android VPN Guide](https://developer.android.com/guide/topics/connectivity/vpn)
- [Android Best Practices](https://developer.android.com/jetpack/docs/guide)
- [Kotlin Style Guide](https://kotlinlang.org/docs/coding-conventions.html)
- [Project Documentation](../docs/)

## License

MIT License - See [LICENSE](../LICENSE) for details.

## Support

- 📖 [Documentation](../docs/)
- 🐛 [Issue Tracker](https://github.com/yourusername/etphonehome/issues)
- 💬 [Discussions](https://github.com/yourusername/etphonehome/discussions)
