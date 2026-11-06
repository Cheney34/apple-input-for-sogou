package com.chiron.iosemoji.compat;

import com.chiron.iosemoji.core.HookState;

/**
 * 版本档案 / 兼容性门控。
 *
 * <p>设计原则（来自任务书硬性要求）：
 * <ul>
 *   <li>只有**静态分析过并记录在案**的版本才启用 UI 替换类 hook；</li>
 *   <li>其它版本一律进入 {@link Mode#DIAGNOSTIC_ONLY}，只打印日志，不改行为；</li>
 *   <li>版本名由 {@code PackageManager} 读取后写入 {@link HookState}。</li>
 * </ul>
 *
 * <p>已知版本的证据见仓库 docs/APK_OVERVIEW.md：
 * {@code 10.32.21.202607061714}（小米定制版，versionCode 2147483647，5 个 dex）。
 */
public final class VersionProfile {

    /** 静态分析验证过的版本名。新增版本必须先补 docs 再改这里。 */
    private static final String[] VERIFIED = {
            "10.32.21.202607061714",
    };

    /** 当前静态分析所针对的目标版本（docs/APK_OVERVIEW.md 的取证对象）。 */
    public static final String TARGET_VERSION_NAME = "10.32.21.202607061714";

    /** 目标 APK 的 SHA-256，用于核对「就是被分析过的那个包」。 */
    public static final String TARGET_APK_SHA256 =
            "90B4A1945D28427A50CC6DF8FA1FE0E29977A0053622FA7A05FBDCFF01F6B09C";

    public enum Mode {
        /** 未验证版本：只允许只读诊断 hook。 */
        DIAGNOSTIC_ONLY,
        /** 已验证版本：允许全部 hook。 */
        FULL,
    }

    private VersionProfile() {
    }

    public static Mode mode() {
        return isVerified(HookState.versionName()) ? Mode.FULL : Mode.DIAGNOSTIC_ONLY;
    }

    public static boolean isVerified(String versionName) {
        if (versionName == null) return false;
        for (String v : VERIFIED) {
            if (v.equals(versionName)) return true;
        }
        return false;
    }

    /** UI 替换类 hook 是否允许安装。 */
    public static boolean allowUiReplacement() {
        return mode() == Mode.FULL;
    }

    /** 只读诊断 hook 在所有版本都允许。 */
    public static boolean allowDiagnostics() {
        return true;
    }

    public static String describe() {
        return "version=" + HookState.versionName() + " mode=" + mode();
    }
}
