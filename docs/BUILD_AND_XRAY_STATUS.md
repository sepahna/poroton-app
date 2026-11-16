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
- a tested adapter from imported share links to the exact Xray runtime API;
- foreground notification, permission flow, stop/restart, and process-death recovery;
- UI navigation for link, clipboard, QR, and subscription import and profile selection;
- subscription refresh with HTTPS-only transport, bounded body size and timeouts;
- device/emulator tests for each protocol and leak/DNS behavior.

A build artifact is not a runtime test. Device-level tunnel verification remains
required before describing the app as a working VPN client.
