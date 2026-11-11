#!/bin/bash
# Builds Clearpane.app and wraps it in a disk image: dist/Clearpane-mac.dmg
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
STAGE="$(mktemp -d)"
cp -R Clearpane.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
mkdir -p ../dist
rm -f ../dist/Clearpane-mac.dmg
hdiutil create -volname "Clearpane" -srcfolder "$STAGE" -ov -format UDZO ../dist/Clearpane-mac.dmg >/dev/null
rm -rf "$STAGE"
echo "Packaged $(cd ../dist && pwd)/Clearpane-mac.dmg"
