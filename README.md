# pingtunnel-client (Android)

<p align="center">
  <a href="https://github.com/esrrhs/pingtunnel-client/actions/workflows/unit-test.yml">
    <img src="https://github.com/esrrhs/pingtunnel-client/actions/workflows/unit-test.yml/badge.svg" alt="Unit & E2E Tests">
  </a>
  <a href="https://github.com/esrrhs/pingtunnel-client/actions/workflows/emulator-test.yml">
    <img src="https://github.com/esrrhs/pingtunnel-client/actions/workflows/emulator-test.yml/badge.svg" alt="Emulator VPN E2E Tests">
  </a>
  <a href="https://github.com/esrrhs/pingtunnel-client/releases">
    <img src="https://img.shields.io/github/v/release/esrrhs/pingtunnel-client?include_prereleases&style=flat-square" alt="Latest Release">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square" alt="License: Apache 2.0">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen?style=flat-square&logo=android" alt="Platform: Android 8.0+">
  <img src="https://img.shields.io/badge/Pingtunnel%20Core-v3.1-orange?style=flat-square" alt="Pingtunnel Core: v3.1">
  <img src="https://img.shields.io/badge/Arch-arm64--v8a%20%7C%20x86__64-informational?style=flat-square" alt="Architectures: arm64-v8a | x86_64">
  <a href="https://github.com/esrrhs/pingtunnel-client/stargazers">
    <img src="https://img.shields.io/github/stars/esrrhs/pingtunnel-client?style=flat-square&logo=github" alt="GitHub Stars">
  </a>
</p>

