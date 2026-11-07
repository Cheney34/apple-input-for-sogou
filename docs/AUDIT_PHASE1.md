# 源码审计报告（Phase 1 · 只读，不改代码）

> 对象：`Cheney34/apple-input-for-sogou` 当前工作树
> 时间：2026-11-07
> 基准声明：**beta2 = commit `7401aa5`**（详见 §3）
> 本报告只做取证与结论，**没有修改任何源码**。唯一的仓库操作是新建只读分支 `stable/beta2`（指向 `7401aa5`）。

---

## 0. 结论摘要（TL;DR）

| # | 结论 | 证据等级 |
|---|---|---|
| 1 | 工作树 HEAD **不是 beta2**，而是 beta5（`2759345`）。仓库**没有 tag、只有一个 `main` 分支**，beta2 与后续改动混在一条线上。 | 【D】git log / branch |
| 2 | GitHub 上 **beta3 / beta4 / beta5 的 release 已被删除**；现存 20 个 release 里最新的是 `v2.0.1-beta4`，而 `v2.0.2-beta2` 被标成了「存档 + prerelease」。基线与发布状态都需要收拢。 | 【R】`GET /releases` |
| 3 | **OPPO 的类名字符串是错的**：`defpackage.yy1` / `defpackage.nm1` 只是 jadx 给「默认包类」生成的**伪包名**；DEX 里真实描述符是 `Lyy1;` / `Lnm1;`（**无包名**）。运行时 FQCN 必须是 `yy1` / `nm1`。当前 OPPO 皮肤 hook 因此 `ClassNotFound` → 被 try/catch 吞掉 → **静默跳过**。这是「OPPO 皮肤用不了」的直接原因之一。 | 【D】OPPO dex ASCII 扫描 + jadx 源 |
| 4 | **普通搜狗 20.19.0 是另一套架构**：没有 `com.sogou.lib.spage.BaseInputMethodService`、没有 `BaseStylusInputMethodService`、没有 `NormalIMERootContainer`、没有 `b0` / `InputLogic` / `keyboard.d`；但**有** `parse.utils.b`、`parse.utils.c`、`theme.common.j`、`parse.entity.b`、`ExpressionUtil`、`NoScrollViewPager`。⇒ 皮肤层（H40/H41）有机会复用；键盘/候选/文本注入层**完全不适用**。 | 【D】三渠道 dex 扫描矩阵（§1.3） |
| 5 | 有 4 个 hook 仍**硬编码小米类名**（`KeyboardRootHook`、`SetKeyboardViewHook`、`CandidateProbeHook`、`TextInjector`），且 `Targets.forPackage()` 对未知包**回落 XIAOMI**——在普通版上等于拿小米类名去 hook。这是「多渠道」最大的结构性风险。 | 【S】源码 `hook/*.java:23-36`、`compat/Targets.java:117-125` |
| 6 | 皮肤层现有两条实现并存：**类名层**（H40 ini + H41 位图，beta2 已确认可用）与**资源层**（H50 `AssetManager#open`，beta3 引入）。资源层是 beta3/4/5「键盘呼不出来」的嫌疑源，且形态上是**进程内无差别拦截**。 | 【S】`hook/AssetSkinHook.java:74-90` |

---

## 1. 三个渠道 APK 台账（实测）

### 1.1 基本信息

| 项 | 小米定制版 | OPPO 定制版 | 普通版 |
|---|---|---|---|
| 文件 | `搜狗輸入法小米版-com.sohu.inputmethod.sogou.xiaomi-10.32.21.202607061714.apk` | `搜狗输入法定制版_10.32.0622.2607201151.apk` | `base.apk` |
| 包名 | `com.sohu.inputmethod.sogou.xiaomi` | `com.sohu.inputmethod.sogouoem` | `com.sohu.inputmethod.sogou` |
| versionName | `10.32.21.202607061714` | `10.32.0622.2607201151` | `20.19.0` |
| versionCode | `2147483647` | `2570` | `2640` |
| 应用名 | 搜狗输入法小米版 | Sogou Keyboard Customized Version | 搜狗输入法 |
| targetSdk | 35 | 33 | 30 |
| ABI | arm64-v8a | arm64-v8a | arm64-v8a |
| 大小 | 64,994,361 B | 100,112,509 B | 98,337,108 B |
| SHA-256 | `90B4A194…6B09C` | `97FFFE51…C074BA` | `FC137C6B…BF67D05` |
| dex 数 | 5（classes…classes5） | 6 | 7 |

