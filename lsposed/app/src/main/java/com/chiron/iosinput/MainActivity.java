package com.chiron.iosinput;

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
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

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

public class MainActivity extends Activity {

    private static final int MAX_LOG_LINES = 300;
    private static final long RESPONSE_TIMEOUT_MS = 4000L;

    private static final String PREFS = "settings";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_HIDE_ICON = "hide_icon";
    private static final String ALIAS = Protocol.MODULE_PKG + ".Launcher";

    private static final int COLOR_ICON_ON = 0xFFFFC24B;
    private static final int COLOR_ICON_OFF = 0xFF9A9CA8;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Set<Integer> selected = new HashSet<>();
    private final Map<Integer, View> cards = new LinkedHashMap<>();

    private SharedPreferences prefs;

    private TextView statusView;
    private TextView logView;
    private TextView summaryView;
    private TextView selectAllView;
    private TextView logToggle;
    private View dot;
    private ProgressBar bar;
    private Button btnInstall;
    private Button btnAll;
    private Button btnRestore;
    private Button btnPing;
    private Switch swHide;
    private GridLayout grid;

    private boolean receiverRegistered = false;
    private boolean awaitingResponse = false;
    private boolean suppressSwitchCallback = false;
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
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        summaryView = findViewById(R.id.summary);
        selectAllView = findViewById(R.id.btn_select_all);
        logToggle = findViewById(R.id.log_toggle);
        dot = findViewById(R.id.dot);
        bar = findViewById(R.id.bar);
        grid = findViewById(R.id.grid);
        btnInstall = findViewById(R.id.btn_install);
        btnAll = findViewById(R.id.btn_all);
        btnRestore = findViewById(R.id.btn_restore);
        btnPing = findViewById(R.id.btn_ping);
        swHide = findViewById(R.id.sw_hide);

        loadSelection();
        buildGrid();
        bindActions();
        bindHideSwitch();

        // v2.0：功能开关 / 兼容信息
        View btnFeatures = findViewById(R.id.btn_features);
        if (btnFeatures != null) {
            btnFeatures.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            });
        }

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

    // ── 分组卡片 ────────────────────────────────────────────────────────────

    private void buildGrid() {
        grid.removeAllViews();
        cards.clear();
        grid.setColumnCount(2);

        // 显式算列宽，不依赖 GridLayout 的 weight（部分 ROM 上不生效会得到 0 宽）。
        int screen = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(8);
        int colWidth = (screen - dp(36) - gap) / 2;

        for (Groups.Group group : Groups.ALL) {
            final int id = group.id;
            View item = getLayoutInflater().inflate(R.layout.item_group, grid, false);
            ((ImageView) item.findViewById(R.id.item_icon)).setImageResource(group.icon);
            ((TextView) item.findViewById(R.id.item_name)).setText(group.label);
            ((TextView) item.findViewById(R.id.item_count)).setText(group.count + " 张");
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!selected.remove(id)) selected.add(id);
                    saveSelection();
                    refreshCards();
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = colWidth - dp(8);
            lp.height = dp(52);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(item, lp);
            cards.put(id, item);
        }
        refreshCards();
    }

    private void refreshCards() {
        int total = 0;
        for (Groups.Group group : Groups.ALL) {
            View item = cards.get(group.id);
            if (item == null) continue;
            boolean on = selected.contains(group.id);
            item.setBackgroundResource(on ? R.drawable.bg_group_on : R.drawable.bg_group_off);
            ImageView icon = item.findViewById(R.id.item_icon);
            icon.setColorFilter(on ? COLOR_ICON_ON : COLOR_ICON_OFF);
            item.findViewById(R.id.item_check).setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) total += group.count;
        }

        boolean all = selected.size() == Groups.ALL.length;
        selectAllView.setText(all ? R.string.select_none : R.string.select_all);

        if (selected.isEmpty()) {
            summaryView.setText("未选择任何分组，点上面的卡片选择");
        } else {
            summaryView.setText("已选 " + selected.size() + " 组 · 共 " + total + " 张表情");
        }
        btnInstall.setEnabled(!selected.isEmpty());
        btnInstall.setText(selected.isEmpty()
                ? getString(R.string.btn_install)
                : "安装选中的 " + selected.size() + " 组");
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

    private void bindActions() {
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected.isEmpty()) return;
                run(Protocol.CMD_INSTALL_GROUPS, "安装选中的 " + selected.size() + " 组", joinSelected());
            }
        });
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(Protocol.CMD_INSTALL_ALL, "安装全部 10 组", null);
            }
        });
        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestore();
            }
        });
        btnPing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ping(true);
            }
        });
        selectAllView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected.size() == Groups.ALL.length) {
                    selected.clear();
                } else {
                    for (Groups.Group g : Groups.ALL) selected.add(g.id);
                }
                saveSelection();
                refreshCards();
            }
        });
        findViewById(R.id.log_header).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean visible = logView.getVisibility() == View.VISIBLE;
                logView.setVisibility(visible ? View.GONE : View.VISIBLE);
                logToggle.setText(visible ? "展开" : "收起");
            }
        });
    }

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

    private void bindHideSwitch() {
        boolean hidden = isIconHidden();
        suppressSwitchCallback = true;
        swHide.setChecked(hidden);
        suppressSwitchCallback = false;
        swHide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppressSwitchCallback) return;
                applyHideIcon(isChecked);
            }
        });
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
            suppressSwitchCallback = true;
            swHide.setChecked(!hide);
            suppressSwitchCallback = false;
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
        btnInstall.setEnabled(!busy && !selected.isEmpty());
        btnAll.setEnabled(!busy);
        btnRestore.setEnabled(!busy);
    }

    private void setConnected(boolean connected) {
        if (dot == null) return;
        dot.setBackgroundTintList(ColorStateList.valueOf(
                connected ? Color.parseColor("#3DD68C") : Color.parseColor("#6C6E7A")));
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
