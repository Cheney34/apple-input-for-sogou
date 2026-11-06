# Apple Input for Sogou — 阶段 0 静态分析报告（1/8）：APK 概览

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi`（小米定制版搜狗输入法）
> 分析日期：2026-11-06
> 分析方式：纯静态（apktool 2.11.1 解码 + jadx 1.5.1 反编译 + androguard 4.1.4 DEX 索引）。**本文件中所有可用于 hook 的结论均标注"运行时未验证"**，除特别说明外均未在真机上验证。

---

## 1. 文件与完整性

| 项目 | 值 | 证据来源 |
|---|---|---|
| 原始路径 | `C:\Users\KOUFIK\Downloads\搜狗輸入法小米版-...apk` | 用户提供 |
| 分析用副本 | `AppleInputForSogou\input\sogou.apk` | 本地复制（ASCII 路径） |
| 大小 | 64,994,361 字节 | zip 目录遍历 |
| SHA-256 | `90B4A1945D28427A50CC6DF8FA1FE0E29977A0053622FA7A05FBDCFF01F6B09C` | 用户提供 + 本地 `Get-FileHash` 一致 |
| zip 条目总数 | 11,256 | `zipfile.namelist()` |
| 分布 | res 6,944 / assets 4,272 / lib 30 / 根 7 / META-INF 3 | 同上 |

### 签名

| 项目 | 值 |
|---|---|
| 签名方案 | v1 (JAR) + v2 + v3 均存在（`APK.is_signed_v1/v2/v3()` → True/True/True）|
| 签名文件 | `META-INF/CERT.RSA` 1,121 B，`CERT.SF` 1,341,721 B，`MANIFEST.MF` 1,341,645 B |
| 证书主体 | `C=CN, CN=私人不羁`（自签名，非搜狗官方主体）|
| 有效期 | 2024-06-21 17:54:24 → 2524-02-21 17:54:24 |
| 证书 SHA-256 | `8FE4D954DFBD81B905BEF58014C9F8F15E70D9292EBC9ADB8547FC97DD77872D` |

> 说明：证书主体是个人名义，说明分发包被二次签名过（第三方分发/魔改版常见）。**模块不得校验官方证书，也不能用该证书重签任何东西**；本文档仅记录指纹，用于用户核对"同一个包"。

---

## 2. 版本信息

| 项目 | 值 | 证据 |
|---|---|---|
| package | `com.sohu.inputmethod.sogou.xiaomi` | AndroidManifest.xml / androguard |
| versionName | `10.32.21.202607061714` | AndroidManifest.xml / androguard |
| versionCode | `2147483647`（0x7FFFFFFF） | androguard `get_androidversion_code()`（二进制 AXML 解析，已与 apktool.yml 交叉验证一致）|
| minSdkVersion | 23 | AndroidManifest.xml |
| targetSdkVersion | 35 | AndroidManifest.xml（effective target 35）|
| compileSdkVersion | 33 | AndroidManifest.xml |
| 组件数 | Activity 351 / Service 24 | androguard |
| 权限数 | 43 | androguard |

> **versionCode 异常点（需在兼容层记录）**：`2147483647` 是 `Integer.MAX_VALUE`，正常应用不会这么写。推测为分发方为"永远最高优先级覆盖安装"而改写。对模块的影响：**不能依赖 versionCode 做版本判定**，版本 profile 必须用 `versionName` 字符串 + 关键类/方法存在性探测双条件。

### 主 Application

```
android:name="com.sohu.inputmethod.sogou.SogouTinkerApplication"
android:process="com.sohu.inputmethod.sogou.xiaomi"
android:largeHeap="true"  android:extractNativeLibs="true"  android:directBootAware="true"
android:appComponentFactory="androidx.core.app.CoreComponentFactory"
```

`SogouTinkerApplication`（classes4.dex，1,451 B）**不是普通的 Application**：

```java
public class SogouTinkerApplication extends com.tencent.rfix.loader.app.RFixApplication {
    public SogouTinkerApplication() { super("com.sohu.inputmethod.sogou.SogouAppApplication"); }
    @Override protected void attachBaseContext(Context context) {
        ...; com.sogou.inputmethod.oem.entry.a.c().d(new u4(new Object[]{this, context, ...}).c(69648));
    }
}
```

- `com.tencent.rfix.loader.app.RFixApplication` → **腾讯 Tinker / RFix 热修复框架**。真实 Application 逻辑在 `com.sohu.inputmethod.sogou.SogouAppApplication`（由 Tinker loader 反射实例化）。
- **对模块的直接影响（重要）**：
  1. 运行时代码可能来自 Tinker 补丁 dex，而非 APK 内的 `classes*.dex`。**所有 hook 必须使用 `lpparam.classLoader` 解析类**（这本来就是 Modern Xposed 的正确做法），绝不能 `Class.forName(..., 系统 CL)`。若线上已下发补丁，类实现可能与本次静态分析的 dex 不完全一致 → 这是"静态→运行时"的最大偏差源，必须在诊断面板打印 `Class.getProtectionDomain().getCodeSource()` 或 `classLoader.toString()` 记录实际来源。
  2. `attachBaseContext` 是加载补丁的时机，因此在 `handleLoadPackage` 里立刻 hook 类是可以的，但**对 Application 之后的初始化链（如 `SogouAppApplication`）要等 `Application.attach` 之后再取值**。
- 大量 `org.aspectj.*` 引用 → 应用经过 **AspectJ 编译期织入**（`SogouIME` 里那些 `M(…, org.aspectj.lang.a)` 合成方法就是织入产物）。含义：**类结构里有大量编译器生成的合成方法，反射枚举方法列表时会看到噪声**；按方法名/签名精确定位是必须的。

---

## 3. IME 入口

```xml
<service android:name="com.sohu.inputmethod.sogou.xiaomi.SogouIME"
         android:permission="android.permission.BIND_INPUT_METHOD" ...>
    <intent-filter>
        <action android:name="android.view.InputMethod"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
    <meta-data android:name="android.view.im" android:resource="@xml/method"/>
