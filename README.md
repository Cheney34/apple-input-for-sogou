# Apple Input for Sogou（Apple 输入法 · 搜狗版）

> **搜狗负责输入，Apple 负责体验。**
>
> 一个针对「小米定制版搜狗输入法」的 LSPosed 模块：保留搜狗的输入引擎、词库、候选生成与上屏逻辑，
> 只把**键盘、候选栏、表情面板**的显示层与交互换成 iOS 风格。

- 目标包名：`com.sohu.inputmethod.sogou.xiaomi`（同时兼容 `com.sohu.inputmethod.sogou`）
- 目标版本：`10.32.21.202607061714`（versionCode `2147483647`，5 个 dex，arm64-v8a）
- 目标 APK SHA-256：`90B4A1945D28427A50CC6DF8FA1FE0E29977A0053622FA7A05FBDCFF01F6B09C`
- 模块包名：`com.chiron.iosemoji` · 版本：`2.0.0-alpha3`（versionCode 22）
- 最低支持：Android 8.0（API 26）· 只在 LSPosed / EdXposed 等 Xposed 框架下工作

## 下载哪个版本？

| 版本 | 适合谁 | 有什么 |
| --- | --- | --- |
| [**v2.0.0-alpha3**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha3) | 想试 iOS 风格键盘的尝鲜用户 | 自绘 iOS 键盘（显示层替换）+ iOS 风格模块界面 + 旧版全部表情功能；**alpha3 修好了设置页假开关与「模块连不上输入法」** |
| [**v1.8**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v1.8) | 只想换 Apple 表情、要稳定 | 表情分组安装 / 隐藏内置分组 / 滑动切换 / Logo 触发词 |
| [**v1.7**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v1.7) | 老设备 / 旧搜狗版本 | 与 v1.8 同代但更早，兼容面略宽 |

### 界面长什么样（alpha2 重写 / alpha3 让开关真正生效）

两个界面都换成 iOS 风格、全部代码自绘，不依赖 androidx / Material，跟随系统深浅色：

- **模块 App**：iOS 设置页 —— 大标题、分组卡片、圆角行、右侧 `✓`/`›`、自绘开关。
  分区：状态 / 表情分组（10 组逐行勾选 + 全选）/ 操作（安装选中、安装全部、还原）/ 模块（功能开关、隐藏桌面图标、重新检测）/ 运行日志。
  原 XML 布局（`activity_main.xml`、`item_group.xml`）已删除，不再需要 `GridLayout`。
- **输入法键盘**：整套 iOS 键盘 —— 字母页 qwertyuiop / asdfghjkl / `⇧ zxcvbnm ⌫` / `123 · 地球 · 空格 · ⏎`，
  符号页 `1234567890` / `-/:;()¥&@"` / `，。、？！…`；按下时键上方浮出放大气泡，`⌫` 长按连删。
  高度按搜狗主题 `py_9.ini` 的 `H=0.591667` 锁定，避免改变输入法 insets（键盘不会被裁）。
> v2.0 沿用旧版包名 `com.chiron.iosemoji`、并沿用同一张调试证书（`CN=Android Debug`），所以是**同包名覆盖升级**：装上 v2.0 会直接替换 v1.7 / v1.8，LSPosed 里原有的模块条目和作用域照旧生效，不需要重新配置。想回旧版，卸载后装 v1.7 / v1.8 即可。

---

## 0. 当前状态（请先读这段）

| 项目 | 状态 |
| --- | --- |
| 静态分析（反编译 + DEX 级取证） | ✅ 完成，产物见 [`docs/`](docs/) |
| 四条核心功能的可行性结论 | ✅ 完成 —— [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) |
| hook 点清单（30 个，含 DEX 验证描述符） | ✅ 完成 —— [`docs/HOOK_POINTS.md`](docs/HOOK_POINTS.md) |
| MVP 方案与测试用例 | ✅ 完成 —— [`docs/MVP_PROPOSAL.md`](docs/MVP_PROPOSAL.md) |
| 模块源码（键盘显示层替换 + 诊断 + 旧表情功能） | ⚠️ 已写完，**javac + aapt2 + d8 + apksigner 全链路真实构建出 APK**，未真机验证 |
| 可安装 APK | ✅ 已构建：`AppleInputForSogou_v2.0.0-alpha2.apk`（21.74 MB，见 Releases） |
| 候选栏 iOS 化 / 长按符号 / 表情面板 iOS 化 | ⏳ 未实现（Phase 4–6） |