### 1.2 为什么必须先做这张表

`versionCode=2147483647`（小米版）是异常值，不能用它做版本比较；`普通版 20.19.0` 的版本号体系与 `小米/OPPO 的 10.32.x` **完全不是一条线**。所以模块的版本门控只能按「包名 + versionName」白名单，不能按 versionCode 排序【D】。

### 1.3 跨渠道类存在性矩阵（DEX 字符串实测）

`Y` = 该渠道 DEX 里存在该 class descriptor。

| 类（我们 hook 或用到的） | 小米 | OPPO | 普通版 |
|---|:--:|:--:|:--:|
| `com/sogou/lib/spage/BaseInputMethodService` | Y | Y | **–** |
| `com/sogou/gesture/BaseStylusInputMethodService` | Y | Y | **–** |
| `com/sohu/inputmethod/sogou/MainImeServiceDel` | Y | Y | Y |
| `com/sohu/inputmethod/ui/frame/NormalIMERootContainer` | Y | Y | **–** |
| `com/sogou/theme/parse/utils/b`（ini 入口） | Y | **–** | Y |
| `com/sogou/theme/parse/utils/c`（解析器） | Y | **–** | Y |
| `com/sogou/theme/common/j`（位图入口） | Y | **–** | Y |
| `com/sogou/theme/parse/entity/b`（ini 载体） | Y | Y | Y |
| `com/sohu/inputmethod/main/manager/b0` | Y | **–** | **–** |
| `com/sohu/inputmethod/input/InputLogic` | Y | **–** | **–** |
| `com/sogou/lib/bu/ui/component/keyboard/d`（键盘外壳） | Y | **–** | **–** |
| `com/sogou/expressionplugin/expression/ExpressionUtil` | Y | **–** | Y |
| `com/sogou/expressionplugin/expression/w` | Y | Y | Y |
| `com/sogou/expressionplugin/emoji/NoScrollViewPager` | Y | Y | Y |
| `Ldefpackage/yy1;` / `Ldefpackage/nm1;` | – | **–** | – |
| `Lyy1;` / `Lnm1;`（**真实**描述符） | – | **Y** | – |

**读法**：
- 皮肤三件套（`parse.utils.b` / `parse.utils.c` / `theme.common.j`）在小米与普通版**同名同包**，在 OPPO 被搬进默认包（`yy1` / `pv1` / `nm1`）。
- 键盘视图体系（`spage` / `NormalIMERootContainer` / `keyboard.d` / `b0` / `InputLogic`）只在小米与 OPPO 有，**普通版 20.x 整层不存在** ⇒ 普通版的键盘替换/候选探针/文本注入必须重新定位，不能复用现有 4 个硬编码 hook。
- `ExpressionUtil` 只在小米与普通版有；OPPO 是 `w`。三种渠道三种情况，正好说明「必须按渠道 profile 描述类名」是对的架构。

---

## 2. 仓库与发布现状

- 本地：`main` 单分支，HEAD = `2759345`（beta5）；**无 tag**。
- 远端 release（`GET /repos/Cheney34/apple-input-for-sogou/releases`，共 20 个）：`v2.0.2-beta2`（prerelease=true，名字「存档（OPPO 皮肤仍在类名层）」）、`v2.0.2-beta1`（存档）、`v2.0.1-beta1…beta4`、`v2.0.0-alpha1…alpha12`、`v1.7`、`v1.8`。**beta3/beta4/beta5 已不在列表里**（与你说的「发布页面已删除」一致）。
- ⇒ 需要收拢成：**release 侧以 beta2 为「稳定」**（去掉存档/prerelease 标记，或另发一个 `v2.0.2-stable`），代码侧以 `stable/beta2` 分支为基线。
- 已执行（只读动作）：`git branch stable/beta2 7401aa5`。

