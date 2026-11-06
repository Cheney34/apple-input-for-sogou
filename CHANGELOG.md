# Changelog

本项目遵循「分析 → 验证 → 实现」的推进方式：每个版本只写**有证据支撑**的东西。

## v2.0.0-alpha6 — 四套皮肤可选：iOS / 微信 / Google / 莫奈取色

### 为什么要多套

用户反馈「现在这个皮肤还是没有达到 iOS 的那种风格」，并且希望在 iOS 之外再要
**微信**、**Google 输入法（Gboard / Material 3）**、**莫奈取色（Android 12+ 动态配色）** 三套。
单一配色不可能同时满足三种审美，于是把「配色」从写死改成**可切换的预设**。

### 这一版怎么改

- **新增 `skin/SkinPreset`**：一套预设 = 底色 / 键帽 / 按下态 / 功能键 / 气泡 / 文字 / 强调色
  + 画法参数（圆角比例、投影、是否画底边分隔）。四套：
  | 预设 | 底 | 键帽 | 功能键 | 强调色 | 画法差别 |
  |---|---|---|---|---|---|
  | `ios` | `#D1D4D9` | 纯白 `#FFFFFF` | `#ADB3BC` | `#007AFF` | 圆角 0.12 + 淡底边 |
  | `wechat` | `#F2F2F2` | 纯白 | `#E9E9E9`（很淡） | 微信绿 `#07C160` | 圆角 0.10 + 淡底边 |
  | `google` | 白 | `#F1F3F4`（键帽比底深） | `#E8EAED` | Google 蓝 `#1A73E8` | 圆角 0.14，无底边 |
  | `monet` | 系统 `neutral1_100` | 系统 `neutral1_50` | `system_neutral2_200` | `system_accent1_500` | 圆角 0.16，取自壁纸配色 |
  莫奈预设走 `Resources.getIdentifier("system_accent1_500","color","android")` 读 framework
  动态配色（API 31+），读不到就退回一组 Material You 兜底色 —— 不会因为取色失败而变难看。
- **`IosSkin` 改为预设驱动**：`patchIni(path, sections, preset)` / `colorOf(name, preset)` /
  `synth(src, kind, color, preset)`；键帽重绘逻辑不变（沿用原图不透明包围盒 → 位置尺寸仍是搜狗的），
  但圆角、投影、底边、配色全部来自预设。
- **切换即时生效**：设置页新增「皮肤风格」行（`ios` / `wechat` / `google` / `monet`），选中后
  - 写 `skin_preset` 到 prefs（冷启动用），
  - 用有序广播连同 7 个开关一起下发，
  - 搜狗进程收到后调用 `ThemeSkinHook.dropCaches()` 清掉 `com.sogou.theme.common.j` 的位图缓存，
    再按新配色重画 —— 不换皮肤也要清缓存，否则还是上一套颜色。
- **配置通道扩展字符串键**：`RuntimeConfig` 增加 `STRINGS` 覆盖层与 `getString/putString`；
  `ConfigProvider` 额外返回 `skin_preset` 列（冷启动也能拿到）；`ModuleConfig.skinPreset()` 优先级
  运行时覆盖 > prefs > 默认 `ios`，非法值回落 `ios`。

### 构建与验证

- `build-alpha6`：`AppleInputForSogou_v2.0.0-alpha6.apk` 22,812,229 B
- `aapt2 dump badging`：`com.chiron.iosemoji` / `versionCode 25` / `2.0.0-alpha6` / label `Apple Input for Sogou`
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（与 v1.7 / v1.8 / alpha2–alpha5 一致，可覆盖升级）
- `dexdump -f`：105 个类全部 `Lcom/chiron/iosemoji/*`，`de/robv` 定义 0（桩未进包）
- dex 字符串确认含 `skin_preset` / `wechat` / `google` / `monet` / `skin caches dropped`

### 仍待确认（需要真机）

