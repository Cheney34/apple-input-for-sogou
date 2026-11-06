# Xposed 入口必须保留（LSPosed 通过 assets/xposed_init 里的类名反射加载）
-keep class com.chiron.iosinput.HookEntry { *; }
-keep class com.chiron.iosinput.hook.** { *; }
-keep class com.chiron.iosinput.core.** { *; }
-keep class com.chiron.iosinput.keyboard.** { *; }
-keep class com.chiron.iosinput.config.** { *; }
-keep class com.chiron.iosinput.compat.** { *; }
-keep class com.chiron.iosinput.Protocol { *; }

# 模块与搜狗进程通过广播通信，Receiver 不能改名
-keep class com.chiron.iosinput.EmojiService { *; }

# 不要混淆 Xposed 回调基类
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# 保留行号，便于看崩溃栈
-keepattributes SourceFile,LineNumberTable