---

## 3. beta2 基线精确定义

- **beta2 = commit `7401aa5`**（`fix(skin): 2.0.2-beta2 — OPPO 定制版位图入口按方法名全挂（f/g/j/k/d/e）+ Drawable 兼容 + seen 诊断`），版本号 `versionCode 37 / 2.0.2-beta2`。
- `7401aa5 → HEAD(2759345)` 的全部差异（**这就是 beta3/4/5 加进来的东西**）：

| 文件 | 变化 | 归属 | 现在怎么看 |
|---|---|---|---|
| `hook/AssetSkinHook.java` | **+435（新文件）** | beta3 → beta5 | 资源层拦截，键盘呼不出来的嫌疑源；建议默认关 |
| `tools/bake_theme.py` | **+306（新文件）** | 重打包路线 | 已被否决（见 `docs/OPPO_REPACK.md` 横幅），保留作技术存档 |
| `hook/ConfigBridge.java` | +50 | beta3 | 定时重读（2/5/10/20/40/60s）、`resyncNow()`、`README` 状态串扩展 |
| `HookEntry.java` | +9 | beta3/5 | 装 `AssetSkinHook`、键盘弹起 resync |
| `hook/ThemeSkinHook.java` | ±15 | beta3/5 | 第三方门控 `isDefaultTheme`、`CUSTOM_SKIPPED` 计数 |
| `config/ModuleConfig.java` | +8 | beta5 | `KEY_RESOURCE_SKIN` / `resourceSkinEnabled()` |
| `config/ConfigValues.java` | +3 | beta5 | 新键进 KEYS/默认值/标签 |
| `SettingsActivity.java` | +28 | beta3/5 | 资源层开关、`skintp` 显示 |
| `hook/SetKeyboardViewHook.java` | +4 | beta3 | 键盘弹起 resync |
| `res/values/strings.xml` / `build.gradle.kts` / `AndroidManifest.xml` | 版本号 | beta5 | 40 / 2.0.2-beta5 |
| `CHANGELOG.md` / `README.md` / `docs/OPPO_REPACK.md` | 文档 | beta3/4/5 | — |

**结论**：beta2 的皮肤实现 = **H40（ini）+ H41（位图，hookAllMethods f/g/j/k/d/e）+ TargetProfile 雏形**。beta3 之后加的**全部**是「资源层 + 配置重读 + 诊断」，可以整块摘掉而不影响 beta2。

---

## 4. 模块入口与初始化时序

**入口文件**：`lsposed/app/src/main/assets/xposed_init` → `com.chiron.iosemoji.HookEntry`

```
┌─ Xposed 加载 ───────────────────────────────────────────────┐
│ HookEntry.handleLoadPackage(lpparam)                        │
│  :52  if (!Protocol.isTarget(lpparam.packageName)) return;  │  ← 只认 3 个包名
│  :56  sTargets = Targets.forPackage(pkg)                    │  ← 未知包回落 XIAOMI（风险点）
│  :57  log("targets: " + sTargets.describe())                │
│  :68  CandidateProbeHook（需开关 + supportsCandidateProbe）  │
│  :81  hook Application#attach(Context)                      │
│       └ after → onAttached(ctx)                             │
│  :102 scheduleAttachFallback(cl)                            │  ← Tinker 壳兜底
│       └ 0/300/1200/3000/8000ms: ActivityThread.currentApplication()
└─────────────────────────────────────────────────────────────┘
                              │
┌─ onAttached(ctx) ───────────┴───────────────────────────────┐
│  :137 if (!Protocol.isTarget(ctx.getPackageName())) return; │
│  :160 log("attached: " + VersionProfile.describe() + …)     │
│  ① HookState.setContext / setVersionName                    │
│  ② ConfigBridge.install(ctx)  （ContentProvider + 3 广播）   │
│  ③ installThemeHooks(cl)      → ThemeSkinHook + AssetSkinHook│
│  ④ tryInstallKeyboardHooks()  → KeyboardRootHook + SetKeyboardView│
│  ⑤ installLegacyHooks()       → GestureHook / GroupFilterHook / LogoHook│
│  ⑥ EmojiService.register(ctx) → 表情安装指令通道             │
│  ⑦ 可选 ViewTreeDumper                                      │
└─────────────────────────────────────────────────────────────┘
```

