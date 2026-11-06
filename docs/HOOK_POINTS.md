# HOOK_POINTS — 推荐注入点清单（含证据与风险）

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` 10.32.21.202607061714
> 所有描述符均以 DEX 为准（`analysis/q.py show <FQCN>` 输出，androguard 直读），**不是**从 jadx 源码猜的。
> 状态列：`本轮未验证` = 只有静态证据，必须在 Phase 1/2 设备上验证。
> 每个 hook 都必须遵守 §9 的统一约束（try/catch、幂等、开关、失败即原样）。

---

## §1 主表

| ID | 目标（类#方法） | 描述符 | dex | 侧 | 用途 | 风险 | 阶段 |
|---|---|---|---|---|---|---|---|
| **H01** | `com.sogou.lib.bu.ui.component.keyboard.d#getRootView` | `()Landroid/view/View;` | classes2 | replace | **键盘 View 的取用总闸**：所有 `setKeyboardView(...getRootView())` 调用点都会拿到我们的 View | 低 | Phase 3 |
| **H02** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setKeyboardView` | `(Landroid/view/View;)V` | classes3 | before/after | 只换 z=3 键盘槽；候选条/工具条/insets 全保留 | 低-中 | Phase 3 |
| **H03** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setCandidatesView` | `(Landroid/view/View;)V` | classes3 | before/after | 换 z=2 候选条槽 | 低-中 | Phase 4 |
| **H04** | `com.sohu.inputmethod.sogou.r2#Z7` | `(Lcom/sohu/inputmethod/input/api/data/b;IZ)V` | classes3 | before | 拿到每次推送的候选数据对象（可过滤/记账） | 低 | Phase 4 |
| **H05** | `com.sohu.inputmethod.sogou.r2#N7` | `(Lcom/sohu/inputmethod/input/api/data/b;)V` | classes3 | after | `q1` 唯一赋值点，确认"视图已绑定数据" | 低 | Phase 4 |
| **H06** | `com.sohu.inputmethod.sogou.AbstractImeServiceDelegate#showCandidateWordView` | `(Lcom/sohu/inputmethod/sogou/r2;IZ)V` 与 `(…;Z)V` | classes3/4 | after | 候选刷新时机（含首屏/翻页） | 低 | Phase 4 |
| **H07** | `com.sohu.inputmethod.presenter.a#k` | `()Lcom/sohu/inputmethod/input/api/data/b;` | classes3 | after | 取候选全局单例（自绘候选条数据源） | 低 | Phase 4 |
| **H08** | `com.sogou.core.ui.a#u` | `(Landroid/view/View;)V` | classes2 | before | 输入视图宿主收 View 的地方（全界面态汇合点） | 中 | Phase 3 备选 |
| **H09** | `com.sogou.lib.spage.BaseInputMethodService#setInputView` | `(Landroid/view/View;)V` | classes2 | before | 真正交给框架的入口（收到的是 `StyleConstraintLayout`） | 高 | 仅兜底 |
| **H10** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#addSmartThemeLayerView` | `(Landroid/view/View;)V` | classes3 | before/after | z=1000 叠加层（最安全的"可见性验证"缝） | **低** | **Phase 2** |
| **H11** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#addChildView` | `(Landroid/view/View;I)V` | classes3 | before/after | 通用插层（任意 z） | 低 | Phase 2 |
| **H12** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setDispatchEventView` | `(Landroid/view/View;IIZ)V` | classes3 | before | 指定事件派发目标（叠加层收触摸） | 中 | Phase 3 |
| **H13** | `com.sohu.inputmethod.input.InputLogic#onText` | `(Ljava/lang/CharSequence;I)V` | classes3 | before | **首选文字注入**（框架自身文字入口） | 低 | Phase 3 |
| **H14** | `com.sohu.inputmethod.input.InputLogic#onText` | `(Ljava/lang/CharSequence;Z)V` | classes3 | before | 裸文字注入（直接 commitText） | 低 | 兜底 |
| **H15** | `com.sohu.inputmethod.input.InputLogic#onKey` | `(I[IZII)V` | classes3 | before | 按键注入（参数 4/5 语义 UNKNOWN） | 中 | Phase 5 |
| **H16** | `com.sohu.inputmethod.sogou.MainImeServiceDel#onKey` | `(I[IZ)V` | classes4 | before | 绕过触摸层的按键注入 | 中 | Phase 5 |
| **H17** | `com.sohu.inputmethod.sogou.u#C` | `(Ljava/lang/CharSequence;I)Z` | classes2 | before | 含符号/配对逻辑的文字注入 | 低 | Phase 5 |
| **H18** | `com.sohu.inputmethod.sogou.MainImeServiceDel#getSogouInputConnection` | `()Lcom/sogou/bu/basic/ic/g;` | classes4 | after | 拿到真实上屏通道（诊断/兜底） | 低 | Phase 1 |
| **H19** | `com.sogou.bu.basic.ic.g#commitText`（接口实现类上 `hookAllMethods`） | `(Ljava/lang/CharSequence;I)Z` | classes2 | before | **最裸兜底**（跳过所有搜狗逻辑） | 中 | Phase 7 |
| **H20** | `com.sogou.expressionplugin.expression.ExpressionKeyboardEmoji#f0` | `(Z)Landroid/view/View;` | classes2 | replace | 表情面板整体替换（高风险，需保留原实例） | 高 | Phase 6 |
| **H21** | `com.sogou.expressionplugin.emoji.NoScrollViewPager#setScrollable` | `(Z)V` | classes2 | after | 组间左右滑动（v1.8 `GestureHook` 核心） | 低 | Phase 6 |
| **H22** | `com.sogou.expressionplugin.emoji.EmojiKeyboardView#showSlideAnimation` | `()V` | classes2 | replace | 去掉 900ms 滑动锁定 | 低 | Phase 6 |
| **H23** | `com.sogou.expressionplugin.expression.ExpressionUtil#m0` | `()Landroid/util/SparseArray;` | classes2 | after | 分组表（隐藏搜狗内置组） | 低 | Phase 6 |
| **H24** | `com.sogou.expressionplugin.expression.ExpressionUtil#n0` | `()Ljava/util/ArrayList;` | classes2 | after | 分组列表（同上） | 低 | Phase 6 |
| **H25** | `com.sogou.expressionplugin.expression.ExpressionUtil#u` | `(Landroid/content/res/AssetManager;Ljava/lang/String;Ljava/lang/String;)Z` | classes2 | before | 阻止内置组资产解包 | 中 | Phase 6 |
| **H26** | `java.io.File#list` | `()Ljava/lang/String;` | —（JDK） | after | 目录枚举过滤（兜底） | 低 | Phase 6 |
| **H27** | `com.sogou.expressionplugin.expression.ExpressionUtil#J` | `(Landroid/content/Context;Lcom/sogou/sogou_router_base/base_bean/BaseExpressionInfo;Landroid/os/Bundle;Ljava/lang/String;)Ljava/lang/String;` | classes2 | before | 表情上屏编码（苹果 emoji 注入点） | 中 | Phase 6 |
| **H28** | `com.sohu.inputmethod.sogou.u#f` | `(Lcom/sogou/sogou_router_base/base_bean/BaseExpressionInfo;)V` | classes2 | before | 表情上屏的统一入口（替代 H27 的更稳选择） | 中 | Phase 6 |
| **H29** | `com.sogou.expressionplugin.expression.manager.f#E`（**需 Phase 6 用 `q.py show` 确认精确 FQCN/描述符**） | 未知 | 未知 | before | 阻止"每进程重解压"（v1.8 缺失的一环） | 中 | Phase 6 |
| **H30** | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer#addKeyboardViewChangedListener` | `(Lcom/sohu/inputmethod/foreign/multilanguage/view/d;)V` | classes3 | —（非 hook，调用） | 看门狗：每次键盘切换后重装我们的 View | 低 | Phase 3 |