皮肤是否真的换成对应风格、切换后是否**不用重启输入法**就变色、莫奈在你机器上取到的色阶是否符合预期。

## v2.0.0-alpha5 — 键帽按 iOS 规格「重绘」，对比度与底色统一

### 为什么 alpha4 还不够像 iOS

alpha4 是给搜狗原图**刷一层色**（先铺目标色、再用原图 alpha 做 `DST_IN` 遮罩）。三个后果：

1. **对比度偏灰**：原图自带内阴影/渐变，刷色之后键帽不是 iOS 那种干净的纯白，整体发闷；
   文字色也只在少数键上改到了。
2. **边角不像**：刷色不改形状，圆角仍是搜狗自己的（近似小圆角），iOS 的键帽圆角明显更圆。
3. **候选栏/顶部条没跟键盘连成一片**：alpha4 把候选栏底色设成透明，结果上面一条灰带
   跟键盘的 `#D1D4D9` 底色对不上，看起来像"状态栏那一块没跟着一起画"。

### 这一版怎么改

- **H41 从「重上色」改成「按 iOS 规格重绘」**（`IosSkin.synth`）：
  - 先求原图的**不透明包围盒**——键帽的位置与大小仍然完全沿用搜狗的（所以九宫格拉伸参数、
    按键命中区一个像素都没变）；
  - 在包围盒里画 iOS 圆角矩形，**半径 = 键帽高度 × 0.12**；
  - 先画 1px 暗色再盖主色，露出的一条就是 iOS 键帽下面那道淡分隔线；
  - 配色改为纯白键帽 / 更灰的功能键（浅色），深色态底色 `#2C2C2E` + 中灰键帽 `#636366`。
- **H40 补对比度与底色统一**：
  - 文字色统一拉满：浅色纯黑 `#000000`、深色纯白 `#FFFFFF`（搜狗默认是 `#35373B` 这种发灰的）；
  - 候选栏 / 拼音区 / 云候选 / 顶部智能条（`CandidateBar` / `ComposingView` / `CloudView` /
    `FloatWindow` / `HeaderView` / `KeyboardHeader`）底色**统一成键盘底色**，不再透明；
  - 选中候选文字用 iOS 蓝（`#007AFF` / 深色 `#0A84FF`）。
- **位图分类细化**：`KIND_FILL`（整块底色，平铺）/ `KIND_KEY`（键帽，圆角重绘）/
  `KIND_POPUP`（气泡，圆角更大）；键帽再按「字母键 / 功能键」与「常态 / 按下态」分色。

### 构建与验证

- 产物 `AppleInputForSogou_v2.0.0-alpha5.apk`（versionCode 24 / versionName 2.0.0-alpha5，
  label `Apple Input for Sogou`，证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`）。
- `dexdump`：定义类 101 个，全部 `Lcom/chiron/iosemoji`，`de/robv` 定义 **0** 个。

### 还需要你确认的三件事

1. 键帽圆角是否接近 iOS（嫌圆/嫌方我调 `RADIUS_RATIO`）；
2. 对比度够不够（键帽纯白 vs 底色 `#D1D4D9`）；
3. 候选栏与顶部条是否已经和键盘连成一片。

## v2.0.0-alpha4 — 换方向：不再盖自绘键盘，改成改搜狗自己的默认皮肤

### 0. 为什么要换方向

- **现象**：装上 alpha2 / alpha3 后，键盘样式会「卡 bug」（键位、底色、候选区错乱）。
- **原因**：搜狗键盘**不是普通 View 树**，而是一棵虚拟组件树自绘
  （`imskit…component.RootComponentView` + 组件，**没有 per-key View**）。
  我们塞进 z=3 槽位的那个 View 只是个外壳：布局、命中测试、候选高度、insets、
  皮肤资源索引全都还是搜狗按自己那套算 —— 两套逻辑必然打架。
