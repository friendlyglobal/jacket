#!/bin/sh
# Renders the Play feature graphic (1024x500) for en-US and every locale in feature-strings.js
# into fastlane/metadata/android/<locale>/images/featureGraphic.png.
#
#   docs/store-art/render-feature.sh                 # all locales
#   docs/store-art/render-feature.sh ja-JP ar        # just these
#   docs/store-art/render-feature.sh --check [...]   # print the layout/font report instead of rendering
#
# Needs network access for Google Fonts. Set CHROME to use another Chrome binary.
set -eu
cd "$(dirname "$0")/../.."
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
PAGE="$PWD/docs/store-art/feature.html"
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
LOCALES=${*:-"en-US $(sed -n 's/^  "\([A-Za-z-]*\)": {.*/\1/p' docs/store-art/feature-strings.js | tr '\n' ' ')"}

for L in $LOCALES; do
  if [ "$CHECK" = 1 ]; then
    "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --window-size=1024,500 --virtual-time-budget=8000 --dump-dom "file://$PAGE?lang=$L&check" 2>/dev/null |
      sed -n 's/.*<script type="application\/json" id="report">\(.*\)<\/script>.*/\1/p'
    continue
  fi
  OUT="fastlane/metadata/android/$L/images/featureGraphic.png"
  mkdir -p "$(dirname "$OUT")"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1024,500 --virtual-time-budget=8000 --screenshot="$OUT" "file://$PAGE?lang=$L" >/dev/null 2>&1
  SIZE=$(sips -g pixelWidth -g pixelHeight "$OUT" | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w "x" h}')
  if [ "$SIZE" != "1024x500" ]; then echo "$OUT is $SIZE, expected 1024x500" >&2; exit 1; fi
  echo "$OUT $SIZE"
done
