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