- **结论**：不再抢着画键盘，改为**顺着搜狗自己的皮肤引擎改外观**。
  搜狗自己画的键盘，天然不会有布局问题。

### 1. 搜狗皮肤是怎么跑的（取证）

```
assets/theme/thememanifest.ini             主题清单
└─ assets/theme/theme_default/theme.ini    [ThemeInfo] / [Advance]（键盘类型 → 布局 ini）
   ├─ 1080/layout/colors.ini               颜色表
   ├─ 1080/layout/images_config.ini        位图九宫格切边
   ├─ 1080/layout/image_list.ini           逻辑名 → png
   ├─ 1080/port/py_9.ini                   几何：W / H / PADDINGS / ROWS / H_OFFSET
   └─ 1080/res/{black,white}/*.png         键帽、键盘底、气泡等位图
```

关键发现：**ini 与位图各只有一个统一出口**，卡住这两个出口就能整套换皮：

- ini：`com.sogou.theme.parse.utils.b#a(java.lang.String)` [classes2] —— `assets/` 前缀走
  `AssetManager`，否则走文件；解析结果是可变的「段 → 键 → 值」表（`parse.entity.b#b()`）。
- 位图：`com.sogou.theme.common.j#f(Context,String,boolean,boolean)` [classes2] —— 资产走
  `getAssets().open()` + `BitmapFactory.decodeStream`，带缓存。

### 2. 新增两个 hook

| Hook | 目标（DEX 已验证） | 作用 |
| --- | --- | --- |
| `H40_theme_ini` | `com.sogou.theme.parse.utils.b#a(java.lang.String)` | 就地改颜色键：强调色 → iOS 蓝（`#007AFF` / 深色 `#0A84FF`）、候选栏与云候选底色 → 透明、功能键文字色 → 黑白 |
| `H41_theme_bitmap` | `com.sogou.theme.common.j#f(Context,String,boolean,boolean)` | 按文件名给位图重上色：键盘底 / 字母键 / 功能键 / 气泡各用 iOS 灰白色板 |

### 3. 只改颜色，绝不动几何

- **不动 `W`/`H`/`PADDINGS`/`ROWS`/`H_OFFSET`**：几何一变就连锁影响 insets 与命中区，
  这正是之前「样式卡 bug」的根源。
- 位图**只重上色、不改尺寸**：新建同尺寸位图铺目标色，再用原图 alpha 做
  `PorterDuff.Mode.DST_IN` 遮罩 → 形状与尺寸完全保持，`images_config.ini` 的九宫格切边参数继续有效。
- 两个 hook 都每次调用判开关：关掉「iOS 皮肤」，下一次读皮肤即回到搜狗原配色（不是一次性替换）。

### 4. 自绘键盘降级为实验开关

- `theme_skin_enabled`（**新，默认开**）：iOS 皮肤 —— 外观主路径。
- `keyboard_replace_enabled`（**默认改为关**）：自绘键盘（H01/H02），实验性。
- 新增 `config/ConfigMigration`：升级到 alpha4 时一次性把老配置里的
  `keyboard_replace_enabled=true` 关掉、`theme_skin_enabled` 置 true（只跑一次，标记 `migrated_v23_skin`）。

### 5. 构建与验证

