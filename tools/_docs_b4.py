# -*- coding: utf-8 -*-
import io, os
ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"

def load(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def save(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

# ---------------- README ----------------
rp = os.path.join(ROOT, "README.md")
r = load(rp)
nl = "\r\n" if "\r\n" in r else "\n"
lines = r.split(nl)

beta3_idx = next(i for i, L in enumerate(lines) if "releases/tag/v2.0.3-beta3" in L)
row_b4 = ("| [**v2.0.3-beta4**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta4) "
          "| 小米定制版（紧急修复） "
          "| **修「键盘呼不出来」**：H50 绝不重编码 nine-patch（`.9.png` / 带 `npTc` 切边块的图，重编码会丢切边数据导致 Inflate 崩）；"
          "资源层改位图改为**默认关**（新开关 `asset_skin_images_enabled`，ini 颜色照改）；"
          "**H60 改默认关**并去掉对 `customizeBottomViewColor` 参数的改写（只观察 + 主动下发），加 3 次失败熔断 |")
# 原 beta3 行降级为存档
lines[beta3_idx] = lines[beta3_idx].replace("| 小米定制版 / 多定制版尝鲜 |",
                                            "| 存档（H60 可能影响键盘唤起） |")
lines.insert(beta3_idx, row_b4)

# 开关表：改默认值 + 追加新开关
for i, L in enumerate(lines):
    if L.startswith("| `miui_bottom_bar_enabled` |"):
        lines[i] = L.replace("| `miui_bottom_bar_enabled` | 开 |",
                             "| `miui_bottom_bar_enabled` | **关** |")
        note = ("| `asset_skin_images_enabled` | 关 | **资源层改位图**（H50 的位图部分）。"
                "默认关：`decode → compress` 会丢 nine-patch 切边数据，曾导致键盘呼不出来；"
                "位图交给类名层 H41 去做更安全 |")
        lines.insert(i + 1, note)
        break

r2 = nl.join(lines)
assert "v2.0.3-beta4" in r2 and "asset_skin_images_enabled" in r2
save(rp, r2)
print("README ok")

# ---------------- README：H50 铁律处补第 0 条 ----------------
r = load(rp); nl2 = "\r\n" if "\r\n" in r else "\n"; L2 = r.split(nl2)
for i, L in enumerate(L2):
    if L.startswith("H50 的三条铁律"):
        add = [
            "H50 的第 0 条铁律（v2.0.3-beta4 补，最要紧）：**绝不重编码 nine-patch**。"
            "`BitmapFactory.decodeByteArray → compress(PNG)` 之后，PNG 里的 `npTc`（九宫格切边）块就没了，"
            "系统 / 搜狗 Inflate 这张图时会直接抛异常 —— 现象就是「键盘呼不出来」。"
            "所以 `.9.png` 和任何带 `npTc` 块的图一律原样放过；资源层默认只改 ini 颜色，位图交给 H41。",
            "",
        ]
        L2[i:i] = add
        break
r = nl2.join(L2)
save(rp, r)
print("README rule ok")

# ---------------- CHANGELOG ----------------
cp = os.path.join(ROOT, "CHANGELOG.md")
c = load(cp); nc = "\r\n" if "\r\n" in c else "\n"
sec = nc.join([
    "## v2.0.3-beta4 — 紧急修复：键盘呼不出来（nine-patch 被重编码）+ H60 默认关",
    "",
    "基线：v2.0.3-beta3（`172474a`）。用户反馈「底栏颜色没应用，而且又无法呼出键盘了」。",
    "",
    "### 1. 键盘呼不出来：H50 重编码 nine-patch（根因）",
    "`hook/AssetSkinHook.java#patchImageBytes()` 原来是 `BitmapFactory.decodeByteArray` → `IosSkin.synth` →",
    "`out.compress(PNG, 100, bos)`。搜狗皮肤里的键帽底图大多是 **nine-patch**（`.9.png` / PNG 内嵌 `npTc` 块），",
    "重新编码会把切边数据整块丢掉 ⇒ 系统或搜狗 Inflate 这张图时抛异常 ⇒ 键盘视图建不起来。",
    "",
    "- 新增 `isNinePatch(path, raw)`：文件名字面 `.9.png`，或原始字节里出现 `npTc` 块，一律 `return null`（原样放过）；",
    "- 资源层位图改为**默认关**：新增开关 `asset_skin_images_enabled`（默认 `false`），"
    "H50 默认只兜底 **ini 颜色**，位图继续交给类名层 H41（那条路已验证可用、且不碰 nine-patch）；",
    "- 设置页新增「资源层改位图（实验）」开关，文案写明风险。",
    "",
    "### 2. H60 默认关 + 不再改 framework 调用参数",
    "上一版在 `customizeBottomViewColor` 的 before 里改写 `args[1..3]`，是 beta3 唯一触碰 framework 调用参数的地方，",
    "也是「键盘唤起失败」的次要嫌疑。现在：",
    "",
    "- 删除 before 改参，改成 **after 只观察**（计数用于诊断）+ `addMiuiBottomView` after 与 `setNavigationBarColor` after 主动下发；",
    "- `miui_bottom_bar_enabled` 默认改 **关**；",
    "- 新增熔断：`apply()` 连续失败 3 次 → `H60 circuit breaker OPEN`，本进程不再改底栏。",
    "",
    "### 3. 构建与验证",
    "- `build/v203b4/AppleInputForSogou_v2.0.3-beta4.apk` 22,750,789 B；`versionCode 44` / `2.0.3-beta4`；",
    "  证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（可覆盖升级）。",
    "- `dexdump -f`：116 个类全部 `Lcom/chiron/iosemoji/*`，非 chiron 0、`de/robv` 定义 0。",
    "- dex 字符串确认：`isNinePatch`、`asset_skin_images_enabled`、`H60 color report observed`、"
    "`H60 circuit breaker OPEN` 均在；旧串 `H60 color report patched` 已消失（改参逻辑确实删掉了）。",
    "",
    "### 4. 现场自救（键盘已经呼不出来的情况）",
    "打开模块 App →「功能开关」→ 关掉「改皮肤（推荐）」→ 点「重启输入法（重载皮肤）」，键盘即可恢复；",
    "只想保留颜色、担心位图的话，保持「资源层改位图」关闭即可。",
    "",
    "",
])
c2 = sec + c
assert c2.startswith("## v2.0.3-beta4")
save(cp, c2)
print("CHANGELOG ok")