**关于「验证」的说明**：本仓库的开发环境**没有 Android SDK / Gradle / 一台真机**，
代码是「按 DEX 验证过的描述符逐行写出来的」。已做过的检查：

- **全链路真实构建**：下载 `platform-35` 的 `android.jar` 与 `build-tools 35` 后，
  用 `aapt2 compile/link → javac → d8 → zip → zipalign → apksigner`（即父仓库 `build.sh` 的思路）
  真的产出了签名 APK；`aapt2 dump badging` 与 `apksigner verify --print-certs` 均通过。
- **javac 严格编译**：`-Xlint:all --release 17` 下全部源码 **0 error**，
  仅 1 条无害的 `this-escape` 提示。
- 这能证明：语法、类型、Android API 用法、资源引用、打包签名链路彼此自洽；
  **不能**证明：LSPosed 运行时行为正确、hook 描述符在该版本搜狗里真的命中。
  后者必须上机验证（见 [`docs/MVP_PROPOSAL.md`](docs/MVP_PROPOSAL.md) 的 TC-01 ~ TC-10）。
所有类名、方法签名、参数字典均可在 `docs/` 的证据表中查到出处（文件 + 行号），
未确认的项目一律标注为 `UNKNOWN`，而不是猜一个看起来合理的名字。

---

## 1. 它到底改了什么

搜狗输入法（小米定制版）的输入视图不是普通的 View 树，而是三层结构：

```
InputMethodService.setInputView(…)
└─ StyleConstraintLayout            ← com.sogou.core.ui.window.b#g() 创建
   ├─ WindowBackgroundLayout        ← 皮肤背景
   └─ NormalIMERootContainer        ← 槽位容器（按 z-index 存子 View）
      ├─ z=2  IMEInputCandidateViewContainer   候选栏（虚拟组件树，自绘）
      ├─ z=3  KeyboardRootComponentView        键盘（虚拟组件树，自绘）
      ├─ z=6  KeyboardHeaderContainer          顶部工具条
      └─ …                                      z=0/4/5/7…1000 共 18 个槽位
```

证据：[`docs/VIEW_HIERARCHY.md`](docs/VIEW_HIERARCHY.md)、[`docs/CLASS_MAP.md`](docs/CLASS_MAP.md)。

由此得到本模块的两个关键判断：

1. **没有 `onCreateInputView`**。整个 APK 的 5 个 dex 里都不存在这个方法名，
   搜狗的输入视图是「算好了再 `setInputView()` 推上去」。
   → 那些「hook `onCreateInputView` 换键盘」的教程对这个包**完全无效**。
2. **键盘只是一个槽位里的一个 View**。`NormalIMERootContainer#setKeyboardView(View)`
   把任意 `View` 放进 z=3 的槽位；键盘真正的实现是一个「虚拟组件树」
   （`RootComponentView` + 组件自绘），**没有 per-key View**。

所以「换皮肤」最省事、最不容易炸的做法是：
**把 z=3 槽位里的那个 View 换成我们自己的 View**，其余（候选、工具条、背景、insets）全部保持搜狗原样。

v2.0.0-alpha2 实现的正是这一步：

| Hook | 目标 | 说明 |
| --- | --- | --- |
| `H01` | `com.sogou.lib.bu.ui.component.keyboard.d#getRootView()` | 返回我们的键盘 View（覆盖所有 `getRootView()` 调用点） |
| `H02` | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setKeyboardView(View)` | 兜底：方法执行后再把我们的 View 塞进 z=3 |
| `H04` | `com.sohu.inputmethod.sogou.r2#Z7 / #N7` 等 | 只读诊断：打印候选列表推送情况 |
| `H09` | `com.sogou.core.ui.a#u(View)` | 预留（默认不启用） |

