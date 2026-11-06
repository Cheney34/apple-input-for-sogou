package com.chiron.iosemoji;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chiron.iosemoji.config.ConfigMigration;
import com.chiron.iosemoji.ui.IosRow;
import com.chiron.iosemoji.ui.IosSwitch;
import com.chiron.iosemoji.ui.IosUi;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模块主界面 —— iOS 设置页风格，全部用代码搭（不依赖 XML 布局、不依赖 androidx）。
 *
 * <p>功能与旧版完全一致：分组选择 / 安装 / 还原 / 隐藏图标 / 连接检测 / 运行日志，
 * 只把外观换成 iOS：大标题、分组卡片、圆角行、右侧 ✓ 与 ›。
 */
public class MainActivity extends Activity {

    private static final int MAX_LOG_LINES = 300;
    private static final long RESPONSE_TIMEOUT_MS = 4000L;

    private static final String PREFS = "settings";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_HIDE_ICON = "hide_icon";
    private static final String ALIAS = Protocol.MODULE_PKG + ".Launcher";

    private static final int COLOR_ICON_ON = 0xFFFF9500;
    private static final int COLOR_ICON_OFF = 0xFF9A9CA8;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Set<Integer> selected = new HashSet<>();
    private final Map<Integer, View> groupRows = new LinkedHashMap<>();
    private final Map<Integer, TextView> groupChecks = new LinkedHashMap<>();

    private SharedPreferences prefs;

    private TextView statusView;
    private TextView logView;
    private TextView summaryView;
    private TextView selectAllView;
    private TextView logToggle;
    private TextView btnInstallText;
    private View btnInstallRow;
    private View btnAllRow;
    private View btnRestoreRow;
    private View dot;
    private ProgressBar bar;
    private IosSwitch swHide;

    private boolean receiverRegistered = false;
    private boolean awaitingResponse = false;
    private Runnable timeoutTask;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String phase = intent.getStringExtra(Protocol.EXTRA_PHASE);
            int current = intent.getIntExtra(Protocol.EXTRA_CURRENT, 0);
            int total = intent.getIntExtra(Protocol.EXTRA_TOTAL, 0);
            String message = intent.getStringExtra(Protocol.EXTRA_MESSAGE);

            awaitingResponse = false;
            if (timeoutTask != null) handler.removeCallbacks(timeoutTask);

            if ("ready".equals(phase)) {
                setConnected(true);
                if (message != null && message.startsWith("已连接 · ")) {
                    statusView.setText("已连接 · " + friendlyName(message.substring(message.indexOf('·') + 1).trim()));
                } else if (message != null) {
                    statusView.setText(message);
                }
                bar.setVisibility(View.GONE);
                setBusy(false);
                return;
            }

            if (message != null) {
                statusView.setText(message);
                appendLog(message);
            }
            if (total > 0 && current > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setMax(total);
                bar.setProgress(Math.min(current, total));
            } else if ("extract".equals(phase) || "commit".equals(phase) || "restore".equals(phase)) {
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                setBusy(true);
            } else if ("done".equals(phase)) {
                bar.setVisibility(View.GONE);
                bar.setIndeterminate(false);
                setBusy(false);
                appendLog("搜狗输入法已重启，切回输入法查看效果");
            } else if ("error".equals(phase)) {
                bar.setVisibility(View.GONE);
                bar.setIndeterminate(false);
                setBusy(false);
                setConnected(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // alpha4 迁移：把老版本默认开启的「自绘键盘」关掉（会跟搜狗布局打架），并启用皮肤方案
        ConfigMigration.runOnce(this);

        buildUi();
        loadSelection();
        buildGroups();
        refreshCards();

        logView.setText("等待连接…");
        setConnected(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Protocol.ACTION_STATUS);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
            receiverRegistered = false;
        }
        ping(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        if (timeoutTask != null) handler.removeCallbacks(timeoutTask);
    }

    // ── 界面搭建（iOS 设置页样式）────────────────────────────────────────────

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(IosUi.bg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(IosUi.bg(this));
        root.setPadding(0, IosUi.dp(this, 22), 0, IosUi.dp(this, 36));
        scroll.addView(root);
        setContentView(scroll);

        int pad = IosUi.dp(this, 22);

        TextView title = IosUi.largeTitle(this, "Apple Input for Sogou");
        title.setPadding(pad, 0, pad, 0);
        root.addView(title);

        TextView sub = IosUi.text(this, "搜狗负责输入 · Apple 负责体验", 14f, IosUi.label2(this), false);
        sub.setPadding(pad, IosUi.dp(this, 4), pad, 0);
        root.addView(sub);

        // 状态卡
        root.addView(IosUi.sectionHeader(this, "状态"));
        LinearLayout statusCard = IosUi.card(this);
        root.addView(statusCard, IosUi.cardParams(this));

        LinearLayout statusRow = IosRow.base(this);
        dot = IosUi.dot(this, 0xFF6C6E7A);
        statusRow.addView(dot);
        statusView = IosUi.text(this, "未连接", 17f, IosUi.label(this), false);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(statusRow);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        bar.setProgressTintList(ColorStateList.valueOf(IosUi.BLUE));
        bar.setIndeterminateTintList(ColorStateList.valueOf(IosUi.BLUE));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 4));
        barLp.leftMargin = IosUi.dp(this, 16);
        barLp.rightMargin = IosUi.dp(this, 16);
        barLp.bottomMargin = IosUi.dp(this, 12);
        statusCard.addView(bar, barLp);

