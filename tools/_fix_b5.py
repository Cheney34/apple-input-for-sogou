# -*- coding: utf-8 -*-
"""v2.0.3-beta5：皮肤拆三层独立开关；资源层（H50）默认关。
小米/官方只用类名层（H40 ini + H41 位图）；OPPO/vivo 才需要 H50，手动开并自担风险。"""
import io, os, re

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

def indent_of(line):
    return line[:len(line) - len(line.lstrip())]

def insert_after(p, needle, new_lines, expect=1):
    """needle: 与该行 strip() 后完全相等；插入内容按该行缩进补齐。"""
    s = read(p); n = nl(p)
    lines = s.split(n)
    idx = [i for i, L in enumerate(lines) if L.strip() == needle]
    if len(idx) != expect:
        raise SystemExit("MISS-INSERT %s %r (%d)" % (os.path.basename(p), needle, len(idx)))
    i = idx[-1]; ind = indent_of(lines[i])
    block = [(ind + x) if x else "" for x in new_lines]
    lines[i + 1:i + 1] = block
    write(p, n.join(lines)); print("ok  ins %-22s %s" % (os.path.basename(p), needle[:48]))

def replace_block(p, old, new):
    s = read(p)
    if s.count(old) != 1:
        raise SystemExit("MISS-REPL %s (%d) @ %r" % (os.path.basename(p), s.count(old), old[:60]))
    write(p, s.replace(old, new, 1)); print("ok  rep %-22s %s" % (os.path.basename(p), old.splitlines()[0].strip()[:48]))

# ---------- 1. ModuleConfig ----------
mc = os.path.join(SRC, r"config\ModuleConfig.java"); m = nl(mc)
insert_after(mc,
    '/** 资源层是否连位图一起重画（默认关：重编码会丢 nine-patch 数据，风险高）。 */',
    ['/** 资源层兜底（H50）总开关：默认关。小米/官方走类名层即可，OPPO/vivo 才需要它。 */',
     'public static final String KEY_ASSET_SKIN = "asset_skin_enabled";',
     ''])
replace_block(mc,
    "    public static boolean assetSkinImagesEnabled() {" + m +
    "        return themeSkinEnabled() && getBoolean(KEY_ASSET_SKIN_IMAGES, false);" + m +
    "    }",
    "    /** 资源层兜底（H50）：进程级拦 AssetManager，默认关，改坏就会「键盘呼不出来」。 */" + m +
    "    public static boolean assetSkinEnabled() {" + m +
    "        return themeSkinEnabled() && getBoolean(KEY_ASSET_SKIN, false);" + m +
    "    }" + m + m +
    "    public static boolean assetSkinImagesEnabled() {" + m +
    "        return assetSkinEnabled() && getBoolean(KEY_ASSET_SKIN_IMAGES, false);" + m +
    "    }")

# ---------- 2. ConfigValues ----------
cv = os.path.join(SRC, r"config\ConfigValues.java")
insert_after(cv, "ModuleConfig.KEY_THEME_SKIN,", ["ModuleConfig.KEY_ASSET_SKIN,"])
insert_after(cv, "case ModuleConfig.KEY_THEME_SKIN:", ["case ModuleConfig.KEY_ASSET_SKIN:"])
# label 分支：第二个 KEY_THEME_SKIN case（label）后面加一个
s = read(cv); n = nl(cv); lines = s.split(n)
idxs = [i for i, L in enumerate(lines) if L.strip() == "case ModuleConfig.KEY_THEME_SKIN:"]
assert len(idxs) == 2, idxs
i = idxs[1]; ind = indent_of(lines[i])
lines[i + 1:i + 1] = [ind + "case ModuleConfig.KEY_ASSET_SKIN:"]
write(cv, n.join(lines)); print("ok  ins ConfigValues label case")

# ---------- 3. RuntimeConfig ----------
insert_after(os.path.join(SRC, r"config\RuntimeConfig.java"),
             'return "skin_on";', ['case ModuleConfig.KEY_ASSET_SKIN:', '    return "asset";'])

