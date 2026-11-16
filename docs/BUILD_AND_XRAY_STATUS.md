# Build and validation status

## Android CI

The `.github/workflows/android-apk.yml` workflow runs the custom importer unit
tests and assembles the existing Proton Android debug variant
`ProductionVanillaOpenSourceDebug`. It exports `universal.apk` only when Gradle
produces exactly one APK output. This prevents accidentally renaming one ABI split
as a universal package.

## Important scope boundary

A successful build from this workflow validates the current Proton app and the
profile import/storage foundation. It does **not** mean custom Xray profiles can
connect. The current app still routes connection requests through Proton's
`VpnConnectionManager`; the custom profile importer is not wired into that
connection lifecycle.

## Pinned Xray runtime input

The CI workflow downloads the upstream `AndroidLibXrayLite` v26.11.9 AAR, whose
source commit is the exact `AndroidLibXrayLite` gitlink revision used by the
upstream v2rayNG checkout at the time this integration was pinned
(`b5a377087e74eaa34e86718710b04c50f4b767f5`). The downloaded artifact is
SHA-256 checked and CI verifies `libgojni.so` exists for arm64-v8a, armeabi-v7a,
x86_64, and x86 before Gradle runs. The app already consumes local AAR files
from `app/libs`, so this makes the native runtime available to the APK build.

The app now has an injectable `XrayCoreRuntime` adapter that initializes the
pinned gomobile library, creates one `CoreController`, and exposes synchronized
start/stop operations. Its start method accepts a complete Xray JSON document
and an already-owned TUN file descriptor. It deliberately does not create a TUN
or start an Android service by itself.

This is still **not a connected VPN implementation**: imported share links are
not yet converted into complete Xray JSON, no custom-profile Android VPN service
owns and supplies the TUN descriptor, and the existing Proton connection UI
does not yet select or invoke this runtime. CI compilation is required to verify
the Kotlin adapter against the pinned AAR.

## Xray runtime integration requirements

The upstream v2rayNG project builds its native Xray runtime from the pinned
`AndroidLibXrayLite` source and packages the generated AAR plus native
dependencies. The native source revision must match the upstream gitlink, and
every declared ABI must be verified. Do not add a guessed Maven coordinate or
copy an arbitrary prebuilt AAR.

The remaining integration must provide:
- a pinned and reproducible Xray AAR build (including gomobile and native ABI checks);
- a single, app-owned Android VPN service lifecycle (no competing Proton and Xray
  tunnels);
- a tested converter from every supported imported share link to complete Xray JSON;\n- a custom-profile Android VPN service that owns the TUN descriptor and delegates to `XrayCoreRuntime`;
- foreground notification, permission flow, stop/restart, and process-death recovery;
- UI navigation for link, clipboard, QR, and subscription import and profile selection;
- subscription refresh with HTTPS-only transport, bounded body size and timeouts;
- device/emulator tests for each protocol and leak/DNS behavior.

A build artifact is not a runtime test. Device-level tunnel verification remains
required before describing the app as a working VPN client.
