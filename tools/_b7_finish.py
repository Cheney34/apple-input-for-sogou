# -*- coding: utf-8 -*-
import io, os, subprocess, zipfile

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
APK = os.path.join(ROOT, r"build\v203b7\AppleInputForSogou_v2.0.3-beta7.apk")

def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

print("APK", os.path.getsize(APK))
with zipfile.ZipFile(APK) as z:
    dex = z.read("classes.dex")
print("dex", len(dex))
for s in ["H61 hooked", "recolorByTree", "collectBars", "bbtree", "bbdecor",
          "onWindowShown", "InputMethodBottomManager", "defpackage"]:
    print("  %-28s %s" % (s, s.encode() in dex))

cl = os.path.join(ROOT, "CHANGELOG.md"); c = "\r\n" if "\r\n" in read(cl) else "\n"
entry = c.join([
 "## v2.0.3-beta7 — 底栏加第二条独立通道（视图树兜底）+ 开关默认开",
 "",
 "beta6 的 H61 只在「找到 `InputMethodBottomManager` 且它的 `sBottomView` 字段名没被混淆」时才生效；",
 "实机仍是原色 ⇒ 说明这条链在用户的 ROM 上没打通。beta7 增加一条**完全不依赖 phrase 内部字段**的通路：",
 "",
 "1. 抓 IME 的 `decorView`：hook `InputMethodService#onWindowShown` 存 WeakReference。",
 "2. 兜底刷色：从 decor 里递归找「`android.widget` 的 RelativeLayout/LinearLayout、可见、高 ≤96dp、宽 >120dp、背景为 null 或纯色」",
 "   的节点（最多 3 个），直接 `setBackgroundColor(皮肤底)`，并把其中的 ImageView 染色、TextView 改文字色。",
 "3. `miui_bottom_bar_enabled` **默认改为开**（H61 现在三条通路并行，且带熔断）。",
 "",
 "诊断串新增 `bbtree=视图树命中数`、`bbdecor=1/0`（是否抓到 decor）。",
 "",
 "- 产物 `build/v203b7/AppleInputForSogou_v2.0.3-beta7.apk`，versionCode 47，22,754,885 B。",
 "",
])
if "v2.0.3-beta7" not in read(cl):
    write(cl, entry + read(cl)); print("CHANGELOG ok")

rd = os.path.join(ROOT, "README.md"); r = read(rd); n = "\r\n" if "\r\n" in r else "\n"
if "v2.0.3-beta7" not in r:
    row = "| **v2.0.3-beta7** | 推荐（实验渠道版） | 底栏加视图树兜底通道；`miui_bottom_bar_enabled` 默认开 |"
    i = r.index("| 版本 |"); j = r.index("\n", i)
    write(rd, r[:j + 1] + row + n + r[j + 1:]); print("README ok")

def git(*a):
    p = subprocess.run(["git"] + list(a), cwd=ROOT, capture_output=True, text=True, errors="ignore")
    print("git", a[0], p.returncode, (p.stdout or "").strip().splitlines()[:1])
    return p.returncode

git("add", "-A")
git("-c", "user.name=Apple%20Input", "-c", "user.email=sogou@localhost", "commit",
    "-m", "feat(miui): 底栏视图树兜底通道 + H61 开关默认开 + decor 捕获（v2.0.3-beta7）")
print("DONE")