键盘上的按键**不自己造字**，而是回到搜狗自己的输入通道：

```
按键 → TextInjector
   → com.sohu.inputmethod.input.i.m().l().onText(chars, 0)     // 主路径
   → com.sohu.inputmethod.input.i.m().l().onKey(-5, {-5}, false, 0, 0)  // 退格
   → 兜底：b0.f0().t0().getSogouInputConnection().commitText(...)       // 原始 IC
```

也就是说：**英文/数字/空格/退格/回车走的是搜狗自己的 `InputLogic`**，
中文联想、词库、云输入这些一概没动（中文拼音输入在 Phase 3 接入搜狗候选后开放）。

---

## 2. 安装

1. 手机已 Root，并安装 **LSPosed**（Magisk / KernelSU 模块形式）。
2. 安装本模块 APK（`com.chiron.iosemoji`）。
3. 打开 LSPosed → 模块 → **启用 Apple Input for Sogou**。
4. 作用域勾选：`搜狗输入法`（小米定制版，包名 `com.sohu.inputmethod.sogou.xiaomi`）。
   如果你装的是通用版，勾 `com.sohu.inputmethod.sogou`。
5. 强行停止一次搜狗输入法（或切换一次输入法），让新进程加载 hook。
6. 打开任意输入框，检查：
   - 键盘是否变成 Apple 样式；
   - LSPosed 日志里是否有 `AppleInputForSogou` 开头的输出。

> 出问题想立刻恢复：在 LSPosed 里关掉模块 → 强行停止搜狗 → 切回输入法，即回到原版。
> 模块**从不修改搜狗 APK**，也不写系统分区。

---

## 3. 功能开关

模块首页底部 → **「功能开关 / 兼容信息」**。

**alpha3 起开关是即时生效的**，不用重启输入法：

1. 拨开关 → 写入模块的 `SharedPreferences`（`chiron_config`，冷启动用）；
2. 同时用**有序广播**（`com.chiron.iosemoji.action.CFG`）把全部键值下发到已运行的搜狗进程，
   hook 侧存进内存覆盖层并立刻按新值工作；
3. 搜狗进程回执一份状态串 → 页面弹出「已生效：iOS 键盘 → 开」；
   若进程没起来，则提示「已保存：…（搜狗进程未运行，切换输入法后生效）」，并在你下次切换输入法时生效；
4. 冷启动走只读 `ContentProvider`（`content://com.chiron.iosemoji.config/config`）读一次，
   不依赖跨进程 prefs 的可读性。

页面顶部「连接状态」卡会显示：搜狗进程是否在线、已装 hook 列表、**进程内真正生效的配置值**、搜狗版本。
所有开关都是**双向可逆**的：关掉立刻退回搜狗原行为（例如关掉「iOS 键盘」会把已注入的键盘摘走）。

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `master_enabled` | 开 | 总开关，关掉后模块在搜狗进程内不做任何事 |
| `keyboard_replace_enabled` | 开 | iOS 键盘显示层替换（H01/H02） |
| `emoji_group_filter_enabled` | 开 | 隐藏搜狗自带「组合」「拼装」分组 |
| `emoji_swipe_enabled` | 开 | 表情面板左右滑动切分组 |
| `logo_trigger_enabled` | 关 | 触发词替换为 Apple  Logo |
| `candidate_probe_enabled` | 关 | 候选数据只读探针（日志量大） |
| `diagnostics_enabled` | 关 | 启动 3 秒后 dump 一次输入视图树 |

---

## 4. 兼容与降级

模块内建版本门控（[`compat/VersionProfile.java`](lsposed/app/src/main/java/com/chiron/iosemoji/compat/VersionProfile.java)）：

