#!/bin/bash
# Builds Clearpane.app next to this script. Needs only the Xcode command line tools.
set -euo pipefail
cd "$(dirname "$0")"

APP="Clearpane.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# Universal binary: Apple silicon and Intel.
for arch in arm64 x86_64; do
    swiftc -O -swift-version 5 -target "$arch-apple-macos13.0" main.swift -o "build-$arch"
done
lipo -create build-arm64 build-x86_64 -output "$APP/Contents/MacOS/Clearpane"
rm -f build-arm64 build-x86_64
cp ../shared/blocklist.txt ../shared/blocklist-extra.txt ../shared/youtube-skip.js ../shared/icon-finder.js ../shared/app-pages.html AppIcon.icns "$APP/Contents/Resources/"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>Clearpane</string>
    <key>CFBundleDisplayName</key><string>Clearpane</string>
    <key>CFBundleIdentifier</key><string>com.local.webshell</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>CFBundleExecutable</key><string>Clearpane</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.3</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsArbitraryLoadsInWebContent</key><true/>
    </dict>
</dict>
</plist>
PLIST

codesign --force --sign - "$APP" >/dev/null 2>&1 || true
echo "Built $(pwd)/$APP"
