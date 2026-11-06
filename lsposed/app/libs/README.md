# Xposed API 82 —— 仅编译期依赖

本目录需要放一个 **`XposedBridgeAPI-82.jar`**，构建时以 `compileOnly` 方式引用：
它不会被打包进 APK，运行时由 LSPosed 框架提供。

## 为什么要自己放

Xposed API 82 是十多年前发布到 jcenter 的构件，jcenter 已停止服务，
所以本工程不写远程依赖，改为本地 jar，保证离线、可复现、不会因为仓库下线而构建失败。

## 怎么拿到

任选其一（都需要自行核对 jar 内容）：

1. 从已安装的 LSPosed / Xposed 生态项目的 `libs/` 目录复制（很多开源 LSPosed 模块仓库都提交了这份 jar）；
2. 用 `api-82-sources.jar` 或 `XposedBridgeAPI-82.jar` 在你常用的 Maven 镜像上搜索同版本构件；
3. 从 Xposed 官方发行物中提取。

放好后确认文件路径为：

```
lsposed/app/libs/XposedBridgeAPI-82.jar
```

校验：jar 内应包含 `de/robv/android/xposed/IXposedHookLoadPackage.class`、
`de/robv/android/xposed/XposedHelpers.class`、`de/robv/android/xposed/XSharedPreferences.class`。

> 说明（诚实标注）：本仓库作者在**没有 Android SDK 的环境**里完成了全部源码，
> 该 jar 未随仓库提交。首次构建前请按上面步骤补齐，否则 `:app:compileDebugJavaWithJavac`
> 会报 `package de.robv.android.xposed does not exist`。
