# Changelog

本项目遵循「分析 → 验证 → 实现」的推进方式：每个版本只写**有证据支撑**的东西。

## v2.0.0-alpha2（重构建）— 项目改名为 Apple Input for Sogou

- **项目对外名称**：`Chiron iOS 输入` → **`Apple Input for Sogou`（Apple 输入法 · 搜狗版）**。
  应用名、模块描述、README/文档标题、日志 TAG（`AppleInputForSogou`）同步改名；
  **包名 `com.chiron.iosemoji` 与签名证书保持不变**，所以仍是同包名覆盖升级。
- **仓库改名**：`Cheney34/chiron-ios-input` → `Cheney34/apple-input-for-sogou`（GitHub 自动 301 重定向旧地址）。
- 新增 `tools/build-apk.ps1`：把「无 Gradle」的七步构建固化成一条命令 —— 自动从 `AndroidManifest.xml`
  读 `versionCode`/`versionName`，优先用 `lsposed/app/libs/XposedBridgeAPI-82.jar`，缺失时回退到
  `lsposed/xposed-stubs/`（编译期签名桩，不会打进 APK）。
- APK 重新构建并替换 Release 资产：`AppleInputForSogou_v2.0.0-alpha2.apk`（versionCode 21 不变，
  `application-label` 已变为 `Apple Input for Sogou`，证书 SHA-256 仍为 `4fbf65ed…c207`）。

## v2.0.0-alpha2 — 包名回退 + iOS 界面重写（真实出包，未上机验证）

> alpha1 把包名从 `com.chiron.iosemoji` 改成了 `com.chiron.iosinput`，导致手机上像是「另一个程序」、
> 无法覆盖升级。alpha2 起**包名回退为 `com.chiron.iosemoji`**（与 v1.7/v1.8 一致），并用同一张调试证书签名，
> 因此属于**同包名覆盖升级**：装上会直接替换旧模块，LSPosed 里的条目和作用域不用重配。

### 修复

- **包名回退**：`com.chiron.iosinput` → `com.chiron.iosemoji`（目录、21 个源文件、`AndroidManifest.xml`、
  `assets/xposed_init`、Gradle `namespace`/`applicationId` 全部同步）。证书与 v1.7/v1.8 相同
  （`CN=Android Debug`，SHA-256 `4fbf65ed…c207`），可直接覆盖安装。
- 明确：旧模块的 4 个 hook（`GestureHook`、`GroupFilterHook`、`LogoHook`、`EmojiService` 广播通道）
  **源码逐字搬入、逻辑未改**，只做了包名替换与去 UTF-8 BOM；新增的只有 `H01`/`H02`/`H04`，且各自挂在开关上。

### 界面重写（iOS 风格，全部自绘，不依赖 androidx / Material）

- **模块 App 界面**：`MainActivity` 与 `SettingsActivity` 从 XML 布局改成代码搭建的 iOS 设置页 ——
  大标题、分组卡片、圆角行、右侧 `✓` / `›`、自绘开关 `IosSwitch`、跟随系统深浅色
  （`values` / `values-night` 两套色板 + `IosUi` 取色）。
  功能与旧版一一对应：分组勾选、安装选中 / 安装全部 / 还原、隐藏桌面图标、连接检测、运行日志。
- **输入法键盘界面**：`keyboard/ChironKeyboardView` 重写为整套 iOS 键盘 ——
  字母页（qwertyuiop / asdfghjkl / ⇧ zxcvbnm⌫ / 123·地球·空格·回车）与符号页两套，
  按 `py_9.ini` 的 `H=0.591667` 锁定高度比例以免改变输入法 insets，
  按下时在键上方浮出 iOS 放大气泡，`⌫` 支持长按连删（420 ms 起、65 ms 间隔）。
  `KeyboardTheme` 管配色与比例常量，`KeyView` 只负责画键（功能键用 `Path` 手绘，避免字体缺字形）。
- 文字上屏仍走搜狗：字母/数字用 `TextInjector.key(code)` →
  `InputLogic.onKey(code, {code}, false, 0, 0)`，进搜狗拼音/数字引擎出候选；
  大写字母与中文标点用 `InputInjector.type()` 直接上屏；`⌫`/空格/回车分别用 `-5` / `32` / `10`。
  失败时逐级降级到 `getSogouInputConnection()`（`commitText` / `deleteSurroundingText` / `sendKeyEvent`）。

### 构建产物（本机实测）

- `aapt2`(2.19) + `javac`(17) + `d8` + `zipalign` + `apksigner` 手工链路真实出包
  `AppleInputForSogou_v2.0.0-alpha2.apk`（21.74 MB，含 `assets/payload.zip`）。
- `aapt2 dump badging`：`package name='com.chiron.iosemoji' versionCode='21' versionName='2.0.0-alpha2'
  minSdkVersion:'26' targetSdkVersion:'35' application-label:'Apple Input for Sogou'`。
- 仍未上机验证：H01/H02 是否命中、键盘触摸与 insets 表现，需按 `docs/MVP_PROPOSAL.md` TC 清单跑一遍。
## v2.0.0-alpha1 — 独立仓库首个版本（javac 冒烟编译通过，未上机验证）

### 验证状态

