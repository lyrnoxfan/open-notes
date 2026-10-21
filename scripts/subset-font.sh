#!/usr/bin/env bash
# Rebuild the bundled serif subset from the upstream LXGW WenKai release.
#
# The app ships a GB2312 subset instead of the 24 MB upstream font: full CJK coverage is not
# needed for a personal notes app, and the missing characters fall back to the system font.
#
# Requires python3 + fonttools (see FONTTOOLS below). Run from the repository root:
#   scripts/subset-font.sh
set -euo pipefail

VERSION="${LXGW_VERSION:-v1.520}"
UPSTREAM_URL="https://github.com/lxgw/LxgwWenKai/releases/download/${VERSION}/LXGWWenKai-Regular.ttf"
WORK_DIR="${WORK_DIR:-$(mktemp -d)}"
OUT="app/src/main/res/font/lxgw_wenkai_regular.ttf"
FONTTOOLS="${FONTTOOLS:-pyftsubset}"

mkdir -p "$(dirname "$OUT")"

if [ ! -f "$WORK_DIR/LXGWWenKai-Regular.ttf" ]; then
  echo "downloading $UPSTREAM_URL"
  curl -fsSL -o "$WORK_DIR/LXGWWenKai-Regular.ttf" "$UPSTREAM_URL"
fi

# GB2312 level 1 + 2 hanzi (6763), ASCII, CJK punctuation and fullwidth forms.
python3 - "$WORK_DIR/charset.txt" <<'PY'
import sys
chars = set()
for hi in range(0xA1, 0xF8):
    for lo in range(0xA1, 0xFF):
        try:
            chars.add(bytes([hi, lo]).decode("gb2312"))
        except Exception:
            pass
chars.update(chr(c) for c in range(0x20, 0x7F))
chars.update("　、。〃々〈〉《》「」『』【】〔〕〖〗〘〙〚〛〜〝〞〟–—‘’‛“”„‟…‧﹏·×÷±°′″‰€£¥©®™№→←↑↓")
open(sys.argv[1], "w", encoding="utf-8").write("".join(sorted(chars)))
PY

# Drop OpenType layout and vertical metrics: the app renders horizontal text only and does no
# kerning beyond what the platform does. Hinting goes too - Android ignores TrueType hinting.
"$FONTTOOLS" "$WORK_DIR/LXGWWenKai-Regular.ttf" \
  --text-file="$WORK_DIR/charset.txt" \
  --output-file="$OUT" \
  --drop-tables+=GSUB,GPOS,GDEF,BASE,JSTF,DSIG,vhea,vmtx,VORG \
  --no-hinting \
  --layout-features='' \
  --name-IDs='*' \
  --recalc-bounds

echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