        // 分组卡
        root.addView(IosUi.sectionHeader(this, "表情分组"));
        groupsCard = IosUi.card(this);
        root.addView(groupsCard, IosUi.cardParams(this));

        summaryView = IosUi.footer(this, "");
        root.addView(summaryView);

        // 操作卡
        root.addView(IosUi.sectionHeader(this, "操作"));
        LinearLayout actionCard = IosUi.card(this);
        root.addView(actionCard, IosUi.cardParams(this));

        btnInstallRow = IosRow.button(this, "安装选中分组", IosUi.BLUE, v -> {
            if (selected.isEmpty()) return;
            run(Protocol.CMD_INSTALL_GROUPS, "安装选中的 " + selected.size() + " 组", joinSelected());
        });
        btnInstallText = (TextView) ((LinearLayout) btnInstallRow).getChildAt(0);
        actionCard.addView(btnInstallRow);
        actionCard.addView(IosUi.separator(this, 0));

        btnAllRow = IosRow.button(this, "安装全部 10 组", IosUi.BLUE,
                v -> run(Protocol.CMD_INSTALL_ALL, "安装全部 10 组", null));
        actionCard.addView(btnAllRow);
        actionCard.addView(IosUi.separator(this, 0));

        btnRestoreRow = IosRow.button(this, "还原成安装前", IosUi.RED, v -> confirmRestore());
        actionCard.addView(btnRestoreRow);

        // 模块卡
        root.addView(IosUi.sectionHeader(this, "模块"));
        LinearLayout moduleCard = IosUi.card(this);
        root.addView(moduleCard, IosUi.cardParams(this));

