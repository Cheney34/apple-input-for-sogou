package com.chiron.iosemoji;

/**
 * 模块与搜狗进程之间的通信协议。
 */
public final class Protocol {

    /** 模块自身包名，广播目标。 */
    public static final String MODULE_PKG = "com.chiron.iosemoji";

    /** 支持的目标输入法。小米渠道包与通用包都在内。 */
    public static final String[] TARGETS = {
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.sohu.inputmethod.sogou",
    };

    public static final String ACTION_CMD = MODULE_PKG + ".action.CMD";
    public static final String ACTION_STATUS = MODULE_PKG + ".action.STATUS";

    /**
     * 有序广播：把设置页的开关下发到搜狗进程（{@link #ACTION_CFG} 改配置，
     * {@link #ACTION_CFG_QUERY} 只查询状态）。
     *
     * <p>用**有序广播**而不是普通广播，是为了能用 {@code resultReceiver} 拿到回执 ——
     * 这正是设置页能显示"已生效 / 搜狗进程未运行"的依据。
     */
    public static final String ACTION_CFG = MODULE_PKG + ".action.CFG";
    public static final String ACTION_CFG_QUERY = MODULE_PKG + ".action.QCFG";
    /**
     * 「重启输入法」：让搜狗进程自己结束（随后系统会重新拉起），用来强制重载皮肤资源。
     *
     * <p>为什么需要它：皮肤 ini / 位图在进程里是带缓存的对象，换预设、换主题、换深浅色后
     * 有些路径不会重新解析；最省事、最不侵入的做法就是让进程重来一遍 ——
     * 效果等同用户在设置里强停搜狗输入法，但不用去系统设置里翻。
     */
    public static final String ACTION_RESTART = MODULE_PKG + ".action.RESTART";

    /** 有序广播回执码：1 = 搜狗进程收到并已应用；0 = 没有接收者（进程没起来）。 */
    public static final int RESULT_OK = 1;
    public static final int RESULT_ERROR = -1;

    /** 配置只读 ContentProvider 的 authority（冷启动读配置用）。 */
    public static final String CFG_AUTHORITY = MODULE_PKG + ".config";

    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_CURRENT = "current";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_MESSAGE = "message";
    /** 逗号分隔的分组编号，配合 {@link #CMD_INSTALL_GROUPS} 使用。 */
    public static final String EXTRA_GROUPS = "groups";

    public static final String CMD_PING = "ping";
    public static final String CMD_INSTALL_ALL = "install_all";
    public static final String CMD_INSTALL_CORE = "install_core";
    public static final String CMD_INSTALL_GROUPS = "install_groups";
    public static final String CMD_RESTORE = "restore";

    /** 分组目录的编号范围，超出这个范围的文件一律不碰。 */
    public static final int GROUP_ID_MIN = 2;
    public static final int GROUP_ID_MAX = 23;

    private Protocol() {
    }

    public static boolean isTarget(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }
}
