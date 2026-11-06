package com.chiron.iosemoji;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chiron.iosemoji.compat.VersionProfile;
import com.chiron.iosemoji.config.ModuleConfig;
import com.chiron.iosemoji.ui.IosRow;
import com.chiron.iosemoji.ui.IosUi;

/**
 * 功能开关页 —— iOS 设置页样式（分组卡片 + 圆角行 + 自绘开关）。
 *
 * <p>只做两件事：把 {@link ModuleConfig} 的键做成开关，并显示版本/兼容信息。
 * 界面全部用代码构建，不引 layout。
 *
 * <p>开关写入模块自己的 SharedPreferences（名字必须与
 * {@link ModuleConfig#PREFS_NAME} 一致），搜狗进程内通过 XSharedPreferences 读取。
 * 生效时机：**下次输入法进程启动**（切换输入法 / 强制停止搜狗）。
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ModuleConfig.PREFS_NAME, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(IosUi.bg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(IosUi.bg(this));
        root.setPadding(0, IosUi.dp(this, 18), 0, IosUi.dp(this, 36));
        scroll.addView(root);
        setContentView(scroll);

        // 头部：返回 + 标题
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
        root.addView(header);

        root.addView(IosUi.footer(this,
                "改动后需要重启输入法进程：切换到别的输入法再切回来，或在系统设置里强制停止搜狗输入法。"));

        // 核心开关
        root.addView(IosUi.sectionHeader(this, "核心"));
        LinearLayout core = IosUi.card(this);
        root.addView(core, IosUi.cardParams(this));

        addSwitch(core, ModuleConfig.KEY_MASTER, "总开关",
                "关闭后本模块在搜狗进程内不做任何事（表情、键盘、诊断全部停用）。", true, true);
        addSwitch(core, ModuleConfig.KEY_KEYBOARD_REPLACE, "iOS 键盘（实验）",
                "用 模块自绘的 iOS 风格键盘顶替搜狗键盘的显示层；输入、联想、词库仍由搜狗引擎完成。", true, true);
        addSwitch(core, ModuleConfig.KEY_CANDIDATE_PROBE, "候选数据探针",
                "只读打印候选列表推送情况，日志量大，仅在排查问题时打开。", false, false);

        // 表情
        root.addView(IosUi.sectionHeader(this, "表情"));
        LinearLayout emoji = IosUi.card(this);
        root.addView(emoji, IosUi.cardParams(this));

        addSwitch(emoji, ModuleConfig.KEY_EMOJI_GROUPS, "隐藏搜狗自带分组",
                "隐藏「组合」和「拼装」两个内置分组，让位给 Apple 风格分组。", true, true);
        addSwitch(emoji, ModuleConfig.KEY_EMOJI_SWIPE, "左右滑动切换分组",
                "放开 NoScrollViewPager 的滑动限制。", true, true);
        addSwitch(emoji, ModuleConfig.KEY_LOGO_TRIGGER, "Logo 触发词",
                "把「苹果logo」等触发词替换为 Apple  Logo 字符。", false, false);

        // 诊断
        root.addView(IosUi.sectionHeader(this, "诊断"));
        LinearLayout diag = IosUi.card(this);
        root.addView(diag, IosUi.cardParams(this));
        addSwitch(diag, ModuleConfig.KEY_DIAGNOSTICS, "视图树诊断",
                "输入法启动 3 秒后 dump 一次输入窗口视图树到日志。", false, false);

        // 兼容信息
        root.addView(IosUi.sectionHeader(this, "兼容信息"));
        LinearLayout info = IosUi.card(this);
        root.addView(info, IosUi.cardParams(this));

        addInfo(info, "本模块版本", moduleVersionLine());
        addInfo(info, "已 DEX 验证的搜狗版本", VersionProfile.TARGET_VERSION_NAME);
        addInfo(info, "目标版本白名单", VersionProfile.describe());
        addInfo(info, "设备上的搜狗", readSogouVersion());
        addInfo(info, "作用域", "com.sohu.inputmethod.sogou.xiaomi\ncom.sohu.inputmethod.sogou");
        addInfo(info, "日志 TAG", "AppleInputForSogou");

        // 说明
        root.addView(IosUi.sectionHeader(this, "说明"));
        root.addView(IosUi.footer(this,
                "• 版本不在白名单时模块自动降级为「只读诊断」，不会替换任何界面。\n"
                        + "• 任何 hook 安装失败都会保留搜狗原行为。\n"
                        + "• 日志可在 LSPosed 日志或 logcat 里按 TAG 过滤。"));
    }

    private void addSwitch(LinearLayout card, String key, String label, String desc,
                           boolean def, boolean withSeparator) {
        boolean checked = prefs.getBoolean(key, def);
        LinearLayout row = IosRow.switchRow(this, label, desc, checked,
                value -> prefs.edit().putBoolean(key, value).apply());
        card.addView(row);
        if (withSeparator) card.addView(IosUi.separator(this, 16));
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
}
