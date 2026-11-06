#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 assets 清单 + 本地 Apple Emoji 素材，重新生成 LSPosed 模块用的 payload.zip。

为什么要这个脚本：
  payload.zip 里是 Apple 彩色 emoji 位图，属 Apple 的美术资源，不随本仓库分发。
  仓库只保存 10 份 info.xml（纯文本清单，无版权问题），
  运行时由本脚本从你本机的素材目录生成完整的 zip。

用法：
  python3 tools/make_payload.py \
      --src /path/to/apple_emoji_png \
      --out assets/payload.zip

素材目录的两种常见命名都支持（脚本内部统一处理）：
  · emoji_u1f600.png / emoji_u1f1e8_1f1e6.png   下划线分隔，带 emoji_u 前缀
  · 1f600.png / 1f1e8-1f1e6.png                 连字符分隔，无前缀
    （后者即 npm i emoji-datasource-apple 里的 img/apple/64 目录）

尺寸不限，脚本统一缩到 64x64（与搜狗 emojipackage 的原始规格一致）。
"""
import argparse
import io
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow: pip install pillow")

SIZE = 64  # 搜狗 emojipackage 的位图规格
TONES = ("1F3FB", "1F3FC", "1F3FD", "1F3FE", "1F3FF")  # 5 档肤色


PREFIX_RE = re.compile(r"^emoji_u", re.I)


def norm_key(name):
    """把各种命名统一成 `1f600_fe0f_20e3` 这种形式（无前缀、无扩展名、下划线）。"""
    k = name.strip().lower()
    if k.endswith(".png"):
        k = k[:-4]
    return PREFIX_RE.sub("", k).replace("-", "_")


def candidates(unified):
    """把一个 unified 串展开成可能的素材 key（按优先级）。"""
    u = norm_key(unified)
    out = [u]
    # 单码位补过 FE0F 的，素材里通常没有 _fe0f 后缀
    if u.endswith("_fe0f"):
        out.append(u[:-5])
    # 组合序列中间带 FE0F 的情况
    if "_fe0f_" in u:
        out.append(u.replace("_fe0f_", "_"))
        out.append(u.replace("_fe0f", ""))
    return list(dict.fromkeys(out))


def skin_variants(unified):
    """返回 [(序号, 候选文件名列表)]。

    两种情形：
      · 普通表情（肤色插在第一个码位后）：1F44B_FE0F → 1F44B_1F3FB_FE0F
      · 本身就带肤色的（如「手拉手」= 人+肤色+ZWJ+握+ZWJ+人+肤色），
        序号 i 表示把序列里所有肤色统一替换成第 i 档。
    """
    parts = unified.upper().split("_")
    if not parts:
        return []
    has_tone = any(p in TONES for p in parts)
    out = []
    for i, tone in enumerate(TONES, start=1):
        if has_tone:
            seq = [tone if p in TONES else p for p in parts]
        else:
            seq = [parts[0], tone] + parts[1:]
        out.append((i, candidates("_".join(seq))))
    return out


def index_source(src):
    """key（规范化后的码位串）→ 真实文件名。"""
    idx = {}
    for f in os.listdir(src):
        if f.lower().endswith(".png"):
            idx[norm_key(f)] = f
    return idx


def write_one(z, src, have, arcname, cands):
    """按候选名找到素材、缩到 SIZE、写进 zip。找不到返回 False。"""
    real = None
    for cand in cands:
        if cand in have:
            real = have[cand]
            break
    if real is None:
        return False
    im = Image.open(os.path.join(src, real)).convert("RGBA")
    if im.size != (SIZE, SIZE):
        im = im.resize((SIZE, SIZE), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    z.writestr(arcname, buf.getvalue())
    return True


VERSION_XML = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?><Version text="{v}" />"""


def build(src, out, manifests, icons, version):
    have = index_source(src)
    groups = sorted(
        d for d in os.listdir(manifests)
        if os.path.isdir(os.path.join(manifests, d))
    )
    if not groups:
        sys.exit(f"{manifests} 下没有分组目录")

    total = miss = 0
    missing = []
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        zi = zipfile.ZipInfo("emojipackage/")
        zi.external_attr = 0o40755 << 16
        z.writestr(zi, b"")

        for g in groups:
            gdir = os.path.join(manifests, g)
            xml_path = os.path.join(gdir, "info.xml")
            if not os.path.isfile(xml_path):
                print(f"  跳过 {g}（无 info.xml）")
                continue

            z.writestr(f"emojipackage/{g}/", b"")
            z.writestr(f"emojipackage/{g}/emoji/", b"")
            z.write(xml_path, f"emojipackage/{g}/info.xml")

            # 分组封面（底部 tab 用的小图标）+ 版本声明
            pkg_type = ET.parse(xml_path).getroot().iter("exppkg").__next__().get("type")
            cover = os.path.join(icons, f"{pkg_type}.png")
            if os.path.isfile(cover):
                z.write(cover, f"emojipackage/{g}/{pkg_type}.png")
            else:
                print(f"  {g} 缺封面 icons/{pkg_type}.png")
            z.writestr(f"emojipackage/{g}/version.json", VERSION_XML.format(v=version))

            root = ET.parse(xml_path).getroot()
            for exp in root.iter("exp"):
                unified = exp.get("unified")
                name = exp.get("name")
                if not unified or not name:
                    continue
                total += 1
                written = write_one(z, src, have, f"emojipackage/{g}/emoji/{name}.png", candidates(unified))
                if not written:
                    miss += 1
                    missing.append((g, name, unified))

                # 支持肤色的表情还要写 _1 ~ _5 五个变体
                if exp.get("supportSkin") == "1":
                    for idx, cands in skin_variants(unified):
                        path = f"emojipackage/{g}/emoji/{name}_{idx}.png"
                        write_one(z, src, have, path, cands)

    print(f"分组 {len(groups)} 个，表情 {total} 条，写入 {total - miss} 张，缺 {miss} 张")
    if missing:
        print("缺失清单（前 20）:")
        for g, n, u in missing[:20]:
            print(f"  {g}/{n}  unified={u}")
        print("—— 这些条目的 xml 仍保留，搜狗会显示为空白占位。")
    size = os.path.getsize(out) / 1048576
    print(f"输出 {out}  {size:.1f} MB")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Apple emoji PNG 素材目录")
    ap.add_argument("--out", default=os.path.join(root, "assets", "payload.zip"))
    ap.add_argument("--manifests", default=os.path.join(root, "manifests"))
    ap.add_argument("--icons", default=os.path.join(root, "icons"))
    ap.add_argument("--version", default="16.0", help="写进每组 version.json 的版本串")
    a = ap.parse_args()

    if not os.path.isdir(a.src):
        sys.exit(f"素材目录不存在: {a.src}")
    build(a.src, a.out, a.manifests, a.icons, a.version)


if __name__ == "__main__":
    main()
