package com.chiron.iosemoji.hook;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Hook 注册表：幂等 + 失败隔离 + 统一日志。
 *
 * <p>任务书硬性要求：
 * <ul>
 *   <li>**幂等**：同一个 hook-id 只安装一次（进程内多次调用安全）；</li>
 *   <li>**失败即原样**：安装失败只记日志，绝不抛出、绝不改变搜狗行为；</li>
 *   <li>**可诊断**：每个 hook 的安装结果都能在设置页/日志里看到。</li>
 * </ul>
 */
public final class HookRegistry {

    public interface Installer {
        void install(ClassLoader classLoader) throws Throwable;
    }

    private static final Set<String> INSTALLED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private HookRegistry() {
    }

    public static void log(String message) {
        XposedBridge.log("[AppleInputForSogou] " + message);
    }

    public static void log(Throwable t) {
        XposedBridge.log(t);
    }

    /**
     * 安装一个 hook。重复调用直接返回；失败记录 id 并吞掉异常。
     *
     * @return true 表示本次调用后该 hook 可用（含此前已安装成功的情况）
     */
    public static boolean install(String id, ClassLoader classLoader, Installer installer) {
        if (INSTALLED.contains(id)) return true;
        if (FAILED.contains(id)) return false;
        try {
            installer.install(classLoader);
            INSTALLED.add(id);
            log("hook ok: " + id);
            return true;
        } catch (Throwable t) {
            FAILED.add(id);
            log("hook FAILED: " + id + " -> " + t);
            log(t);
            return false;
        }
    }

    public static boolean isInstalled(String id) {
        return INSTALLED.contains(id);
    }

    public static boolean isFailed(String id) {
        return FAILED.contains(id);
    }

    public static String summary() {
        return "installed=" + INSTALLED + " failed=" + FAILED;
    }

    /** 供诊断页展示。 */
    public static Set<String> installedIds() {
        return Collections.unmodifiableSet(INSTALLED);
    }
}
