# -*- coding: utf-8 -*-
"""beta8：把所有「条」的底色统一成键盘底色（一个颜色，完全一样）"""
import io, os

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
SRC = os.path.join(ROOT, r"lsposed\app\src\main\java\com\chiron\iosemoji")

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def rep(p, pairs):
    t = read(p)
    for old, new in pairs:
        if t.count(old) != 1:
            raise SystemExit("MISS %s (%d): %r" % (os.path.basename(p), t.count(old), old[:80]))
        t = t.replace(old, new, 1)
    write(p, t)
    print("ok", os.path.basename(p))

# 1) SkinPreset：barBg 恒等于 kbBg
rep(os.path.join(SRC, r"skin\SkinPreset.java"), [
 ("    public final int barBg;         // 候选栏 / 顶部工具条底色（可与键盘底色不同）",
  "    public final int barBg;         // 候选栏 / 顶部工具条底色（v2.0.3-beta8 起恒等于 kbBg：要「完全一样的底色」）"),
 ("        this.barBg = barBg;",
  "        // v2.0.3-beta8：用户要求工具栏/候选条与键盘底**完全同色**，这里统一收敛到 kbBg，\n"
  .replace("\n", "\r\n" if "\r\n" in read(os.path.join(SRC, r"skin\SkinPreset.java")) else "\n") +
  "        // 传进来的 barBg 只作历史兼容，不再生效。\n".replace("\n", "\r\n" if "\r\n" in read(os.path.join(SRC, r"skin\SkinPreset.java")) else "\n") +
  "        this.barBg = kbBg;"),
])

# 2) IosSkin：bar 段落 / bar 文字对比度 / bar 底图全部用 kbBg
rep(os.path.join(SRC, r"skin\IosSkin.java"), [
 ("int accentOnBar = ensureContrast(p.accent, p.barBg, 3.0);",
  "int accentOnBar = ensureContrast(p.accent, p.kbBg, 3.0);"),
 ("int barText = ensureContrast(p.text, p.barBg, 3.0);",
  "int barText = ensureContrast(p.text, p.kbBg, 3.0);"),
 ("changed |= unifyBg(sections, name, p.barBg);",
  "changed |= unifyBg(sections, name, p.kbBg);"),
 ("        return isBarImage(fileName) ? p.barBg : p.kbBg;",
  "        // v2.0.3-beta8：条与键盘底同色，不再区分。\n"
  .replace("\n", "\r\n" if "\r\n" in read(os.path.join(SRC, r"skin\IosSkin.java")) else "\n") +
  "        return p.kbBg;".replace("\n", "\r\n" if "\r\n" in read(os.path.join(SRC, r"skin\IosSkin.java")) else "\n")),
])

# 3) 底栏（H61）：也用 kbBg
rep(os.path.join(SRC, r"hook\MiuiBottomBarHook.java"), [
 ("        int bg = p.barBg != 0 && !FORCE_TRANSPARENT ? p.barBg : p.kbBg;",
  "        int bg = p.kbBg;   // v2.0.3-beta8：底栏与键盘底完全同色"),
])

# 4) 版本 → 48 / 2.0.3-beta8
n = "\r\n" if "\r\n" in read(os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml")) else "\n"
rep(os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"),
    [('android:versionCode="47"', 'android:versionCode="48"'),
     ('android:versionName="2.0.3-beta7"', 'android:versionName="2.0.3-beta8"')])
rep(os.path.join(ROOT, r"lsposed\app\build.gradle.kts"),
    [("versionCode = 47", "versionCode = 48"),
     ('versionName = "2.0.3-beta7"', 'versionName = "2.0.3-beta8"')])
rep(os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
    [("v2.0.3-beta7", "v2.0.3-beta8")])
print("ALL OK")
