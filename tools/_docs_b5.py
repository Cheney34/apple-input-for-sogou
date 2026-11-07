# -*- coding: utf-8 -*-
import io, os
ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f: return f.read()
def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f: f.write(s)
def nl(p): return "\r\n" if "\r\n" in read(p) else "\n"

# ---------- README ----------
rp = os.path.join(ROOT, "README.md"); r = read(rp); n = nl(rp); L = r.split(n)
i4 = next(i for i, x in enumerate(L) if "releases/tag/v2.0.3-beta4" in x) if any("releases/tag/v2.0.3-beta4" in x for x in L) else None
row = ("| [**v2.0.3-beta5**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta5) "
       "| 小米定制版（推荐） "
       "| **皮肤拆成三层独立开关**：类名层（H40 ini + H41 位图，默认开，小米/官方只需这层）/ 资源层 ini（H50，**默认关**）/ 资源层位图（默认关）。"
       "H50 只碰 `colors.ini`/`cands.ini`/`cands_outer.ini`/`phoneSkin.ini` 四个纯颜色文件，"
       "结构性 ini（mapping/thememanifest/theme/template/keys/texts/images_config/image_list）一律不碰；"
       "修 beta3「只开改皮肤也呼不出键盘」|")
idx_b3 = next(i for i, x in enumerate(L) if "releases/tag/v2.0.3-beta3" in x)
L.insert(idx_b3, row)
L[idx_b3 + 1] = L[idx_b3 + 1].replace("| 存档（H60 可能影响键盘唤起） |", "| 存档（H50 动到结构性 ini，可能导致键盘呼不出来） |")
# 开关表：追加 asset_skin_enabled
for i, x in enumerate(L):
    if x.startswith("| `asset_skin_images_enabled` |"):
        L.insert(i, ("| `asset_skin_enabled` | **关** | **资源层兜底（H50）**：进程级拦 `AssetManager`，只给「类名被重打包」的 "
                     "OPPO/vivo 兜底；小米/官方保持关闭 |"))
        break
write(rp, n.join(L)); print("README ok")

# ---------- CHANGELOG ----------
cp = os.path.join(ROOT, "CHANGELOG.md"); c = read(cp); nc = nl(cp)
sec = nc.join([
    "## v2.0.3-beta5 — 皮肤拆三层开关：资源层（H50）默认关",
    "",
    "基线：v2.0.3-beta4（`a207636`）。用户反馈：**即使只开「改皮肤」，键盘也呼不出来**。",
    "",
    "### 1. 结论：小米/官方根本不需要 H50",
    "alpha12 时期（只有 H40 ini + H41 位图）小米上皮肤是能用的；H50 是 v2.0.2-beta1 才加进来的",
    "「资源层兜底」，用来救类名被重打包的 OPPO/vivo。它 hook 的是 `AssetManager#open`，属于进程级拦截，",
    "一旦改到结构性文件，主题解析就失败 → 键盘视图建不起来。",
    "",
    "所以改成三层各自独立：",
    "",
    "| 层 | 开关 | 默认 | 小米/官方 | OPPO/vivo |",
    "| --- | --- | --- | --- | --- |",
    "| 类名层 ini（H40） | `theme_skin_enabled` | 开 | 需要 | 类名缺失，用不到 |",
    "| 类名层位图（H41） | `theme_skin_enabled` | 开 | 需要 | 类名缺失，用不到 |",
    "| 资源层 ini（H50） | `asset_skin_enabled` | **关** | 不需要 | 需要，手动开 |",
    "| 资源层位图 | `asset_skin_images_enabled` | 关 | 不需要 | 可选（nine-patch 有风险） |",
    "",
    "### 2. H50 只碰四个纯颜色文件",
    "新增 `INI_WHITELIST = {colors.ini, cands.ini, cands_outer.ini, phoneSkin.ini}`，",
    "其余 ini（`file_name_mapping_*.ini`、`thememanifest.ini`、`theme.ini`、`layout/template.ini`、`keys.ini`、",
    "`texts.ini`、`images_config.ini`、`image_list.ini`）**全部原样放过**并计入 `iniskip` 诊断 ——",
    "这些是映射/模板/九宫格切边定义，改一个字节就可能让主题解析炸掉。",
    "",
    "### 3. 构建与验证",
    "- `build/v203b5/AppleInputForSogou_v2.0.3-beta5.apk` 22,750,789 B；`versionCode 45` / `2.0.3-beta5`；签名同上（可覆盖升级）。",
    "- dex：116 个类全部 `Lcom/chiron/iosemoji/*`，非 chiron 0；串 `asset_skin_enabled`、`isColorIni`、`cands_outer.ini`、",
    "  `iniskip`、`isNinePatch` 均在包内。",
    "",
    "### 4. 装完请确认",
    "1. 设置页「资源层兜底（H50）」必须是**关**；「资源层改位图」也必须是**关**；",
    "2. 只留「改皮肤（推荐）」开，然后点「重启输入法」；",
    "3. 键盘应恢复正常且键帽变色；把 `skin= / skini= / skimg= / H41 bitmap getters hooked on N class(es)` 发我。",
    "",
    "",
])
write(cp, sec + c); print("CHANGELOG ok")
