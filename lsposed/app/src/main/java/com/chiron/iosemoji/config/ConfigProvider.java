package com.chiron.iosemoji.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 只读配置提供者 —— 给**冷启动**的搜狗进程读配置用。
 *
 * <p>为什么需要它：搜狗进程启动时模块还没和 App 通信过，XSharedPreferences 在部分
 * 系统/框架组合上读不到本 App 的私有 prefs（Android 7+ prefs 文件是 0600、目录 0700），
 * 结果就是"开关全是默认值、怎么点都没反应"。ContentResolver 是跨进程、无需权限提升、
 * 各版本都靠得住的通道，所以冷启动走这里，运行中改开关走有序广播（见 {@code Protocol}）。
 *
 * <p>只暴露 7 个布尔开关与模块版本号，不含任何隐私数据；写入一律拒绝。
 */
public class ConfigProvider extends ContentProvider {

    /** 与 AndroidManifest 里 android:authorities 必须一致。 */
    public static final String AUTHORITY = "com.chiron.iosemoji.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    /** 附加列：模块版本名，便于 hook 侧日志里说明"读到的是哪个版本的配置"。 */
    public static final String COLUMN_VERSION = "module_version";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = new String[ConfigValues.KEYS.length + 1];
        System.arraycopy(ConfigValues.KEYS, 0, columns, 0, ConfigValues.KEYS.length);
        columns[ConfigValues.KEYS.length] = COLUMN_VERSION;

        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        SharedPreferences prefs = null;
        if (getContext() != null) {
            prefs = getContext().getSharedPreferences(ModuleConfig.PREFS_NAME, 0);
        }
        for (String key : ConfigValues.KEYS) {
            boolean def = ConfigValues.defaultValue(key);
            row.add(key, prefs != null && prefs.getBoolean(key, def) ? 1 : 0);
        }
        row.add(COLUMN_VERSION, moduleVersionName());
        return cursor;
    }

    private String moduleVersionName() {
        try {
            if (getContext() == null) return "unknown";
            return getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.chiron.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return null;
    }
}
