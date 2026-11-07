# -*- coding: utf-8 -*-
"""beta7：底栏第二条独立通道（视图树兜底）+ 开关默认开 + 更细日志"""
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

def sub(p, old, new, count=1):
    s = read(p)
    if s.count(old) != count:
        raise SystemExit("MISS %s: %r" % (os.path.basename(p), old[:80]))
    write(p, s.replace(old, new, count))
    print("ok", os.path.basename(p), "|", old.splitlines()[0].strip()[:60])

# 1) 开关默认开
mc = os.path.join(SRC, r"config\ModuleConfig.java"); m = nl(mc)
sub(mc,
    "        return masterEnabled() && getBoolean(KEY_MIUI_BOTTOM_BAR, false);",
    "        // v2.0.3-beta7：H61 已改成「改参 + 建完补一次 + 视图树兜底」三条并行，默认开。\n"
    .replace("\n", m) +
    "        return masterEnabled() && getBoolean(KEY_MIUI_BOTTOM_BAR, true);")

# 2) H61：decor 捕获 + 视图树兜底
h = os.path.join(SRC, r"hook\MiuiBottomBarHook.java"); n = nl(h)
sub(h,
    "import android.view.View;",
    "import android.view.View;\n".replace("\n", n) +
    "import android.view.ViewGroup;")
sub(h,
    "    private static volatile long sLastNavApply;",
    "    private static volatile long sLastNavApply;" + n +
    "    /** IME 窗口的 decorView（onWindowShown 时抓一次），用于视图树兜底。 */" + n +
    "    private static volatile java.lang.ref.WeakReference<View> sDecor;" + n +
    "    private static final AtomicInteger TREE_HITS = new AtomicInteger();")
sub(h,
    "        hookFramework(cl);",
    "        hookFramework(cl);" + n +
    "        hookDecorCapture(cl);")
sub(h,
    "    /** framework 侧只做「观察 + 补一次」，不再改参（改参放到 phrase 侧，出问题也只在底栏）。 */",
    "    /** 抓 IME 的 decorView：底栏是 MIUI 挂在 SoftInputWindow 上的兄弟节点。 */" + n +
    "    private static void hookDecorCapture(ClassLoader cl) {" + n +
    "        try {" + n +
    "            XposedBridge.hookAllMethods(android.inputmethodservice.InputMethodService.class, \"onWindowShown\"," + n +
    "                    new XC_MethodHook() {" + n +
    "                        @Override" + n +
    "                        protected void afterHookedMethod(MethodHookParam param) {" + n +
    "                            try {" + n +
    "                                android.inputmethodservice.InputMethodService svc =" + n +
    "                                        (android.inputmethodservice.InputMethodService) param.thisObject;" + n +
    "                                if (svc.getWindow() == null || svc.getWindow().getWindow() == null) return;" + n +
    "                                sDecor = new java.lang.ref.WeakReference<View>(" + n +
    "                                        svc.getWindow().getWindow().getDecorView());" + n +
    "                                postApply(120L);" + n +
    "                            } catch (Throwable ignored) {" + n +
    "                            }" + n +
    "                        }" + n +
    "                    });" + n +
    "        } catch (Throwable t) {" + n +
    "            HookRegistry.log(\"H60 decor capture skip: \" + t);" + n +
    "        }" + n +
    "    }" + n + n +
    "    /** framework 侧只做「观察 + 补一次」，不再改参（改参放到 phrase 侧，出问题也只在底栏）。 */")
sub(h,
    "            View bottom = (View) staticField(cls, \"sBottomView\");" + n +
    "            if (bottom == null) {" + n +
    "                HookRegistry.log(\"H60 apply skipped: sBottomView == null（等 addMiuiBottomView）\");" + n +
    "                return;" + n +
    "            }",
    "            View bottom = (View) staticField(cls, \"sBottomView\");" + n +
    "            if (bottom == null) {" + n +
    "                // 字段名被混淆 / 还没建好：走视图树兜底，不依赖 phrase 内部字段。" + n +
    "                int hits = recolorByTree(c);" + n +
    "                if (hits > 0) {" + n +
    "                    TREE_HITS.addAndGet(hits);" + n +
    "                    APPLIED.incrementAndGet();" + n +
    "                    if (APPLIED.get() <= 4) {" + n +
    "                        HookRegistry.log(\"H60 tree fallback recolored \" + hits + \" view(s) -> #\" + Integer.toHexString(c[0]));" + n +
    "                    }" + n +
    "                    return;" + n +
    "                }" + n +
    "                if (APPLIED.get() <= 4) HookRegistry.log(\"H60 apply skipped: sBottomView == null（等 addMiuiBottomView）\");" + n +
    "                return;" + n +
    "            }")