> `H29` 的类名来自 jadx 源码（`expression/manager/f.java`），**未在 DEX 索引中确认**，实现前必须 `q.py show` 核对；`H19` 的 `commitText` 实现在 `com.sogou.bu.basic.ic.u`（`u extends b`），建议 `XposedBridge.hookAllMethods(ic对象.getClass(), "commitText", …)` 而非硬编码类。

---

## §2 关键注入点详述

### H01 `com.sogou.lib.bu.ui.component.keyboard.d#getRootView()Landroid/view/View;` ★ 最推荐
```java
// classes2.dex，公开方法，返回键盘根 View
XposedHelpers.findAndHookMethod(
    "com.sogou.lib.bu.ui.component.keyboard.d", lpparam.classLoader,
    "getRootView", new XC_MethodHook() { ... return chironKeyboardView; });
```
- **为什么最好**：全工程所有把键盘装进 z=3 的地方都写成 `setKeyboardView(<s0>.getRootView())`：
  `sogou/g0.java:414`、`sogou/g0.java:666`、`sogou/MainImeServiceDel.java:2326`、`sogou/manager/j.java:299`（表情面板）、`com/sohu/inputmethod/main/view/c.java:60`。
  一次 hook 覆盖全部重装路径（浮窗/游戏/加载/表情返回）。
