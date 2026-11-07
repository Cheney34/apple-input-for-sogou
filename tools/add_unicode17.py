#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Unicode 17.0（Emoji 17.0）新增的表情补进 manifests/ 里的 10 份 info.xml。

为什么要有这个脚本：
  manifests 是从 Emoji 16.0 的清单手工整理出来的静态文件，新增表情没有
  「自动跟随上游」的机制。每次 Unicode 发新版都得往里补条目，手改 2000 多行
  XML 容易写错 id / unified，所以把规则固化成脚本，幂等、可复查。

规则：
  · 新增条目追加在所属分组末尾，`name` 从全局最大值 +1 开始顺序分配
    （现有 id 是 20000..22066 连续区段，分组顺序：笑脸→人物→组件→动物→
     食物→旅行→活动→物品→符号→旗帜）。
  · `unified` 单码位的按现有惯例补 `_FE0F`；ZWJ 序列用 Unicode 的 RGI 形式。
  · 支持肤色的条目 `supportSkin="1"`，由 tools/make_payload.py 展开 5 档。

数据来源：Unicode 17.0 / Emoji 17.0（2025-09 发布），
  新增 7 个基字符 + 芭蕾舞者 + 戴兔耳朵的人 / 摔跤选手的肤色变体。
  中文名对照 https://www.emojiall.com/zh-hans/emoji-version/17.0

用法：
  python3 tools/add_unicode17.py            # 干跑，只打印将要改动的内容
  python3 tools/add_unicode17.py --write    # 真正写入
"""
import argparse
import os
import re
import sys

MARK = 'unicode-17'  # 写进条目注释用不到，这里只作幂等标记（按 unified 判重）

# 分组目录 → [(unified, 英文名, 是否支持肤色, 备注)]
NEW = {
    "3_9000000010000": [  # 笑脸
        ("1FAEA_FE0F", "distorted face", 0, "变形的脸"),
        ("1FAEF_FE0F", "fight cloud", 0, "打斗云团"),
    ],
    "4_9000000009000": [  # 人物
        ("1FAC8_FE0F", "hairy creature", 0, "毛怪"),
        ("1F9D1_200D_1FA70", "ballet dancer", 1, "芭蕾舞者"),
    ],
    "6_9000000006000": [  # 动物与自然
        ("1FACD_FE0F", "orca", 0, "虎鲸"),
    ],
    "7_9000000005000": [  # 食物与饮品
        # Emoji 17.0 定稿里去掉了 1FADD 苹果核，这里保持空列表，留个说明
    ],
    "8_9000000004000": [  # 旅行与地点
        ("1F6D8_FE0F", "landslide", 0, "山体滑坡"),
    ],
    "10_9000000002000": [  # 物品
        ("1FA8A_FE0F", "trombone", 0, "长号"),
        ("1FA8E_FE0F", "treasure chest", 0, "宝箱"),
    ],
}

# 17.0 给这 6 个老表情补了肤色变体，原清单是 supportSkin="0"
NOW_SKINNABLE = [
    "1F46F_FE0F",              # 戴兔耳朵的人
    "1F46F_200D_2642_FE0F",    # 兔先生
    "1F46F_200D_2640_FE0F",    # 兔女郎
    "1F93C_FE0F",              # 摔跤选手
    "1F93C_200D_2642_FE0F",    # 男生摔跤
    "1F93C_200D_2640_FE0F",    # 女生摔跤
]

EXP_RE = re.compile(r'<exp name="(\d+)"[^/]*unified="([^"]+)"[^/]*/>')
ID_START = 22067  # 现有清单最大 id 22066 的下一个


def snippet(name, unified, other, skin):
    return ('    <exp name="%d" other="[%s]" pinyin="" unified="%s" softbank="" '
            'supportSkin="%d" FE0F="0" currentColor="0" hasSelectedColor="0"/>'
            % (name, other, unified, skin))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifests", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "manifests"))
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    if not os.path.isdir(a.manifests):
        sys.exit("找不到 manifests 目录: %s" % a.manifests)

    files = {}
    used_ids, used_uni = set(), set()
    for d in sorted(os.listdir(a.manifests)):
        p = os.path.join(a.manifests, d, "info.xml")
        if not os.path.isfile(p):
            continue
        s = open(p, encoding="utf-8").read()
        files[d] = s
        for m in EXP_RE.finditer(s):
            used_ids.add(int(m.group(1)))
            used_uni.add(m.group(2))

    nxt = max(ID_START, max(used_ids) + 1)
    added, flipped, skipped = 0, 0, 0

    for d, items in NEW.items():
        s = files[d]
        ins = []
        for unified, other, skin, cn in items:
            if unified in used_uni:
                skipped += 1
                continue
            line = snippet(nxt, unified, other, skin)
            ins.append(line)
            print("+ %s  %-28s %-18s %s" % (d, unified, other, cn))
            used_ids.add(nxt)
            used_uni.add(unified)
            nxt += 1
            added += 1
        if ins:
            idx = s.rfind("  </exppkg>")
            if idx < 0:
                sys.exit("%s 结构异常：找不到 </exppkg>" % d)
            eol = "\r\n" if "\r\n" in s else "\n"  # 原文件是 CRLF，别整篇改成 LF
            s = s[:idx] + eol.join(ins) + eol + s[idx:]
            files[d] = s

    # 给老条目打开肤色支持
    for d in files:
        s = files[d]
        for uni in NOW_SKINNABLE:
            old = ('unified="%s" softbank="" supportSkin="0"' % uni)
            if old in s:
                s = s.replace(old, 'unified="%s" softbank="" supportSkin="1"' % uni)
                flipped += 1
                print("~ %s  %-28s supportSkin 0 -> 1" % (d, uni))
        files[d] = s

    print("\n新增 %d 条，打开肤色 %d 条，跳过已存在 %d 条" % (added, flipped, skipped))
    if not a.write:
        print("干跑结束，加 --write 才写盘")
        return

    for d, s in files.items():
        p = os.path.join(a.manifests, d, "info.xml")
        open(p, "w", encoding="utf-8", newline="").write(s)
    print("已写入 %d 个 info.xml" % len(files))


if __name__ == "__main__":
    main()
