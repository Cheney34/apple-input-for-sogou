# -*- coding: utf-8 -*-
import io, os, subprocess

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

cl = os.path.join(ROOT, "CHANGELOG.md"); c = "\r\n" if "\r\n" in read(cl) else "\n"
entry = c.join([
 "## v2.0.3-beta6 — 全面屏底栏改 hook 真正的落点 InputMethodBottomManager（H61）",
 "",
 "### 为什么之前的 H60 改不动底栏",
 "- 底栏不是 framework 画的：MIUI 从 `com.miui.phrase` 动态 dex 里加载 `com.miui.inputmethod.InputMethodBottomManager` 后挂上去。",
 "- 反编译 phrase 5.7.4 确认 `setBottomColor(boolean,int,int,int)` 第一行就是 `if (sBottomView == null) { Log.e(...); return; }`：",
 "  搜狗上报颜色那一刻底栏还没建好，这次上报被**直接丢弃**；随后 `addMiuiBottomView()` 用默认色建视图，再没人重设 ⇒ 观感就是「怎么都没变」。",
 "",
 "### 本次改法（H61，三条一起做）",
 "1. `customizeBottomViewColor` / `setBottomColor` **before 改参**：`args[0]=true`、`args[1]`=`SkinPreset.barBg`、`args[2]/[3]`=图标两态色（按对比度算）。",
 "2. `addMiuiBottomView` / `refreshBottomSkin` **after** 补一次：反射改 `sBottomView.setBackgroundColor` + `sLeftButton/sRightButton.setBackgroundTintList` + `currentBottomViewColor`。",
 "3. framework 侧只保留「观察 + 触发补一次」，不再改 framework 调用参数（缩小对键盘唤起的影响面）。",
 "- 类加载：依次用 `lpparam.classLoader` → Context classLoader → 当前线程 → 系统 loader 找该类，并在 0.3/1/3/6/12/24/45 秒重试（动态 dex 注入有延迟）。",
 "- 诊断：状态串新增 `bb=改参/主动应用;bbmg=1;bbfw=1;bbld=ime;bbdark=…;bbdis=…`。",
 "- 开关：仍是 `miui_bottom_bar_enabled`，**默认关**，需手动打开「全面屏底栏跟随皮肤（实验）」。",
 "",
 "### 构建验证",
 "- `build/v203b6/AppleInputForSogou_v2.0.3-beta6.apk`，versionCode 46，22,750,789 B，证书同上（可覆盖升级）。",
 "- dex：120 个类全 `Lcom/chiron/iosemoji/*`，无 `de/robv` 定义；串 `InputMethodBottomManager`/`H61 hooked`/`sBottomIconStates` 均在。",
 "",
])
if "v2.0.3-beta6" not in read(cl):
    write(cl, entry + read(cl))
    print("CHANGELOG ok")

rd = os.path.join(ROOT, "README.md"); r = read(rd); n = "\r\n" if "\r\n" in r else "\n"
if "v2.0.3-beta6" not in r:
    row = "| **v2.0.3-beta6** | 推荐（实验渠道版） | H61：底栏改为 hook `InputMethodBottomManager`（真正的颜色落点） |"
    marker = "| 版本 |"
    i = r.index(marker)
    j = r.index("\n", i)
    r = r[:j + 1] + row + n + r[j + 1:]
    write(rd, r)
    print("README ok")

def git(*args):
    p = subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True, text=True, errors="ignore")
    print("git", args[0], "->", p.returncode, (p.stdout or "").strip().splitlines()[:1], (p.stderr or "").strip()[:160])
    return p.returncode

git("add", "-A")
git("-c", "user.name=Apple%20Input", "-c", "user.email=sogou@localhost", "commit",
    "-m", "feat(miui): H61 hook InputMethodBottomManager（改参+建完补一次）；framework 侧只观察（v2.0.3-beta6）")
print("DONE")