- 版本名命中白名单（当前只有 `10.32.21.202607061714`）→ `Mode.FULL`，允许 UI 替换；
- 其它版本 → `Mode.DIAGNOSTIC_ONLY`，**只打日志、不改任何界面**；
- 任何一个 hook 安装失败 → 该功能静默不生效，搜狗原行为保持不变；
- 所有 hook 都走 `HookRegistry`：幂等（装过就不重复装）、异常吞掉并记录、不向搜狗进程抛。

**已知限制（alpha）**：我们的键盘 View 还没有实现 `com.sohu.inputmethod.sogou.s0` 接口，
所以 `setKeyboardView()` 里对 `instanceof s0` 的判断会走「非 s0」分支（顶边规则略有差异）；
`getKeyboardView()` 的调用方会把我们的 View 当成「已经在位的键盘视图」。
Phase 3 会用空实现补上 `s0` 来消除这两点差异。

---

## 5. 构建

### 5.1 前置

1. **Android Studio**（Jellyfish / AGP 8.7+）或本机 Gradle 8.9+；
2. **`XposedBridgeAPI-82.jar`** 放到 `lsposed/app/libs/`（**未随仓库提交**，获取方式见
   [`lsposed/app/libs/README.md`](lsposed/app/libs/README.md)）；
3. （可选）表情包素材：`assets/payload.zip` 需由你自己本机的 Apple emoji 位图生成，
   见 [`tools/make_payload.py`](tools/make_payload.py)。**不含 payload.zip 也能编译**，
   只是「安装表情分组」功能会在运行时提示失败。

### 5.2 命令

```bash
# 生成表情包资源（可选）
python3 tools/make_payload.py --src /path/to/apple_emoji_png \
        --out lsposed/app/src/main/assets/payload.zip

# 构建
cd lsposed
./gradlew :app:assembleDebug        # 或用 Android Studio 直接 Run
# 产物：lsposed/app/build/outputs/apk/debug/app-debug.apk
```

签名配置指向仓库里的调试签名 [`signing/debug.keystore`](signing/README.md)
（alias `androiddebugkey`，密码 `android`）——**同包名升级必须用同一签名**。

> 未在本机验证：作者环境没有 Android SDK，`gradlew` 也没有随仓库提交 wrapper jar。
> 首次用 Android Studio 打开工程时会自动补齐 wrapper 与 SDK 组件。

### 5.3 无 Gradle / 无 Android Studio 的真实构建（✅ 已在本机跑通，即 Release 里的 APK）

不装 Android Studio 也能出包，只要 `android.jar` 与 `build-tools`（`aapt2` / `d8` / `zipalign` / `apksigner`）。

**最省事：一条命令**（脚本会走完下面的 7 步，并自动从 `AndroidManifest.xml` 读 `versionCode` / `versionName`）：

```powershell
pwsh -File tools/build-apk.ps1 `
    -AndroidJar   "<SDK>\platforms\android-35\android.jar" `
    -BuildToolsDir "<SDK>\build-tools\35.0.0" `
    -Out          build\AppleInputForSogou_v2.0.0-alpha2.apk
```

编译用到的 Xposed API：优先用 `lsposed/app/libs/XposedBridgeAPI-82.jar`（仓库不提交，需自行放入）；
没有该文件时自动回退到 `lsposed/xposed-stubs/`（只有签名、没有实现，**仅参与编译，不会打进 APK**）。

下面是脚本内部等价的手工步骤，供核对：

```powershell
# 0) 取工具（一次性）
#    https://dl.google.com/android/repository/platform-35_r01.zip        -> android-35/android.jar
#    https://dl.google.com/android/repository/build-tools_r35_windows.zip -> build-tools/android-15/*

# 1) 资源
aapt2 compile --dir lsposed/app/src/main/res -o build/res.zip
aapt2 link -o build/base.apk -I android.jar `
    --manifest lsposed/app/src/main/AndroidManifest.xml -R build/res.zip `
    -A lsposed/app/src/main/assets --java build/gen `
    --min-sdk-version 26 --target-sdk-version 35 `
    --version-code 21 --version-name 2.0.0-alpha2 --auto-add-overlay

