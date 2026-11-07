# -*- coding: utf-8 -*-
"""v2.0.3-beta4 紧急修复：
1) H50：绝不重编码 nine-patch（.9.png / npTc）→ 这是"键盘呼不出来"的元凶；
         资源层改位图改为默认关（ini 颜色照改）。
2) H60：默认关、去掉 before 改参、加熔断。
"""
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
    n = s.count(old)
    if n != count:
        raise SystemExit("PATTERN MISS in %s: expected %d got %d for %r" % (os.path.basename(p), count, n, old[:70]))
    write(p, s.replace(old, new, count))
    print("ok  %-24s %s" % (os.path.basename(p), old.splitlines()[0].strip()[:52]))

# ---------- 1. ModuleConfig：资源层位图开关（默认关） ----------
mc = os.path.join(SRC, r"config\ModuleConfig.java"); m = nl(mc)
sub(mc,
    '    public static final String KEY_MIUI_BOTTOM_BAR = "miui_bottom_bar_enabled";',
    '    public static final String KEY_MIUI_BOTTOM_BAR = "miui_bottom_bar_enabled";' + m +
    '    /** 资源层是否连位图一起重画（默认关：重编码会丢 nine-patch 数据，风险高）。 */' + m +
    '    public static final String KEY_ASSET_SKIN_IMAGES = "asset_skin_images_enabled";')
sub(mc,
    "    public static boolean miuiBottomBarEnabled() {" + m +
    "        return masterEnabled() && getBoolean(KEY_MIUI_BOTTOM_BAR, true);" + m +
    "    }",
    "    public static boolean miuiBottomBarEnabled() {" + m +
    "        // v2.0.3-beta4：默认关。H60 会 hook framework 的颜色上报链路，属于「可能影响键盘能否唤起」的改动，" + m +
    "        // 先让它在小米上验证过再默认开。" + m +
    "        return masterEnabled() && getBoolean(KEY_MIUI_BOTTOM_BAR, false);" + m +
    "    }" + m + m +
    "    public static boolean assetSkinImagesEnabled() {" + m +
    "        return themeSkinEnabled() && getBoolean(KEY_ASSET_SKIN_IMAGES, false);" + m +
    "    }")

# ---------- 2. ConfigValues ----------
cv = os.path.join(SRC, r"config\ConfigValues.java"); c = nl(cv)
sub(cv, "            ModuleConfig.KEY_MIUI_BOTTOM_BAR,",
        "            ModuleConfig.KEY_MIUI_BOTTOM_BAR," + c + "            ModuleConfig.KEY_ASSET_SKIN_IMAGES,")
sub(cv, "            case ModuleConfig.KEY_CANDIDATE_PROBE:" + c + "            case ModuleConfig.KEY_LOGO_TRIGGER:",
        "            case ModuleConfig.KEY_CANDIDATE_PROBE:" + c + "            case ModuleConfig.KEY_LOGO_TRIGGER:" + c +
        "            case ModuleConfig.KEY_ASSET_SKIN_IMAGES:")
sub(cv, '            case ModuleConfig.KEY_MIUI_BOTTOM_BAR:' + c + '                return "全面屏底栏";',
        '            case ModuleConfig.KEY_MIUI_BOTTOM_BAR:' + c + '                return "全面屏底栏";' + c +
        '            case ModuleConfig.KEY_ASSET_SKIN_IMAGES:' + c + '                return "资源层改位图";')

# ---------- 3. RuntimeConfig 短名 ----------
rc = os.path.join(SRC, r"config\RuntimeConfig.java"); r = nl(rc)
sub(rc, '            case ModuleConfig.KEY_MIUI_BOTTOM_BAR:' + r + '                return "bb";',
        '            case ModuleConfig.KEY_MIUI_BOTTOM_BAR:' + r + '                return "bb";' + r +
        '            case ModuleConfig.KEY_ASSET_SKIN_IMAGES:' + r + '                return "aimg";')

