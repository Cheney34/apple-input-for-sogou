# Apple Input for Sogou（Apple 输入法 · 搜狗版）

> **搜狗负责输入，Apple 负责体验。**
>
> 一个针对「小米定制版搜狗输入法」的 LSPosed 模块：保留搜狗的输入引擎、词库、候选生成与上屏逻辑，
> 只把**键盘、候选栏、表情面板**的显示层与交互换成 iOS 风格。

- 目标包名：`com.sohu.inputmethod.sogou.xiaomi`（同时兼容 `com.sohu.inputmethod.sogou`）
- 目标版本：`10.32.21.202607061714`（versionCode `2147483647`，5 个 dex，arm64-v8a）
- 目标 APK SHA-256：`90B4A1945D28427A50CC6DF8FA1FE0E29977A0053622FA7A05FBDCFF01F6B09C`
- 模块包名：`com.chiron.iosemoji` · 版本：`2.0.2-beta5`（versionCode 40）
- 最低支持：Android 8.0（API 26）· 只在 LSPosed / EdXposed 等 Xposed 框架下工作


### 支持哪些定制版？

| 输入法 | 包名 | 皮肤改造（类名层 H40/H41） | 资源层兜底（H50） | 表情分组/翻页 |
| --- | --- | --- | --- | --- |
| 小米定制版 10.32.21.202607061714 | `com.sohu.inputmethod.sogou.xiaomi` | ✅ 已确认 | ✅ 装上 | ✅ 已确认 |
| 搜狗官方 20.19.0 | `com.sohu.inputmethod.sogou` | ⏳ 入口已定位（ini 同名同签名；位图 `common.j`→**`common.g`**），待实测 | ✅ 装上 | ⏳ 待验证 |
| OPPO 定制版 10.32.0622.x | `com.sohu.inputmethod.sogouoem` | ⏳ 类名已修（**`yy1` / `nm1`**），待实测 | ✅ 装上 | 部分（分组过滤 ✅ / 解压旁路待验证） |
| vivo 定制版 11.41.14023 | `com.sohu.inputmethod.sogou.vivo` | ⏳ ini 入口已定位（**`gh`** `d/e/f`），位图入口未定位 | ✅ 装上 | ⏳ 类名齐全待实测 |

小米定制版另有一条 **MIUI 专属**改造：`H60` 让系统「全面屏键盘优化」那条底栏也用所选皮肤的底色/图标色
（framework 层的 `InputMethodServiceInjector`，跟搜狗自己的皮肤是两条独立通道，所以 H40/H41 改不到它）。

开发优先级（2026-11-07 定）：**小米 > 搜狗官方 > OPPO == vivo**。
逐步推进，每步都要：①小米回归通过 ②目标渠道实测 ③未测渠道一律标「未兼容」。

> ✅ 已修（v2.0.3-beta1）：OPPO 的类名已从 `defpackage.yy1` / `defpackage.nm1` 改成 **`yy1` / `nm1`**（默认包类的真实 FQCN）；
> DEX 里的真实描述符是 `Lyy1;` / `Lnm1;`（**无包名**），运行时 FQCN 应为 `yy1` / `nm1`。
> 这意味着 OPPO 皮肤 hook 目前**根本没挂上**（ClassNotFound 被 try/catch 吞掉）。详见 [docs/AUDIT_PHASE1.md](docs/AUDIT_PHASE1.md)。

OPPO 定制版把整个主题模块用 `-repackageclasses '` 塞进了默认包，所以同一份逻辑对应的是
`defpackage.yy1#a(String)`（ini）与 `defpackage.nm1#j(...)`（位图），而不是小米版的
`com.sogou.theme.parse.utils.b#a` 与 `com.sogou.theme.common.j#f`。映射统一收在
`compat/Targets.java` 里，**没核对到的名字一律填 null 并跳过该功能**，不用猜的名字去 hook。
## 下载哪个版本？