**幂等与容错**：所有安装都经 `HookRegistry.install(id, cl, installer)`——`INSTALLED`/`FAILED` 两个并发集合去重，异常只记日志不抛（`hook/HookRegistry.java`）。⇒ 单渠道适配失败不会连累其他 hook【S】。

---

## 5. 皮肤资源加载流程（当前实现）

### 5.1 类名层（beta2 已有，小米版已验证可用【R】）

```
搜狗读皮肤 ini
   └─ com.sogou.theme.parse.utils.b#a(String path)        ← H40 after
        ├ assets/ 前缀 → parse.impl.b#b(Context,String,true,entity.a)
        └ 否则          → parse.impl.a#a(String,entity.a) → c(File) → d(Stream) → e(Stream,String,entity.a)
           返回 com.sogou.theme.parse.entity.b（内部 ArrayMap<段, Map<键,值>>，b() 可取出就地改）
          └ H40 afterHook：entity.b#b() → IosSkin.patchIni(path, sections, preset)
               · 只改已存在的键（文字色/强调色/候选栏底色），不新增段
               · 文字色经 ensureContrast(fg,bg,3.0) 按 WCAG 兜底

搜狗读皮肤位图
   └ com.sogou.theme.common.j#f(Context,String,Z,Z)  …（OPPO：nm1#j/k/e）  ← H41 after（hookAllMethods）
        └ IosSkin.kindOf(path) → colorOfKind(...) → synth(src,kind,color,preset)
             · fullBleed 判据：包围盒 < 85% 视为图标/字形 → 原样返回
             · hasInk 判据：盒内亮度中位数偏离 ≥40 的像素 >2% → 原样返回
             · detectRadius：从资源自身取圆角
```

**H41 的挂法**（beta2 的关键修复）：`GETTER_NAMES = {"f","g","j","k","d","e"}` 对 `Targets.bitmapClass` 逐个 `XposedBridge.hookAllMethods`，日志 `H41 bitmap getters hooked on <class>: N/6`。不再写死单一签名——这是 OPPO（`nm1` 用 `k`/`e`）与小米（`j` 用 `f`）能共存的唯一办法【S】。

### 5.2 资源层（beta3 引入，**未经渠道验证**）

```
任意 context.getAssets().open("theme/…")        ← H50（AssetManager#open(String) / open(String,int)）
   └ AssetSkinHook.onOpen：isThemeAsset(path) → isDefaultTheme(path) → 读全字节
        ├ .ini  → 行内取值替换（parseIni → diff → applyDiff 只替换值）+ sameSkeleton 复核
        └ .png/.webp → decodeBitmap → IosSkin.synth → PNG 重编码
   └ 失败 3 次 → trip() 熔断，本进程不再改皮肤资源
```

**形态问题（必须在多渠道方案里解决）**：
1. 它 hook 的是 `AssetManager#open` —— **进程内所有资源读取都会经过**。虽然用 path 白名单收窄，但这是「无差别拦截」的形态，与手册 §6.3 的要求相冲突。
2. 它返回的是替换后的 `InputStream`，改变了「调用方拿到的字节」；一旦某渠道的皮肤链路对字节长度/CRC 有假设（或对同一个 asset 多次 open），风险不可控。
3. beta3 曾把 ini **重新序列化**导致第三方皮肤解析异常 → 键盘起不来；beta5 已改为行内替换 + 骨架复核 + 熔断，但**根因是「拦截面太大」，不是「改法不够细」**。

---

## 6. INI / XML 解析链路

