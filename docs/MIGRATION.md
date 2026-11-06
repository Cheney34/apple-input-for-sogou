# 迁移说明：本仓库如何从 chiron-apple-emoji 拆分出来

> 结论先说：`chiron-ios-input` 是**独立仓库、独立包名、独立版本线**。
> 与 `Cheney34/chiron-apple-emoji` 之间**没有 Git 子模块关系，也没有运行时依赖**，
> 只是代码血缘上由后者的 `lsposed-sogou/` 目录演化而来。

## 1. 为什么拆

父仓库 `chiron-apple-emoji` 的定位是「把搜狗的表情面板换成 Apple 风格」（v1.8），
其 `lsposed-sogou/` 是一个**只做表情**的 LSPosed 模块，构建方式是手写 `build.sh`
（aapt2 + javac + d8 + apksigner），不依赖 Gradle。

新项目 `Chiron iOS 输入` 的目标要大得多：**保留搜狗输入引擎，替换键盘/候选栏/表情面板的
显示层与交互**（见 `docs/FEASIBILITY.md`）。目标、代码量、发布节奏都不同，因此拆库：

| 维度 | chiron-apple-emoji | chiron-ios-input（本仓库） |
| --- | --- | --- |
| 范围 | 表情面板美化 | 输入体验整体 iOS 化（键盘/候选/表情） |
| 包名 | `com.chiron.iosemoji` | `com.chiron.iosemoji` |
| 版本 | v1.8（versionCode 10） | v2.0.0-alpha2（versionCode 21） |
| 构建 | `build.sh` 手写链路 | Gradle（AGP 8.7.3）+ Android Studio 工程 |
| 工程结构 | 单目录 `src/` | `lsposed/app/src/main/java/…` 标准分层 |
| 文档 | README + SOURCE_INDEX | `docs/` 全套静态分析取证 |

## 2. 文件搬迁对照

| 父仓库路径 | 本仓库路径 | 说明 |
| --- | --- | --- |
| `lsposed-sogou/src/com/chiron/iosemoji/EmojiService.java` | `lsposed/app/src/main/java/com/chiron/iosemoji/EmojiService.java` | 表情分组安装（广播 + 解包），逻辑未改 |
| `…/GestureHook.java` | `lsposed/app/src/main/java/com/chiron/iosemoji/GestureHook.java` | 表情面板左右滑动，未改 |
| `…/GroupFilterHook.java` | `…/GroupFilterHook.java` | 隐藏「组合」「拼装」分组，未改 |
| `…/LogoHook.java` | `…/LogoHook.java` | 触发词 → Apple Logo，默认由开改**关** |
| `…/Protocol.java` | `…/Protocol.java` | `MODULE_PKG` 改为 `com.chiron.iosemoji`，其余不变 |
| `…/Groups.java` | `…/Groups.java` | 分组常量，未改 |
| `…/HookEntry.java` | `…/HookEntry.java` | **重写**：进程过滤 → 配置 → 类级 hook → `Application#attach` → 版本判定 → 延迟补装 |
| `…/MainActivity.java` | `…/MainActivity.java` | 保留；新增一个「功能开关」入口按钮 |
| `lsposed-sogou/res/**` | `lsposed/app/src/main/res/**` | 整体搬迁；`strings.xml` 文案更新、新增 1 个字符串 |
| `lsposed-sogou/manifests/*/info.xml` | `manifests/*/info.xml` | 10 份分组清单原样保留 |
| `lsposed-sogou/tools/make_payload.py` | `tools/make_payload.py` | 原样 |
| `lsposed-sogou/make_icons.py` | `tools/make_icons.py` | 原样 |
| `lsposed-sogou/signing/debug.keystore` | `signing/debug.keystore` | 同一份调试签名（alias `androiddebugkey`） |
| `lsposed-sogou/build.sh` | （删除） | 由 Gradle 取代；命令行构建方式见 README §5 |
| `lsposed-sogou/assets/xposed_init` | `lsposed/app/src/main/assets/xposed_init` | 内容改为 `com.chiron.iosemoji.HookEntry` |

## 3. 代码层面的机械改名

搬迁时对全部 Java 源码做了两条机械替换（无逻辑改动）：

1. `com.chiron.iosemoji` → `com.chiron.iosemoji`（`package` / `import` / 常量）；
2. 日志 TAG 与模块名 `IOSEmoji` → `ChironIOSInput`。

`SharedPreferences` 文件名沿用 `chiron_config`（新增用途，旧版本本来没有配置项）。

## 4. 在父仓库里需要做的清理（**尚未执行**，由父仓库维护者决定）

如果希望父仓库彻底「分离」本模块，建议：

1. 删除 `lsposed-sogou/` 整个目录（历史和已发布 APK 仍留在 Releases 里，不影响老用户）；
2. 删除 `.github/workflows` 中构建该模块的任务；
3. `README.md` / `SOURCE_INDEX.md` 里「LSPosed 模块」一节改为指向本仓库；
4. `tools/make_payload.py` 若本仓库已有，父仓库可保留一份或同样移除；
5. `CHANGELOG.md` 追加一条「LSPosed 模块已迁出至 chiron-ios-input」。

> 本次拆分**只在本地完成**，没有对父仓库做任何写操作（也不具备其推送凭据）。

## 5. 共存与升级注意

- **包名不同**（`com.chiron.iosemoji` vs `com.chiron.iosemoji`），
  所以两个模块可以同时安装，不会互相覆盖，也不会因为签名不同而 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 但两者都会 hook 同一个搜狗进程：如果同时启用，`GestureHook`/`GroupFilterHook`/`LogoHook`
  会被安装两遍（各自幂等，但日志会重复、行为可能互相覆盖）。
  **建议只启用其中一个**。从旧模块迁移时的推荐顺序：
  1. LSPosed 里停用 `Chiron Emoji · 搜狗`；
  2. 安装并启用本模块；
  3. 强行停止搜狗输入法；
  4. 确认表情分组、键盘均正常后，再卸载旧模块。
- 表情分组已安装到磁盘的内容（`files/sogou/emojipackage/`）**不会因为换模块而丢失**，
  必要时用任一模块的「还原」按钮清理。

## 6. 版本线

- 父仓库模块：`1.x`（历史线，最后一个版本 v1.8 / versionCode 10）；
- 本仓库：从 `2.0.0-alpha2` / versionCode 21 起，`versionCode` 与父仓库**不重合**，
  便于将来若需要「合并安装」时区分。

### 关于包名（alpha2 更正）

拆分初期把模块包名从 `com.chiron.iosemoji` 改成了 `com.chiron.iosinput`，结果在手机上表现成「另一个程序」：
旧模块不会被替换，LSPosed 里要重新配一条作用域，桌面出现两个入口。

**alpha2 起包名回退为 `com.chiron.iosemoji`**，并用与 v1.7 / v1.8 相同的调试证书签名
（`CN=Android Debug`，SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`），
因此是**同包名覆盖升级**：

- 装 v2.0.0-alpha2 会直接替换手机上的 v1.7 / v1.8；
- LSPosed 里原有的模块条目、作用域勾选、模块开关（`chiron_config`）都沿用，不需要重配；
- 想回旧版：卸载后装 v1.7 / v1.8 即可（同签名，data 不会被签名冲突挡住）。

改动只影响 `namespace`/`applicationId`、`AndroidManifest.xml` 的 `package`、`assets/xposed_init`
与 Java 包目录名；源码逻辑未动。