- 产物：`AppleInputForSogou_v2.0.0-alpha4.apk` = 22,808,133 B；versionCode 23 / versionName 2.0.0-alpha4；
  `application-label='Apple Input for Sogou'`；证书 `CN=Android Debug`
  SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`（与 v1.7 / v1.8 一致 ⇒ 同包名覆盖升级）。
- `dexdump -f` 复验：定义类 101 个，**全部 `Lcom/chiron/iosemoji`**，`de/robv` 定义 **0** 个
  （alpha3 修的「桩类污染 dex」没有复发）；dex 字符串里可查到
  `hook/ThemeSkinHook`、`skin/IosSkin`、`config/ConfigMigration`、`H40_theme_ini`、`H41_theme_bitmap`、`theme_skin_enabled`。
- 仍未上机验证：皮肤是否真的生效、位图重上色是否覆盖到你用的那套皮肤、开关即时可逆。

## v2.0.0-alpha3 — 设置页真的能用了 + 修掉导致「连不上输入法」的构建缺陷

这一版只修两个问题，都是实测可验证的真 bug。

### 1. 「功能开关」页以前是假开关（已修）

- **症状**：点开设置页，拨开关没有任何反馈，输入法行为也不变。
- **原因**：旧实现只把值写进模块自己的 SharedPreferences，**从不下发到已经运行的搜狗进程**；
  hook 侧又只在进程启动时读一次，且冷启动时用 `XSharedPreferences` 读别的 App 的私有 prefs
  在部分系统上读不到 —— 于是开关永远是默认值。
- **修法（三条通道）**：
  1. **运行中即时生效**：改开关 → 写本地 prefs（冷启动用）**并且**用**有序广播**把所有键值
     直接下发到搜狗进程（`com.chiron.iosemoji.action.CFG`），hook 侧落到内存覆盖层
     `RuntimeConfig`，开关判断每次都读它；
  2. **回执即反馈**：搜狗进程收到后 `setResultData(...)` 回一份状态串，设置页据此弹出
     「已生效：iOS 键盘 → 开」或「已保存：…（搜狗进程未运行，切换输入法后生效）」；
  3. **冷启动也能读到**：新增只读 `ContentProvider`（`content://com.chiron.iosemoji.config/config`），
     搜狗进程启动时用它读一次开关，不再依赖跨进程 prefs 的可读性。
- 设置页新增「连接状态」卡：搜狗进程是否在线、已装 hook 列表、**进程内真正生效的配置值**、搜狗版本。
- 所有开关改为**双向即时可逆**：hook 常驻安装，回调里判断开关 —— 关掉立刻退回搜狗原行为
  （例如关掉「iOS 键盘」会把注入的键盘摘走）。

### 2. 修掉「模块连不上输入法」的构建缺陷（已修）

- **症状**：装上新版后，模块页显示未连接、LSPosed 里看不到本模块的 hook 日志。
- **原因**：构建脚本在缺少官方 `XposedBridgeAPI-82.jar` 时，把 `lsposed/xposed-stubs/*.java`
  当作**源码**参与编译，桩类被打进了 `classes.dex` —— 模块包里出现了**假的**
  `de.robv.android.xposed.*` 类（`XposedBridge`/`XposedHelpers`/`XC_MethodHook`/
  `IXposedHookLoadPackage`），运行时会与框架注入的同名类打架，导致模块内 hook 静默失效。
  用 `dexdump` 对比确认：修前 dex 109,680 B、含桩类；修后 107,320 B、94 个类**全部**是
  `com.chiron.iosemoji.*`，无 `de.robv` 定义。
- **修法**：桩只编译成独立 `xposed-stubs.jar` 作 `-classpath` 用，不进 `sources.txt`；
  d8 打包时再按 `de\robv\` 路径排除一次，双保险。
- 同时加固 attach：搜狗是 Tinker 热修复壳（`SogouTinkerApplication`），模块可能在
  `Application#attach` 之后才被加载，attach hook 永远不触发。现在额外用
  `ActivityThread.currentApplication()` 主动取 Context，并按 0/300/1200/3000/8000ms 重试。

### 构建产物（本机实测）

- `build/alpha3/AppleInputForSogou_v2.0.0-alpha3.apk`，22,804,037 B（含 `assets/payload.zip`）
- `package name='com.chiron.iosemoji' versionCode='22' versionName='2.0.0-alpha3'`，
  `application-label:'Apple Input for Sogou'`，证书 SHA-256 `4fbf65ed…c207`（同包名覆盖升级）

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