- ✅ `javac -Xlint:all --release 17`（classpath = `android.jar` API 35 + Xposed API 桩 + `R` 桩）：
  全部源码 **0 error**，仅 1 条无害的 `this-escape` 提示。
- ✅ 全仓库扫 `iosemoji|IOSEmoji` 残留 → 0 命中。
- ⚠️ 未做 Gradle 构建（环境无 Android SDK），未在真机/模拟器运行，
  hook 描述符是否在该版本搜狗中真的命中仍需上机按 `docs/MVP_PROPOSAL.md` TC-01~TC-10 验证。

### 构建产物（本机实测）

- 环境无 Android SDK / Gradle，改用 `android.jar`(API 35) + `build-tools 35` 手工链路
  `aapt2 compile/link → javac → d8 → zip → zipalign → apksigner` **真实出包**：
  `AppleInputForSogou_v2.0.0-alpha1.apk`，21.74 MB。
- `aapt2 dump badging` 与 `apksigner verify --print-certs` 均通过
  （`CN=Android Debug`，证书 SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`）。
- **首次内置 `assets/payload.zip`**（21.63 MB）：由 `tools/make_payload.py` 从
  `emoji-datasource-apple@15.1.2` 的 64×64 位图生成，10 个分组清单共 2067 项，成功写入 2059 张，
  缺 8 张（`1FA89/1FA8F/1FADF/1FAE9/1FAC6/1FABE/1FADC/1F1E8_1F1F6` —— 该素材版本尚无这几枚新 emoji，
  对应槽位在面板里显示为空白占位）。因此 v2.0.0-alpha2 的「安装表情分组」功能开箱可用。
  按版权要求，位图**不提交进 git**，只作为 Release 资产分发。

### 修复

- 从父仓库搬来的 6 个 Java 文件带 UTF-8 BOM（`\ufeff`），会让 `javac`/Gradle 直接编译失败，
  已统一去除：`EmojiService` / `GestureHook` / `GroupFilterHook` / `Groups` / `LogoHook` / `Protocol`。

### 新增：iOS 键盘显示层替换（Phase 2 骨架）

- `H01` hook `com.sogou.lib.bu.ui.component.keyboard.d#getRootView()`，
  一次覆盖所有 `setKeyboardView(<s0>.getRootView())` 调用点
  （`g0:414/666`、`MainImeServiceDel:2326`、`manager/j:299`、`main/view/c:60`）。
- `H02` hook `NormalIMERootContainer#setKeyboardView(View)` 兜底补塞 z=3 槽位。
- `ChironKeyboardView`：自绘键盘（QWERTY + 123/空格/⌫/⏎），高度按搜狗主题
  `H=0.591667 × 屏宽` 计算，避免改动 `onComputeInsets`。
- `TextInjector`：字符/功能键走搜狗自己的 `InputLogic#onText` / `#onKey`，
  InputConnection 作为兜底 —— **输入引擎完全不动**。

### 新增：配置与兼容

- `ModuleConfig`：7 个开关（总开关、键盘替换、表情分组过滤、表情滑动、Logo 触发词、
  候选探针、视图树诊断），经 `XSharedPreferences` 在搜狗进程内读取，读不到一律用默认值。
- `VersionProfile`：版本白名单 `10.32.21.202607061714`；非白名单版本自动降级为
  `DIAGNOSTIC_ONLY`（只读，不改界面）。
- `HookRegistry`：hook 安装幂等 + 失败隔离 + 统一日志（TAG `AppleInputForSogou`）。

### 新增：诊断

- `CandidateProbeHook`：只读打印候选列表推送（`r2#Z7` / `r2#N7` / `showCandidateWordView`）。
- `ViewTreeDumper`：输入法启动 3 秒后 dump 输入窗口视图树。
- `SettingsActivity`：开关界面 + 版本/兼容信息。

### 新增：文档

- `docs/` 全套静态分析：`APK_OVERVIEW`、`CLASS_MAP`、`VIEW_HIERARCHY`、`EVENT_CALLBACK_MAP`、
  `INPUT_ENGINE_MAP`、`EMOJI_PANEL_MAP`、`FEASIBILITY`、`HOOK_POINTS`（H01–H30）、
  `MVP_PROPOSAL`、`MIGRATION`。

### 变更（自 v1.8 起）

- 模块名 `IOSEmoji` → `AppleInputForSogou`（日志 TAG 同步改名）；**包名保持 `com.chiron.iosemoji`**，便于同签名覆盖升级 v1.7 / v1.8。
- 构建方式：`build.sh`（aapt2 + javac + d8 手写链路）→ **Gradle / Android Studio 工程**。
- 仓库：从 `chiron-apple-emoji` 的 `lsposed-sogou/` 目录拆分独立成库。

### 保留（v1.8 既有能力，行为不变）

- 表情分组安装器（10 组 Apple 风格 emoji，`assets/payload.zip` 由使用者本机生成）。
- 隐藏搜狗自带「组合」「拼装」分组（`GroupFilterHook`）。
- 表情面板左右滑动切组（`GestureHook`）。
- 触发词 → Apple  Logo（`LogoHook`，默认关闭）。

---

## v1.8（父仓库 chiron-apple-emoji）

- 修复 `LogoHook` 默认不再吃「苹果」「pg」这类常用词。
- 更早历史见父仓库 CHANGELOG。
