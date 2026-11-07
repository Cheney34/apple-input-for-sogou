# -*- coding: utf-8 -*-
"""beta9：用 ClassLoader.loadClass 分发拿到 phrase 的 InputMethodBottomManager（HyperCeiler 同思路），
并把它的静态默认色一起改掉，使 setBottomColor(false,...) 这条默认路径也用我们的颜色。"""
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
    n = "\r\n" if "\r\n" in t else "\n"
    for old, new in pairs:
        old = old.replace("\n", n); new = new.replace("\n", n)
        if t.count(old) != 1:
            raise SystemExit("MISS %s (%d): %r" % (os.path.basename(p), t.count(old), old[:90]))
        t = t.replace(old, new, 1)
    write(p, t)
    print("ok", os.path.basename(p))

h = os.path.join(SRC, r"hook\MiuiBottomBarHook.java")

# 1) 在 install 里加上 loadClass 分发
rep(h, [(
"        hookFramework(cl);\n        hookDecorCapture(cl);",
"        hookFramework(cl);\n        hookDecorCapture(cl);\n        hookClassLoaderDispatch();")])

# 2) 新增 loadClass 分发 + 静态默认色改写
rep(h, [(
"    /** 抓 IME 的 decorView：底栏是 MIUI 挂在 SoftInputWindow 上的兄弟节点。 */",
"""    /**
     * ★ 关键：phrase 的 {@code InputMethodBottomManager} 在动态 dex 的 ClassLoader 里，
     * 它不一定能通过 lpparam.classLoader 直接找到（HyperCeiler 也是用 loadClass 分发解决的）。
     * 这里挂 {@code ClassLoader#loadClass}，一旦有人加载这个类，就地把它 hook 上。
     * 判据只有一个 {@code name.equals(...)}，命中一次后立即卸载负担（sManagerHooked）。
     */
    private static void hookClassLoaderDispatch() {
        if (sDispatchInstalled) return;
        sDispatchInstalled = true;
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sManagerHooked) return;
                try {
                    Object a0 = param.args == null || param.args.length == 0 ? null : param.args[0];
                    if (!CLS_BOTTOM_MANAGER.equals(a0)) return;
                    Object r = param.getResult();
                    if (!(r instanceof Class)) return;
                    attachBottomManager((Class<?>) r);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, cb);
        } catch (Throwable t) {
            HookRegistry.log("H61 loadClass dispatch skip: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, cb);
        } catch (Throwable ignored) {
        }
        HookRegistry.log("H61 loadClass dispatch armed for " + CLS_BOTTOM_MANAGER);
    }

    /** 抓 IME 的 decorView：底栏是 MIUI 挂在 SoftInputWindow 上的兄弟节点。 */""")])

# 3) tryInstallBottomManager 里的 hook 部分抽成 attachBottomManager（供两边复用）
rep(h, [(
"""    /** phrase 侧的 InputMethodBottomManager：改参（治本）+ 建完之后补一次。 */
    private static void tryInstallBottomManager(ClassLoader cl) {
        if (sManagerHooked || sDisabled) return;
        Class<?> cls = resolveBottomManager(cl);
        if (cls == null) return;
        try {""",
"""    /** phrase 侧的 InputMethodBottomManager：改参（治本）+ 建完之后补一次。 */
    private static void tryInstallBottomManager(ClassLoader cl) {
        if (sManagerHooked || sDisabled) return;
        Class<?> cls = resolveBottomManager(cl);
        if (cls == null) return;
        attachBottomManager(cls);
    }

    /** 真正挂 hook（loadClass 分发与主动查找都走这里）。 */
    private static synchronized void attachBottomManager(Class<?> cls) {
        if (sManagerHooked || sDisabled || cls == null) return;
        try {""")])

# 4) attach 成功后：把三个静态默认色改成我们的颜色
rep(h, [(
"""            sManagerHooked = true;
            HookRegistry.log("H61 hooked: " + CLS_BOTTOM_MANAGER + " via " + sManagerLoader);""",
"""            sManagerHooked = true;
            HookRegistry.log("H61 hooked: " + CLS_BOTTOM_MANAGER + " via " + sManagerLoader);
            // 顺手改掉它的「默认色」：addMiuiBottomViewInner 里 setBottomColor(false,0,0,0)
            // 会拿这三个静态字段刷底栏，改掉之后连默认路径都是我们的颜色。
            seedDefaults(cls);""")])

# 5) 新增 seedDefaults
rep(h, [(
"    private static Class<?> resolveBottomManager(ClassLoader cl) {",
"""    /** 把 phrase 的静态默认色替换成当前皮肤色（失败只记日志）。 */
    private static void seedDefaults(Class<?> cls) {
        try {
            int[] c = colors();
            if (c == null) return;
            setStatic(cls, "sDefBottomViewColor", c[0]);
            setStatic(cls, "sDefIconColorUnpressed", c[1]);
            setStatic(cls, "sDefIconColorPressed", c[2]);
            setStatic(cls, "currentBottomViewColor", c[0]);
            setStatic(cls, "sPhrasePanelBottomViewColorBackup", c[0]);
            HookRegistry.log(String.format("H61 defaults seeded -> #%08X", c[0]));
        } catch (Throwable t) {
            HookRegistry.log("H61 seedDefaults failed: " + t);
        }
    }

    private static Class<?> resolveBottomManager(ClassLoader cl) {""")])

# 6) 字段 + 状态
rep(h, [(
"    private static volatile boolean sManagerHooked;",
"    private static volatile boolean sManagerHooked;\n    private static volatile boolean sDispatchInstalled;")])
rep(h, [(
'                + ";bbmg=" + (sManagerHooked ? 1 : 0)',
'                + ";bbmg=" + (sManagerHooked ? 1 : 0)\n                + ";bbdp=" + (sDispatchInstalled ? 1 : 0)')])

# 7) 版本 → 49 / 2.0.3-beta9
rep(os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"),
    [('android:versionCode="48"', 'android:versionCode="49"'),
     ('android:versionName="2.0.3-beta8"', 'android:versionName="2.0.3-beta9"')])
rep(os.path.join(ROOT, r"lsposed\app\build.gradle.kts"),
    [("versionCode = 48", "versionCode = 49"),
     ('versionName = "2.0.3-beta8"', 'versionName = "2.0.3-beta9"')])
rep(os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
    [("v2.0.3-beta8", "v2.0.3-beta9")])
print("ALL OK")