</service>
```

- 类继承链（DEX 级已验证，见 `CLASS_MAP.md`）：
  `com.sohu.inputmethod.sogou.xiaomi.SogouIME`（151 B 空壳）→ `com.sohu.inputmethod.sogou.SogouIME` → `BaseSogouIME` → `com.sogou.lib.spage.BaseInputMethodService` → `com.sogou.gesture.BaseStylusInputMethodService` → `android.inputmethodservice.InputMethodService`
- 进程名与包名相同（`:主进程`），另有 9 个独立进程：`:ac_checker` `:heap_analysis` `:p0` `:patch` `:rarely` `:remote` `:speech` `com.sohu.inputmethod.status` `sogou.mobile.explorer.hotwords`。
  → **模块必须在 `handleLoadPackage` 里同时检查 `packageName` 与 `processName`**，只在主进程（以及确实需要时 `:remote`）工作；`:patch` 是 Tinker 补丁进程，绝不能注入 UI 逻辑。

---

## 4. DEX 布局

| dex | 大小 (B) | SHA-256 前 32 位 | 备注 |
|---|---|---|---|
| classes.dex | 8,751,104 | `0F00DDFF73973CEDE7D935A5F3C5D686` | |
| classes2.dex | 8,127,052 | `E0C064098ED8D64EF69BEB309C0AC213` | imskit 虚拟控件框架、spage 服务基类 |
| classes3.dex | 8,857,000 | `F455014F28DC5F2DD586D0CE903313AF` | 主题/皮肤体系、候选视图、RootContainer |
| classes4.dex | 7,975,032 | `43EA9EBCAEB251F00327EBC85ABF3D2B` | IME 服务、MainImeServiceDel（控制中枢）|
| classes5.dex | 3,416,068 | `694BF1616B2F624FBAF642D06317B331` | |
| AndroidManifest.xml | 128,660 | `5CC3C0A6D63B956C53888D9CFC300153` | |
| resources.arsc | 3,466,444 | `95C257538A2670F95BCA91A19FCAFF25` | |

- 全量类数（5 个 dex 去重后）：**33,797**（`analysis/dex_index.json`）。
- 额外 dex（非主代码路径）：
  - `assets/reflection.dex` 2,320 B（极小，疑似反射用桩）
  - `assets/zxing.dex` 374,652 B（扫码库，动态加载）

---

## 5. 原生库

`lib/` 只有 **arm64-v8a**（30 个 .so），无 armeabi-v7a / x86 / x86_64。

| 库 | 大小 (B) | 推断用途 |
|---|---|---|
| `libSGMtlgShell.so` | 4,953,000 | **壳/加固（Metal Shell 类）**，应用启动最早加载 |
| `libsogouime.so` | 3,475,224 | 输入法核心引擎（词库/候选/组词）|
| `libsogouupdcore.so` | 5,840 | 升级核心 |
| `libmmkv.so` | — | 键值存储 |
| `libluajava.so` | — | Lua 脚本（`assets/sogou.js`、`assets/js`）|
| `libc++_shared.so` | — | |
| `libbugly*.so` | — | 腾讯 Bugly 崩溃上报 |
| `libgraphics-core.so` | — | 图形/皮肤渲染 |

**影响**：
- 模块只能在 arm64 设备上工作（无其他 ABI），dex 层 hook 不受影响，但**任何试图替换 native 引擎的方案直接排除**。
- `libSGMtlgShell.so` 的存在意味着存在**加固壳**。本次静态分析未发现 Java 层的 Xposed 检测代码（在 `com/sogou/**`、`com/sohu/inputmethod/sogou/**` 中检索 `de.robv`、`XposedBridge`、`XposedHelpers`、`isXposed`、`checkXposed`、`hookDetect` 均 0 命中），但 **native 壳是否检测 hook 属于 UNKNOWN**，必须在阶段 1 真机验证（见 `FEASIBILITY_AND_HOOKS.md` 的风险表）。

---

## 6. assets 结构（与功能相关部分）

| 路径 | 条目数 | 内容 |
|---|---|---|
| `assets/theme` | 2,152 | **皮肤/主题体系**：`theme_default`、`theme_game`、`theme_custom`（`mecSkinBlack.ssf` 842 KB / `mecSkinWhite.ssf` 719 KB）、`thememanifest.ini`、`file_name_mapping_1080.ini` |
| `assets/emoji` | 1,425 | 表情资源 |
| `assets/foreign` | 285 | 外语键盘 |
| `assets/lottie` / `lottie_black` | 152 / 15 | 动画 |
| `assets/wxsmile` | 104 | 微信表情 |
| `assets/raw` | 58 | |
| `assets/html` | 10 | 内置 H5 |
| `assets/opengl` | 10 | 渲染 |
| `assets/emoji_black` | 9 | |
| `assets/skin_maker` | 9 | 皮肤制作器 |
| 单文件 | — | `exp_emoji_group`、`assemble_emoji_info`、`pre_assemble_emoji`、`exp_symbol`、`exp_symbol_hot`（表情/符号分组清单）；`default_corpus.json`、`basephrases_v2.json`（词库基线）；`ssconfig.xml`；`gestures`；`sogou.js`、`js`；`fanlingxi.zip` |

### 6.1 皮肤格式（重要发现）

`.ssf` 皮肤文件 **就是一个 ZIP**（文件头 `50 4B 03 04`），内含 `phoneTheme.ini`、`res/` 图片、`layout/` 布局。

默认主题 `assets/theme/theme_default/`：

```
theme.ini                      # [ThemeInfo] NAME/AUTHOR/VERSION/PROTOCOL=5；[Advance] 把键盘类型映射到布局 ini
1080/{port,land,layout,res}/    # 按分辨率
  port/py_9.ini  py_26.ini  en_26.ini  digit_9.ini  handwriting.ini  voice.ini  wubi.ini ...
  layout/keys.ini(5.4K)  cands.ini(26K)  template.ini(86K)  images_config.ini(32K)  image_list.ini(29K)
```

`port/py_9.ini` 节选（**这是键盘布局的声明式定义**）：

```ini
[Keyboard]
W=1
H=0.591667
TOUCH_MODE=2
PADDINGS=0.00741,0.00313,0.00741,0.00741
BG_IMAGE=Background
ROWS=ROW1,ROW2,ROW3,ROW4

[ROW1]
H_OFFSET=0.16204
KEYS=Key_PunctPY,Key_ABC,Key_DEF,Key_Backspace
...
[Key_Backspace]
W=0.16204
TYPE=2
F_FLAG=1
F_KEYS=,Key_Clear_Input
```

`layout/cands.ini` 定义候选栏（`[ComposingView]`、`[CandidateBar]`、`[CloudView]`、`[CandidateCodeView]` 的尺寸/内边距/文字样式/背景色）。

**含义**：搜狗键盘的**几何、配色、按键文字样式、候选栏样式全部由 ini 皮肤描述**。这给出一条"不注入代码"的低风险路线（见 `FEASIBILITY_AND_HOOKS.md` 的路线 B）。但它只能改"搜狗自己画的东西"，改不了 iOS 特有的交互（如 iOS 候选栏的滑动行为、iOS 表情面板结构）。

---

## 7. 与模块设计直接相关的 6 个环境结论

1. **不要在 `handleLoadPackage` 之外做重活**：类总量 33,797，`dex_index` 全量解析约 30 s，说明运行时任何全量反射扫描都会拖慢键盘唤起（键盘唤起预算 <100 ms）。诊断信息必须异步、可关闭。
2. **只用 `lpparam.classLoader`**（Tinker 热修复原因）。
3. **只对主进程生效**（9 个附加进程）。
4. **不要碰 native**（仅 arm64，且有加固壳）。
5. **版本判定不要用 versionCode**（= 2,147,483,647 恒定）。
6. **AspectJ 织入**导致类里存在大量 `ajc$`/合成方法，`hookAllMethods` 这类"按名批量 hook"极易误伤，**优先 `findAndHookMethod` 精确签名**。

---

## 8. 未验证项（UNKNOWN）

| 项 | 状态 | 需要什么证据 |
|---|---|---|
| native 壳是否检测 Xposed/内存 hook | UNKNOWN | 阶段 1 真机跑通后观察崩溃/退出 |
| 线上是否存在 Tinker 补丁包（会替换 dex） | UNKNOWN | 真机 dump 实际加载 dex 路径 |
| `assets/reflection.dex` 的用途 | UNKNOWN | 反编译该 dex（2,320 B，极小）|
| `libSGMtlgShell.so` 是否在 `attachBaseContext` 前拦截 | UNKNOWN | 真机日志时序 |