An official Android client for **[pingtunnel](https://github.com/esrrhs/pingtunnel)** that encapsulates TCP and UDP traffic inside standard ICMP echo (ping) packets to break through restrictive firewalls and ISP throttles.

**Root-free**: Standard Android application permissions are sufficient to send and receive custom ICMP packets.

```
App / System Traffic
   │
   ▼
VpnService (TUN virtual network device, full packet capture)
   │
   ▼
hev-socks5-tunnel (JNI, tun2socks)
   │
   ▼ (Optional) RuleSocksServer router — Bypass LAN / CN Direct IPs / Direct Domains
   │
   ▼
pingtunnel daemon (libpingtunnel.so, local SOCKS5: CONNECT + UDP ASSOCIATE)
   │
   ▼
ICMP echo (Rootless ICMP datagram socket) ──▶ pingtunnel server ──▶ Destination / Internet
```

---

## How Root-Free ICMP Works

Android (Linux kernel 3.11+) allows unprivileged processes to create **ICMP datagram ping sockets** (`SOCK_DGRAM + IPPROTO_ICMP`). The embedded pingtunnel core replaces raw sockets (which require `CAP_NET_RAW` or root privileges) with `icmp.ListenPacket("udp4", ...)` on Android targets (`icmp_listen_android.go`).

It automatically accommodates the kernel's unprivileged ICMP socket behaviors:
1. Re-writing echo IDs in outgoing packets (the client skips explicit ID checks).
2. Supporting `*net.UDPAddr` address types natively.

Consequently, any regular Android application holding only standard `INTERNET` permission can exchange custom ICMP echo packets without root access.

---

## Features

- **Rootless Operation**: Tunnel all TCP/UDP traffic through ICMP without requiring root or custom ROMs.
- **Full VPN Capture**: Intercept and proxy system-wide IPv4 and IPv6 traffic via Android `VpnService` and high-performance `hev-socks5-tunnel`.
- **Per-App Proxy**: Fine-grained routing control — proxy all apps, only selected apps, or bypass selected apps.
- **Smart Split Tunneling**: Built-in bypass LAN, mainland China IP direct routing (APNIC chnroute tables), and direct domain lists with customizable routing rules.
- **Multi-Profile Management**: Shadowsocks-style power toggle, import/export via QR code, deep links, or JSON files, real-time RTT latency testing, and automatic failover.
- **Seamless Reconnection**: Exponential backoff reconnects, seamless tunnel re-establishment during network handoffs (Wi-Fi ↔ Cellular), and automatic pausing on trusted Wi-Fi networks.
- **System Integration**: Quick Settings Tile, boot-completed auto-start, and Always-On VPN guidance.
- **Real-Time Diagnostics & Analytics**: Live transfer speed notifications, per-session and accumulated traffic metrics, active connection inspect, and connection history.
- **Security & Integrity Checks**: Integrated kernel log viewer, tunnel self-check, IP/DNS leak testing, and remote egress IP geo-lookup.
- **Unified AEAD Encryption**: Powered by pingtunnel v3.1 single passphrase authentication (`-password`) with PBKDF2 key derivation and ChaCha20-Poly1305 (default) or AES-256 / AES-128 GCM encryption.

---

## Server Deployment

Run a [pingtunnel](https://github.com/esrrhs/pingtunnel) **v3.1+** server on your remote machine:

```bash
# Start server with default ChaCha20-Poly1305 encryption
sudo ./pingtunnel -type server -password your-strong-passphrase

# Or specify AES-256 GCM encryption
sudo ./pingtunnel -type server -password your-strong-passphrase -encrypt aes256
```

In the Android app, create a new profile, enter your server's IP address or domain name, and configure the identical passphrase and encryption algorithm.

---

## Parameter Mapping

App configuration fields map directly to pingtunnel client CLI arguments:

| App Field | CLI Argument | Default | Description |
|---|---|---|---|
| Server Address | `-s` | *Required* | Server IP address or domain name (periodically re-resolved) |
| Password | `-password` | *Required* | Shared secret for authentication and AEAD encryption (≥ 8 characters) |
| Cipher | `-encrypt` | `chacha20` | ChaCha20-Poly1305 (default), AES-256, or AES-128 |
| Idle Timeout | `-timeout` | `60` | Connection idle timeout in seconds |
| Max Connections | `-maxconn` | `0` | Maximum concurrent connections (`0` = unlimited) |
| Congestion Control | `-congestion` | `bb` | Bandwidth-adaptive flow control (`""` disables) |
| Route IPv6 | — | `true` | Whether to route IPv6 (`::/0`) through the VPN interface |

> ℹ️ **Note on v3.1+ Tuning**: Low-level TCP ARQ parameters (buffer sizes, sliding window, resend timeouts, and compression) are managed dynamically and automatically by the latest pingtunnel core.
>
> Fixed local parameters: `-type client -socks5 -l 127.0.0.1:<random-port> -nolog`.

---

## Building

### Prerequisites

- **JDK**: Java 17+
- **Android SDK & NDK**: NDK r25+ (NDK r29 recommended, API 26+)
- **Go**: 1.26+

### Build Steps

```bash
# 1. Build all native binaries (cross-compiles pingtunnel + ndk-build hev-socks5-tunnel)
bash scripts/build_native.sh
# Outputs:
#   app/src/main/jniLibs/<abi>/libpingtunnel.so       (Go executable disguised as .so)
#   app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so (tun2socks native bridge)

# 2. Build the Debug APK
./gradlew assembleDebug

# 3. Run JVM unit & E2E tests
./gradlew testDebugUnitTest
```

### Technical Notes

- **Native Execution**: `libpingtunnel.so` is a static Go executable packed into `jniLibs` to reside in `nativeLibraryDir`, where Android API 29+ still permits binary execution. `useLegacyPackaging = true` in `app/build.gradle.kts` is required to extract the `.so` to disk upon installation.
- **16 KB Page Size Compatibility**: Go binaries are linked with `-extldflags=-Wl,-z,max-page-size=16384` to ensure compatibility with Android 15+ 16 KB memory page alignment requirements.
- **System DNS Resolver**: `CGO_ENABLED=1` is enforced during cross-compilation so that pingtunnel links against Bionic libc to resolve hostnames via the Android system resolver (as Android lacks `/etc/resolv.conf`).

---

## Acknowledgements

- [esrrhs/pingtunnel](https://github.com/esrrhs/pingtunnel) — Core ICMP tunneling engine
- [itismoej](https://github.com/itismoej) — Android rootless ICMP datagram ping socket support (pingtunnel PR #79)
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — tun2socks data plane
- [felixonmars/dnsmasq-china-list](https://github.com/felixonmars/dnsmasq-china-list) — Direct domain list data source

---

## License

[Apache-2.0](LICENSE) © [esrrhs](https://github.com/esrrhs)
