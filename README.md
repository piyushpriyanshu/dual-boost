# DualBoost

Combine **Wi-Fi + mobile data** on Android for faster downloads. Built for the Nothing Phone (4a), Android 16, and works on Android 10+ (arm64).

## Install

1. Open the **Releases** section of this repository on your phone.
2. Download the newest `DualBoost-vX.Y.Z.apk`.
3. Open it and allow "Install unknown apps" for your browser when asked.

New versions install over the old one; your settings are kept.

## Before first use

- Turn on **Mobile data**.
- In **Developer options**, keep **Mobile data always active** turned on.
  (To unlock Developer options: Settings → About phone → tap *Build number* 7 times.)
- In DualBoost Settings, set **Battery: unrestricted**.

## How it works

DualBoost runs a local VPN entirely on the phone. No outside server is used.

- A bundled tun2socks engine ([hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), MIT) turns all phone traffic into individual connections.
- A built-in relay picks **Wi-Fi or mobile data for every new connection** and pins it to that network (`Network.bindSocket`).
- The balancer learns each link's real speed and sends each connection where it will be fastest.
  For example, with 50 Mbps Wi-Fi and 30 Mbps mobile, about 5 of every 8 parallel connections go over Wi-Fi.

**What gets faster:** anything that uses several connections at once. That includes large downloads, Play Store updates, cloud backups, many browser tabs, and speed tests in multi-connection mode.
**What doesn't:** a single stream, such as one video or one game connection. It stays on one link. Combining a single stream needs a remote bonding server, which this app doesn't use.

## Features

- **Combine** mode (both links) or **Backup** mode (Wi-Fi, with instant mobile failover)
- Daily mobile data limit
- Per-app exclusions, for banking apps etc.
- "Keep each website on one link" for sites that dislike changing IPs
- Live per-link speeds, a 60-second chart, notification speed, and a Quick Settings tile
- Supports Always-on VPN, restarts after reboot, and resumes after updates
- Local network devices (printers, Chromecast) stay reachable

## Building

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`) and published as a release.
Local build: `git clone --recursive`, then `./gradlew assembleRelease` (needs the Android SDK, and NDK 28.2).

The signing key in `keystore/` is committed on purpose. It keeps every release update-compatible for this personal sideloaded app. Don't reuse it for anything published.