        moduleCard.addView(IosRow.clickable(this, "功能开关 / 兼容信息",
                "键盘替换、候选探针、表情分组、日志", v ->
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class))));
        moduleCard.addView(IosUi.separator(this, 16));

        LinearLayout hideRow = IosRow.switchRow(this, "隐藏桌面图标",
                "从 LSPosed 或「应用信息 → 打开」进入", isIconHidden(), this::applyHideIcon);
        swHide = findSwitch(hideRow);
        moduleCard.addView(hideRow);
        moduleCard.addView(IosUi.separator(this, 16));

        moduleCard.addView(IosRow.clickable(this, "重新检测连接",
                "在搜狗里打一次字，让输入法进程先启动", v -> ping(true)));
        moduleCard.addView(IosUi.separator(this, 16));

        // 重启输入法：换皮肤 / 改开关后观感没变时按一下，等于帮用户强停一遍搜狗进程。
        moduleCard.addView(IosRow.clickable(this, "重启输入法",
                "重新加载皮肤与配置（不用去系统设置强停）", v -> restartIme()));

        // 日志卡
        root.addView(IosUi.sectionHeader(this, "运行日志"));
        LinearLayout logCard = IosUi.card(this);
        root.addView(logCard, IosUi.cardParams(this));

        LinearLayout logHeader = IosRow.base(this);
        logHeader.addView(IosUi.text(this, "Hook 日志", 17f, IosUi.label(this), false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logToggle = IosUi.text(this, "展开", 15f, IosUi.label2(this), false);
        logHeader.addView(logToggle);
        logCard.addView(logHeader);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(IosUi.label2(this));
        logView.setVisibility(View.GONE);
        logView.setPadding(IosUi.dp(this, 16), IosUi.dp(this, 4),
                IosUi.dp(this, 16), IosUi.dp(this, 14));
        logCard.addView(logView);

        logHeader.setOnClickListener(v -> {
            boolean visible = logView.getVisibility() == View.VISIBLE;
            logView.setVisibility(visible ? View.GONE : View.VISIBLE);
            logToggle.setText(visible ? "展开" : "收起");
        });
    }

    private LinearLayout groupsCard;

    private IosSwitch findSwitch(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof IosSwitch) return (IosSwitch) child;
        }
        return null;
    }

    // ── 分组行 ──────────────────────────────────────────────────────────────

    private void buildGroups() {
        groupsCard.removeAllViews();
        groupRows.clear();
        groupChecks.clear();

        for (int i = 0; i < Groups.ALL.length; i++) {
            Groups.Group group = Groups.ALL[i];
            final int id = group.id;

            LinearLayout row = IosRow.base(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(group.icon);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    IosUi.dp(this, 22), IosUi.dp(this, 22));
            iconLp.rightMargin = IosUi.dp(this, 12);
            row.addView(icon, iconLp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(IosUi.text(this, group.label, 17f, IosUi.label(this), false));
            texts.addView(IosUi.text(this, group.count + " 张表情", 13f, IosUi.label2(this), false));
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView check = IosUi.text(this, "✓", 19f, IosUi.BLUE, true);
            check.setVisibility(View.GONE);
            check.setPadding(IosUi.dp(this, 8), 0, 0, 0);
            row.addView(check);

            row.setOnClickListener(v -> {
                if (!selected.remove(id)) selected.add(id);
                saveSelection();
                refreshCards();
            });

            groupsCard.addView(row);
            if (i < Groups.ALL.length - 1) groupsCard.addView(IosUi.separator(this, 50));

            groupRows.put(id, row);
            groupChecks.put(id, check);
        }

        // 全选 / 取消全选
        groupsCard.addView(IosUi.separator(this, 0));
        LinearLayout selectAllRow = IosRow.base(this);
        selectAllView = IosUi.text(this, "全选", 17f, IosUi.BLUE, false);
        selectAllRow.addView(selectAllView);
        selectAllRow.setOnClickListener(v -> {
            if (selected.size() == Groups.ALL.length) {
                selected.clear();
            } else {
                for (Groups.Group g : Groups.ALL) selected.add(g.id);
            }
            saveSelection();
            refreshCards();
        });
        groupsCard.addView(selectAllRow);
    }

    private void refreshCards() {
        int total = 0;
        for (Groups.Group group : Groups.ALL) {
            TextView check = groupChecks.get(group.id);
            if (check == null) continue;
            boolean on = selected.contains(group.id);
            check.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) total += group.count;
        }

        boolean all = selected.size() == Groups.ALL.length;
        if (selectAllView != null) selectAllView.setText(all ? "取消全选" : "全选");

        if (selected.isEmpty()) {
            summaryView.setText("未选择任何分组，点上面的行选择要替换的表情");
        } else {
            summaryView.setText("已选 " + selected.size() + " 组 · 共 " + total + " 张表情");
        }
        if (btnInstallText != null) {
            btnInstallText.setText(selected.isEmpty()
                    ? "安装选中分组"
                    : "安装选中的 " + selected.size() + " 组");
        }
        setRowEnabled(btnInstallRow, !selected.isEmpty());
    }

    private void loadSelection() {
        String raw = prefs.getString(KEY_SELECTED, null);
        if (raw == null) {
            for (Groups.Group g : Groups.ALL) selected.add(g.id);
            return;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.length() == 0) continue;
            try {
                selected.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveSelection() {
        StringBuilder sb = new StringBuilder();
        for (Groups.Group g : Groups.ALL) {
            if (!selected.contains(g.id)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(g.id);
        }
        prefs.edit().putString(KEY_SELECTED, sb.toString()).apply();
    }

    // ── 交互 ────────────────────────────────────────────────────────────────

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle("还原原始表情")
                .setMessage(R.string.confirm_restore)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        run(Protocol.CMD_RESTORE, "还原成安装前", null);
                    }
                })
                .show();
    }

    private boolean isIconHidden() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(new ComponentName(this, ALIAS));
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyHideIcon(boolean hide) {
        ComponentName alias = new ComponentName(this, ALIAS);
        try {
            getPackageManager().setComponentEnabledSetting(alias,
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            prefs.edit().putBoolean(KEY_HIDE_ICON, hide).apply();
            appendLog(hide ? "桌面图标已隐藏 · 从 LSPosed 或「应用信息 → 打开」可进入"
                    : "桌面图标已恢复");
        } catch (Throwable t) {
            appendLog("切换失败：" + t);
            if (swHide != null) swHide.setChecked(!hide);
        }
    }

    // ── 通信 ────────────────────────────────────────────────────────────────

    private String joinSelected() {
        StringBuilder sb = new StringBuilder();
        for (Groups.Group g : Groups.ALL) {
            if (!selected.contains(g.id)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(g.id);
        }
        return sb.toString();
    }

    private void run(String cmd, String label, String groups) {
        appendLog("→ " + label);
        boolean sent = sendCommand(cmd, groups);
        if (!sent) {
            statusView.setText("没找到搜狗输入法（小米渠道包或通用包）");
            setConnected(false);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        setBusy(true);
        awaitingResponse = true;
        if (timeoutTask != null) handler.removeCallbacks(timeoutTask);
        timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (!awaitingResponse) return;
                bar.setVisibility(View.GONE);
                setBusy(false);
                statusView.setText("没有收到响应。请确认：LSPosed 已启用本模块、作用域勾选了「搜狗输入法」，"
                        + "并且刚才切到输入法打过字（让搜狗进程先启动）");
                appendLog("超时：搜狗进程里没有 Hook 在监听");
                setConnected(false);
            }
        };
        handler.postDelayed(timeoutTask, RESPONSE_TIMEOUT_MS);
    }

    private void ping(boolean manual) {
        if (sendCommand(Protocol.CMD_PING, null)) {
            if (manual) {
                statusView.setText("正在检测…");
                appendLog("→ 检查连接");
            }
            awaitingResponse = true;
            if (timeoutTask != null) handler.removeCallbacks(timeoutTask);
            timeoutTask = new Runnable() {
                @Override
                public void run() {
                    if (!awaitingResponse) return;
                    setBusy(false);
                    setConnected(false);
                    statusView.setText("未连接。LSPosed 里启用模块并勾选搜狗输入法作用域，"
                            + "然后切到搜狗打一次字，再回来点这里");
                    appendLog("未连接：Hook 没有应答");
                }
            };
            handler.postDelayed(timeoutTask, RESPONSE_TIMEOUT_MS);
        } else if (manual) {
            statusView.setText("没找到搜狗输入法（小米渠道包或通用包）");
            setConnected(false);
        }
    }

    /**
     * 重启输入法：让搜狗进程自己结束，系统随后重新拉起它。
     *
     * <p>等价于「系统设置 → 应用 → 搜狗输入法 → 强制停止」，但一键完成。
     * 换皮肤或改开关后观感没变时，按这个最干净。
     */
    private void restartIme() {
        List<String> targets = installedTargets();
        if (targets.isEmpty()) {
            appendLog("没找到搜狗输入法，无法重启");
            statusView.setText("没找到搜狗输入法（小米渠道包或通用包）");
            setConnected(false);
            return;
        }
        final int[] ok = {0};
        for (String pkg : targets) {
            try {
                Intent intent = new Intent(Protocol.ACTION_RESTART).setPackage(pkg);
                sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (getResultCode() == Protocol.RESULT_OK) ok[0]++;
                    }
                }, handler, 0, null, null);
                appendLog("→ 重启输入法：" + pkg);
            } catch (Throwable t) {
                appendLog("重启失败：" + pkg + " " + t.getMessage());
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ok[0] > 0) {
                    appendLog("输入法已重启，切到任意输入框即会重新加载皮肤");
                    statusView.setText("输入法已重启，切到任意输入框即会重新加载皮肤");
                } else {
                    appendLog("没连上搜狗进程（先在搜狗里打一次字，再按重启）");
                    statusView.setText("没连上搜狗进程：先在搜狗里打一次字，再按「重启输入法」");
                }
                setConnected(ok[0] > 0);
            }
        }, 1500);
    }

    private boolean sendCommand(String cmd, String groups) {
        List<String> targets = installedTargets();
        if (targets.isEmpty()) return false;
        for (String pkg : targets) {
            Intent intent = new Intent(Protocol.ACTION_CMD)
                    .setPackage(pkg)
                    .putExtra(Protocol.EXTRA_CMD, cmd);
            if (groups != null) intent.putExtra(Protocol.EXTRA_GROUPS, groups);
            try {
                sendBroadcast(intent);
            } catch (Throwable t) {
                appendLog("发送失败：" + pkg + " " + t.getMessage());
            }
        }
        return true;
    }

    private List<String> installedTargets() {
        List<String> found = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String pkg : Protocol.TARGETS) {
            try {
                pm.getPackageInfo(pkg, 0);
                found.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return found;
    }

    // ── 界面状态 ────────────────────────────────────────────────────────────

    private void setBusy(boolean busy) {
        setRowEnabled(btnInstallRow, !busy && !selected.isEmpty());
        setRowEnabled(btnAllRow, !busy);
        setRowEnabled(btnRestoreRow, !busy);
    }

    private void setRowEnabled(View row, boolean enabled) {
        if (row == null) return;
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.4f);
    }

    private void setConnected(boolean connected) {
        if (dot == null) return;
        dot.setBackgroundTintList(ColorStateList.valueOf(
                connected ? 0xFF34C759 : 0xFF6C6E7A));
    }

    private static String friendlyName(String pkg) {
        if (pkg == null) return "搜狗输入法";
        if (pkg.contains(".xiaomi")) return "搜狗输入法 · 小米渠道";
        return "搜狗输入法";
    }

    private void appendLog(String line) {
        logLines.addLast(clock.format(new Date()) + "  " + line);
        while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String s : logLines) sb.append(s).append('\n');
        logView.setText(sb.toString());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
