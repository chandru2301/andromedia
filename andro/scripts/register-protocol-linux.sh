#!/usr/bin/env bash
# Register the andro:// URL scheme for a DEV (unpackaged) run.
#
# Browser sign-in hands the Clerk ticket back through
# andro://auth/callback?ticket=… . On Linux that needs a .desktop file
# declaring x-scheme-handler/andro; Electron's setAsDefaultProtocolClient
# cannot create one for an unpackaged app, so it silently does nothing and the
# handoff dies with no error anywhere.
#
# Packaged builds do not need this: electron-builder writes the MimeType into
# the generated .desktop file. NOTE that a bare AppImage is not installed into
# the desktop database either, so AppImage users need AppImageLauncher (or a
# .deb/.rpm build) for the scheme to resolve.
#
# Usage:  ./scripts/register-protocol-linux.sh          # register
#         ./scripts/register-protocol-linux.sh --remove # undo

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
DESKTOP_FILE="$DESKTOP_DIR/andro-dev.desktop"

if [[ "${1:-}" == "--remove" ]]; then
  rm -f "$DESKTOP_FILE"
  update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
  echo "Removed $DESKTOP_FILE"
  exit 0
fi

ELECTRON_BIN="$APP_DIR/node_modules/.bin/electron"
if [[ ! -x "$ELECTRON_BIN" ]]; then
  echo "error: $ELECTRON_BIN not found — run npm install in andro/ first." >&2
  exit 1
fi

mkdir -p "$DESKTOP_DIR"
cat > "$DESKTOP_FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Name=andro (dev)
Comment=Andro desktop app — development build
Exec=$ELECTRON_BIN $APP_DIR %u
Icon=$APP_DIR/build/icon.png
Terminal=false
Categories=Development;
MimeType=x-scheme-handler/andro;
StartupWMClass=andro
NoDisplay=true
DESKTOP

update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
xdg-mime default andro-dev.desktop x-scheme-handler/andro

echo "Registered andro:// -> $DESKTOP_FILE"
echo "Verify:  xdg-mime query default x-scheme-handler/andro"
echo "Test:    xdg-open 'andro://auth/callback?ticket=TEST'"