- **注意**：`getRootView()` 也被 `ui/frame/e.f(s0)`（尺寸镜像，`ui/frame/e.java:40-56`）等用于测量；返回我们的 View 后，这些地方会按我们的 View 计算 ⇒ 我们的 View 必须正确实现 `onMeasure`（固定高度 + `MATCH_PARENT` 宽）。
- **兼容兜底**：同时 hook H02，after 阶段再确认 z=3 是不是我们的 View；不是就补装。

### H02 `NormalIMERootContainer#setKeyboardView(Landroid/view/View;)V` ★ 推荐（配 H01）
- 方法体（`ui/frame/NormalIMERootContainer.java:946-985`）：先 `removeChildView(12)`（或 3），设 id `com.sogou.lib.bu.ui.e.m`、`RelativeLayout.LayoutParams(-2,-2)`、`indexof s0 → addRule(6)` 否则 `addRule(3, e.e)`、`addRule(9)`、`addChildView(view,3)`、`updateAllChildren()`，最后通知 `mKeyboardViewChangedListener`。
- **before 换参**：`param.args[0] = chironView`（最干净，但会让 `g0:666` 之类"重新 `getRootView()`"的调用被覆盖成原 View，故需 before+after 双保险）。
- **after 补层**：`((NormalIMERootContainer) param.thisObject).addChildView(chironView, 3)`（同一 z 覆盖）。
- **必要条件**：我们的 View 最好 `implements com.sohu.inputmethod.sogou.s0`（空实现 + `getRootView()` 返回自身 + `getHeight()/getKeyboardHeight()` 返回真实高度），因为：
  - `:959` 用 `view instanceof s0` 决定顶边规则；
  - `checkSogouKeyboardViewExists()`（`:266-269`）= `getChildViewByIndex(3) instanceof s0`；
  - `getKeyboardView()` 的 10 处消费者会把它当 `s0` 用。

### H13 `InputLogic#onText(Ljava/lang/CharSequence;I)V` ★ 首选文字入口
- 方法体（`input/InputLogic.java:2899-2905`）：`if (i.m().n().onText(cs, i)) return; u.Z().C(cs, i);` ⇒ 走完整搜狗流程（talkback 拦截 → 符号/配对 → 上屏）。
- 线程：主线程调用；内部 `com.sogou.bu.basic.ic.u` 有串行 `ExecutorService`。
- 我们只需要**调用**它（不是 hook 它），除非要做"用户按键统计"这类旁路。

