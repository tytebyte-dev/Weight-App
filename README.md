<div align="center">

# Weight App

**An unofficial YouTube client for Android phones and tablets**

[English](README.md) · [Русский](README.ru.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84)
![Status: unofficial](https://img.shields.io/badge/status-unofficial-lightgrey)

</div>

Weight App is a touch-first YouTube client in plain Material Design. It keeps the playback and
account engine of [SmartTube](https://github.com/yuliskov/SmartTube) (the Android TV client by
yuliskov, through the NewTube fork) and adds a phone interface, its own Media3 player, offline
saving, a Music tab and a built-in censorship bypass that runs entirely inside the app.

> **Unofficial.** The project is independent. It is not affiliated with, sponsored or endorsed by
> Google, YouTube or SmartTube's developer. YouTube and Android are trademarks of Google LLC.

## Contents

- [Features](#features)
- [Install](#install)
- [Built-in censorship bypass](#built-in-censorship-bypass)
- [Music tab](#music-tab)
- [Downloads and storage](#downloads-and-storage)
- [Diagnostic log](#diagnostic-log)
- [Build from source](#build-from-source)
- [Signing and releases (CI)](#signing-and-releases-ci)
- [Repository layout](#repository-layout)
- [Credits and licenses](#credits-and-licenses)

## Features

- **Touch interface.** Bottom navigation (Home, Music, Subscriptions, Downloads, You), search,
  settings with an in-page search, light, dark and system themes.
- **Player.** Own Media3 player: background playback, picture-in-picture, a mini-player you can
  drag down while browsing, lock-screen and headset controls, playback speed 0.25x to 2x,
  subtitles (including auto-translated), chapters, dubbed audio tracks, comments and live chat.
- **Account.** Sign in with a code (the TV way) or with a Google account of the device through
  microG. Subscriptions, history, playlists, Watch later and recommendations come from the
  account; several accounts are supported. Signing in is optional.
- **Less noise.** SponsorBlock, DeArrow and Return YouTube Dislike; no Shorts.
- **Offline.** Save a video (up to 1080p) or only its audio and play it later without a
  connection.
- **Casting.** Play to a Chromecast from the phone, or pair with SmartTube on a TV.
- **Built-in bypass.** A local proxy inside the app that opens YouTube where it is blocked or
  throttled. See [below](#built-in-censorship-bypass).
- **Music tab.** A song list with a compact player bar instead of the full player.
  See [below](#music-tab).
- **Storage page.** Shows what the app's own cache holds and clears it.
  See [below](#downloads-and-storage).
- **Privacy.** No analytics, no crash reporting, no ad SDKs.
- Languages: English, Russian, Spanish.

## Install

1. Open the **Releases** page of this repository and download the APK for your phone:
   `arm64-v8a` fits most modern phones, `armeabi-v7a` is for older 32-bit devices, `x86` and
   `x86_64` are for emulators, and `universal` runs everywhere but is larger.
2. Allow installing apps from your browser or file manager when Android asks, then open the file.
3. Updating: install a newer APK over the old one. Releases are always signed with the same key,
   so the update keeps your data. A build signed with another key cannot replace it; uninstall
   first in that case.

Requirements: Android 7.0 (API 24) or newer.

Application id: `io.github.tytebyte_dev.weight_app`.

## Built-in censorship bypass

The bypass is a **local proxy inside the app**. It is not a VPN: no system VPN permission is
requested, nothing outside Weight App is routed through it, and other apps are not affected.

How it works:

- The engine is [Xray-core](https://github.com/XTLS/Xray-core) 26.3.27, compiled with gomobile
  into the app (`native/xraybridge`). It listens on a loopback SOCKS5 port and the app's own
  HTTP client is pointed at it.
- The app finds the core by reflection. If the core is not built into an APK, the app still runs
  and the bypass reports that the core is missing.
- It starts by itself in the regions of a configurable list (default `RU, BY, IR, CN, TM, MM, KP`),
  and a quick reachability probe confirms YouTube really is blocked before the proxy is used.
  A start watchdog gives up after 20 seconds instead of hanging.
- **DPI bypass** (TLS fragmentation) and **encrypted DNS** are separate switches in
  *Settings → Bypass* and are **off by default**.

Adding servers (*Settings → Bypass*): paste or import share links or a subscription URL.
Supported links: `vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://` (`hy2://`),
`socks://` / `socks5://` and `wireguard://`. A subscription may be a base64 list or a plain
list, one link per line. Several servers can be stored; pick the active one in the list.

Limits to be aware of: the proxy covers the app's own HTTP traffic (OkHttp). Components that use
a different network stack, such as Cronet, do not go through it.

## Music tab

The second tab shows YouTube Music as a song list: square cover, title and artist, duration, a
download button, a like star and a three-dot menu. Filter chips (All, Songs, Playlists, Artists)
sit under a search pill.

Tapping a song does **not** open the full player. The player starts hidden, minimizes itself
and a compact bar appears above the navigation with the cover, title, play/pause, next and close.
The playing cover shows an equalizer animation. Tapping the bar expands the normal player;
minimizing it again brings the music bar back.

## Downloads and storage

- **Downloads tab.** Saved videos and audio live in the app's own storage and are never touched
  by any cleanup.
- **Automatic cache cleanup** (*Settings → General*). When a watched video's player is really
  closed, its entry in the streaming cache is removed, so the app does not grow like a social
  network app. Videos that are in the Downloads tab, and the one that is playing, are kept.
- **Storage usage page** (*Settings → General → Storage usage*). A donut chart splits the
  app's cache into Video, Pictures and Other, with a tick, percentage and size per category and a
  button *Clear cache N MB* that clears the ticked ones. Downloads are not counted.

## Diagnostic log

The file icon in the top bar (left of the cast and search icons) shares the app's recent
diagnostic log. The same action is in *Settings → About*. The log is kept in memory and nothing
is sent unless you share it. Attach it when reporting a bug.

## Build from source

Requirements: JDK 21, Android SDK, NDK 21.0.6113669 (the app's `ndkVersion`), and, to build the
bypass core, Go with gomobile and NDK 27.2.12479018.

```bash
git clone --recurse-submodules https://github.com/tytebyte-dev/Weight-App.git
cd Weight-App

# MediaServiceCore is another project's repository: apply the patches the app needs
for p in patches/*.patch; do git -C MediaServiceCore apply "../$p"; done

# optional: build the bypass core (otherwise the app runs without it)
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
native/build-cores.sh

./gradlew :smarttubetv:assembleStmobileDebug
```

APKs land in `smarttubetv/build/outputs/renamed_apks/<variant>/`. To sign locally, create
`keystore.properties` in the repository root with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword` (the file is git-ignored). Without it, debug builds use the auto-generated debug key
and an `.debug` application id suffix.

Unit tests of the bypass logic: `smarttubetv/src/test/java/com/newtube/mobile/bypass`.

## Signing and releases (CI)

Two GitHub Actions workflows live in `.github/workflows`:

| Workflow | Trigger | What it does |
|:--|:--|:--|
| **Test build** | push to `main`, manual | Builds debug APKs for arm64-v8a, armeabi-v7a, x86, x86_64 and a universal one and uploads them as artifacts, signed with the `testkey` certificate. |
| **Release** | manual | Builds the release APKs, signs them with the `shared` certificate, checks the signature, creates a tag and publishes a GitHub Release. |

Release details:

- **Tag.** Automatic: `v<versionName>` from `smarttubetv/build.gradle` (for example `v1.15.3`),
  or `v<versionName>.<run number>` if that tag already exists.
- **Notes.** Automatic: version and commit, a *Full changelog* link comparing with the previous
  release, and the list of changes generated by GitHub. A *pre-release* tick is available.
- **Keys.** The certificates are in `signing/` (`testkey.x509.pem`, `shared.x509.pem`). The private
  keys are the public AOSP keys; CI takes them from the repository secrets `TESTKEY_PK8_B64` and
  `SHARED_PK8_B64` (base64 of the `.pk8` files) or, if a secret is absent, downloads them from the
  AOSP mirror. `.github/scripts/setup-signing.sh` checks that the key matches the certificate and
  fails the build otherwise.

> The AOSP test keys are publicly known. They keep updates compatible between builds but do not
> prove authorship; do not treat a signature by them as a mark of trust.

## Repository layout

| Path | Contents |
|:--|:--|
| `smarttubetv/` | The app module; the phone code is in `src/stmobile/java/com/newtube/mobile` (`bypass`, `casting`, `downloads`, `player`, `ui`, `update`). |
| `common/`, `sabr-media3/`, `doubletapplayerview-media3/`, `filepicker-lib/`, `slidableactivity/` | Shared libraries and the player pieces. |
| `MediaServiceCore/`, `SharedModules/` | Git submodules (upstream engine). |
| `patches/` | Patches applied on top of `MediaServiceCore` (system Google account sign-in, microG account types). |
| `native/` | Bypass cores: Xray-core, the gomobile bridge, vendored hev-socks5-tunnel (not built), `build-cores.sh`. |
| `signing/` | Public signing certificates. |
| `fastlane/` | Store-style app descriptions. |
| `macrobenchmark/`, `tools/` | Benchmarks and helper scripts. |
| `website/` | The project website sources. |

## Credits and licenses

- Weight App is released under the [MIT License](LICENSE).
- Built on [SmartTube](https://github.com/yuliskov/SmartTube) by yuliskov and the NewTube fork,
  which provide the engine; Weight App adds the changes described here.
- [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0) powers the bypass;
  [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT) is vendored under `native/`
  but is not built.
- SponsorBlock and DeArrow data © their contributors, CC BY-NC-SA 4.0.