| 版本 | 适合谁 | 有什么 |
| --- | --- | --- |
| [**v2.0.3-beta4**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta4) | 小米定制版（紧急修复） | **修「键盘呼不出来」**：H50 绝不重编码 nine-patch（`.9.png` / 带 `npTc` 切边块的图，重编码会丢切边数据导致 Inflate 崩）；资源层改位图改为**默认关**（新开关 `asset_skin_images_enabled`，ini 颜色照改）；**H60 改默认关**并去掉对 `customizeBottomViewColor` 参数的改写（只观察 + 主动下发），加 3 次失败熔断 |
| [**v2.0.3-beta3**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta3) | 存档（H60 可能影响键盘唤起） | 新增 **H60：MIUI「全面屏键盘优化」底栏跟随皮肤**（framework 层三处 hook，只改颜色、可单独关）；设置页**明确显示连上的是哪个渠道**（`已连接 · 小米定制版（com.…xiaomi）`）；H60 保守化（不动 `isCustom`、透明场景不接管、高频方法节流） |
| [**v2.0.3-beta2**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta2) | 存档（不含底栏适配） | 恢复资源层皮肤 hook（H50）；**下线自绘键盘**（移除 `keyboard_replace_enabled`、删除 H01，H02 只保留「键盘弹出即对齐配置」）；`Targets` 支持多候选类/多方法名（官方 `common.g`、OPPO `nm1`、vivo `gh`）；位图过滤改按图名判定，修「官方版只有工具栏变色」 |
| [**v2.0.3-beta1**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.3-beta1) | 存档 | 删掉资源层皮肤 hook（H50）；渠道档案化：官方 20.19.0 / OPPO / vivo 各自一份 `Targets`；未知渠道不再回落小米 |
| [**v2.0.2-beta2**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.2-beta2) | **稳定版 · 推荐** | 目前唯一在**小米定制版真机确认可用**的版本：改搜狗默认皮肤（H40 改 ini + H41 按方法名全挂位图入口）、七套皮肤、表情包完整、开关即时可逆。**当前基线。** |
| [**v2.0.2-beta1**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.2-beta1) | 存档 | 多定制版支持：**OPPO 定制版（`com.sohu.inputmethod.sogouoem`）也能改皮肤**（该版类名不同 → 新增 `Targets` 映射表）；小米版行为不变 |
| [**v2.0.1-beta4**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.1-beta4) | 存档 | iOS 深色底色压到深灰 `#222223`（键 `#6D6D6F` / 字 `#FDFDFE` 不变），功能键与按下态按新色阶重推 |
| [**v2.0.1-beta3**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.1-beta3) | 存档 | iOS 皮肤深色版：键 `#6D6D6F` / 字 `#FDFDFE`，底色压到深灰 `#222223`，其余层级按同一色阶过渡 |
| [**v2.0.1-beta2**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.1-beta2) | 存档 | 七套皮肤：iOS / 微信 / Google / **幽墨 Material** / **幽墨 Pixel** / **樱花粉** / 莫奈取色 |
| [**v2.0.1-beta1**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.1-beta1) | 存档 | 微信皮肤重做：功能键与底色同色（微信是「无键帽」的）、候选栏改白底、键帽圆角 0.16/几乎无投影；候选栏底色从键盘底色里独立出来（`barBg`），四套皮肤各自指定 |
| [**v2.0.0-alpha12**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha12) | 存档 | 微信/Google 按原生观感重写配色，莫奈取色掺 accent + 竖向渐变，**候选栏与顶部工具条终于跟键盘同底色** |
| [**v2.0.0-alpha11**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha11) | 存档 | 表情包补齐：10 个分组页签图标 + 8 个 Unicode 16 新表情（payload 3868 项，与 v1.8 完全一致） |
| [**v2.0.0-alpha10**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha10) | 存档 | 深色模式对比度实算修正（键帽/底色对比 1.4 → 2.3–5.0，文字按底色自动取黑/白）+ Emoji 图标与分组图标不再被裁剪或误涂 |
| [**v2.0.0-alpha9**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha9) | 存档 | 修掉「图标被当键帽涂成方块」（设置齿轮、语言/地区旗帜等）+ 键帽只认严格白名单 + 覆盖率兜底 |
| [**v2.0.0-alpha8**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha8) | 存档 | 新增「**重启输入法**」按钮（一键重载皮肤）+ 键帽按搜狗原图圆角与平台规范重画（留键缝、图标不被刷掉）+ 皮肤诊断行 |
| [**v2.0.0-alpha7**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha7) | 存档 | 存档 | 修掉「键帽漏改」的真 bug + **皮肤诊断行**（一眼看出改没改到）+ 四套皮肤 |
| [**v2.0.0-alpha6**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha6) | 存档 | 四套皮肤可选：**iOS / 微信 / Google / 莫奈取色**（设置页「皮肤风格」切换，切换即时生效）+ 键帽按所选风格重绘 + 候选栏与顶部条底色统一 |
| [**v2.0.0-alpha5**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha5) | 存档 | 只有 iOS 一套皮肤（键帽按 iOS 圆角重绘） |
| [**v2.0.0-alpha4**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha4) | 存档 | 第一版改皮肤（只刷色，圆角仍沿用搜狗自己的，观感不如 alpha5） |
| [**v2.0.0-alpha3**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v2.0.0-alpha3) | 存档，不建议用 | 自绘键盘版：键盘样式会跟搜狗布局打架（卡 bug），仅作历史保留 |
| [**v1.8**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v1.8) | 只想换 Apple 表情、要稳定 | 表情分组安装 / 隐藏内置分组 / 滑动切换 / Logo 触发词 |
| [**v1.7**](https://github.com/Cheney34/apple-input-for-sogou/releases/tag/v1.7) | 老设备 / 旧搜狗版本 | 与 v1.8 同代但更早，兼容面略宽 |

### 界面长什么样（alpha2 重写 / alpha3 让开关真正生效 / alpha4 换成改皮肤 / alpha5 重绘键帽 / alpha6 四套皮肤 / alpha7 可测量诊断 / alpha8 重启按钮+重画 / alpha9 图标不再被误涂 / alpha10 深色对比度实算 / alpha11 表情包补齐 / alpha12 候选条统一 / v2.0.1-beta1 微信皮肤重做）

两个界面都换成 iOS 风格、全部代码自绘，不依赖 androidx / Material，跟随系统深浅色：

- **模块 App**：iOS 设置页 —— 大标题、分组卡片、圆角行、右侧 `✓`/`›`、自绘开关。
  分区：状态 / 表情分组（10 组逐行勾选 + 全选）/ 操作（安装选中、安装全部、还原）/ 模块（功能开关、隐藏桌面图标、重新检测）/ 运行日志。
  原 XML 布局（`activity_main.xml`、`item_group.xml`）已删除，不再需要 `GridLayout`。
- **输入法键盘（alpha4 起改皮肤 / alpha5 重绘键帽 / alpha6 四套皮肤 / alpha7 可测量诊断 / alpha8 重启按钮+重画 / alpha9 只改真键帽、图标原样保留）**：**不再盖一层自绘键盘**，而是改搜狗自己默认皮肤 ——
  配色、键帽形状、候选栏与顶部条底色全部按所选风格重画（键帽沿用原图不透明包围盒 → 位置尺寸仍是搜狗的，只换圆角与颜色）。
  四套风格（设置页「外观 → 皮肤风格」，切换即时生效）：

  | 风格 | 底色 | 键帽 | 功能键 | 强调色 | 画法 |
  |---|---|---|---|---|---|
  | **iOS**（默认） | `#D1D4D9` | 纯白 | 更灰 `#ADB3BC` | `#007AFF` | 圆角 0.12 + 淡底边 |
  | **微信** | `#F2F2F2` | 纯白 | 很淡 `#E9E9E9` | 微信绿 `#07C160` | 圆角 0.10 + 淡底边 |
  | **Google** | 白 | 浅灰 `#F1F3F4` | `#E8EAED` | Google 蓝 `#1A73E8` | 圆角 0.14，无底边 |
  | **莫奈取色** | 系统 `neutral1_100` | 系统 `neutral1_50` | `system_neutral2_200` | `system_accent1_500` | 圆角 0.16，取壁纸配色（Android 12+） |

  键盘布局、按键命中、候选栏高度、输入法 insets 全部还是搜狗自己算的，所以**不会再出现"键盘样式卡 bug"**。
  （自绘键盘已于 v2.0.3-beta2 整体下线：它会把搜狗算好的布局与命中区打乱，得不偿失。）
> v2.0 沿用旧版包名 `com.chiron.iosemoji`、并沿用同一张调试证书（`CN=Android Debug`），所以是**同包名覆盖升级**：装上 v2.0 会直接替换 v1.7 / v1.8，LSPosed 里原有的模块条目和作用域照旧生效，不需要重新配置。想回旧版，卸载后装 v1.7 / v1.8 即可。

---

## 0. 当前状态（请先读这段）

| 项目 | 状态 |
| --- | --- |
| 静态分析（反编译 + DEX 级取证） | ✅ 完成，产物见 [`docs/`](docs/) |
| 四条核心功能的可行性结论 | ✅ 完成 —— [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) |
| hook 点清单（30 个，含 DEX 验证描述符） | ✅ 完成 —— [`docs/HOOK_POINTS.md`](docs/HOOK_POINTS.md) |
| MVP 方案与测试用例 | ✅ 完成 —— [`docs/MVP_PROPOSAL.md`](docs/MVP_PROPOSAL.md) |
| 模块源码（改搜狗默认皮肤 + 资源层兜底 + 诊断 + 旧表情功能） | ⚠️ 已写完，**javac + aapt2 + d8 + apksigner 全链路真实构建出 APK**，未真机验证 |
| 可安装 APK | ✅ 已构建：`AppleInputForSogou_v2.0.0-alpha4.apk`（21.75 MB，见 Releases） |
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

### 1.1 首选路线（alpha4 起）：改搜狗自己的默认皮肤

alpha2/alpha3 走的是「把 z=3 槽位里的 View 换成我们的」，结果键盘样式会卡 bug ——
因为搜狗键盘是**虚拟组件树自绘**（`RootComponentView` + 组件，没有 per-key View），
命中测试、候选高度、insets、皮肤资源索引全都建立在「这是它自己的视图」这个前提上。

所以 alpha4 换成顺着它自己的皮肤引擎改。搜狗默认皮肤是这样跑的：

```
assets/theme/thememanifest.ini             主题清单（THEME_SET=theme_default,…）
└─ assets/theme/theme_default/theme.ini    [ThemeInfo] / [Advance]（键盘类型 → 布局 ini）
   ├─ 1080/layout/colors.ini               颜色表（强调色、文字色、气泡色…）
   ├─ 1080/layout/images_config.ini        位图九宫格切边（IMAGE=key.png,28,30,38,40 TYPE=1）
   ├─ 1080/layout/image_list.ini           逻辑名 → png（NORMAL=key_qwerty_png）
   ├─ 1080/port/py_9.ini                   几何：W / H / PADDINGS / ROWS / H_OFFSET
   └─ 1080/res/{black,white}/*.png         键帽、键盘底、气泡等位图
```

所有 ini 都走同一个入口，所有位图也都走同一个入口 —— 只要卡住这两个出口，就能整套换皮：

| Hook | 目标（DEX 已验证的描述符） | 做什么 |
| --- | --- | --- |
| `H40` | `com.sogou.theme.parse.utils.b#a(java.lang.String)` | 拿解析后的「段→键→值」表，只改颜色键：强调色 → iOS 蓝（`#007AFF`/深色 `#0A84FF`）、候选栏/云候选底色 → 透明（去掉灰底块）、功能键文字色 → 黑白 |
| `H41` | `com.sogou.theme.common.j#f(Context,String,boolean,boolean)` | 按文件名给位图重上色：键帽/键盘底/功能键/气泡各用 iOS 灰白色板；做法是新建同尺寸位图铺目标色，再用原图 alpha 做 `DST_IN` 遮罩 → **形状与尺寸不变** |

两条铁律：

1. **只改颜色，绝不动 `W`/`H`/`PADDINGS`/`ROWS`/`H_OFFSET`** —— 几何一动就会连锁影响 insets 与命中区，这正是之前「样式卡 bug」的根源。
2. 位图只重上色、不改尺寸，所以 `images_config.ini` 的九宫格切边参数继续有效，圆角不会被拉花。

关掉「iOS 皮肤」开关，下一次读皮肤就回到搜狗原样（`H40`/`H41` 每次调用都判开关，不是一次性替换）。

### 1.2 资源层兜底（H50）：OEM 改了类名也能改到

类名层（H40/H41）靠「每个渠道各自的类名表」，一旦某个 OEM 把主题模块 repackage 进默认包
（OPPO / vivo 就是这样），类名层就找不到入口。这时靠 H50 兜底：

| Hook | 目标 | 说明 |
| --- | --- | --- |
| `H50` | `android.content.res.AssetManager#open(String)` / `#open(String,int)` | 所有版本读皮肤都是 `assets/theme/…`，在资源流这一层改 |

H50 的第 0 条铁律（v2.0.3-beta4 补，最要紧）：**绝不重编码 nine-patch**。`BitmapFactory.decodeByteArray → compress(PNG)` 之后，PNG 里的 `npTc`（九宫格切边）块就没了，系统 / 搜狗 Inflate 这张图时会直接抛异常 —— 现象就是「键盘呼不出来」。所以 `.9.png` 和任何带 `npTc` 块的图一律原样放过；资源层默认只改 ini 颜色，位图交给 H41。

H50 的三条铁律（beta3/beta4 的教训，少一条就会「键盘呼不出来」）：

1. **只改内置默认皮肤**：路径命中 `sogou_custom` / `theme_custom` / `/themes/` / `.ssf` / `phonetheme` 一律原样放过 —— 用户自己装的第三方皮肤一个字都不碰。
2. **ini 只做行内取值替换**：不重新序列化（那会毁掉第三方 ini 的注释/续行/重复键）；改完复核段名与键数，不一致就返回原文件。
3. **熔断**：连续 3 次失败后本进程不再改写任何皮肤资源（日志 `H50 circuit breaker OPEN`），保命优先。

H50 与类名层共用「改皮肤」总开关：关掉就两层一起停。

---

## 2. 安装

1. 手机已 Root，并安装 **LSPosed**（Magisk / KernelSU 模块形式）。
2. 安装本模块 APK（`com.chiron.iosemoji`）。
3. 打开 LSPosed → 模块 → **启用 Apple Input for Sogou**。
4. 作用域勾选：`搜狗输入法`（小米定制版，包名 `com.sohu.inputmethod.sogou.xiaomi`）。
   如果你装的是通用版，勾 `com.sohu.inputmethod.sogou`。
5. 强行停止一次搜狗输入法（或切换一次输入法），让新进程加载 hook。
6. 打开任意输入框，检查：
   - 键盘配色是否变成 Apple 风格（iOS 蓝强调色、灰白键帽、候选栏无灰底块）；
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
3. 搜狗进程回执一份状态串 → 页面弹出「已生效：iOS 皮肤 → 开」；
   若进程没起来，则提示「已保存：…（搜狗进程未运行，切换输入法后生效）」，并在你下次切换输入法时生效；
4. 冷启动走只读 `ContentProvider`（`content://com.chiron.iosemoji.config/config`）读一次，
   不依赖跨进程 prefs 的可读性。

页面顶部「连接状态」卡会显示：搜狗进程是否在线、**连上的是哪个渠道**（小米定制版 / 搜狗官方版 / OPPO 定制版 / vivo 定制版，含包名）、
已装 hook 列表、**进程内真正生效的配置值**、搜狗版本。
所有开关都是**双向可逆**的：关掉立刻退回搜狗原行为（例如关掉「改皮肤」，下一次读皮肤就是搜狗原配色，H50 也一起停）。

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `master_enabled` | 开 | 总开关，关掉后模块在搜狗进程内不做任何事 |
| `theme_skin_enabled` | 开 | **改搜狗默认皮肤**（H40 改 ini 颜色 + H41 重绘键帽/底色）—— 外观的主路径 |
| `skin_preset` | `ios` | 皮肤风格：`ios` / `wechat` / `google` / `monet`（莫奈需 Android 12+），见设置页「皮肤风格」 |
| `miui_bottom_bar_enabled` | **关** | **MIUI「全面屏键盘优化」底栏跟随皮肤**（H60，仅小米定制版有效）；关掉就交回 MIUI 自己管 |
| `asset_skin_images_enabled` | 关 | **资源层改位图**（H50 的位图部分）。默认关：`decode → compress` 会丢 nine-patch 切边数据，曾导致键盘呼不出来；位图交给类名层 H41 去做更安全 |

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

> **两个坑（v2.0.0-alpha11 起已修，别再踩）**
> 1. `--icons` 默认指向仓库里的 [`icons/`](icons) 目录（10 个分组页签图标，84×84）。
>    这个目录**必须放进仓库**：它是 `info.xml` 里 `exppkg.type` 对应的封面图
>    （`smileys_emotion.png` / `people_body.png` / … / `flags.png`）。
>    少了它，表情面板底部页签图标就是空的 —— 位图能装上，但看起来「有表情丢了」。
> 2. 素材版本要 **emoji-datasource-apple ≥ 16.0.0**（3793 张）。15.1.2 只有 3785 张，
>    缺 8 个 Unicode 16 新表情（1FA89 竖琴 / 1FA8F 铲子 / 1FADF 溅 / 1FAE9 眼下袋 /
>    1FAC6 指纹 / 1FABE 无叶树 / 1FADC 根茎类 / 1F1E8-1F1F6 萨克岛旗），
>    这 8 个会在分组里显示成空白占位。
>
> 生成完自检：`payload.zip` 内应含 **3868** 个条目（3817 张表情 + 10 个封面 + 分组目录/info.xml/version.json）。

### 5.2 命令

```bash
# 生成表情包资源（可选）
python3 tools/make_payload.py --src /path/to/apple_emoji_png \
        --out lsposed/app/src/main/assets/payload.zip \
        --manifests manifests --icons icons --version 16.0

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
│       ├── hook/                   # HookRegistry / H02 配置对齐 / H40 H41 H50 皮肤 / 探针
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
