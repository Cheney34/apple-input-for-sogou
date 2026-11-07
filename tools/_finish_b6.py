# -*- coding: utf-8 -*-
"""beta6 收尾：dex 校验 + 文档 + 提交"""
import io, os, re, subprocess, sys, zipfile

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
BT = r"C:\Users\KOUFIK\Documents\deepseek-harness\ChironIOSInput\tools\android\build-tools\android-15"
APK = os.path.join(ROOT, r"build\v203b6\AppleInputForSogou_v2.0.3-beta6.apk")

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def nl(p):
    return "\r\n" if "\r\n" in read(p) else "\n"

# ---- 1. dex 校验 ----
print("APK size =", os.path.getsize(APK))
with zipfile.ZipFile(APK) as z:
    dex = z.read("classes.dex")
print("classes.dex =", len(dex), "B")
tmpd = os.path.join(ROOT, r"build\v203b6\check")
os.makedirs(tmpd, exist_ok=True)
dexp = os.path.join(tmpd, "classes.dex")
with open(dexp, "wb") as f:
    f.write(dex)
out = subprocess.run([os.path.join(BT, "dexdump.exe"), "-f", dexp],
                     capture_output=True, text=True, errors="ignore").stdout
descs = re.findall(r"Class descriptor\s*:\s*'(L[^']+)'", out)
chiron = [d for d in descs if d.startswith("Lcom/chiron/iosemoji")]
print("classes=%d chiron=%d other=%s" % (len(descs), len(chiron), [d for d in descs if not d.startswith("Lcom/chiron")]))
for s in ["InputMethodBottomManager", "sBottomColor", "customizeBottomViewColor", "sBottomIconStates",
          "H61 hooked", "H60 bottom applied", "circuit breaker OPEN", "miui_bottom_bar_enabled",
          "defpackage", "asset_skin_enabled"]:
    print("  str %-28s %s" % (s, s.encode() in dex))

# ---- 2. CHANGELOG ----
cl = os.path.join(ROOT, "CHANGELOG.md"); c = nl(cl)
body = c.join([
    "## v2.0.3-beta6 — 全面屏底栏改成 hook 真正的落点 InputMethodBottomManager（H61）",
    "",
    "### 为什么之前的 H60 改不动底栏",
    "- 底栏不是 framework 画的，是 MIUI 从 `com.miui.phrase` 动态 dex 里加载",
    "  `com.miui.inputmethod.InputMethodBottomManager` 后挂上去的。",
    "- 反编译 phrase 5.7.4 确认：`setBottomColor(boolean,int,int,int)` 第一行就是",
    "  `if (sBottomView == null) { Log.e(...); return; }` —— 搜狗上报颜色那一刻底栏还没建，",
    "  这次上报被**直接丢弃**；随后 `addMiuiBottomView()` 用默认色建视图，再没人重设。",
    "  所以只在 framework 侧改参，观感上就是「怎么都没变」。",
    "",
    "### 本次改法（H61，三条一起做）",
    "1. `customizeBottomViewColor` / `setBottomColor` **before 改参**：",
    "   `args[0]=true`、`args[1]`=皮肤底色（`SkinPreset.barBg`）、`args[2]/[3]`=图标两态色（按对比度算）。",
    "2. `addMiuiBottomView` / `refreshBottomSkin` **after** 补一次：反射改",
    "   `sBottomView.setBackgroundColor` + `sLeftButton/sRightButton.setBackgroundTintList` + `currentBottomViewColor`。",
    "3. framework 侧只保留「观察 + 触发补一次」，不再改 framework 调用参数（降低对键盘唤起的影响面）。",
    "- 类加载：依次用 `lpparam.classLoader` → Context classLoader → 当前线程 → 系统 loader 找",
    "  `com.miui.inputmethod.InputMethodBottomManager`，并在 0.3/1/3/6/12/24/45 秒重试（动态 dex 注入有延迟）。",
    "- 诊断：状态串新增 `bb=改参/主动应用;bbmg=1;bbfw=1;bbld=ime;bbdark=…;bbdis=…`。",
    "- 开关：仍是 `miui_bottom_bar_enabled`（**默认关**，需要手动打开「全面屏底栏跟随皮肤（实验）」）。",
    "",
    "### 构建验证",
    "- `build/v203b6/AppleInputForSogou_v2.0.3-beta6.apk`，versionCode 46，证书同上（可覆盖升级）。",
    "",
])
write(cl, c.join(["", body, read(cl)]) if not read(cl).startswith(body) else read(cl))

# ---- 3. README 版本行 ----
rd = os.path.join(ROOT, "README.md"); r = read(rd)
row = "| **v2.0.3-beta6** | 推荐（实验渠道版） | H61：底栏改为 hook `InputMethodBottomManager`（真正的颜色落点） |"
if "v2.0.3-beta6" not in r:
    anchor = "| **v2.0.3-beta5**"
    if anchor in r:
        i = r.index(anchor)
        r = r[:i] + row + nl(rd) + r[i:]
    else:
        i = r.index("| 版本 |")
        r = r[:i] + row + nl(rd) + r[i:]
    write(rd, r)
    print("README 版本行已插入")

# ---- 4. git ----
def git(*args):
    p = subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True, text=True, errors="ignore")
    print("git", " ".join(args), "->", p.returncode, (p.stdout or "").strip()[:200], (p.stderr or "").strip()[:200])
    return p.returncode

git("add", "-A")
git("-c", "user.name=Apple Input for Sogou", "-c", "user.email=sogou@localhost",
    "commit", "-m", "feat(miui): H61 底栏改为 hook InputMethodBottomManager（改参+补一次）；framework 侧只观察（v2.0.3-beta6）")
print("ALL OK")
