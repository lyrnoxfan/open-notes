#!/usr/bin/env python3
"""WCAG contrast report for the DesignTokens palette.

Reads app/src/main/java/com/example/privatenotes/ui/theme/Color.kt directly, so the numbers can
never drift from the palette the app actually ships. Run: python3 scripts/contrast.py
"""

from __future__ import annotations

import pathlib
import re
import sys

COLOR_KT = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/com/example/privatenotes/ui/theme/Color.kt"
)

# (foreground, background, purpose, required ratio)
PAIRS = [
    ("onAction", "action", "主按钮文字 / 鼠尾草底", 4.5),
    ("textPrimary", "background", "正文 / 页面底色", 7.0),
    ("textPrimary", "surface", "正文 / 卡片底", 7.0),
    ("textPrimary", "surfaceMuted", "正文 / 弱底", 7.0),
    ("textSecondary", "background", "次要文字 / 页面底色", 4.5),
    ("textSecondary", "surface", "次要文字 / 卡片底", 4.5),
    ("textSecondary", "surfaceMuted", "次要文字 / 雾蓝底", 4.5),
    ("accent", "background", "强调文字 / 页面底色", 4.5),
    ("accent", "surface", "强调文字 / 卡片底", 4.5),
    ("onAccent", "accent", "强调按钮上的文字", 4.5),
    ("onAccentContainer", "accentContainer", "选中胶囊上的文字", 4.5),
    ("error", "background", "错误文字 / 页面底色", 4.5),
    ("error", "surface", "错误文字 / 卡片底", 4.5),
    # 装饰性细线不承担识别控件的职责，只要求「看得出有这条线」；输入框边界另用 dividerStrong。
    ("divider", "background", "分割线 / 页面底色（装饰）", 1.3),
    ("dividerStrong", "background", "输入框边界 / 页面底色", 1.6),
]


def parse_tokens(source: str, scheme: str) -> dict[str, tuple[int, int, int]]:
    block = re.search(rf"object {scheme}(?:\s*:\s*\w+)? \{{(.*?)\n    \}}", source, re.S)
    if block is None:
        raise SystemExit(f"could not find DesignTokens.{scheme} in {COLOR_KT}")
    tokens = {}
    for name, value in re.findall(r"val (\w+) = Color\(0x([0-9A-Fa-f]{8})\)", block.group(1)):
        argb = int(value, 16)
        tokens[name] = ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)
    if not tokens:
        raise SystemExit(f"no colours parsed from DesignTokens.{scheme}")
    return tokens


def channel(value: int) -> float:
    c = value / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = (channel(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg: tuple[int, int, int], bg: tuple[int, int, int]) -> float:
    a, b = luminance(fg), luminance(bg)
    lighter, darker = max(a, b), min(a, b)
    return (lighter + 0.05) / (darker + 0.05)


def hex_of(rgb: tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % rgb


def main() -> int:
    source = COLOR_KT.read_text(encoding="utf-8")
    failures = 0
    for scheme in ("Light", "Dark"):
        tokens = parse_tokens(source, scheme)
        print(f"\n## {scheme}\n")
        print("| 前景 | 背景 | 用途 | 色值 | 对比度 | 要求 | 结果 |")
        print("| --- | --- | --- | --- | --- | --- | --- |")
        for fg, bg, purpose, required in PAIRS:
            value = ratio(tokens[fg], tokens[bg])
            ok = value >= required
            if not ok:
                failures += 1
            print(
                f"| `{fg}` | `{bg}` | {purpose} | {hex_of(tokens[fg])} / {hex_of(tokens[bg])} "
                f"| {value:.2f}:1 | ≥ {required:.1f}:1 | {'✅' if ok else '❌'} |"
            )
    print(f"\n{'全部达标' if failures == 0 else f'{failures} 组未达标'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
