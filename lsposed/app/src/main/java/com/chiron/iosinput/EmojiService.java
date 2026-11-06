package com.chiron.iosinput;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 运行在搜狗输入法进程内。收到模块发来的广播后，用搜狗自己的权限把
 * assets/payload.zip 解压进 files/sogou/emojipackage。
 */
public final class EmojiService {

    private static final String TAG = "ChironIOSInput";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** 黄脸 / 符号 / 旗帜 三组。 */
    private static final Set<Integer> CORE_GROUPS = coreGroups();

    private static Set<Integer> coreGroups() {
        Set<Integer> set = new HashSet<>();
        for (int id : Groups.CORE) set.add(id);
        return set;
    }

    private static final String MARK_FILE = ".ios-emoji-installed";
    private static final String STAGING_DIR = ".ios-emoji-staging";
    private static final String BACKUP_DIR = ".ios-emoji-backup";

    private EmojiService() {
    }

    static void register(final Context context) {
        if (!REGISTERED.compareAndSet(false, true)) return;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null || !Protocol.ACTION_CMD.equals(intent.getAction())) return;
                String cmd = intent.getStringExtra(Protocol.EXTRA_CMD);
                if (cmd == null) return;

                if (Protocol.CMD_PING.equals(cmd)) {
                    sendStatus(context, "ready", 0, 0,
                            "已连接 · " + context.getPackageName());
                    return;
                }
                if (!RUNNING.compareAndSet(false, true)) {
                    sendStatus(context, "busy", 0, 0, "正在处理，请稍候…");
                    return;
                }
                startWorker(context, cmd, intent.getStringExtra(Protocol.EXTRA_GROUPS));
            }
        };

        IntentFilter filter = new IntentFilter(Protocol.ACTION_CMD);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedLog.i("command receiver registered in " + context.getPackageName());
        } catch (Throwable t) {
            XposedLog.e("registerReceiver failed", t);
            REGISTERED.set(false);
            return;
        }
        sendStatus(context, "ready", 0, 0, "已连接 · " + context.getPackageName());
    }

    private static void startWorker(final Context context, final String cmd,
                                    final String groupsExtra) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Protocol.CMD_RESTORE.equals(cmd)) {
                        restore(context);
                        sendStatus(context, "done", 1, 1, "已还原为安装前的表情包");
                    } else {
                        Set<Integer> filter = null;
                        String label = "全部 10 组";
                        if (Protocol.CMD_INSTALL_CORE.equals(cmd)) {
                            filter = CORE_GROUPS;
                            label = "笑脸 / 符号 / 旗帜";
                        } else if (Protocol.CMD_INSTALL_GROUPS.equals(cmd)) {
                            filter = parseGroups(groupsExtra);
                            if (filter.isEmpty()) throw new IOException("没有选中任何分组");
                            label = describeGroups(filter);
                        }
                        install(context, filter);
                        sendStatus(context, "done", 1, 1, "完成 · " + label);
                    }
                    Thread.sleep(900);
                    XposedLog.i("killing Sogou process to reload emoji");
                    Process.killProcess(Process.myPid());
                } catch (Throwable t) {
                    XposedLog.e("operation failed", t);
                    sendStatus(context, "error", 0, 0, "失败：" + describe(t));
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "ios-emoji-worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** "3,4,11" → {3,4,11}，非法编号与越界编号一律丢弃。 */
    private static Set<Integer> parseGroups(String raw) {
        Set<Integer> out = new HashSet<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.length() == 0) continue;
            try {
                int id = Integer.parseInt(s);
                if (id >= Protocol.GROUP_ID_MIN && id <= Protocol.GROUP_ID_MAX) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String describeGroups(Set<Integer> ids) {
        List<String> labels = new ArrayList<>();
        for (Groups.Group g : Groups.ALL) {
            if (ids.contains(g.id)) labels.add(g.label);
        }
        if (labels.isEmpty()) return ids.size() + " 个分组";
        if (labels.size() <= 2) {
            StringBuilder sb = new StringBuilder();
            for (String s : labels) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(s);
            }
            return sb.toString();
        }
        return labels.size() + " 个分组";
    }

    // ── 安装 ────────────────────────────────────────────────────────────────

    private static void install(Context ctx, Set<Integer> filter) throws Exception {
        File sogouRoot = new File(ctx.getFilesDir(), "sogou");
        File packageRoot = new File(sogouRoot, "emojipackage");
        File staging = new File(sogouRoot, STAGING_DIR);
        File backup = new File(sogouRoot, BACKUP_DIR);

        if (!sogouRoot.isDirectory() && !sogouRoot.mkdirs()) {
            throw new IOException("无法创建 " + sogouRoot);
        }
        if (!packageRoot.isDirectory() && !packageRoot.mkdirs()) {
            throw new IOException("无法创建 " + packageRoot);
        }
        deleteTree(staging, sogouRoot);
        if (!staging.mkdirs()) throw new IOException("无法创建暂存目录");

        Context module = ctx.createPackageContext(
                Protocol.MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY);

        sendStatus(ctx, "extract", 0, 0, "正在解压表情包…");
        List<String> groups = extract(ctx, module, staging, filter, sogouRoot);

        if (groups.isEmpty()) throw new IOException("载荷为空，未找到任何分组");
        for (String name : groups) {
            if (!new File(new File(staging, name), "info.xml").isFile()) {
                throw new IOException("分组不完整：" + name);
            }
        }

        // 只替换本次涉及的分组，其余分组（比如只装三组时的人物、动物）原样保留。
        // 备份目录按“分组编号”去重，装多少次都只留最早那一份原厂副本。
        sendStatus(ctx, "commit", groups.size(), groups.size(), "正在替换分组…");
        if (!backup.isDirectory() && !backup.mkdirs()) {
            throw new IOException("无法创建备份目录");
        }

        Set<Integer> targetIds = new HashSet<>();
        for (String name : groups) targetIds.add(groupId(name));

        List<File[]> movedOld = new ArrayList<>();
        List<File[]> installed = new ArrayList<>();
        try {
            for (File dir : listGroupDirs(packageRoot)) {
                int id = groupId(dir.getName());
                if (!targetIds.contains(id)) continue;
                if (findGroupDirById(backup, id) != null) {
                    // 原厂版本已经在更早的一次安装里备份过，这里直接丢掉当前这份
                    deleteTree(dir, sogouRoot);
                    continue;
                }
                File target = new File(backup, dir.getName());
                if (!dir.renameTo(target)) throw new IOException("备份失败：" + dir.getName());
                movedOld.add(new File[]{dir, target});
            }
            for (String name : groups) {
                File from = new File(staging, name);
                File to = new File(packageRoot, name);
                File stale = findGroupDirById(packageRoot, groupId(name));
                if (stale != null) deleteTree(stale, sogouRoot);
                if (!from.renameTo(to)) throw new IOException("写入失败：" + name);
                installed.add(new File[]{from, to});
            }
        } catch (Exception e) {
            for (int i = installed.size() - 1; i >= 0; i--) {
                File[] pair = installed.get(i);
                if (pair[1].exists()) pair[1].renameTo(pair[0]);
            }
            for (int i = movedOld.size() - 1; i >= 0; i--) {
                File[] pair = movedOld.get(i);
                if (pair[1].exists()) pair[1].renameTo(pair[0]);
            }
            throw e;
        }

        deleteTree(staging, sogouRoot);

        StringBuilder mark = new StringBuilder();
        for (String name : groups) mark.append(name).append('\n');
        writeText(new File(sogouRoot, MARK_FILE), mark.toString());

        sendStatus(ctx, "commit", groups.size(), groups.size(),
                "已写入 " + groups.size() + " 个分组，正在重载输入法…");
    }

    /**
     * 把 payload.zip 里 emojipackage/ 下的分组解压到暂存目录，返回分组目录名。
     */
    private static List<String> extract(Context ctx, Context module, File staging,
                                        Set<Integer> filter, File sogouRoot) throws IOException {
        List<String> groups = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        InputStream raw = module.getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] buf = new byte[1 << 16];
        int images = 0;
        long lastReport = 0L;
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("emojipackage/")) continue;

                String rest = name.substring("emojipackage/".length());
                int slash = rest.indexOf('/');
                if (slash <= 0) continue;

                String group = rest.substring(0, slash);
                int id = groupId(group);
                if (id < Protocol.GROUP_ID_MIN || id > Protocol.GROUP_ID_MAX) continue;
                if (filter != null && !filter.contains(id)) continue;

                String relative = rest.substring(slash + 1);
                if (relative.length() == 0 || relative.contains("..")) continue;

                File out = new File(new File(staging, group), relative);
                if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) {
                    throw new IOException("无法创建目录 " + out.getParentFile());
                }
                FileOutputStream fos = new FileOutputStream(out);
                BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 15);
                try {
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                } finally {
                    bos.close();
                }

                if (!seen.contains(group)) {
                    seen.add(group);
                    groups.add(group);
                }
                if (relative.startsWith("emoji/")) {
                    images++;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 120) {
                        lastReport = now;
                        sendStatus(ctx, "extract", images, 0,
                                "正在解压… 已写入 " + images + " 张");
                    }
                }
            }
        } finally {
            try {
                zis.close();
            } catch (IOException ignored) {
            }
        }
        return groups;
    }

    // ── 还原 ────────────────────────────────────────────────────────────────

    private static void restore(Context ctx) throws IOException {
        File sogouRoot = new File(ctx.getFilesDir(), "sogou");
        File packageRoot = new File(sogouRoot, "emojipackage");
        File backup = new File(sogouRoot, BACKUP_DIR);
        File staging = new File(sogouRoot, STAGING_DIR);

        sendStatus(ctx, "restore", 0, 0, "正在还原…");

        File mark = new File(sogouRoot, MARK_FILE);
        boolean hasMark = mark.isFile();
        boolean hasBackup = backup.isDirectory();
        if (!hasMark && !hasBackup) {
            throw new IOException("这里没有安装记录，不需要还原");
        }

        String recorded = hasMark ? readText(mark) : "";
        for (String line : recorded.split("\n")) {
            String name = line.trim();
            if (name.length() == 0) continue;
            int id = groupId(name);
            if (id < Protocol.GROUP_ID_MIN || id > Protocol.GROUP_ID_MAX) continue;
            File live = findGroupDirById(packageRoot, id);
            if (live != null) deleteTree(live, sogouRoot);
        }

        File[] backups = backup.listFiles();
        int restored = 0;
        if (backups != null) {
            for (File dir : backups) {
                if (!dir.isDirectory()) continue;
                File live = findGroupDirById(packageRoot, groupId(dir.getName()));
                if (live != null) deleteTree(live, sogouRoot);
                if (dir.renameTo(new File(packageRoot, dir.getName()))) restored++;
            }
        }
        deleteTree(backup, sogouRoot);
        deleteTree(staging, sogouRoot);
        mark.delete();

        sendStatus(ctx, "restore", restored, restored,
                restored > 0 ? "已还原 " + restored + " 个原始分组" : "原始分组已还原");
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static int groupId(String dirName) {
        int underscore = dirName.indexOf('_');
        String head = underscore > 0 ? dirName.substring(0, underscore) : dirName;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<File> listGroupDirs(File root) {
        List<File> out = new ArrayList<>();
        File[] files = root.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            int id = groupId(f.getName());
            if (id >= Protocol.GROUP_ID_MIN && id <= Protocol.GROUP_ID_MAX) out.add(f);
        }
        return out;
    }

    /** 按分组编号找目录，因为搜狗每次升级可能把 3_11000 改成 3_12000。 */
    private static File findGroupDirById(File root, int id) {
        for (File f : listGroupDirs(root)) {
            if (groupId(f.getName()) == id) return f;
        }
        return null;
    }

    private static void deleteTree(File target, File guard) throws IOException {
        String guardPath = guard.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(guardPath)) {
            throw new IOException("拒绝删除非托管路径 " + target);
        }
        if (target.exists() && !deleteRecursively(target)) {
            throw new IOException("无法删除 " + target);
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static String readText(File file) {
        try {
            return new String(readAll(file), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }

    static void sendStatus(Context ctx, String phase, int current, int total, String message) {
        try {
            Intent intent = new Intent(Protocol.ACTION_STATUS)
                    .setPackage(Protocol.MODULE_PKG)
                    .putExtra(Protocol.EXTRA_PHASE, phase)
                    .putExtra(Protocol.EXTRA_CURRENT, current)
                    .putExtra(Protocol.EXTRA_TOTAL, total)
                    .putExtra(Protocol.EXTRA_MESSAGE, message);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "sendStatus failed", t);
        }
    }

    /** 统一日志出口：既进 logcat，也进 LSPosed 日志页。 */
    static final class XposedLog {
        static void i(String msg) {
            Log.i(TAG, msg);
            de.robv.android.xposed.XposedBridge.log(TAG + ": " + msg);
        }

        static void e(String msg, Throwable t) {
            Log.e(TAG, msg, t);
            de.robv.android.xposed.XposedBridge.log(TAG + ": " + msg);
            de.robv.android.xposed.XposedBridge.log(t);
        }
    }
}
