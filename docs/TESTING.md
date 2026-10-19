# Testing ET Phone Home

This guide covers how to test ET Phone Home components.

## Table of Contents

- [Unit Tests](#unit-tests)
- [Integration Tests](#integration-tests)
- [Manual Testing](#manual-testing)
- [Performance Testing](#performance-testing)
- [Troubleshooting Tests](#troubleshooting-tests)

## Unit Tests

### Rust Tests

Run all Rust unit tests:

```bash
cd desktop
cargo test
```

Run tests with output:

```bash
cargo test -- --nocapture
```

Run specific test:

```bash
cargo test test_handshake_request_roundtrip
```

Run tests in release mode:

```bash
cargo test --release
```

**Current Test Coverage:**
- ✅ Protocol serialization/deserialization
- ✅ Message roundtrip tests
- ✅ Invalid input handling
- ✅ IP address conversion
- ⏸️ TUN interface tests (requires root, ignored by default)

### Android Tests

Run Android unit tests:

```bash
cd android
./gradlew test
```

Run instrumented tests (requires connected device):

```bash
./gradlew connectedAndroidTest
```

## Integration Tests

### End-to-End Test

**Prerequisites:**
- Android device with USB debugging
- ADB installed
- Root/sudo access on desktop

**Steps:**

1. **Build everything:**
   ```bash
   # Android
   cd android && ./gradlew assembleDebug

   # Desktop
   cd desktop && cargo build --release
   ```

2. **Install Android app:**
   ```bash
   adb install android/app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Setup ADB forwarding:**
   ```bash
   adb forward tcp:8888 tcp:8888
   ```

4. **Start Android app:**
   - Open "ET Phone Home" on device
   - Tap "Start Tethering" (status shows "Waiting for desktop")

5. **Run desktop client:**
   ```bash
   sudo desktop/target/release/etphonehome usb
   ```

6. **Verify connection:**
   ```bash
   # Test internet connectivity
   ping -c 4 8.8.8.8
   curl http://example.com
   ```

### Protocol Test

Test protocol communication separately:

```bash
# Start Android app first, then:
cd desktop
cargo run --example protocol_test
```

(Note: protocol_test example needs to be created)

## Manual Testing

### Connection Tests

**USB Connection:**
```bash
# Test ADB connection
adb devices

# Test port forwarding
adb forward --list

# Test connection
sudo etphonehome usb --port 8888
```

**WiFi Connection (when implemented):**
```bash
# Find Android IP
adb shell ip addr show wlan0

# Test connection
sudo etphonehome wifi --host 192.168.1.100 --port 8888
```

### Stress Tests

**High Traffic Test:**
```bash
# While connected, run:
# Download large file
wget http://speedtest.tele2.net/10MB.zip

# Upload test
curl -F "file=@largefile.bin" http://httpbin.org/post

# Continuous ping
ping -i 0.2 8.8.8.8
```

**Long Duration Test:**
```bash
# Leave running overnight
sudo etphonehome usb 2>&1 | tee etphonehome.log
```

**Reconnection Test:**
1. Start connection
2. Unplug/replug USB cable
3. Restart Android app
4. Verify auto-reconnect (future feature)

### Statistics Verification

Monitor connection statistics:

```bash
# Watch Android app UI for:
- Bytes sent/received
- Connection duration
- Error count

# Check desktop logs for:
grep "Stats:" etphonehome.log
```

## Performance Testing

### Throughput Test

**Download Speed:**
```bash
# While connected:
time wget -O /dev/null http://speedtest.tele2.net/100MB.zip
```

**Upload Speed:**
```bash
# While connected:
dd if=/dev/zero bs=1M count=10 | curl -F "file=@-" http://httpbin.org/post
```

### Latency Test

```bash
# Test ping times
ping -c 100 8.8.8.8 > ping_results.txt

# Analyze results
awk '/time=/ {sum+=$7; n++} END {print "Avg:", sum/n, "ms"}' ping_results.txt
```

### CPU/Memory Usage

**Linux:**
```bash
# Monitor during operation
top -p $(pgrep etphonehome)

# Or use htop
htop
```

**Android:**
```bash
# Monitor app resources
adb shell dumpsys meminfo solutions.errant.etph
adb shell top -n 1 | grep etphonehome
```

## Automated Test Scripts

### Quick Test Script

Create `test-connection.sh`:

```bash
#!/bin/bash

echo "Testing ET Phone Home connection..."

# Test DNS
if ! nslookup google.com >/dev/null 2>&1; then
    echo "❌ DNS resolution failed"
    exit 1
fi
echo "✓ DNS working"

# Test ping
if ! ping -c 3 8.8.8.8 >/dev/null 2>&1; then
    echo "❌ Ping failed"
    exit 1
fi
echo "✓ Ping working"

# Test HTTP
if ! curl -s http://example.com >/dev/null; then
    echo "❌ HTTP failed"
    exit 1
fi
echo "✓ HTTP working"

# Test HTTPS
if ! curl -s https://google.com >/dev/null; then
    echo "❌ HTTPS failed"
    exit 1
fi
echo "✓ HTTPS working"

echo "✅ All tests passed!"
```

Run with:
```bash
chmod +x test-connection.sh
./test-connection.sh
```

## Troubleshooting Tests

### Common Test Failures

**"Failed to create TUN interface"**
```bash
# Load TUN module
sudo modprobe tun

# Check permissions
ls -l /dev/net/tun
```

**"Connection refused"**
```bash
# Verify Android app is running
adb shell ps | grep etphonehome

# Verify ADB forwarding
adb forward --list

# Check firewall
sudo iptables -L
```

**App stays on "Waiting for desktop"**
- USB: check `adb forward --list`
- WiFi: check the token, and that the client uses TLS (default) or the app's "Require encryption" is off

### Test Data Collection

Collect diagnostic data:

```bash
# Desktop logs
RUST_LOG=debug sudo etphonehome usb 2>&1 | tee debug.log

# Android logs
adb logcat -s ETPhoneHome:V > android.log

# System info
uname -a > sysinfo.txt
lsmod | grep tun >> sysinfo.txt
ip addr show >> sysinfo.txt
```

## Performance Benchmarks

Expected performance metrics:

| Metric | USB Mode | WiFi Mode | Notes |
|--------|----------|-----------|-------|
| Throughput | 80-95% of mobile speed | 70-85% of mobile speed | Varies by device |
| Latency | +5-15ms | +10-25ms | Added to base latency |
| CPU (Desktop) | 5-15% | 5-15% | Single core |
| CPU (Android) | 10-20% | 10-20% | Depends on traffic |
| Memory (Desktop) | ~10MB | ~10MB | RSS |
| Memory (Android) | ~30MB | ~30MB | Includes UI |

## Continuous Testing

### CI/CD Tests

Our GitHub Actions run:
- `cargo test` on every commit
- `cargo clippy` for linting
- `./gradlew test` for Android

### Pre-commit Tests

Before committing, run:

```bash
# Rust
cd desktop
cargo fmt
cargo clippy
cargo test

# Android
cd android
./gradlew test
```

## Test Coverage

Current coverage status:

**Rust:**
- Protocol: ~80% (serialization/deserialization)
- Client: ~40% (connection tests need mocking)
- TUN: ~20% (requires root, mostly integration tests)

**Android:**
- Protocol: 0% (TODO)
- VPN Service: 0% (TODO: add unit tests)
- UI: 0% (TODO: add instrumented tests)

## Adding New Tests

### Rust Test Template

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_feature_name() {
        // Arrange
        let input = create_test_input();

        // Act
        let result = function_under_test(input);

        // Assert
        assert_eq!(result, expected_value);
    }
}
```

### Android Test Template

```kotlin
@Test
fun testFeatureName() {
    // Arrange
    val input = createTestInput()

    // Act
    val result = functionUnderTest(input)

    // Assert
    assertEquals(expectedValue, result)
}
```

## Next Steps

- [ ] Add protocol mocking for client tests
- [ ] Add Android instrumented tests
- [ ] Create integration test suite
- [ ] Add performance regression tests
- [ ] Set up test coverage reporting
- [ ] Add fuzzing tests for protocol

## Resources

- [Rust Testing Book](https://doc.rust-lang.org/book/ch11-00-testing.html)
- [Android Testing Guide](https://developer.android.com/training/testing)
- [Protocol Specification](../protocol/PROTOCOL.md)
