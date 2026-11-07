# -*- coding: utf-8 -*-
import io, os
ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
SRC = os.path.join(ROOT, r"lsposed\app\src\main\java\com\chiron\iosemoji")

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f: return f.read()
def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f: f.write(s)
def nl(p): return "\r\n" if "\r\n" in read(p) else "\n"

# 1) AssetSkinHook: 补 INI_WHITELIST + isColorIni
ash = os.path.join(SRC, r"hook\AssetSkinHook.java"); a = nl(ash); s = read(ash)
assert "INI_WHITELIST" not in s
anchor = "    /** 只认皮肤相关资源，别去碰 emoji / lottie / 字体。 */"
assert s.count(anchor) == 1, s.count(anchor)
block = a.join([
    "    /**",
    "     * ini 白名单：只碰纯颜色/候选样式的文件。",
    "     * file_name_mapping_*.ini、thememanifest.ini、theme.ini、layout/template.ini、keys.ini、",
    "     * texts.ini、images_config.ini、image_list.ini 都是<b>结构性</b>文件（映射/模板/九宫格切边），",
    "     * 改写它们会让主题解析失败、键盘直接起不来 —— 一律不碰。",
    "     */",
    "    private static final String[] INI_WHITELIST = {",
    '            "colors.ini", "cands.ini", "cands_outer.ini", "phoneskin.ini"',
    "    };",
    "",
    "    private static boolean isColorIni(String path) {",
    "        if (path == null) return false;",
    "        String n = path.toLowerCase(java.util.Locale.ROOT);",
    "        int i = n.lastIndexOf('/');",
    "        if (i >= 0) n = n.substring(i + 1);",
    "        for (String w : INI_WHITELIST) {",
    "            if (n.equals(w)) return true;",
    "        }",
    "        return false;",
    "    }",
    "",
])
s = s.replace(anchor, block + anchor, 1)
write(ash, s); print("ok  AssetSkinHook INI_WHITELIST")

# 2) ConfigValues.label 补 ASSET_SKIN
cv = os.path.join(SRC, r"config\ConfigValues.java"); c = nl(cv); t = read(cv)
anchor2 = '                return "全面屏底栏";'
assert t.count(anchor2) == 1, t.count(anchor2)
add = (anchor2 + c +
       "            case ModuleConfig.KEY_ASSET_SKIN:" + c +
       '                return "资源层兜底（H50）";')
t = t.replace(anchor2, add, 1)
write(cv, t); print("ok  ConfigValues label ASSET_SKIN")
print("DONE")
