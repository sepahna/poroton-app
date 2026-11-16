# Build and Xray integration status

## Implemented on `feature/xray-profile-import`

- Persistent custom profile model and store, plus import parsing for VLESS, VMess, Trojan, Shadowsocks, SOCKS/SOCKS5 and HTTP share links.
- Plain-text and Base64 subscription import, duplicate detection, and refresh of saved subscription sources.
- A pinned AndroidLibXrayLite v26.11.9 AAR download in CI, with SHA-256 verification and checks for arm64-v8a, armeabi-v7a, x86_64 and x86 native libraries.
- `XrayCoreRuntime` initializes the gomobile library and wraps a single CoreController with synchronized start/stop operations.
- `XrayConfigBuilder` creates initial Xray JSON for the supported protocols, including TCP, WebSocket, gRPC, HTTP/2, HTTPUpgrade, XHTTP, TLS and REALITY options where represented by the share link.
- `CustomXrayVpnService` owns the Android TUN descriptor, creates IPv4 and IPv6 routes, starts/stops the Xray runtime, and posts a persistent disconnect notification.
- A profile manager reachable from the Proton home screen. It supports manual paste, Android text sharing, QR decoding from an image, import, deletion, subscription refresh and connect/disconnect actions.
- VPN consent is requested through the Android system permission flow.
- A guard prevents starting the custom tunnel while another VPN is active, because Android supports only one active VPN per user profile and silently replacing Proton's tunnel would leave Proton's UI state inconsistent.
- Unit tests cover initial VLESS/VMess config generation, Trojan TLS defaults, Shadowsocks outbound shape, and unsupported transport/REALITY validation.

## Build workflow

The workflow `.github/workflows/android-apk.yml` runs unit tests and assembles
`ProductionVanillaOpenSourceDebug`. It copies the APK to `dist/universal.apk`
only when Gradle produces exactly one APK. The workflow is PR-driven (plus
manual dispatch) to avoid running the same full build twice for both a branch
push and its pull-request event.

A successful CI build confirms compilation and packaging only. It does not prove
that a remote proxy can be reached or that packets are flowing through the TUN.

## Remaining validation and known limitations

- The latest CI run must complete successfully before an APK artifact can be claimed.
- No emulator or physical-device connection test has been completed yet. The
  VPN consent flow, IPv4/IPv6 routing, DNS behavior, Android revocation, app
  backgrounding and real VLESS/VMess/Trojan/SS endpoints still need device tests.
- The custom service is not integrated into Proton's existing
  `VpnConnectionManager`. The profile manager deliberately asks users to
  disconnect an existing VPN first; unified Proton/Xray connection-state
  management and seamless hand-off remain future work.
- QR import currently decodes a QR code from a selected image; it does not run a
  live camera scanner.
- The config builder implements a useful subset, not full v2rayNG parity.
  Advanced routing, custom DNS rules, per-app split tunneling, plugins,
  transport-specific edge cases and every legacy URI variant are not yet covered.
- Live ping/latency and traffic statistics are not wired into the UI.
- Automatic service recovery after process death and subscription scheduling
  are not implemented.

Do not describe this branch as a fully verified VPN client until the CI APK is
built and a real device successfully connects through each required protocol
with DNS and leak checks.
