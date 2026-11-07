# -*- coding: utf-8 -*-
"""beta5 续跑（幂等）：补齐 ConfigValues / RuntimeConfig / AssetSkinHook / SettingsActivity / 版本号。"""
import io, os

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
SRC = os.path.join(ROOT, r"lsposed\app\src\main\java\com\chiron\iosemoji")

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def nl(p):
    return "\r\n" if "\r\n" in read(p) else "\n"

def lines_of(p):
    return read(p).split(nl(p))

def save_lines(p, L):
    write(p, nl(p).join(L))

def indent_of(L):
    return L[:len(L) - len(L.lstrip())]

def add_case(p, marker, new_cases, before=None):
    """在 switch 里 marker 那一行之后（或 before 那行之前）插入 case 行。幂等。"""
    L = lines_of(p); ind = None
    if any(x.strip() in new_cases for x in L):
        print("skip", os.path.basename(p), new_cases[0].strip()); return
    tgt = [i for i, x in enumerate(L) if x.strip() == marker]
    if not tgt:
        raise SystemExit("MISS marker %r in %s" % (marker, p))
    i = tgt[0]; ind = indent_of(L[i])
    L[i + 1:i + 1] = [ind + c for c in new_cases]
    save_lines(p, L); print("ok  add", os.path.basename(p), new_cases[0].strip()[:46])

def add_before(p, marker, new_lines):
    L = lines_of(p)
    if any(x.strip() in new_lines for x in L):
        print("skip", os.path.basename(p), new_lines[0].strip()); return
    tgt = [i for i, x in enumerate(L) if x.strip() == marker]
    if not tgt:
        raise SystemExit("MISS before %r in %s" % (marker, p))
    i = tgt[0]; ind = indent_of(L[i])
    L[i:i] = [(ind + x) if x else "" for x in new_lines]
    save_lines(p, L); print("ok  bef", os.path.basename(p), new_lines[0].strip()[:46])

# ---- ConfigValues ----
cv = os.path.join(SRC, r"config\ConfigValues.java")
add_before(cv, "case ModuleConfig.KEY_ASSET_SKIN_IMAGES:",
           ["case ModuleConfig.KEY_ASSET_SKIN:"])          # defaultValue → false 组
add_case(cv, 'return "全面屏底栏";',
         ['case ModuleConfig.KEY_ASSET_SKIN:', 'return "资源层兜底（H50）";'])

# ---- RuntimeConfig ----
add_case(os.path.join(SRC, r"config\RuntimeConfig.java"), 'return "skin_on";',
         ['case ModuleConfig.KEY_ASSET_SKIN:', 'return "asset";'])

# ---- ModuleConfig 自检 ----
mcs = read(os.path.join(SRC, r"config\ModuleConfig.java"))
assert 'KEY_ASSET_SKIN = "asset_skin_enabled"' in mcs, "ModuleConfig 缺 KEY_ASSET_SKIN"
assert "public static boolean assetSkinEnabled()" in mcs, "ModuleConfig 缺 assetSkinEnabled"
print("ok  check ModuleConfig")

# ---- AssetSkinHook ----
ash = os.path.join(SRC, r"hook\AssetSkinHook.java"); a = nl(ash); s = read(ash)
if "if (!ModuleConfig.assetSkinEnabled()) return;" not in s:
    old = "            if (!ModuleConfig.themeSkinEnabled()) return;"
    assert s.count(old) == 1
    s = s.replace(old, "            if (!ModuleConfig.assetSkinEnabled()) return;")
    write(ash, s); print("ok  gate assetSkinEnabled")
else:
    print("skip gate")

if "INI_SKIPPED" not in read(ash):
    add_case(ash, "private static final AtomicInteger IMG_SKIPPED = new AtomicInteger();",
             ["private static final AtomicInteger INI_SKIPPED = new AtomicInteger();"])
    L = lines_of(ash)
    for i, x in enumerate(L):
        if x.strip().startswith('if (IMG_SKIPPED.get() > 0) sb.append("/skip")'):
            ind = indent_of(L[i])
            L[i + 1:i + 1] = [ind + 'if (INI_SKIPPED.get() > 0) sb.append("/iniskip").append(INI_SKIPPED.get());']
            break
    save_lines(ash, L); print("ok  stateFields iniskip")

s = read(ash)
if "INI_WHITELIST" not in s:
    add_before(ash, "/** 只认皮肤相关资源，别去碰 emoji / lottie / 字体。 */",
               ["/**",
                " * ini 白名单：只碰纯颜色/候选样式的文件。",
                " * file_name_mapping_*.ini、thememanifest.ini、theme.ini、layout/template.ini、keys.ini、",
                " * texts.ini、images_config.ini、image_list.ini 都是结构性文件（映射/模板/九宫格切边），",
                " * 改写它们会让主题解析失败、键盘直接起不来 —— 一律不碰。",
                " */",
                "private static final String[] INI_WHITELIST = {",
                '"colors.ini", "cands.ini", "cands_outer.ini", "phoneskin.ini"',
                "};",
                "",
                "private static boolean isColorIni(String path) {",
                "if (path == null) return false;",
                "String n = path.toLowerCase(java.util.Locale.ROOT);",
                "int i = n.lastIndexOf('/');",
                "if (i >= 0) n = n.substring(i + 1);",
                "for (String w : INI_WHITELIST) {",
                "if (n.equals(w)) return true;",
                "}",
                "return false;",
                "}",
                ""])

s = read(ash)
if "isColorIni(path)" not in s:
    old = "            if (isIni(path)) {" + a + "                out = patchIniBytes(path, raw, presetId);"
    assert s.count(old) == 1, s.count(old)
    new = ("            if (isIni(path) && !isColorIni(path)) {" + a +
           "                INI_SKIPPED.incrementAndGet();" + a +
           "                return;" + a +
           "            }" + a + old)
    write(ash, s.replace(old, new, 1)); print("ok  ini whitelist gate")
else:
    print("skip ini gate")

# ---- SettingsActivity ----
add_before(os.path.join(SRC, "SettingsActivity.java"),
           'addSwitch(look, ModuleConfig.KEY_ASSET_SKIN_IMAGES, "资源层改位图（实验）",',
           ['addSwitch(look, ModuleConfig.KEY_ASSET_SKIN, "资源层兜底（H50，OPPO/vivo 才需要）",',
            '        "直接拦 AssetManager 读皮肤文件，给「类名被重打包」的渠道兜底。"',
            '                + "小米/官方请保持关闭：它是进程级拦截，改坏就会「键盘呼不出来」。",',
            '        true);'])

# ---- 版本 ----
for p, o, n2 in [
    (os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"), 'android:versionCode="44"', 'android:versionCode="45"'),
    (os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"), 'android:versionName="2.0.3-beta4"', 'android:versionName="2.0.3-beta5"'),
    (os.path.join(ROOT, r"lsposed\app\build.gradle.kts"), "versionCode = 44", "versionCode = 45"),
    (os.path.join(ROOT, r"lsposed\app\build.gradle.kts"), 'versionName = "2.0.3-beta4"', 'versionName = "2.0.3-beta5"'),
    (os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
     '<string name="version_label">v2.0.3-beta4</string>', '<string name="version_label">v2.0.3-beta5</string>'),
]:
    s = read(p)
    if o in s:
        write(p, s.replace(o, n2, 1)); print("ok  ver", os.path.basename(p), o[-18:])
    else:
        print("skip ver", os.path.basename(p), o[-18:])
print("ALL OK")