# ---------- 4. AssetSkinHook ----------
ash = os.path.join(SRC, r"hook\AssetSkinHook.java"); a = nl(ash)
replace_block(ash,
    "            if (!ModuleConfig.themeSkinEnabled()) return;",
    "            if (!ModuleConfig.assetSkinEnabled()) return;")
replace_block(ash,
    '        if (IMG_SKIPPED.get() > 0) sb.append("/skip").append(IMG_SKIPPED.get());',
    '        if (IMG_SKIPPED.get() > 0) sb.append("/skip").append(IMG_SKIPPED.get());' + a +
    '        if (INI_SKIPPED.get() > 0) sb.append("/iniskip").append(INI_SKIPPED.get());')
insert_after(ash, "private static final AtomicInteger IMG_SKIPPED = new AtomicInteger();",
             ["private static final AtomicInteger INI_SKIPPED = new AtomicInteger();"])
insert_after(ash, "/** 只认皮肤相关资源，别去碰 emoji / lottie / 字体。 */",
             ["/**",
              " * ini 白名单：只碰纯颜色/候选样式的文件。",
              " * file_name_mapping_*.ini、thememanifest.ini、theme.ini、layout/template.ini、keys.ini、",
              " * texts.ini、images_config.ini、image_list.ini 都是结构性文件（映射/模板/九宫格切边），",
              " * 改写它们会让主题解析失败、键盘直接起不来 —— 一律不碰。",
              " */",
              'private static final String[] INI_WHITELIST = {',
              '"colors.ini", "cands.ini", "cands_outer.ini", "phoneskin.ini"',
              '};',
              '',
              'private static boolean isColorIni(String path) {',
              'if (path == null) return false;',
              'String n = path.toLowerCase(java.util.Locale.ROOT);',
              'int i = n.lastIndexOf(\'/\');',
              'if (i >= 0) n = n.substring(i + 1);',
              'for (String w : INI_WHITELIST) {',
              'if (n.equals(w)) return true;',
              '}',
              'return false;',
              '}',
              ''])
replace_block(ash,
    "            if (isIni(path)) {" + a +
    "                out = patchIniBytes(path, raw, presetId);",
    "            if (isIni(path) && !isColorIni(path)) {" + a +
    "                INI_SKIPPED.incrementAndGet();" + a +
    "                return;" + a +
    "            }" + a +
    "            if (isIni(path)) {" + a +
    "                out = patchIniBytes(path, raw, presetId);")

# ---------- 5. SettingsActivity ----------
insert_after(os.path.join(SRC, "SettingsActivity.java"),
    'addSwitch(look, ModuleConfig.KEY_ASSET_SKIN_IMAGES, "资源层改位图（实验）",',
    ['addSwitch(look, ModuleConfig.KEY_ASSET_SKIN, "资源层兜底（H50，OPPO/vivo 才需要）",',
     '        "直接拦 AssetManager 读皮肤文件，给「类名被重打包」的渠道兜底。"',
     '                + "小米/官方请保持关闭：它是进程级拦截，改坏就会「键盘呼不出来」。",',
     '        true);'])

# ---------- 6. 版本 ----------
sub_pairs = [
    (os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"), 'android:versionCode="44"', 'android:versionCode="45"'),
    (os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"), 'android:versionName="2.0.3-beta4"', 'android:versionName="2.0.3-beta5"'),
    (os.path.join(ROOT, r"lsposed\app\build.gradle.kts"), "versionCode = 44", "versionCode = 45"),
    (os.path.join(ROOT, r"lsposed\app\build.gradle.kts"), 'versionName = "2.0.3-beta4"', 'versionName = "2.0.3-beta5"'),
    (os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
     '<string name="version_label">v2.0.3-beta4</string>', '<string name="version_label">v2.0.3-beta5</string>'),
]
for p, o, nn in sub_pairs:
    replace_block(p, o, nn)
print("ALL OK")
