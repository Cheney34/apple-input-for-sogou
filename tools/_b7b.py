# -*- coding: utf-8 -*-
import io, os, re

ROOT = r"C:\Users\KOUFIK\Documents\deepseek-harness\default-workspace\apple-input-for-sogou"
SRC = os.path.join(ROOT, r"lsposed\app\src\main\java\com\chiron\iosemoji")
h = os.path.join(SRC, r"hook\MiuiBottomBarHook.java")

with io.open(h, "r", encoding="utf-8", newline="") as f:
    s = f.read()
n = "\r\n" if "\r\n" in s else "\n"

# --- 1) apply() 兜底：sBottomView == null → 走视图树 ---
pat = re.compile(
    r'( +)View bottom = \(View\) staticField\(cls, "sBottomView"\);\r?\n'
    r' +if \(bottom == null\) \{\r?\n'
    r' +HookRegistry\.log\("H60 apply skipped: sBottomView == null[^\r\n]*\r?\n'
    r' +return;\r?\n'
    r' +\}')
def repl(m):
    i = m.group(1)
    L = [
        i + 'View bottom = (View) staticField(cls, "sBottomView");',
        i + 'if (bottom == null) {',
        i + '    // 字段名被混淆 / 还没建好：走视图树兜底，不依赖 phrase 内部字段。',
        i + '    int hits = recolorByTree(c);',
        i + '    if (hits > 0) {',
        i + '        TREE_HITS.addAndGet(hits);',
        i + '        APPLIED.incrementAndGet();',
        i + '        if (APPLIED.get() <= 4) {',
        i + '            HookRegistry.log("H60 tree fallback recolored " + hits + " view(s) -> #"',
        i + '                    + Integer.toHexString(c[0]));',
        i + '        }',
        i + '        return;',
        i + '    }',
        i + '    if (APPLIED.get() <= 4) {',
        i + '        HookRegistry.log("H60 apply skipped: sBottomView == null（等 addMiuiBottomView）");',
        i + '    }',
        i + '    return;',
        i + '}',
    ]
    return n.join(L)
s2, cnt = pat.subn(repl, s)
if cnt != 1:
    raise SystemExit("apply fallback miss: %d" % cnt)
s = s2
print("ok apply fallback")

# --- 2) 视图树工具方法：插到 staticField 声明之前 ---
anchor = re.compile(r'\r?\n( +)private static Object staticField\(Class<\?> cls, String name\) \{')
i = None
for m in anchor.finditer(s):
    i = m
if i is None:
    raise SystemExit("staticField anchor miss")
ind = i.group(1)
helpers = n.join([
    "",
    ind + "/**",
    ind + " * 视图树兜底：从 IME decorView 里找出 MIUI 那条底栏并直接刷色。",
    ind + " * 判据：android.widget 的 RelativeLayout/LinearLayout、可见、高 1dp~96dp、宽度接近满宽、背景是普通色。",
    ind + " */",
    ind + "private static int recolorByTree(int[] c) {",
    ind + "    View decor = sDecor == null ? null : sDecor.get();",
    ind + "    if (decor == null) return 0;",
    ind + "    try {",
    ind + "        java.util.ArrayList<View> out = new java.util.ArrayList<>();",
    ind + "        collectBars(decor, out, 0);",
    ind + "        int hits = 0;",
    ind + "        for (int k = 0; k < out.size(); k++) {",
    ind + "            View v = out.get(k);",
    ind + "            v.setBackgroundColor(c[0]);",
    ind + "            tintChildren(v, c);",
    ind + "            hits++;",
    ind + "        }",
    ind + "        return hits;",
    ind + "    } catch (Throwable t) {",
    ind + "        return 0;",
    ind + "    }",
    ind + "}",
    "",
    ind + "private static void collectBars(View v, java.util.ArrayList<View> out, int depth) {",
    ind + "    if (v == null || depth > 6 || out.size() >= 3) return;",
    ind + "    if (v.getVisibility() != View.VISIBLE) return;",
    ind + "    int hh = v.getHeight();",
    ind + "    int ww = v.getWidth();",
    ind + "    boolean barLike = hh > 0 && hh <= dp(96) && ww > dp(120)",
    ind + "            && (v instanceof RelativeLayout || v instanceof android.widget.LinearLayout)",
    ind + "            && v.getClass().getName().startsWith(\"android.widget\");",
    ind + "    if (barLike) {",
    ind + "        android.graphics.drawable.Drawable bg = v.getBackground();",
    ind + "        if (bg == null || bg instanceof android.graphics.drawable.ColorDrawable) {",
    ind + "            out.add(v);",
    ind + "            return;",
    ind + "        }",
    ind + "    }",
    ind + "    if (v instanceof ViewGroup) {",
    ind + "        ViewGroup g = (ViewGroup) v;",
    ind + "        for (int k = 0; k < g.getChildCount(); k++) {",
    ind + "            collectBars(g.getChildAt(k), out, depth + 1);",
    ind + "        }",
    ind + "    }",
    ind + "}",
    "",
    ind + "private static void tintChildren(View v, int[] c) {",
    ind + "    if (!(v instanceof ViewGroup)) return;",
    ind + "    ViewGroup g = (ViewGroup) v;",
    ind + "    for (int k = 0; k < g.getChildCount(); k++) {",
    ind + "        View ch = g.getChildAt(k);",
    ind + "        if (ch instanceof ImageView) {",
    ind + "            ((ImageView) ch).setColorFilter(c[1]);",
    ind + "        } else if (ch instanceof android.widget.TextView) {",
    ind + "            ((android.widget.TextView) ch).setTextColor(c[1]);",
    ind + "        }",
    ind + "    }",
    ind + "}",
    "",
    ind + "private static int dp(int v) {",
    ind + "    try {",
    ind + "        Context ctx = HookState.context();",
    ind + "        if (ctx == null) return v * 3;",
    ind + "        return (int) (v * ctx.getResources().getDisplayMetrics().density);",
    ind + "    } catch (Throwable t) {",
    ind + "        return v * 3;",
    ind + "    }",
    ind + "}",
])
s = s[:i.start()] + n + helpers + s[i.start():]
print("ok tree helpers")

# --- 3) stateFields 增加 bbtree/bbdecor ---
old = '                + ";bbdark="'
if s.count(old) != 1:
    raise SystemExit("stateFields anchor miss")
s = s.replace(old, '                + ";bbtree=" + TREE_HITS.get()\n'
                   '                + ";bbdecor=" + (sDecor != null && sDecor.get() != null ? 1 : 0)\n'
                   '                + ";bbdark="'.replace("\n", n), 1)
print("ok stateFields")

with io.open(h, "w", encoding="utf-8", newline="") as f:
    f.write(s)

# --- 4) 版本号 ---
def bump(p, pairs):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        t = f.read()
    for a, b in pairs:
        if a not in t:
            raise SystemExit("bump miss in %s: %r" % (os.path.basename(p), a))
        t = t.replace(a, b)
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t)
    print("ok bump", os.path.basename(p))

bump(os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml"),
     [('android:versionCode="46"', 'android:versionCode="47"'),
      ('android:versionName="2.0.3-beta6"', 'android:versionName="2.0.3-beta7"')])
bump(os.path.join(ROOT, r"lsposed\app\build.gradle.kts"),
     [("versionCode = 46", "versionCode = 47"),
      ('versionName = "2.0.3-beta6"', 'versionName = "2.0.3-beta7"')])
bump(os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml"),
     [("v2.0.3-beta6", "v2.0.3-beta7")])
print("ALL OK")