# ---------- 4. AssetSkinHook：nine-patch 硬保护 + 位图默认关 ----------
ash = os.path.join(SRC, r"hook\AssetSkinHook.java"); a = nl(ash)
sub(ash,
    "    private static byte[] patchImageBytes(String path, byte[] raw, String presetId) {" + a +
    "        try {" + a +
    "            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);",
    "    private static byte[] patchImageBytes(String path, byte[] raw, String presetId) {" + a +
    "        try {" + a +
    "            // ★ 铁律 0：nine-patch 绝对不重画。" + a +
    "            // decode → compress 会把 PNG 里的 npTc（九宫格切边数据）整块丢掉，" + a +
    "            // 搜狗/系统 Inflate 这张图时会直接抛异常 —— 现象就是「键盘呼不出来」。" + a +
    "            if (isNinePatch(path, raw)) return null;" + a +
    "            // 资源层默认只兜底 ini 颜色；位图交给类名层（H41）去做，避免影响启动。" + a +
    "            if (!ModuleConfig.assetSkinImagesEnabled()) return null;" + a +
    "            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);")
sub(ash,
    "    // -------------------------------------------------------------- utils",
    "    /**" + a +
    "     * 是不是 nine-patch：文件名字面 {@code .9.png}，或 PNG 里带 {@code npTc} 块（九宫格切边）。" + a +
    "     * 这类图一旦被 decode→compress 重编码，切边数据就没了，系统加载会崩。" + a +
    "     */" + a +
    "    private static boolean isNinePatch(String path, byte[] raw) {" + a +
    "        if (path != null && path.toLowerCase().endsWith(\".9.png\")) return true;" + a +
    "        if (raw == null || raw.length < 16) return false;" + a +
    "        for (int i = 0; i + 3 < raw.length; i++) {" + a +
    "            if (raw[i] == 'n' && raw[i + 1] == 'p' && raw[i + 2] == 'T' && raw[i + 3] == 'c') return true;" + a +
    "        }" + a +
    "        return false;" + a +
    "    }" + a + a +
    "    // -------------------------------------------------------------- utils")

# ---------- 5. H60：默认关 / 去掉 before 改参 / 熔断 ----------
h60 = os.path.join(SRC, r"hook\MiuiBottomBarHook.java"); h = nl(h60)
sub(h60,
    "    private static volatile long sLastNavApply;",
    "    private static volatile long sLastNavApply;" + h +
    "    /** 熔断：连续失败 3 次就彻底停用 H60（宁可底栏原色，也不能影响键盘唤起）。 */" + h +
    "    private static volatile boolean sDisabled;" + h +
    "    private static final AtomicInteger FAIL = new AtomicInteger();" + h +
    "    private static final int FAIL_MAX = 3;")
sub(h60,
    "        return ModuleConfig.masterEnabled()" + h +
    "                && ModuleConfig.themeSkinEnabled()" + h +
    "                && ModuleConfig.miuiBottomBarEnabled();",
    "        return !sDisabled" + h +
    "                && ModuleConfig.masterEnabled()" + h +
    "                && ModuleConfig.themeSkinEnabled()" + h +
    "                && ModuleConfig.miuiBottomBarEnabled();")