- 搜狗用的是 **`ArrayMap<段, ArrayMap<键,值>>` 的类 INI 结构**，解析器为 `com.sogou.theme.parse.utils.c#n(BufferedReader)`（小米/普通版）；`parse/parseimpl/*` 是「一个类解析一个段」的分发层（如 `i.java→ROWS`、`f0.java→H_OFFSET`、`b.java→H_OFFSET/…`、`w0.java→CANDIDTAE_PREVIEW`），由 `parse/frame/*` 调度。
- 段/键常量集中在 `parse/frame/a.java`：文本样式键 `TEXT_STYLE / TEXT_STYLE_SELECTED / TEXT_HIGHLIGHT_STYLE / COMMITTED_TEXT_STYLE / FG_STYLE / POPUP_FG_STYLE / POPUP_BG_STYLE / MINOR_LABEL_STYLE / MIDDLE_LABEL_STYLE / LABEL_STYLE / BG_IMAGES`；图标键 `ICON / ICON_PRESSED`。
- **默认皮肤（内置）走 APK assets**：`parse/frame/h0.java` 读 `assets/theme/file_name_mapping_{720,1080}.ini`、`assets/foreign/theme_{folded,flip}_mapping.ini`；`parse/frame/p0.java` 注册 `assets/theme/thememanifest.ini` 的解析器。⇒ 改默认皮肤不必改 APK，只需在读取/解析环节替换内容【D】。
- 目前模块**不解析 XML**，只改 ini 与位图；`res/xml/preemoji.xml`（81 KB，表情内置分组）只在表情链路上被搜狗自己解析。
- **OPPO 差异**：解析器实现被搬到默认包（`pv1#e`），入口是 `yy1#a(String)`；`entity.b` 载体未改名（仍在 `com.sogou.theme.parse.entity`）。

---

## 7. Hook 总表（当前源码实测）

| ID | 层 | 目标类 | 方法/签名 | 是否 Targets 驱动 | 默认 | 风险 |
|---|---|---|---|---|---|---|
| H01 | 键盘 | `com.sogou.lib.bu.ui.component.keyboard.d` | `getRootView():View` | ✗ 硬编码 | 关 | 仅小米；OPPO/普通版无此类 |
| H02 | 键盘 | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer` | `setKeyboardView(View)` | ✗ 硬编码 | 关 | 同上（OPPO 有类但缺 `addSmartThemeLayerView`） |
| H04 | 候选 | `com.sohu.inputmethod.sogou.r2` | `Z7(data.b,int,boolean)` / `N7(data.b)`；`AbstractImeServiceDelegate#showCandidateWordView` | ✗ 硬编码 | 关 | 仅小米 |
| H40 | 皮肤(类名) | `com.sogou.theme.parse.utils.b`（OPPO: `yy1`） | `a(String):parse.entity.b` | ✓ | **开** | 普通版同名可用；OPPO 当前名字写错 |
| H41 | 皮肤(类名) | `com.sogou.theme.common.j`（OPPO: `nm1`） | `hookAllMethods{f,g,j,k,d,e}` | ✓ | **开** | 同上；重载差异靠 hookAllMethods 兜 |
| H42 | 诊断 | `com.sogou.theme.parse.utils.c` | `n(BufferedReader)` 计数 | ✓（可 null） | 随皮肤 | 无副作用 |
| H50 | 皮肤(资源) | **`android.content.res.AssetManager`** | `open(String)` / `open(String,int)` | ✓（只判 path） | **开** | 进程级无差别拦截；beta3/4/5 问题嫌疑源 |
| L1 | 表情 | `NoScrollViewPager` | `setScrollable(Z)` + 2 构造 | ✓ | 开 | 三渠道都有该类 |
| L2 | 表情 | `EmojiKeyboardView` | `showSlideAnimation()` | ✓ | 开 | 三渠道都有该类 |
| L3 | 表情 | **`java.io.File`** | `list():String[]` | ✗（判路径后缀） | 开 | 进程级拦截文件枚举 |
| L4 | 表情 | `ExpressionUtil` / `w` | `u(AssetManager,String,String)` / `x(...)` | ✓ | 开 | 三渠道三种类名 |
| L5 | 提交 | **`android.inputmethodservice.InputMethodService`** | `onCreate()`→`getCurrentInputConnection()`；`hookAllMethods{commitText,setComposingText}` | ✗ | 关 | 跨渠道安全，但会挂到系统类 |
| T1 | 注入 | `com.sohu.inputmethod.input.i` / `main.manager.b0` / `MainImeServiceDel` | `m()→l()→onText/onKey`；`f0()→t0()→getSogouInputConnection()` | ✗ 硬编码 | 随键盘 | **仅小米**；普通版无 `i`/`b0` |
| C1 | 配置 | `Application` | `attach(Context)` | — | 常开 | 需要 attach 兜底（Tinker 壳） |