### H20 `ExpressionKeyboardEmoji#f0(Z)Landroid/view/View;`（高风险）
- 返回表情面板 View，被 `expression/manager/j.java:292` 取用后 `setKeyboardView(...)`。
- 风险：`EmojiKeyboardView` 会被 `presenter.i` 反向调用（`updateMenuData`/`showSlideAnimation`/`setRVCanScroll`/`getPresenter`/`initData`）。若替换返回值，必须**保留原实例**（创建但不上树），否则 NPE。
- 推荐折中：**不换返回值**，用 H02 在 z=3 上叠加 Chiron 面板并隐藏原生面板（`ExpressionViewContainer` 的可见性）。

### H21/H22 表情组间滑动（v1.8 已验证可用，本工程复核）
- `NoScrollViewPager`（classes2，`extends androidx.viewpager.widget.ViewPager`）只有 `onInterceptTouchEvent/onTouchEvent/setScrollable` 三个自有方法；`slide` 字段门控滑动。
- `EmojiKeyboardView#showSlideAnimation()V`（classes2）会 `setScrollable(false)` 900ms（源码 :695-712）⇒ `XC_MethodReplacement.DO_NOTHING` 即可去掉锁定。

### H25/H26/H29 分组隐藏（v1.8 `GroupFilterHook` 的修正版）
- v1.8 只做：`File.list()` 过滤 + `ExpressionUtil.u(...)` before 返回 true。
- **本工程必须补**：真实解包在 `expression/manager/f.F(...)`（`f.java:419`），入口 `f.E(Context)`（`:991`），调用方 `ExpressionServiceImpl.java:475`（受版本号门控）⇒ 只 hook `u(...)` 不能阻止重启后重解压（这也是 v1.8 需要"0/300/1200/3000ms 四次重装"的根因）。
- 另注：`ExpressionUtil.o0(Set)` 枚举的是 `d.F`（`<sdcard>/sogou/expression/`），不是 `emojipackage` 目录 —— 若要过滤必须以 `m0()/n0()` 为准。

---

## §3 明确**不要**做的事（有证据的负向清单）

| 不要 | 原因 | 证据 |
|---|---|---|
| hook `onCreateInputView` / `onCreateCandidatesView` | 整个 APK 没有任何类声明这两个方法 | `q.py mfind 'onCreateInputView\|onCreateCandidatesView'` 空输出（3 次独立确认） |
| 给键盘子控件挂 `OnClickListener` / 覆写 `View.onTouchEvent` | 键盘没有真实 per-key View，只有 `imskit` 虚拟组件 | `RootComponentView extends View`；`r2#O6(Canvas)` 直接绘制候选 |
| 反射扫描 `com.sohu.*` / `com.sogou.*` 找"像 key 的方法" | 全工程 AspectJ 织入，存在大量 synthetic `M/N/O/P/...` 方法；扫描会命中错误目标并拖慢启动 | `APK_OVERVIEW.md`、`CLASS_MAP.md` §0 |
| 用 `s0` 注入按键 | `s0` 没有任何按键/文字方法，全部转发给组件 `g` | `lib/bu/ui/component/keyboard/d.java`（`K→g.U7`、`Q→g.q7`…） |
| 在 `KeyboardTouchEventHandler#f1` 上做拦截 | 方法体未反编译（3299 条指令），语义不可证 | `EVENT_CALLBACK_MAP.md` §8 |
| 依赖 `model.l` 的字段语义做候选 UI | 字段全为单字母，语义未知 | `INPUT_ENGINE_MAP.md` §3.2 |
| 直接改 `resources.arsc` / 重打包 | 用户硬性要求 + 破坏签名/加固壳 | 任务书；`APK_OVERVIEW.md`（加固壳 `libSGMtlgShell.so`） |

---

## §4 hook 安装时机（顺序很重要）

