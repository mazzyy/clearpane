# Clearpane

A clean window onto any website. Clearpane opens a start page of your choice as its own
app, on macOS and Android, and blocks ads, trackers, popups and redirects along the way.

<img src="logo.png" width="96" alt="Clearpane logo">

## Features

- **Start page**: the address you set in Settings opens every time the app starts.
- **Optional address bar**: type an address, or words to search.
- **Ad and tracker blocking**: about 3,700 known ad hosts are blocked, and common ad
  containers are hidden.
- **Popup and redirect blocking**: no second windows, and an optional "stay on this site"
  lock that stops a page sending you elsewhere.
- **Favourites**: saved pages shown as an app grid with each site's own icon.
- **History**: grouped by site or as a flat list, with search and multi-select delete.
  Can be set to save every page, only website names, or nothing.
- **YouTube ad skipper** (optional, best effort): presses Skip, jumps unskippable ads to
  their end, hides promoted slots. This is against YouTube's terms of service and breaks
  whenever YouTube changes its page.

## Layout

| Folder | What it is |
|---|---|
| `mac/` | macOS app: one Swift file (`main.swift`) and `build.sh` |
| `android/` | Android app: a Gradle project with one Java activity |
| `shared/` | Files both apps bundle: blocklists, the built-in pages, page scripts, icon generator |

The favourites, history and About screens are one HTML file, `shared/app-pages.html`,
used by both apps. Edit the `AUTHOR` block at the top of its script to change the About tab.

## Build

### macOS

Needs the Xcode command line tools (`xcode-select --install`).

```bash
cd mac && ./build.sh
open Clearpane.app
```

### Android

Needs JDK 17 and the Android SDK (platform 34, build-tools 34). Either open `android/`
in Android Studio and press Run, or from a terminal:

```bash
cd android && ./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

After changing anything in `shared/`, copy it into the Android assets before building:

```bash
cp shared/blocklist.txt shared/blocklist-extra.txt shared/*.js shared/app-pages.html android/app/src/main/assets/
```

## Blocklists

- `shared/blocklist.txt` is maintained by hand; add your own hosts at the bottom.
- `shared/blocklist-extra.txt` is Peter Lowe's ad and tracking server list from
  <https://pgl.yoyo.org/adservers/>. It is his work; see that site for its licence terms.

Blocking is by host name, so ads served from the same host as the content get through.

## Notes

- The Android application ID and the macOS bundle ID are `com.local.webshell`, kept from
  an earlier name so that existing installs keep their settings.
- Site icons shown in Favourites are fetched from each site at the moment you save it and
  belong to their owners.
- No licence has been chosen for this code yet.

Made by [Abdul Musawar](https://github.com/mazzyy).