---

## 8. 跨渠道不兼容点（按严重度）

1. **【致命】OPPO 类名写错**：`Targets.OPPO` 用 `"defpackage.yy1"` / `"defpackage.nm1"`，运行时真实 FQCN 是 `yy1` / `nm1`（默认包）。⇒ H40/H41 在 OPPO 上必然 `ClassNotFound`，被 `HookRegistry` 吞掉。**这不是"没触发"，是"根本没挂上"**。
2. **【致命】普通版缺失整个视图层**：`spage`、`NormalIMERootContainer`、`keyboard.d`、`b0`、`InputLogic` 都不存在 ⇒ H01/H02/H04/T1 在普通版上 100% 失败；而 `Targets.forPackage()` 会对未知包回落 `XIAOMI`，等于用小米名去普通版里找类（现在靠 try/catch 兜住，但会刷错误日志）。
3. **【高】硬编码 vs Targets 混用**：只有皮肤（H40/H41/H42）与表情（L1/L2/L4）走 Targets；键盘、候选、注入、根容器全是常量。手册要求「配置文件驱动」，现状只做了 1/3。
4. **【高】H50 的拦截面**：`AssetManager#open` 是进程级入口，与手册 §6.3「不能对所有 `open()` 无差别替换」直接冲突。若保留，必须：只对白名单 path、只回退到原始流、默认关闭、按渠道开关。
5. **【中】H40 的单一签名**：`parse.utils.b#a(String)` 假设，一旦某渠道改成 `a(String, boolean)` 或换类名就静默失效（H41 已用 hookAllMethods 解决同类问题，H40 还没有）。
6. **【中】`File#list` 全局拦截**（L3）：只为过滤 `/sogou/emojipackage` 下的 `2_`/`-2_` 目录，但挂的是 `java.io.File` 本身；渠道间表情目录名一旦不同，等于无效拦截。
7. **【中】`LogoHook` 挂系统类**（L5）：`InputMethodService#onCreate` + `getCurrentInputConnection`；行为安全但属于「挂系统方法」，在多渠道下应改为「只在目标进程 + 只在需要时」。
8. **【低】版本门控依赖 versionName 精确匹配**：`VersionProfile.VERIFIED` 只有小米一个版本串；OPPO/普通版走 `DIAGNOSTIC_ONLY` 分支，容易被误当成「功能失效」。
9. **【低】配置通道依赖 ContentProvider + 有序广播**：ColorOS/HyperOS 都可能拦；已有 6 次定时重读兜底，但普通版若进程名/`applicationId` 不同，`authority` 解析要复核。

---

## 9. 需要进一步用 APK / 运行日志确认的问题

**A. 普通版 `com.sohu.inputmethod.sogou` 20.19.0（全空白，优先级最高）**
- A1 输入视图根类是什么？（无 `NormalIMERootContainer`；需找 IME 的 `setInputView` 调用方与根容器）
- A2 候选栏视图类 / 候选数据 fasade 类（无 `r2`、无 `input.api.data.b`？）
- A3 皮肤链路是否仍是 `parse.utils.b#a(String)` + `theme.common.j#f(...)`（矩阵显示"有类"，但**方法签名未验证**）
- A4 文本提交入口（无 `input.i` / `b0`）——是否仍在 `MainImeServiceDel#getSogouInputConnection()`？
- A5 表情分组目录与 `ExpressionUtil#u` 是否同语义（此类存在）
- A6 是否存在 Xposed/Tinker 反检测

