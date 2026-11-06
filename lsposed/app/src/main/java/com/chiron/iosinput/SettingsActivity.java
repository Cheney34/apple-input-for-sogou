package com.chiron.iosinput;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.chiron.iosinput.compat.VersionProfile;
import com.chiron.iosinput.config.ModuleConfig;

/**
 * 功能开关页（v2.0）。
 *
 * <p>只做两件事：把 {@link ModuleConfig} 的键做成开关，并显示版本/兼容信息。
 * 界面全部用代码构建，不额外引 layout —— 避免和 v1.8 既有界面资源冲突。
 *
 * <p>开关写入的是模块自己的 SharedPreferences（名字必须与
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
        scroll.setBackgroundColor(Color.parseColor("#0E0E10"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title(root, "功能开关");
        hint(root, "改动后需要重启输入法进程：切换到别的输入法再切回来，或在系统设置里强制停止搜狗输入法。");

        switchRow(root, ModuleConfig.KEY_MASTER, "总开关",
                "关闭后本模块在搜狗进程内不做任何事（表情、键盘、诊断全部停用）。", true);
        switchRow(root, ModuleConfig.KEY_KEYBOARD_REPLACE, "iOS 键盘皮肤（实验）",
                "用 Chiron 自绘键盘顶替搜狗键盘的显示层；输入、联想、词库仍由搜狗引擎完成。", true);
        switchRow(root, ModuleConfig.KEY_EMOJI_GROUPS, "隐藏搜狗自带表情分组",
                "隐藏「组合」和「拼装」两个内置分组，让位给 Apple 风格分组。", true);
        switchRow(root, ModuleConfig.KEY_EMOJI_SWIPE, "表情面板左右滑动切换分组",
                "放开 NoScrollViewPager 的滑动限制。", true);
        switchRow(root, ModuleConfig.KEY_LOGO_TRIGGER, "Logo 触发词（默认关闭）",
                "把「苹果logo」等触发词替换为 Apple  Logo 字符。", false);
        switchRow(root, ModuleConfig.KEY_CANDIDATE_PROBE, "候选数据探针（诊断）",
                "只读打印候选列表推送情况，日志量大，仅在排查问题时打开。", false);
        switchRow(root, ModuleConfig.KEY_DIAGNOSTICS, "视图树诊断",
                "输入法启动 3 秒后 dump 一次输入窗口视图树到日志。", false);

        title(root, "兼容信息");
        hint(root, moduleVersionLine());
        hint(root, "目标版本白名单：" + VersionProfile.describe());
        hint(root, "已 DEX 验证的目标：" + VersionProfile.TARGET_VERSION_NAME);
        hint(root, "搜狗安装包：" + readSogouVersion());
        hint(root, "作用域：com.sohu.inputmethod.sogou.xiaomi / com.sohu.inputmethod.sogou");

        title(root, "说明");
        hint(root, "• 版本不在白名单时模块自动降级为「只读诊断」，不会替换任何界面。\n"
                + "• 任何 hook 安装失败都会保留搜狗原行为。\n"
                + "• 日志 TAG：ChironIOSInput（用 LSPosed 日志或 logcat 查看）。");

        setContentView(scroll);
    }

    private void switchRow(LinearLayout parent, final String key, String label, String desc,
                           boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(14);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.parseColor("#F2F2F7"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(Color.parseColor("#8E8E93"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setPadding(0, dp(2), dp(8), 0);
        texts.addView(d);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });

        row.addView(texts);
        row.addView(sw);
        parent.addView(row, rp);
    }

    private void title(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#F2F2F7"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        parent.addView(tv, lp);
    }

    private void hint(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#8E8E93"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setLineSpacing(dp(2), 1f);
        parent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private String moduleVersionLine() {
        try {
            return "本模块版本：" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "本模块版本：unknown";
        }
    }

    private String readSogouVersion() {
        PackageManager pm = getPackageManager();
        for (String pkg : Protocol.TARGETS) {
            try {
                String v = pm.getPackageInfo(pkg, 0).versionName;
                return pkg + " = " + v;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 未安装，继续
            }
        }
        return "未安装（或未在作用域内可见）";
    }
}