```
IXposedHookLoadPackage.handleLoadPackage(lpparam)
 ├─ ① 包名过滤：只允许 com.sohu.inputmethod.sogou.xiaomi / com.sohu.inputmethod.sogou
 ├─ ② 类级 hook（不需要 Context）：H01、H02、H03、H04、H05、H06、H10、H11、H12、
 │     H20、H21、H22、H23、H24、H25、H26、H27、H28（全部 try/catch 独立安装）
 ├─ ③ hook Application#attach(Context)：拿到 Context 后
 │     ├─ 读配置（开关/主题/版本档）
 │     ├─ 版本校验（versionName + classes.dex 校验和）→ 不匹配则进入"仅日志模式"
 │     └─ 注册诊断/设置 Activity、创建资源（字体/图片）
 └─ ④ 主线程 Handler 上延迟补装（0/300/1200/3000ms）：
       表情/分组相关 hook（H23-H29）—— 因为搜狗在进程启动早期就会重解压资产
```
- 幂等：每个 hook ID 记录在 `Set<String> INSTALLED`，重复安装直接 return。
- 失败：`catch (Throwable t) { XposedBridge.log(TAG + " install " + id + " failed: " + Log.getStackTraceString(t)); }` —— **绝不 rethrow**。
- 版本档：`VersionProfile` 保存 `10.32.21.202607061714` → 所有钩子 ID 的启用集合；未知版本只做 H18（只读诊断）。

---

## §5 每个 hook 的验收标准（Phase 2 起逐条跑）

| ID | 验收方法 |
|---|---|
| H10/H11 | 注入一个 200×200 红色 View 到 z=1000，出现在键盘正上方；10 次弹出/收起后仍在 |
| H01/H02 | 用自绘纯色 View（无逻辑）替换键盘，键盘区域变成我们的 View，候选条与工具条仍在原位 |
| H13 | 从自绘键盘点击 "a"，输入框出现 "a" 且搜狗候选条随之出现以 "a" 开头的候选 |
| H16 | 点击自绘退格键 → 输入框删除一个字符 |
| H17 | 输入 "，" 触发自动配对/中文标点行为（与原生一致） |
| H03/H04/H05 | 自绘候选条渲染出与原生相同的 9 个候选文本且顺序一致 |
| H21/H22 | 表情面板左右滑动切组无 900ms 卡顿 |
| H23/H24/H25/H29 | 冷启动后分组列表无 "组合(2_…)" / "拼装(-2_…)" |
| H18 | 日志中能打印当前 `ic.g` 实现类名与 `EditorInfo.inputType`（诊断页可见） |

---

## §6 与旧模块（v1.8）的对应关系

| v1.8 组件 | 本工程处置 |
|---|---|
| `Protocol.java`（PING/INSTALL_*/RESTORE + STATUS 广播） | 保留，扩展 `cmd` 命名空间到 `com.chiron.iosinput.*` |
| `HookEntry.java`（Application#attach + 4 段延迟安装） | 保留骨架；把"表情专用"逻辑改为按 hook ID 注册表驱动 |
| `GestureHook.java`（`NoScrollViewPager#setScrollable` + `showSlideAnimation`） | 保留（H21/H22），加开关 |
| `GroupFilterHook.java`（`File.list` + `ExpressionUtil.u`） | 保留并**补齐** H29，改为 H23/H24 优先 |
| `LogoHook.java`（hook IC 的 `commitText` 改写触发词为 U+F8FF） | 保留为可选功能（H19 的受限用法），默认关闭；新工程的键盘层不再需要它 |
| `EmojiService.java`（安装苹果 emoji 分组到 Sogou 目录） | 保留为"表情资源安装器"，与新面板解耦 |
| `MainActivity.java`（设置/安装 UI） | 拆分：设置页 + 诊断页（新工程新增） |
| `Groups.java` / `manifests/*.xml` / `assets/payload.zip` | 原样迁入新仓库（作为 emoji 资源包） |
| 无 Gradle（`build.sh` + aapt2/d8/apksigner 手搓） | **新工程改为 Gradle（AGP + Kotlin）**，保留 `build.sh` 作为无 AS 环境的备用路径 |