**B. OPPO `com.sohu.inputmethod.sogouoem` 10.32.0622**
- B1 `yy1#a(String)` 返回值是否仍是 `parse.entity.b`（jadx 已见 `public static b a(String)`，**需运行验证**是否被 H40 命中）
- B2 `nm1` 的 `j/k/e` 三个入口哪个是真正常用（H41 已全挂，需日志 `H41 bitmap getters hooked on nm1: N/6` + `skinse`/`seen=` 计数）
- B3 键盘外壳 s0 实现是否只有 `com.sohu.inputmethod.sogou.floatmode.l`（决定能否做自绘键盘）
- B4 候选视图 / `b0` / `InputLogic` 的等价类
- B5 该版是否把 `spage` 也改名（矩阵显示 `BaseInputMethodService` 存在，但 `NormalIMERootContainer` 有却缺 `addSmartThemeLayerView`，说明是同源不同版本）

**C. 小米版（回归基线）**
- C1 beta2 在小米版的实测日志存档（`H40 skin ini patched: N` / `H41 bitmap getters hooked on …: 6/6` / `skimg=`）——目前只有"能用"的口头结论，**没有留存日志**
- C2 `py_9.ini` 的 `[Key_Enter]` 无独立 `BG_IMAGES` ⇒ 回车键走字母键底图（影响"发送键变绿"这类需求）
- C3 第三方皮肤路径（`.theme/sogou_custom/…`、`/themes/…`、`.ssf`）在真机上的实际形态

---

## 10. 建议的第一阶段动作（不含代码改动）

1. **收拢基线**：`stable/beta2` 分支已建（`7401aa5`）；把 GitHub 上 `v2.0.2-beta2` 从「存档/prerelease」改回「稳定」，或发 `v2.0.2-stable` 指向同一 APK。
2. **把 Targets 变成真配置**：新增第 3 个 profile `STANDARD`（先全 `null`，逐项填）；`forPackage()` 对未知包返回 `null` 而不是回落 XIAOMI（宁可什么都不 hook）。
3. **硬编码迁入 Targets**：`KeyboardRootHook` / `SetKeyboardViewHook` / `CandidateProbeHook` / `TextInjector` 的 `CLS_*` 常量改成读 profile（profile 为 null 就跳过）。
4. **修 OPPO 名字**：`"defpackage.yy1"` → `"yy1"`、`"defpackage.nm1"` → `"nm1"`（这一条预期能让 OPPO 皮肤立刻从"没挂上"变成"挂上"）。
5. **H50 降级为实验**：默认关、只在 `Targets` 明确允许的渠道开；把「拦截面」写进 README 的风险栏。
6. **建立测试留痕**：每个渠道跑一遍 §11 的矩阵，日志归档到 `docs/logs/<channel>-<date>.txt`。

---

## 11. 回归测试矩阵（手册 §11 的落地版）

| 场景 | 小米 | 普通版 | OPPO |
|---|---|---|---|
| 输入法启动 / 键盘弹出 | beta2 已通过【R】 | 待测 | 待测（beta2 未验证） |
| 中英文输入 / 候选显示 | beta2 已通过【R】 | 待测 | 待测 |
| 皮肤生效（内置默认） | beta2 已通过【R】 | 待测 | 待测 |
| 第三方皮肤不崩 | 待测 | 待测 | 待测 |
| 表情安装 / 面板 | beta2 已通过【R】 | 待测 | 待测 |
| 异常回退（关开关即恢复） | 待测 | 待测 | 待测 |

> 未测过的渠道，一律按「未兼容」表述，不写"已支持"。

---

## 12. 取证命令（可复现）

```powershell
# APK 台账
& $BT\aapt2.exe dump badging <apk>
Get-FileHash <apk> -Algorithm SHA256

# 类存在性（不依赖 androguard，直接扫 dex 字符串）
# 见本报告脚本片段：ZipFile 打开 apk → 读 classes*.dex → ASCII 匹配 "Lcom/…;"

# 全量反编译
java -jar ChironIOSInput\tools\apktool.jar d -s -f <apk> -o <out>
ChironIOSInput\tools\jadx\bin\jadx.bat --no-res -d <out> <apk>

# 运行日志
adb logcat -c ; <复现> ; adb logcat -d -v time > sogou-log.txt
adb shell dumpsys input_method
```

**日志关键字**（模块自己的）：`AppleInputForSogou`、`targets:`、`H40 skin ini patched`、`H41 bitmap getters hooked on`、`H41 skin bitmap redrawn`、`H50 circuit breaker OPEN`、`attached:`。