# 2) 编译（模块源码 + build/gen/**/R.java + Xposed API 桩）
javac -encoding UTF-8 --release 17 -classpath android.jar -d build/classes @sources.txt

# 3) dex
d8 --release --lib android.jar --min-api 26 --output build/dex build/classes.jar

# 4) 把 classes.dex 塞回 base.apk（先删同名 entry），再对齐、签名
zipalign -f -p 4 build/unaligned.apk build/aligned.apk
apksigner sign --ks signing/debug.keystore --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --min-sdk-version 26 `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out AppleInputForSogou_v2.0.0-alpha2.apk build/aligned.apk
```

本仓库 v2.0.0-alpha2 的 Release APK 就是用这套流程产出的，校验结果：

- `aapt2 dump badging`（alpha3）：`package name='com.chiron.iosemoji' versionCode='22' versionName='2.0.0-alpha3' minSdkVersion:'26' targetSdkVersion:'35' application-label:'Apple Input for Sogou'`
- `apksigner verify`：通过；签名 `CN=Android Debug`，证书 SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`
- APK 体积 21.74 MB（其中 `assets/payload.zip` ≈ 21.6 MB 是 Apple 表情位图，仓库不提交、只进 Release 资产）

### 5.4 只做 javac 冒烟检查（最省事）

如果只想确认源码能编译、暂时不想装 Android Studio，可以只用 `javac`：

```bash
# 1) 取 android.jar（Android 35 平台包里的 android.jar）
curl -O https://dl.google.com/android/repository/platform-35_r01.zip
unzip platform-35_r01.zip android-35/android.jar

# 2) 准备两个桩：Xposed API 桩（IXposedHookLoadPackage / XposedHelpers /
#    XposedBridge / XC_MethodHook / XC_MethodReplacement / XSharedPreferences /
#    XC_LoadPackage）与 R 桩（因为 R 由 aapt2 生成，纯 javac 环境下不存在）

# 3) 编译
javac -encoding UTF-8 -Xlint:all --release 17 \
      -classpath android.jar \
      -d out @sources.txt      # sources.txt = 模块源码 + 桩
