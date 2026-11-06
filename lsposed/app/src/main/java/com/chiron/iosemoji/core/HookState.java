package com.chiron.iosemoji.core;

import android.content.Context;
import android.view.View;

import java.lang.ref.WeakReference;

/**
 * 运行期共享状态。
 *
 * <p>这里只放"必然存在"的东西：搜狗进程的 {@link Context}，以及我们当前注入的键盘 View。
 * 所有 hook 之间通过它传递，避免互相持有强引用导致 Activity / Service 泄漏。
 *
 * <p>注意：本模块只在搜狗输入法进程内运行（见 {@code Protocol#isTarget}），
 * 因此这里的静态字段不会被别的进程读到。
 */
public final class HookState {

    private static volatile WeakReference<Context> sContext = new WeakReference<>(null);
    private static volatile WeakReference<View> sKeyboardView = new WeakReference<>(null);
    private static volatile String sVersionName = "unknown";

    private HookState() {
    }

    public static void setContext(Context ctx) {
        if (ctx != null) {
            sContext = new WeakReference<>(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
        }
    }

    public static Context context() {
        return sContext.get();
    }

    public static void setKeyboardView(View view) {
        sKeyboardView = new WeakReference<>(view);
    }

    public static View keyboardView() {
        return sKeyboardView.get();
    }

    public static void setVersionName(String versionName) {
        if (versionName != null && versionName.length() > 0) {
            sVersionName = versionName;
        }
    }

    public static String versionName() {
        return sVersionName;
    }
}
