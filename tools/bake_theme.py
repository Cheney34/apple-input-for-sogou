#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Apple Input 的配色直接"烤"进搜狗皮肤资源目录（离线版 IosSkin）。

用途：不装 LSPosed 模块，直接改 APK 里的默认皮肤（assets/theme/theme_default/1080），
重打包成"改过皮肤"的安装包。

设计原则（与模块里的 IosSkin 一致）：
  1. 只改颜色，不动任何 W/H/PADDINGS/ROWS 等几何参数 —— 几何一动，命中区与 insets 全乱。
  2. 位图只改"实心"的底色/键帽（不透明占比 > 阈值），透明的高亮/蒙版原样保留，
     否则每个候选词都会变成一块色块。
  3. 图标（设置齿轮、语言旗帜、emoji 等）一律不动。

用法：
  python bake_theme.py --theme <assets/theme/theme_default> --preset ios --mode light
  python bake_theme.py --theme <assets/theme/theme_default> --preset ios --mode dark
  python bake_theme.py --theme <assets/theme/theme_default> --preset ios --mode colors
"""
import argparse
import os
import re
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")

# ── 预设调色板（与 SkinPreset.java 保持同一套数值） ────────────────────────────
PRESETS = {
    "ios": {
        "light": dict(kb="D1D4D9", bar="D1D4D9", key="FFFFFF", keyp="E5E5EA",
                      func="ADB3BC", funcp="9FA5AE", popup="FFFFFF",
                      text="000000", accent="007AFF", radius=0.12, edge=True, shadow=0x1A),
        "dark": dict(kb="222223", bar="222223", key="6D6D6F", keyp="7F7F80",
                     func="444445", funcp="4F4F51", popup="6D6D6F",
                     text="FDFDFE", accent="0A84FF", radius=0.12, edge=True, shadow=0x33),
    },
    "wechat": {
        "light": dict(kb="EDEDED", bar="FFFFFF", key="FFFFFF", keyp="E6E6E6",
                      func="EDEDED", funcp="D5D5D5", popup="FFFFFF",
                      text="191919", accent="07C160", radius=0.16, edge=False, shadow=0x12),
        "dark": dict(kb="111111", bar="232323", key="2C2C2C", keyp="3A3A3A",
                     func="262626", funcp="333333", popup="2C2C2C",
                     text="EDEDED", accent="07C160", radius=0.16, edge=False, shadow=0x20),
    },
    "google": {
        "light": dict(kb="F1F3F4", bar="FFFFFF", key="FFFFFF", keyp="E3E5E8",
                      func="E8EAED", funcp="DADCE0", popup="FFFFFF",
                      text="202124", accent="1A73E8", radius=0.14, edge=False, shadow=0x1F),
        "dark": dict(kb="1F2023", bar="3C4043", key="4A4F55", keyp="5A6067",
                     func="3A3E44", funcp="474C52", popup="4A4F55",
                     text="F1F3F4", accent="8AB4F8", radius=0.14, edge=False, shadow=0x2A),
    },
}

# ── 分类（与 IosSkin.kindOf / isProtected / isBarImage 对齐） ───────────────────
FILL_PREFIX = ("bg.png", "bg1.png", "bg_cands", "cand_bg", "candidate_bg", "first_cand",
               "more_cands", "function_bg", "float_function", "cloud_bg", "bg_cloud",
               "bg_composing", "bg_header", "toolbar", "blank_shadow_bg", "bg_container_key",
               "bg_", "keyboard_bg")
BAR_PREFIX = ("candidate", "cand_bg", "bg_cand", "first_cand", "more_cands",
              "function_bg", "float_function", "cloud_bg", "bg_cloud", "bg_composing",
              "bg_header", "toolbar")
KEY_PREFIX = ("key_qwerty", "key_us", "key_number", "key_digit", "key_symbol",
              "key_char", "key_function", "key_func", "key_grid", "key_sl", "key_active")
KEY_EXACT = ("key.png", "key_n.png", "key_p.png")
POPUP_PREFIX = ("popup", "bg_popup")

PROTECT = ("icon", "logo", "menu", "setting", "gear", "flag", "globe", "lang", "voice",
           "emoji", "face", "doutu", "lottie", "avatar", "head", "skin_preview",
           "candidate_preview", "theme_preview", "arrow", "chevron", "delete_icon",
           "enter_icon", "space_icon", "emoji_", "_emoji", "sticker")


def kind_of(name: str) -> str:
    n = name.lower()
    if any(p in n for p in PROTECT):
        return "none"
    if n.startswith(POPUP_PREFIX):
        return "popup"
    if n in KEY_EXACT or n.startswith(KEY_PREFIX):
        return "key"
    if n.startswith(FILL_PREFIX):
        return "fill"
    return "none"


def is_bar(name: str) -> bool:
    return name.lower().startswith(BAR_PREFIX)


def hex_to_rgb(h: str):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ── 位图重绘 ──────────────────────────────────────────────────────────────────
def opaque_bbox(im: Image.Image):
    """返回 alpha > 8 的包围盒；全透明返回 None。"""
    alpha = im.getchannel("A")
    box = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
    return box


def opaque_ratio(im: Image.Image, box) -> float:
    alpha = im.getchannel("A").crop(box)
    hist = alpha.histogram()
    solid = sum(hist[9:])
    total = max(1, (box[2] - box[0]) * (box[3] - box[1]))
    return solid / total


def recolor_fill(im: Image.Image, color: str) -> Image.Image:
    """保留原图形状（alpha），把颜色换成目标色 —— 用于整块底色。"""
    rgb = hex_to_rgb(color)
    flat = Image.new("RGBA", im.size, rgb + (255,))
    flat.putalpha(im.getchannel("A"))
    return flat


def recolor_cap(im: Image.Image, box, color: str, preset: dict) -> Image.Image:
    """按 iOS 规范重画键帽：圆角矩形 + 可选底边线 + 可选投影，保留画布尺寸。"""
    w, h = im.size
    x0, y0, x1, y1 = box
    bw, bh = x1 - x0, y1 - y0
    gap = max(1, round(bh * 0.02))
    # 小图（一两个像素的边线/分隔）内缩后会翻转，直接按原包围盒画
    if (x1 - x0) <= gap * 3 or (y1 - y0) <= gap * 3:
        gap = 0
    r = max(preset["radius"] * bh, bh * 0.10)
    r = min(r, max(1.0, min(bw, bh) * 0.45))

    S = 4  # 超采样，减少锯齿
    canvas = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    rgb = hex_to_rgb(color)

    if preset.get("edge"):
        edge = tuple(max(0, c - 18) for c in rgb)
        d.rounded_rectangle([((x0 + gap) * S), ((y0 + gap + 1) * S),
                             ((x1 - gap) * S), ((y1 - gap + 1) * S)],
                            radius=r * S, fill=edge + (255,))
    d.rounded_rectangle([((x0 + gap) * S), ((y0 + gap) * S),
                         ((x1 - gap) * S), ((y1 - gap) * S)],
                        radius=r * S, fill=rgb + (255,))
    return canvas.resize((w, h), Image.LANCZOS)


def process_dir(path: str, preset: dict, dry: bool):
    stats = {"fill": 0, "key": 0, "popup": 0, "skip": 0}
    for name in sorted(os.listdir(path)):
        if not name.lower().endswith(".png"):
            continue
        kind = kind_of(name)
        if kind == "none":
            stats["skip"] += 1
            continue
        fp = os.path.join(path, name)
        try:
            im = Image.open(fp).convert("RGBA")
        except Exception as e:  # noqa: BLE001
            print("  跳过（打不开）%s: %s" % (name, e))
            continue
        box = opaque_bbox(im)
        if not box:
            stats["skip"] += 1
            continue
        ratio = opaque_ratio(im, box)
        # 透明的高亮/蒙版不要动，否则每个候选词都会盖一块色
        if ratio < 0.55:
            stats["skip"] += 1
            continue
        if kind == "fill":
            out = recolor_fill(im, preset["bar"] if is_bar(name) else preset["kb"])
            stats["fill"] += 1
        elif kind == "key":
            color = preset["func"] if "func" in name.lower() else preset["key"]
            out = recolor_cap(im, box, color, preset)
            stats["key"] += 1
        else:  # popup
            out = recolor_cap(im, box, preset["popup"], dict(preset, radius=preset["radius"] * 1.5))
            stats["popup"] += 1
        if not dry:
            out.save(fp, "PNG", optimize=True)
    print("  底色 %d / 键帽 %d / 气泡 %d / 保留原样 %d"
          % (stats["fill"], stats["key"], stats["popup"], stats["skip"]))
    return stats


# ── ini 文本色 ────────────────────────────────────────────────────────────────
BARE_KEYS = ("COLOR_FIRST_DEFAULT_THEME", "COLOR_FUNCTION_THEME",
             "COLOR_FUNCTION_PRESSED_THEME", "COLOR_LATIN_THEME",
             "COLOR_PRESSED_THEME", "COLOR_NUMBER_THEME", "COLOR_SYMBOL_THEME",
             "COLOR_SHIFT_THEME")


def patch_colors_ini(fp: str, preset: dict, dark: bool):
    """只改文字/强调色；键帽颜色由位图决定，避免两套逻辑打架。"""
    if not os.path.exists(fp):
        print("  colors.ini 不存在，跳过：%s" % fp)
        return
    text = preset["text"]
    accent = preset["accent"]
    src = open(fp, encoding="utf-8", errors="replace").read().splitlines()
    out, section = [], ""
    changed = 0
    for line in src:
        s = line.strip()
        if s.startswith("[") and s.endswith("]"):
            section = s[1:-1]
        if "=" in line and not s.startswith(";"):
            k, _, v = line.partition("=")
            key = k.strip()
            nk = re.sub(r"\s+", "", key)
            if nk in BARE_KEYS:
                line = "%s=%s" % (k, accent if nk == "COLOR_FIRST_DEFAULT_THEME" else text)
                changed += 1
            elif section.startswith("COMMON_TEXT") and nk in ("TEXT_COLOR", "TEXT_COLOR_PRESSED"):
                alpha = "0xD9" if "FUNC" in section or section == "COMMON_TEXT_COLOR" else "0xFF"
                line = "%s=%s%s" % (k, alpha, text)
                changed += 1
            elif section.startswith("COMMON_TEXT_POPUP") and nk in ("TEXT_COLOR", "TEXT_COLOR_PRESSED"):
                line = "%s=0xFF%s" % (k, text)
                changed += 1
        out.append(line)
    open(fp, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print("  colors.ini 改写 %d 行" % changed)


def patch_phone_skin(fp: str, preset: dict):
    """夜间变体（res/black/phoneSkin.ini）：把里面的文字色统一成预设文字色。"""
    if not os.path.exists(fp):
        print("  phoneSkin.ini 不存在，跳过：%s" % fp)
        return
    src = open(fp, encoding="utf-8", errors="replace").read().splitlines()
    out, changed = [], 0
    for line in src:
        s = line.strip()
        if s.startswith("TEXT_COLOR") or s.startswith("TEXT_COLOR_PRESSED"):
            k = line.partition("=")[0]
            line = "%s=0xFF%s" % (k, preset["text"])
            changed += 1
        out.append(line)
    open(fp, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print("  phoneSkin.ini 改写 %d 行" % changed)


BAR_SECTIONS = (
    "composingview", "cloudview", "candidatebar", "floatwindow", "headerview",
    "keyboardheader", "smartbar", "toolbarview", "more_cands_bar", "translate_bar",
    "symbolgridview", "keyboardswitchcontainer", "platformcontainer", "editview",
    "customfunctionview", "searchbar",
)


def patch_cands_ini(fp: str):
    """候选栏/工具条底色改成透明，让位图自己说话（与模块里 unifyBg 的思路一致）。"""
    if not os.path.exists(fp):
        print("  cands.ini 不存在，跳过：%s" % fp)
        return
    src = open(fp, encoding="utf-8", errors="replace").read().splitlines()
    out, section, changed = [], "", 0
    for line in src:
        s = line.strip()
        if s.startswith("[") and s.endswith("]"):
            section = s[1:-1].strip().lower()
        if section in BAR_SECTIONS and "=" in line and not s.startswith(";") and not s.startswith("#"):
            k = line.partition("=")[0]
            nk = re.sub(r"\s+", "", k)
            if nk in ("BG_COLOR", "BG_COLOR_PRESSED"):
                line = "%s=0x00000000" % k
                changed += 1
        out.append(line)
    open(fp, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print("  cands.ini 改写 %d 行" % changed)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", required=True, help="assets/theme/theme_default 目录")
    ap.add_argument("--preset", default="ios", choices=sorted(PRESETS))
    ap.add_argument("--mode", required=True, choices=["light", "dark", "colors", "all"])
    ap.add_argument("--res-dir", default="1080/res")
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()

    theme = a.theme
    p_light = PRESETS[a.preset]["light"]
    p_dark = PRESETS[a.preset]["dark"]

    if a.mode in ("light", "all"):
        print("[浅色] %s" % os.path.join(theme, a.res_dir, "white"))
        process_dir(os.path.join(theme, a.res_dir, "white"), p_light, a.dry)
    if a.mode in ("dark", "all"):
        print("[深色] %s" % os.path.join(theme, a.res_dir, "black"))
        process_dir(os.path.join(theme, a.res_dir, "black"), p_dark, a.dry)
        patch_phone_skin(os.path.join(theme, a.res_dir, "black", "phoneSkin.ini"), p_dark)
    if a.mode in ("colors", "all"):
        print("[文字色] colors.ini")
        patch_colors_ini(os.path.join(theme, "1080", "layout", "colors.ini"), p_light, False)
        patch_cands_ini(os.path.join(theme, "1080", "layout", "cands.ini"))


if __name__ == "__main__":
    main()
