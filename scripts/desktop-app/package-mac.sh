#!/usr/bin/env bash
# The macOS packages of the desktop app, from the .app electron-builder left in desktop/app/dist:
#
#   scripts/desktop-app/package-mac.sh <arch>          # arm64 | x64
#
# 1. ad-hoc code signature over the whole bundle (`codesign -s -`). Nothing is notarized — there
#    is no Apple developer account behind a non-profit community project — but a sealed ad-hoc
#    bundle is what Gatekeeper knows how to talk about ("Apple could not verify…", with *Open
#    Anyway* in System Settings → Privacy & Security). An unsigned bundle it calls "damaged" and
#    offers to move to the Trash, and on Apple silicon unsigned code does not run at all.
# 2. unibot-Desktop-<version>-mac-<arch>.zip — `ditto`, the way Finder makes archives, which
#    keeps symlinks, resource forks and permissions; unzip, drag to Applications.
# 3. unibot-Desktop-<version>-mac-<arch>.dmg — an APFS image made with `hdiutil` directly.
#    electron-builder's HFS+ image copied with Finder error -36 on some Macs (0.1.20, 0.1.21).
set -euo pipefail
arch="${1:?arch: arm64 | x64}"
here="$(cd "$(dirname "$0")/../.." && pwd)"
app_dir="$here/desktop/app"
version="$(node -p "require('$app_dir/package.json').version")"
app="$(ls -d "$app_dir"/dist/mac*/unibot.app | head -1)"
[ -d "$app" ] || { echo "no unibot.app under $app_dir/dist — run 'npm run dist:dir' first" >&2; exit 1; }
out="$app_dir/dist"
name="unibot-Desktop-$version-mac-$arch"

echo "== ad-hoc signature"
# inner code first: the bundled runtime's executables and libraries are loose files under
# Resources, which --deep does not visit
find "$app/Contents/Resources/runtime" -type f \( -perm -u+x -o -name "*.so" -o -name "*.dylib" \) -print0 \
  | xargs -0 -n 50 codesign --force --sign - --timestamp=none 2>/dev/null || true
codesign --force --deep --sign - --timestamp=none "$app"
codesign --verify --deep --strict "$app" && echo "signature ok"

echo "== zip"
rm -f "$out/$name.zip"
ditto -c -k --sequesterRsrc --keepParent "$app" "$out/$name.zip"

echo "== dmg"
stage="$(mktemp -d)"
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
rm -f "$out/$name.dmg"
hdiutil create -volname "unibot $version" -srcfolder "$stage" -ov -fs APFS -format UDZO -quiet "$out/$name.dmg"
rm -rf "$stage"
hdiutil verify -quiet "$out/$name.dmg" && echo "dmg ok"

# electron-builder's own mac artifacts, if any, must not go on the release next to these
find "$out" -maxdepth 1 -type f \( -name "*.dmg" -o -name "*.zip" -o -name "*.blockmap" \) ! -name "$name.*" -delete
ls -la "$out"/*.zip "$out"/*.dmg