```

本仓库当前状态：上述流程 **0 error**（`-Xlint:all` 下仅 1 条无害的 `this-escape`）。
桩代码只是为了让 `javac` 通过，**不要**把它们打进 APK——真实构建用
`lsposed/app/libs/XposedBridgeAPI-82.jar`（见 [`lsposed/app/libs/README.md`](lsposed/app/libs/README.md)）。

---

## 6. 目录结构

```
.
├── docs/                       # 静态分析产物（全部 DEX/源码取证，含行号与证据等级）
│   ├── APK_OVERVIEW.md         # ① APK 总体信息、签名、dex、native、主题包结构
│   ├── CLASS_MAP.md            # ② 输入法核心类结构（5 类 IMS 链、delegate、单例入口）
│   ├── VIEW_HIERARCHY.md       # ③ 键盘视图层级（三层窗口、18 个 z 槽位、替换缝隙）
│   ├── EVENT_CALLBACK_MAP.md   # ④ 按键事件链路（触摸 → 15 跳到 commitText）
│   ├── INPUT_ENGINE_MAP.md     # ⑤ 候选数据流（JNI → 引擎 → model → 候选条）
│   ├── EMOJI_PANEL_MAP.md      # ⑥ 表情面板结构、数据模型与替换点
│   ├── FEASIBILITY.md          # ⑦ 四条核心功能可行性报告
│   ├── HOOK_POINTS.md          # ⑧ 推荐 hook 点（H01–H30，含 DEX 描述符）
│   ├── MVP_PROPOSAL.md         # 最小可行方案 + TC 用例
│   └── MIGRATION.md            # 本仓库如何从 chiron-apple-emoji 拆分出来
├── lsposed/                    # Android Studio 工程（模块本体）
│   └── app/src/main/java/com/chiron/iosemoji/
│       ├── HookEntry.java          # LSPosed 入口
│       ├── MainActivity.java       # 表情分组安装界面（沿用 v1.8）
│       ├── SettingsActivity.java   # 功能开关 / 兼容信息
│       ├── core/                   # HookState（Context/版本）
│       ├── config/                 # ModuleConfig（XSharedPreferences）
│       ├── compat/                 # VersionProfile（版本门控）
│       ├── hook/                   # HookRegistry / H01 / H02 / 探针 / TextInjector
│       ├── keyboard/               # ChironKeyboardView / KeyboardFactory
│       ├── diagnostics/            # ViewTreeDumper
│       └── (v1.8 既有) EmojiService / GestureHook / GroupFilterHook / Groups / LogoHook / Protocol
├── manifests/                  # 10 份搜狗表情分组 info.xml（纯文本，无版权素材）
├── tools/                      # make_payload.py（生成表情位图包）、make_icons.py
├── signing/                    # 调试签名（详见 README）
└── LICENSE                     # MIT
```

---

## 7. 它的边界

**做**：
- 替换 z=3 键盘槽位的显示层；
- 通过搜狗自己的 `InputLogic` / `InputConnection` 注入按键文本；
- 隐藏、筛选搜狗自带表情分组，改变表情面板的滑动行为；
- 输出详尽日志，方便定位问题。

**不做**：
- 不重打包搜狗 APK，不改它的任何文件；
- 不实现输入引擎、不做词库、不做拼音解析（这些继续由 `libsogouime.so` 负责）；
- 不 hook `onCreateInputView`（不存在）、不反射扫描搜狗类（AspectJ 织入太多，会误伤）；
- 不监听用户输入内容用于上传/统计。

---

## 8. 免责声明

本项目仅用于**个人学习与自用美化**。使用它需要 Root 与 Xposed 框架，
存在导致输入法崩溃、系统不稳定、数据丢失的风险，**后果自负**。
请勿用于绕过任何付费/授权机制。搜狗输入法、Apple Emoji 的商标与素材版权归各自权利人所有；
本仓库不包含任何 Apple 彩色 emoji 位图（`payload.zip` 由使用者本机生成）。

## 9. 与 chiron-apple-emoji 的关系

本仓库是从 [`Cheney34/chiron-apple-emoji`](https://github.com/Cheney34/chiron-apple-emoji)
的 `lsposed-sogou/` 目录**拆分**出来的独立项目：

- 保留：表情分组安装器、分组过滤、滑动解锁、Logo 触发词（v1.8 已验证的功能）；
- 改变：模块名 `IOSEmoji` → `AppleInputForSogou`、入口类新写、构建方式（手写脚本 → Gradle + 可复现的 aapt2 链路）；**包名保持 `com.chiron.iosemoji` 不变**，保证同签名覆盖升级；
- 新增：iOS 键盘显示层替换、配置开关、版本门控、诊断工具、全套静态分析文档。

两者**没有运行时依赖**，可以分别安装/卸载（注意：同时安装时两个模块都会 hook 搜狗进程，
建议只启用其中一个）。迁移细节见 [`docs/MIGRATION.md`](docs/MIGRATION.md)。

## 10. 路线图

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 0 | APK 静态分析（类结构/视图/事件/候选/表情） | ✅ |
| Phase 1 | 运行时验证（视图树、调用链、insets） | ⏳ 需要真机日志 |
| Phase 2 | 最小注入：z=3 槽位换成自绘键盘 | 🟡 代码已写，待验证 |
| Phase 3 | 键盘 UI 完整化（`s0` 空壳、长按浮标、空格拖动光标） | ⏳ |
| Phase 4 | 候选栏 iOS 化（自绘 `r2` 替代 / `data.b` 过滤） | ⏳ |
| Phase 5 | 长按符号、中英切换、大小写态 | ⏳ |
| Phase 6 | 表情面板 iOS 化（重排 + 最近使用 + 搜索） | ⏳ |
| Phase 7 | 兼容矩阵、发布、CI | ⏳ |

## License

MIT，见 [LICENSE](LICENSE)。
