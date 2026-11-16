# Custom proxy profile import (initial implementation)

This branch introduces an isolated import and persistence layer under
`com.protonvpn.android.customvpn`. It does not replace Proton's VPN service,
does not change the existing home UI, and does not claim to establish a tunnel.

## Implemented

- Parse share links for VLESS, VMess, Trojan, Shadowsocks, SOCKS/SOCKS5 and HTTP.
- Decode plain-text and Base64 subscription payloads containing one link per line.
- Deduplicate imported links and persist profile metadata in a dedicated preference file.
- Keep the original URI intact for a later Xray configuration adapter.

## Not yet implemented

- Xray/AndroidLibXrayLite dependency and runtime adapter.
- Android VPN/TUN service integration and lifecycle handling.
- UI entry points (paste, QR scan, clipboard, subscription URL management).
- Remote subscription refresh, traffic stats, ping, and connection tests.

## Security and correctness notes

Imported links can contain credentials. Never log `rawUri`, subscription bodies,
UUIDs, passwords, or generated Xray JSON. Subscription fetching must be added with
HTTPS validation, bounded response size, timeouts, and clear user consent. A profile
being imported or saved must never be presented as connected.
