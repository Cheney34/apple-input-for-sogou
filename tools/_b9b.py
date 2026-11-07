# -*- coding: utf-8 -*-
import io, os, re

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
h = os.path.join(ROOT, r"lsposed\app\src\main\java\com\chiron\iosemoji\hook\MiuiBottomBarHook.java")

with io.open(h, "r", encoding="utf-8", newline="") as f:
    s = f.read()
n = "\r\n" if "\r\n" in s else "\n"

# A) 把 tryInstallBottomManager 的 hook 部分拆出 attachBottomManager
pat = re.compile(
    r"    private static void tryInstallBottomManager\(ClassLoader cl\) \{\r?\n"
    r"        if \(sManagerHooked \|\| sDisabled\) return;\r?\n"
    r"        Class<\?> cls = resolveBottomManager\(cl\);\r?\n"
    r"        if \(cls == null\) return;\r?\n"
    r"        try \{")
new = n.join([
    "    private static void tryInstallBottomManager(ClassLoader cl) {",
    "        if (sManagerHooked || sDisabled) return;",
    "        Class<?> cls = resolveBottomManager(cl);",
    "        if (cls == null) return;",
    "        attachBottomManager(cls);",
    "    }",
    "",
    "    /** 真正挂 hook（loadClass 分发与主动查找共用的入口）。 */",
    "    private static synchronized void attachBottomManager(Class<?> cls) {",
    "        if (sManagerHooked || sDisabled || cls == null) return;",
    "        try {",
])
s, c = pat.subn(lambda m: new, s)
assert c == 1, "A miss %d" % c

# B) 挂好后 seed 默认色
pat2 = re.compile(r'(sManagerHooked = true;\r?\n\s*HookRegistry\.log\("H61 hooked: " \+ CLS_BOTTOM_MANAGER \+ " via " \+ sManagerLoader\);)')
s, c = pat2.subn(lambda m: m.group(1) + n + "            // 顺手改掉它的默认色：addMiuiBottomViewInner 里 setBottomColor(false,0,0,0)" + n + "            // 会拿静态字段刷底栏，改掉之后连默认路径都是我们的颜色。" + n + "            seedDefaults(cls);", s)
assert c == 1, "B miss %d" % c

# C) seedDefaults 定义
anchor = "    private static Class<?> resolveBottomManager(ClassLoader cl) {"
add = n.join([
    "    /** 把 phrase 的静态默认色换成当前皮肤色（失败只记日志）。 */",
    "    private static void seedDefaults(Class<?> cls) {",
    "        try {",
    "            int[] c = colors();",
    "            if (c == null) return;",
    '            setStatic(cls, "sDefBottomViewColor", c[0]);',
    '            setStatic(cls, "sDefIconColorUnpressed", c[1]);',
    '            setStatic(cls, "sDefIconColorPressed", c[2]);',
    '            setStatic(cls, "currentBottomViewColor", c[0]);',
    '            setStatic(cls, "sPhrasePanelBottomViewColorBackup", c[0]);',
    '            HookRegistry.log(String.format("H61 defaults seeded -> #%08X", c[0]));',
    "        } catch (Throwable t) {",
    '            HookRegistry.log("H61 seedDefaults failed: " + t);',
    "        }",
    "    }",
    "",
])
assert s.count(anchor) == 1
s = s.replace(anchor, add + anchor, 1)

# D) 字段 sDispatchInstalled
old = "    private static volatile boolean sManagerHooked;"
assert s.count(old) == 1
s = s.replace(old, old + n + "    private static volatile boolean sDispatchInstalled;", 1)

# E) 状态串加 bbdp
old = '                + ";bbmg=" + (sManagerHooked ? 1 : 0)'
assert s.count(old) == 1
s = s.replace(old, old + n + '                + ";bbdp=" + (sDispatchInstalled ? 1 : 0)', 1)

with io.open(h, "w", encoding="utf-8", newline="") as f:
    f.write(s)
print("hook patched")

def bump(p, pairs):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        t = f.read()
    for a, b in pairs:
        assert a in t, (os.path.basename(p), a)
        t = t.replace(a, b)
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t)
    print("bump", os.path.basename(p))

bump(os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"),
     [('android:versionCode="48"', 'android:versionCode="49"'),
      ('android:versionName="2.0.3-beta8"', 'android:versionName="2.0.3-beta9"')])
bump(os.path.join(ROOT, r"lsposed\app\build.gradle.kts"),
     [("versionCode = 48", "versionCode = 49"),
      ('versionName = "2.0.3-beta8"', 'versionName = "2.0.3-beta9"')])
bump(os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
     [("v2.0.3-beta8", "v2.0.3-beta9")])
print("ALL OK")