sub(h60,
    '        hookAll(injector, "customizeBottomViewColor", new XC_MethodHook() {' + h +
    "            @Override" + h +
    "            protected void beforeHookedMethod(MethodHookParam param) {" + h +
    "                try {" + h +
    "                    if (!enabled() || param.args == null || param.args.length < 4) return;" + h +
    "                    if (!(param.args[1] instanceof Integer)) return;" + h +
    "                    int want = (Integer) param.args[1];" + h +
    "                    // 调用方本来就要「透明/无底栏」（全屏、游戏、悬浮键盘）时不要接管，" + h +
    "                    // 否则会把本来不显示的底栏点亮成一条灰条。" + h +
    "                    if (!FORCE_TRANSPARENT && (want >>> 24) == 0) return;" + h +
    "                    int[] c = colors();" + h +
    "                    if (c == null) return;" + h +
    "                    // 只改颜色，不动 args[0]（isCustom）—— 由调用方决定要不要走自定义配色。" + h +
    "                    param.args[1] = c[0];" + h +
    "                    param.args[2] = c[1];" + h +
    "                    param.args[3] = c[2];" + h +
    "                    int n = PATCHED.incrementAndGet();" + h +
    "                    if (n <= 5) {" + h +
    '                        HookRegistry.log(String.format("H60 color report patched -> #%08X", c[0]));' + h +
    "                    }" + h +
    "                } catch (Throwable t) {" + h +
    '                    HookRegistry.log("H60 before patch failed: " + t);' + h +
    "                }" + h +
    "            }" + h +
    "        });",
    "        // v2.0.3-beta4：**不再改 customizeBottomViewColor 的参数**。" + h +
    "        // 上一版在 before 里改写 args，个别 ROM 上会让 MIUI 的底栏创建失败并连带影响键盘唤起；" + h +
    "        // 现在改成「只观察 + 主动下发」（第 2、3 两点 + apply()），行为可逆、失败不伤原生链路。" + h +
    '        hookAll(injector, "customizeBottomViewColor", new XC_MethodHook() {' + h +
    "            @Override" + h +
    "            protected void afterHookedMethod(MethodHookParam param) {" + h +
    "                try {" + h +
    "                    if (!enabled() || param.args == null || param.args.length < 4) return;" + h +
    "                    int n = PATCHED.incrementAndGet();" + h +
    "                    if (n <= 3) HookRegistry.log(\"H60 color report observed #\" + n);" + h +
    "                } catch (Throwable ignored) {" + h +
    "                }" + h +
    "            }" + h +
    "        });")
sub(h60,
    "        } catch (Throwable t) {" + h +
    '            HookRegistry.log("H60 apply failed: " + t);' + h +
    "        } finally {" + h +
    "            sApplying = false;" + h +
    "        }",
    "        } catch (Throwable t) {" + h +
    '            HookRegistry.log("H60 apply failed: " + t);' + h +
    "            if (FAIL.incrementAndGet() >= FAIL_MAX && !sDisabled) {" + h +
    "                sDisabled = true;" + h +
    '                HookRegistry.log("H60 circuit breaker OPEN —— 本进程不再改底栏（保命优先）");' + h +
    "            }" + h +
    "        } finally {" + h +
    "            sApplying = false;" + h +
    "        }")

# ---------- 6. SettingsActivity：开关 + 文案 ----------
sa = os.path.join(SRC, "SettingsActivity.java"); s = nl(sa)
sub(sa,
    "        addSwitch(look, ModuleConfig.KEY_MIUI_BOTTOM_BAR, \"全面屏底栏跟随皮肤\"," + s +
    "                \"MIUI「全面屏键盘优化」底部那条栏也用所选皮肤的底色/图标色（仅小米定制版有效）。\"," + s +
    "                true);",
    "        addSwitch(look, ModuleConfig.KEY_MIUI_BOTTOM_BAR, \"全面屏底栏跟随皮肤（实验）\"," + s +
    "                \"MIUI「全面屏键盘优化」底部那条栏也用所选皮肤的底色/图标色（仅小米定制版）。\"" + s +
    "                        + \"仍在验证，默认关；若开关后键盘呼不出来，先关掉它再点「重启输入法」。\"," + s +
    "                true);" + s +
    "        addSwitch(look, ModuleConfig.KEY_ASSET_SKIN_IMAGES, \"资源层改位图（实验）\"," + s +
    "                \"连资源层的键帽位图也重画。默认关：重编码会丢 nine-patch 切边数据，曾导致键盘呼不出来。\"," + s +
    "                true);")

# ---------- 7. 版本 44 / 2.0.3-beta4 ----------
man = os.path.join(ROOT, r"lsposed\app\src\main\AndroidManifest.xml")
sub(man, 'android:versionCode="43"', 'android:versionCode="44"')
sub(man, 'android:versionName="2.0.3-beta3"', 'android:versionName="2.0.3-beta4"')
gr = os.path.join(ROOT, r"lsposed\app\build.gradle.kts")
sub(gr, "versionCode = 43", "versionCode = 44")
sub(gr, 'versionName = "2.0.3-beta3"', 'versionName = "2.0.3-beta4"')
st = os.path.join(ROOT, r"lsposed\app\src\main\res\values\strings.xml")
sub(st, "<string name=\"version_label\">v2.0.3-beta3</string>", "<string name=\"version_label\">v2.0.3-beta4</string>")
print("ALL OK")