sub(h,
    "    private static Object staticField(Class<?> cls, String name) {",
    "    /**" + n +
    "     * 视图树兜底：从 IME decorView 里找出 MIUI 那条底栏并直接刷色。" + n +
    "     * 判据：是 RelativeLayout / LinearLayout，可见、高 1dp~96dp、宽度接近满宽，且不是我们自己的容器。" + n +
    "     */" + n +
    "    private static int recolorByTree(int[] c) {" + n +
    "        View decor = sDecor == null ? null : sDecor.get();" + n +
    "        if (decor == null) return 0;" + n +
    "        try {" + n +
    "            java.util.ArrayList<View> out = new java.util.ArrayList<>();" + n +
    "            collectBars(decor, c, out, 0);" + n +
    "            int hits = 0;" + n +
    "            for (View v : out) {" + n +
    "                v.setBackgroundColor(c[0]);" + n +
    "                tintChildren(v, c);" + n +
    "                hits++;" + n +
    "            }" + n +
    "            return hits;" + n +
    "        } catch (Throwable t) {" + n +
    "            return 0;" + n +
    "        }" + n +
    "    }" + n + n +
    "    private static void collectBars(View v, int[] c, java.util.ArrayList<View> out, int depth) {" + n +
    "        if (v == null || depth > 6 || out.size() >= 3) return;" + n +
    "        if (v.getVisibility() != View.VISIBLE) return;" + n +
    "        int h = v.getHeight();" + n +
    "        int w = v.getWidth();" + n +
    "        boolean barLike = h > 0 && h <= dp(96) && w > dp(120)" + n +
    "                && (v instanceof RelativeLayout || v instanceof android.widget.LinearLayout)" + n +
    "                && v.getClass().getName().startsWith(\"android.widget\");" + n +
    "        if (barLike) {" + n +
    "            android.graphics.drawable.Drawable bg = v.getBackground();" + n +
    "            // 只认「有底色 / 无底色」的普通条，不碰带自定义 LayerDrawable 的键盘容器" + n +
    "            if (bg == null || bg instanceof android.graphics.drawable.ColorDrawable) {" + n +
    "                out.add(v);" + n +
    "                return;" + n +
    "            }" + n +
    "        }" + n +
    "        if (v instanceof ViewGroup) {" + n +
    "            ViewGroup g = (ViewGroup) v;" + n +
    "            for (int i = 0; i < g.getChildCount(); i++) {" + n +
    "                collectBars(g.getChildAt(i), c, out, depth + 1);" + n +
    "            }" + n +
    "        }" + n +
    "    }" + n + n +
    "    private static void tintChildren(View v, int[] c) {" + n +
    "        if (!(v instanceof ViewGroup)) return;" + n +
    "        ViewGroup g = (ViewGroup) v;" + n +
    "        for (int i = 0; i < g.getChildCount(); i++) {" + n +
    "            View ch = g.getChildAt(i);" + n +
    "            if (ch instanceof ImageView) {" + n +
    "                ((ImageView) ch).setColorFilter(c[1]);" + n +
    "            } else if (ch instanceof android.widget.TextView) {" + n +
    "                ((android.widget.TextView) ch).setTextColor(c[1]);" + n +
    "            }" + n +
    "        }" + n +
    "    }" + n + n +
    "    private static int dp(int v) {" + n +
    "        try {" + n +
    "            Context ctx = HookState.context();" + n +
    "            if (ctx == null) return v * 3;" + n +
    "            return (int) (v * ctx.getResources().getDisplayMetrics().density);" + n +
    "        } catch (Throwable t) {" + n +
    "            return v * 3;" + n +
    "        }" + n +
    "    }" + n + n +
    "    private static Object staticField(Class<?> cls, String name) {")
sub(h,
    "                + \";bbdark=\" + (sSkinDark == null ? \"-\" : (sSkinDark ? \"1\" : \"0\"))",
    "                + \";bbtree=\" + TREE_HITS.get()" + n +
    "                + \";bbdecor=\" + (sDecor != null && sDecor.get() != null ? 1 : 0)" + n +
    "                + \";bbdark=\" + (sSkinDark == null ? \"-\" : (sSkinDark ? \"1\" : \"0\"))")

# 3) 版本 → 47 / 2.0.3-beta7
man = os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml")
sub(man, 'android:versionCode="46"', 'android:versionCode="47"')
sub(man, 'android:versionName="2.0.3-beta6"', 'android:versionName="2.0.3-beta7"')
gr = os.path.join(ROOT, r"lsposed\app\build.gradle.kts")
sub(gr, "versionCode = 46", "versionCode = 47")
sub(gr, 'versionName = "2.0.3-beta6"', 'versionName = "2.0.3-beta7"')
st = os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml")
sub(st, "v2.0.3-beta6", "v2.0.3-beta7")
print("ALL OK")
