package com.chiron.iosemoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chiron.iosemoji.compat.VersionProfile;
import com.chiron.iosemoji.config.ConfigMigration;
import com.chiron.iosemoji.config.ConfigValues;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.skin.SkinPreset;
import com.chiron.iosemoji.ui.IosRow;
import com.chiron.iosemoji.ui.IosSwitch;
import com.chiron.iosemoji.ui.IosUi;

/**
 * 功能开关页（iOS 设置页样式）。
 *
 * <p>两个关键点，都是为了"点了真的有反应"：
 * <ol>
 *   <li><b>双向通道</b>：每次改开关 → 写本地 prefs（冷启动用）**并且**用有序广播把
 *       全部键值下发到搜狗进程（运行中即时生效），用回执告诉你"已生效 / 进程没起来"；</li>
 *   <li><b>状态回显</b>：顶部卡片显示搜狗进程在线状态、已装 hook 列表、以及进程内
 *       当前真正生效的配置值 —— 不再靠"猜"。</li>
 * </ol>
 *
 * <p>界面全部用代码构建，不引 layout。
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView hooksText;
    private TextView effectiveText;
    private TextView requestHint;
    /** 当前皮肤风格的值（iOS / 微信 / Google / 莫奈取色）。 */
    private TextView presetValue;

    /** 广播回执窗口：500ms 内没回执就认为进程不在。 */
    private static final long REPLY_TIMEOUT_MS = 500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ModuleConfig.PREFS_NAME, MODE_PRIVATE);
        // alpha4 迁移：关掉会与搜狗布局冲突的自绘键盘、启用皮肤方案（只跑一次）
        ConfigMigration.runOnce(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(IosUi.bg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(IosUi.bg(this));
        root.setPadding(0, IosUi.dp(this, 18), 0, IosUi.dp(this, 36));
        scroll.addView(root);
        setContentView(scroll);

        buildHeader(root);
        buildStatusCard(root);
        buildSwitchCards(root);
        buildInfoCards(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        queryStatus(false);
    }

    // ── 头部 ────────────────────────────────────────────────────────────

    private void buildHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(IosUi.dp(this, 20), 0, IosUi.dp(this, 20), 0);

        TextView back = IosUi.text(this, "‹ 返回", 17f, IosUi.BLUE, false);
        back.setPadding(0, IosUi.dp(this, 8), IosUi.dp(this, 12), IosUi.dp(this, 8));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = IosUi.text(this, "功能开关", 17f, IosUi.label(this), true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = IosUi.text(this, "完成", 17f, IosUi.BLUE, false);
        done.setPadding(IosUi.dp(this, 12), IosUi.dp(this, 8), 0, IosUi.dp(this, 8));
        done.setOnClickListener(v -> finish());
        header.addView(done);

        root.addView(header);
    }

    // ── 连接状态卡片 ─────────────────────────────────────────────────────

    private void buildStatusCard(LinearLayout root) {
        root.addView(IosUi.sectionHeader(this, "连接状态"));
        LinearLayout card = IosUi.card(this);
        root.addView(card, IosUi.cardParams(this));

        statusText = IosUi.text(this, "检测中…", 15f, IosUi.label2(this), false);
        card.addView(labelRow("搜狗进程", statusText));

        hooksText = IosUi.text(this, "—", 13f, IosUi.label2(this), false);
        card.addView(labelRow("已装 hook", hooksText));

        effectiveText = IosUi.text(this, "—", 13f, IosUi.label2(this), false);
        effectiveText.setTextIsSelectable(true);
        effectiveText.setOnLongClickListener(v -> {
            copyToClipboard("ChironSkinState", effectiveText.getText().toString());
            return true;
        });
        card.addView(labelRow("皮肤 / 生效值", effectiveText));

        requestHint = IosUi.text(this, "开关改动会立刻下发；若显示未连接，切换一下输入法再回来即可。",
                13f, IosUi.label2(this), false);
        requestHint.setPadding(IosUi.dp(this, 16), IosUi.dp(this, 6),
                IosUi.dp(this, 16), IosUi.dp(this, 10));
        card.addView(requestHint);

        LinearLayout btn = IosRow.button(this, "重新检测连接", IosUi.BLUE, v -> queryStatus(true));
        card.addView(btn);

        // 重启输入法：等于帮用户在系统设置里"强制停止搜狗输入法"，但不用离开本页面。
        // 换皮肤 / 换深浅色 / 改开关后如果观感没变，按一下这个，最干净。
        LinearLayout btnRestart = IosRow.button(this, "重启输入法（重载皮肤）", IosUi.ORANGE,
                v -> restartIme());
        card.addView(btnRestart);
        card.addView(IosUi.separator(this, 16));
    }

    private LinearLayout labelRow(String label, TextView value) {
        LinearLayout row = IosRow.base(this);
        row.addView(IosUi.text(this, label, 15f, IosUi.label(this), false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        value.setPadding(IosUi.dp(this, 12), 0, 0, 0);
        row.addView(value);
        return row;
    }

    // ── 开关卡片 ─────────────────────────────────────────────────────────

    private void buildSwitchCards(LinearLayout root) {
        root.addView(IosUi.sectionHeader(this, "核心"));
        LinearLayout core = IosUi.card(this);
        root.addView(core, IosUi.cardParams(this));
        addSwitch(core, ModuleConfig.KEY_MASTER, "总开关",
                "关闭后本模块在搜狗进程内不做任何事（表情、皮肤、诊断全部停用）。", true);
        addSwitch(core, ModuleConfig.KEY_CANDIDATE_PROBE, "候选数据探针",
                "只读打印候选列表推送情况，日志量大，仅在排查问题时打开。", false);

        root.addView(IosUi.sectionHeader(this, "外观"));
        LinearLayout look = IosUi.card(this);
        root.addView(look, IosUi.cardParams(this));
        addSwitch(look, ModuleConfig.KEY_THEME_SKIN, "改皮肤（推荐）",
                "改搜狗**自己的默认皮肤**：配色、键帽形状、候选栏底色都按所选风格重画。"
                        + "布局、按键命中、候选栏高度全部沿用搜狗原样，所以不会错位。", true);
        addPresetRow(look);
        addSwitch(look, ModuleConfig.KEY_KEYBOARD_REPLACE, "自绘键盘（实验）",
                "用模块自绘的键盘盖在搜狗键盘上。可能与搜狗自己算的布局/命中区冲突（样式错乱），"
                        + "一般不需要打开。", false);

        root.addView(IosUi.sectionHeader(this, "表情"));
        LinearLayout emoji = IosUi.card(this);
        root.addView(emoji, IosUi.cardParams(this));
        addSwitch(emoji, ModuleConfig.KEY_EMOJI_GROUPS, "隐藏搜狗自带分组",
                "隐藏「组合」和「拼装」两个内置分组，让位给 Apple 风格分组。", true);
        addSwitch(emoji, ModuleConfig.KEY_EMOJI_SWIPE, "左右滑动切换分组",
                "放开 NoScrollViewPager 的滑动限制。", true);
        addSwitch(emoji, ModuleConfig.KEY_LOGO_TRIGGER, "Logo 触发词",
                "把「苹果logo」等触发词替换为 Apple  Logo 字符。", false);

        root.addView(IosUi.sectionHeader(this, "诊断"));
        LinearLayout diag = IosUi.card(this);
        root.addView(diag, IosUi.cardParams(this));
        addSwitch(diag, ModuleConfig.KEY_DIAGNOSTICS, "视图树诊断",
                "输入法启动 3 秒后 dump 一次输入窗口视图树到日志。", false);
    }

    private void addSwitch(LinearLayout card, final String key, String label, String desc,
                           boolean withSeparator) {
        boolean checked = prefs.getBoolean(key, ConfigValues.defaultValue(key));
        LinearLayout row = IosRow.switchRow(this, label, desc, checked,
                new IosSwitch.OnChangeListener() {
                    @Override
                    public void onChanged(boolean value) {
                        applySwitch(key, value);
                    }
                });
        card.addView(row);
        if (withSeparator) card.addView(IosUi.separator(this, 16));
    }

    // ── 皮肤风格选择 ─────────────────────────────────────────────────────

    private void addPresetRow(LinearLayout card) {
        String cur = prefs.getString(ModuleConfig.KEY_SKIN_PRESET, ModuleConfig.DEFAULT_SKIN_PRESET);
        LinearLayout row = IosRow.base(this);
        row.addView(IosRow.textColumn(this, "皮肤风格",
                "iOS / 微信 / Google / 莫奈取色（莫奈要 Android 12+）"), IosRow.weight());
        presetValue = IosUi.text(this, SkinPreset.labelOf(cur), 15f, IosUi.label2(this), false);
        presetValue.setPadding(IosUi.dp(this, 12), 0, 0, 0);
        row.addView(presetValue);
        row.addView(IosRow.chevron(this));
        row.setClickable(true);
        row.setOnClickListener(v -> showPresetDialog());
        card.addView(row);
        card.addView(IosUi.separator(this, 16));
    }

    private void showPresetDialog() {
        final String[] ids = SkinPreset.IDS;
        final String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) labels[i] = SkinPreset.labelOf(ids[i]);
        new AlertDialog.Builder(this)
                .setTitle("皮肤风格")
                .setItems(labels, (dialog, which) -> applyPreset(ids[which]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 换皮肤预设：落盘 + 下发（搜狗进程会清掉位图缓存后按新配色重画）。 */
    private void applyPreset(String presetId) {
        prefs.edit().putString(ModuleConfig.KEY_SKIN_PRESET, presetId).commit();
        if (presetValue != null) presetValue.setText(SkinPreset.labelOf(presetId));
        final int[] ok = {0};
        for (String pkg : Protocol.TARGETS) {
            sendConfigIntent(pkg, ok);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ok[0] > 0) {
                    toast("已换皮肤：" + SkinPreset.labelOf(presetId));
                } else {
                    toast("已保存：" + SkinPreset.labelOf(presetId)
                            + "（搜狗进程未运行，切换输入法后生效）");
                }
                queryStatus(false);
            }
        }, REPLY_TIMEOUT_MS);
    }

    /** 改开关：先落盘（冷启动用），再下发（运行中即时生效），最后按回执给反馈。 */
    private void applySwitch(String key, boolean value) {
        prefs.edit().putBoolean(key, value).commit();
        final String label = ConfigValues.label(key);
        final int[] ok = {0};
        for (String pkg : Protocol.TARGETS) {
            sendConfigIntent(pkg, ok);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ok[0] > 0) {
                    toast("已生效：" + label + " → " + (value ? "开" : "关"));
                } else {
                    toast("已保存：" + label + " → " + (value ? "开" : "关")
                            + "（搜狗进程未运行，切换输入法后生效）");
                }
                queryStatus(false);
            }
        }, REPLY_TIMEOUT_MS);
    }

    /**
     * 重启输入法：让搜狗进程自己结束，系统随后会在需要时重新拉起它。
     *
     * <p>效果等同于用户在系统设置里「强制停止」搜狗输入法，但不用离开这一页。
     * 皮肤 ini / 位图在进程内是带缓存的对象，换预设或换深浅色后若观感没变，
     * 按一下这个是最干净的重载方式。
     */
    private void restartIme() {
        final int[] ok = {0};
        for (String pkg : Protocol.TARGETS) {
            try {
                Intent intent = new Intent(Protocol.ACTION_RESTART).setPackage(pkg);
                sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (getResultCode() == Protocol.RESULT_OK) ok[0]++;
                    }
                }, main, 0, null, null);
            } catch (Throwable t) {
                // 某个包没装/不可见就跳过
            }
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ok[0] > 0) {
                    toast("已重启输入法：点任意输入框即可重新加载皮肤");
                } else {
                    toast("没连上搜狗进程（先点开一次键盘，再按这个）");
                }
                queryStatus(false);
            }
        }, 1500);
    }

    private void sendConfigIntent(String targetPkg, final int[] ok) {
        try {
            Intent intent = new Intent(Protocol.ACTION_CFG).setPackage(targetPkg);
            for (String k : ConfigValues.KEYS) {
                intent.putExtra(k, prefs.getBoolean(k, ConfigValues.defaultValue(k)));
            }
            // 皮肤预设是字符串，一起带过去（搜狗进程据此换配色并清位图缓存）
            intent.putExtra(ModuleConfig.KEY_SKIN_PRESET,
                    prefs.getString(ModuleConfig.KEY_SKIN_PRESET, ModuleConfig.DEFAULT_SKIN_PRESET));
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (getResultCode() == Protocol.RESULT_OK) ok[0]++;
                }
            }, main, 0, null, null);
        } catch (Throwable t) {
            // 广播失败就靠超时提示，不影响已落盘的配置
        }
    }

    // ── 状态查询 ─────────────────────────────────────────────────────────

    private void queryStatus(final boolean userInitiated) {
        final StringBuilder data = new StringBuilder();
        final int[] replies = {0};
        for (final String pkg : Protocol.TARGETS) {
            try {
                Intent intent = new Intent(Protocol.ACTION_CFG_QUERY).setPackage(pkg);
                sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (getResultCode() != Protocol.RESULT_OK) return;
                        String d = getResultData();
                        if (d == null || d.length() == 0) return;
                        replies[0]++;
                        if (data.length() > 0) data.append(" | ");
                        data.append(d);
                    }
                }, main, 0, null, null);
            } catch (Throwable ignored) {
                // 单个包失败不影响另一个
            }
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (replies[0] == 0) {
                    statusText.setText("未连接");
                    statusText.setTextColor(IosUi.RED);
                    hooksText.setText("—");
                    effectiveText.setText("—");
                    if (userInitiated) {
                        toast("搜狗进程没响应：确认已启用模块、作用域包含搜狗、版本在白名单内");
                    }
                } else {
                    renderState(data.toString());
                }
            }
        }, REPLY_TIMEOUT_MS);
    }

    /** 解析 ConfigBridge.state() 返回的 "v=…;src=…;hooks=[…];master=1;…;sg=…"。 */
    private void renderState(String raw) {
        String version = null;
        String source = null;
        String hooks = null;
        String sg = null;
        String skinPreset = null;
        String skinIni = null;
        String skinImg = null;
        String skinParser = null;
        String skinCached = null;
        String skinKept = null;
        String skinSkip = null;
        StringBuilder eff = new StringBuilder();
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            switch (k) {
                case "v":
                    version = v;
                    break;
                case "src":
                    source = v;
                    break;
                case "hooks":
                    hooks = v;
                    break;
                case "sg":
                    sg = v;
                    break;
                case "skin":
                    skinPreset = v;
                    break;
                case "skini":
                    skinIni = v;
                    break;
                case "skimg":
                    skinImg = v;
                    break;
                case "skinq":
                    skinParser = v;
                    break;
                case "skinc":
                    skinCached = v;
                    break;
                case "skink":
                    skinKept = v;
                    break;
                case "skip":
                    skinSkip = v;
                    break;
                default:
                    if (eff.length() > 0) eff.append(' ');
                    eff.append(k).append('=').append("1".equals(v) ? "开" : "关");
            }
        }
        statusText.setText("已连接" + (source != null ? "（配置来源 " + source + "）" : ""));
        statusText.setTextColor(IosUi.GREEN);
        hooksText.setText(hooks == null || hooks.length() == 0 ? "—" : hooks);
        // 皮肤一行的读法：ini=调用/改动，img=解码/重画；img 右侧是 0 就说明"读到了但没认出来"
        StringBuilder skinLine = new StringBuilder();
        skinLine.append("皮肤=").append(skinPreset == null ? "ios" : skinPreset);
        if (skinIni != null) skinLine.append("  ini=").append(skinIni);
        if (skinImg != null) skinLine.append("  图=").append(skinImg);
        if (skinParser != null) skinLine.append("  解析=").append(skinParser);
        if (skinCached != null) skinLine.append("  缓存=").append(skinCached);
        if (skinKept != null) skinLine.append("  保留=").append(skinKept);
        if (eff.length() > 0) skinLine.append('\n').append(eff);
        if (skinSkip != null && skinSkip.length() > 0) {
            skinLine.append("\n没认出来的图：").append(skinSkip);
        }
        effectiveText.setText(skinLine.toString());
        requestHint.setText("搜狗版本 " + (sg == null ? "unknown" : sg)
                + " · 模块 " + (version == null ? "unknown" : version));
    }

    // ── 兼容信息 ─────────────────────────────────────────────────────────

    private void buildInfoCards(LinearLayout root) {
        root.addView(IosUi.sectionHeader(this, "兼容信息"));
        LinearLayout info = IosUi.card(this);
        root.addView(info, IosUi.cardParams(this));

        addInfo(info, "本模块版本", moduleVersionLine());
        addInfo(info, "已 DEX 验证的搜狗版本", VersionProfile.TARGET_VERSION_NAME);
        addInfo(info, "目标版本白名单", VersionProfile.describe());
        addInfo(info, "设备上的搜狗", readSogouVersion());
        addInfo(info, "作用域", "com.sohu.inputmethod.sogou.xiaomi\ncom.sohu.inputmethod.sogou");
        addInfo(info, "日志 TAG", "AppleInputForSogou");

        root.addView(IosUi.sectionHeader(this, "说明"));
        root.addView(IosUi.footer(this,
                "• 开关改动即时下发到已运行的搜狗进程；进程没起来时改动会在下次启动生效。\n"
                        + "• 版本不在白名单时模块自动降级为「只读诊断」，不会替换任何界面。\n"
                        + "• 任何 hook 安装失败都会保留搜狗原行为。\n"
                        + "• 日志可在 LSPosed 日志或 logcat 里按 TAG 过滤。"));
    }

    private void addInfo(LinearLayout card, String label, String value) {
        LinearLayout row = IosRow.base(this);
        row.addView(IosUi.text(this, label, 15f, IosUi.label(this), false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = IosUi.text(this, value, 13f, IosUi.label2(this), false);
        v.setPadding(IosUi.dp(this, 12), 0, 0, 0);
        row.addView(v);
        card.addView(row);
        card.addView(IosUi.separator(this, 16));
    }

    private String moduleVersionLine() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private String readSogouVersion() {
        PackageManager pm = getPackageManager();
        for (String pkg : Protocol.TARGETS) {
            try {
                return pm.getPackageInfo(pkg, 0).versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 未安装，继续
            }
        }
        return "未安装（或未在作用域内可见）";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 长按状态文本即可复制，方便把「皮肤改到没改到」的原样数字贴出来。 */
    private void copyToClipboard(String label, String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            toast("已复制诊断信息");
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }
}
