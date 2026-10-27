# DualBoost

Combine **Wi-Fi + mobile data** on Android. Built for the Nothing Phone (4a) on Android 16; works on Android 10+ (arm64). No root, no remote server.

## Install

Open **Releases** on your phone, download the newest `DualBoost-vX.Y.Z.apk` and open it. After that, the app updates itself (Settings → Updates).

## Before first use

- Turn on **Mobile data**, and keep **Developer options → Mobile data always active** on.
- In DualBoost Settings, set **Battery: unrestricted**.
- Optional: run **Speed test** once. DualBoost then knows each link's real speed from the start.

## Features

**Boost (whole phone)**
- Local VPN: every connection any app opens goes over Wi-Fi or mobile, balanced by each link's measured speed.
- Instant failover: a new connection switches links if the first one hasn't connected within 0.6 s. DNS goes over both links. Dead links are detected by health probes.
- **Low-ping mode**: games and call apps stay on the lowest-ping link and never switch mid-session.
- **App rules** per app: Both links, Low ping, Wi-Fi only, Mobile data, or Bypass.
- Modes: Combine, or Backup (Wi-Fi with instant mobile takeover). Daily mobile-data limit. Ad-blocking DNS (AdGuard, Family or Custom).
- Works with download managers such as **1DM+ / ADM**: keep the boost on and use 8–16 connections per download.

**Download booster (one file over both links)**
- Share a link to DualBoost, or paste it. The file is fetched in pieces over both links at once, so even single downloads get the combined speed.
- Takes over and races slow pieces near the end, so a slow or frozen link never holds the finish.
- Pause and resume; survives app restarts; waits for the internet to come back; a failure keeps what was already downloaded.
- Saves to `Downloads/DualBoost`.

**Insight & tools**
- **Speed test**: Wi-Fi, mobile, and combined download, upload and ping.
- **Diagnostics**: ping, jitter, signal, Wi-Fi band and standard, 4G/5G, and a plain-language "why is it slow?".
- Live speeds, a 60-second chart, data used today (Wi-Fi and mobile), and speed shown in Mbps, Kbps or Auto.
- Home-screen **widget**, Quick Settings tile, and long-press app **shortcuts**.
- **Report a problem**: opens a ready-filled GitHub issue with diagnostics (no browsing data).

## How it works

- `hev-socks5-tunnel` 2.18.0 (MIT), with a small patch in `patches/` that tags each connection with its source port, turns phone traffic into individual connections.
- A built-in SOCKS5 relay asks Android which app owns each connection (`getConnectionOwnerUid`). It applies that app's rule and pins the outgoing socket to Wi-Fi or mobile (`Network.bindSocket`).
- The download booster uses HTTP range requests, with a shared piece queue, speed-proportional takeover and end-of-download racing. The design is inspired by [Plexo](https://github.com/anmolkapil/plexo).

A single stream, such as one video or one game connection, stays on one link. Splitting a single stream needs a remote bonding server, which this app doesn't use.

## Building

Every push is compiled by GitHub Actions (`.github/workflows/build.yml`). The engine patch is applied automatically. Pushes to `main` are published as releases.
Local build: `git clone --recursive`, apply `patches/*.patch` inside `app/src/main/jni/hev-socks5-tunnel`, then `./gradlew assembleRelease` (needs the Android SDK and NDK 28.2).

The signing key in `keystore/` is committed on purpose. It keeps every release update-compatible for this personal sideloaded app.
