# Chiron iOS Input — 阶段 0 静态分析报告（3/8）：键盘 View 层级

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` `10.32.21.202607061714`
> 证据来源：`analysis\jadx_out\sources\`（jadx 1.5.1）+ `analysis\dex_index.json`（androguard）
> 标注：【D】DEX 确认 ｜【S】源码确认 ｜【R】需运行时验证 ｜ UNKNOWN 未确认
> **本文档描述的层级全部来自静态代码，尚未在真机上抓过 `View` 树；所有"可以在这里插入/替换"的结论均为设计假设，需运行验证。**

---

## 1. 总览：输入窗口是三层结构，不是一层

```
┌─ InputMethodService 的 softInput 窗口 DecorView ─────────────────────────────┐
│                                                                              │
│  StyleConstraintLayout  (com.sogou.core.ui.window.b.c)                        │
│  id = com.sogou.imskit.core.ui.kernal.a.a                                     │
│  LayoutParams = FrameLayout.LayoutParams(-2,-2) gravity=BOTTOM                │
│  ▲ 这一层才是 super.setInputView() 真正收到的东西                              │
│  │                                                                            │
│  ├─ [背景] WindowBackgroundLayout (com.sogou.core.ui.window.b.d)  -1 × -1      │
│  │        match_parent，含 KeyboardBackgroundLayout 背景绘制器                  │
│  │                                                                            │
│  └─ [内容] ContentView (com.sogou.core.ui.a.f)                                │
│          由 com.sogou.core.ui.a.u(View) 写入                                   │
│          正常键盘态 = NormalIMERootContainer                                  │
│          其他态 = 手写/游戏/悬浮/表情… 等 INPUT_VIEW_TYPE 对应视图              │
└──────────────────────────────────────────────────────────────────────────────┘
```

证据链：
- `com/sogou/lib/spage/BaseInputMethodService.java:292-303`：`setInputView(View v)` → `this.b.u(v)`（`b` 为 `com.sogou.core.ui.a`）→ `ConstraintLayout c = this.b.c()` → `super.setInputView(c)`。
- `com/sogou/core/ui/window/b.java`：`protected StyleConstraintLayout c;`、`protected WindowBackgroundLayout d;`、`g()` 中 `c = new StyleConstraintLayout(ctx); c.setId(com.sogou.imskit.core.ui.kernal.a.a); FrameLayout.LayoutParams lp = (-2,-2); lp.gravity = 80;`、`f()` 中 `c.addView(d, (-1,-1))`。
- `com/sogou/core/ui/a.java`：`private View f;`（内容）、`u(View)`：`if (f != null) c.removeView(f); f = view; if (view.getId() == -1) view.setId(com.sogou.core.ui.b.a); l(); a(c, view); n();`、`p()` 返回 `f`。

> **设计结论 A**：只替换 `setInputView` 的入参是**不够**的 —— 传入的 view 会被塞进 `StyleConstraintLayout` 的一个槽位。要真正掌控整块键盘区域，必须同时理解 `com.sogou.core.ui.a`（背景层 + 内容槽）。

---

## 2. 正常键盘态：`NormalIMERootContainer` 内部布局

类：`com.sohu.inputmethod.ui.frame.NormalIMERootContainer` [classes3]（41,404 B，`extends com.sohu.inputmethod.ui.frame.RootContainer`【D】）
父类：`com.sohu.inputmethod.ui.frame.RootContainer`（**package-private**，`extends com.sohu.inputmethod.ui.CornerRelativeLayout`，169 行）
容器模型：`protected SparseArray<a> mChildrenArray`，`a = { View a; int b; }`，`b` 即 **z-index**，由 `addChildView(View, int)` 写入，`complateChildrenAdd()` → `fillAllChildren()` 时一次性 `removeAllViews()` + 按序 `addView`。

### 2.1 z-index 全表【S】（`ui/frame/NormalIMERootContainer.java`）

| z | Setter（行号） | 内容 | id 资源 | 布局参数 |
|---|---|---|---|---|
| 1000 | `addSmartThemeLayerView`（:234，插入于 :248） | 智能主题图层 | — | — |
| 0 | `setFlxImeView`（:742，插入 :756） | Flx 小程序壳 | `com.sogou.lib.bu.ui.e.i` | — |
| 2 | **`setCandidatesView`（:579，插入 :588）** | **候选栏容器** | `com.sogou.lib.bu.ui.e.e` | `(-1,-2)`，`addRule(10)` 贴顶，`addRule(1, e.h)` |
| 3 | **`setKeyboardView`（:946，插入 :973）** | **键盘视图** | `com.sogou.lib.bu.ui.e.m` | `(-2,-2)`，`addRule(3, e.e)` 位于候选栏下方；若入参 `instanceof s0` 则改用 `addRule(6, e.e)` |
| 4 | `setFirstCandidatesView`（:634，插入 :647） | 首候选视图 | `com.sogou.lib.bu.ui.e.h` | — |
| 5 | `setKeyboardResizeView`（:899，插入 :907） | 键盘尺寸调整遮罩 | — | — |
| 6 | 构造函数（插入 :115） | `KeyboardHeaderContainer`（工具条/智能栏容器） | `com.sogou.lib.bu.ui.e.l` | — |
| 7 | `setKeyboardHwView`（:878，插入 :892） | 手写板 | `com.sogou.lib.bu.ui.e.k` | — |
| 9 | `setFloatDragView`（:676，插入 :693） | 悬浮拖拽条 | `com.sogou.lib.bu.ui.e.a` | — |
| 10 | `setKeyboardTypeChangeViewLeft`（:914，插入 :923） | 左侧类型切换 | — | — |
| 11 | `setKeyboardTypeChangeViewRight`（:930，插入 :939） | 右侧类型切换 | — | `addRule(3, e.e)` |
| 12 | `setPlatformEditView`（:987，插入 :1003） | 平台编辑视图（全屏编辑） | `com.sogou.lib.bu.ui.e.n` | — |
| 13 | `setFloatResizeView`（:699，插入 :723） | 悬浮缩放条 | — | — |
| 14 | `setFirstGuidanceView`（:654，插入 :669） | 首次引导浮层 | — | — |
| ? | `setElderVoiceView`（:614） | 适老语音视图 | — | 具体 z 未确认 |

对外读接口（用于模块判断"当前装了什么"）：
`getCandiatesView()`→`getChildViewByIndex(2)`（:298）、`getKeyboardView()`→`getChildViewByIndex(3)`（:370）、`getFirstCandidatesView()`→`getChildViewByIndex(4)`（:302）、`getFloatDragView()`→`getChildViewByIndex(9)`（:306）、`getHeaderView(int)`（:338）、`getKeyboardHwView()`（:345）、`getKeyboardHeader()`（:349）、`getKeyboardResizeView()`（:362）。

### 2.2 稳定依赖的两个 View 身份

- `getKeyboardView()` 返回的 View 常态下就是 `s0.getRootView()` 的返回值，即 `KeyboardRootComponentView`（`com.sohu.inputmethod.sogou.keyboard.KeyboardRootComponentView extends com.sogou.imskit.core.ui.virtualwidget.component.RootComponentView`）【D】+【R，实际类型需运行时确认】。
- `getCandiatesView()` 返回 `IMEInputCandidateViewContainer`（`com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer`）【D，经 `b0.f0().b0()` / `manager.o.v()` 返回类型确认】。

### 2.3 `setKeyboardView` 是实现"键盘显示层替换"的最佳单点【S，:946-985】

要点：`setKeyboardView` 是**所有键盘类视图的唯一入口**（正常键盘、游戏键盘、表情面板、平台编辑视图之外的绝大多数状态都走它），并且：
1. 它会写 `private View mCurKeyboardView`；
2. 它会 `removeChildView(12)`（踢掉平台编辑视图）；
3. 它会给视图设 `id = com.sogou.lib.bu.ui.e.m` 与 `RelativeLayout.LayoutParams`；
4. 它按 `instanceof KeyboardRootComponentView` 决定"键盘类型切换箭头"的可见性（`changeKeyboardTypeChangeViewVisibility`）；
5. 末尾向 `mKeyboardViewChangedListener`（`ArrayList<com.sohu.inputmethod.foreign.multilanguage.view.d>`）广播 `a(oldView, newView)`。

因此：**在 after 钩子里把 `newView` 的可见性/透明度改掉、或在自己新建的透明承载层里嵌我们的 iOS 视图，是一步到位、影响面最小的做法**（风险：`mCurKeyboardView` 已指向原 View，后续 `removeChildView(3)` 仍按 z-index 工作，不受影响）。

### 2.4 表情面板也在 z=3 上【S】

`sogou/j.java:299`：`b0.f0().e0().setKeyboardView(expressionScreenView);`
→ **表情面板 = 用 setKeyboardView 顶掉键盘视图**。这直接决定了表情面板替换/接管的可行方案（Phase 6）。

---

## 3. 创建与安装时序（静态，运行时未验证）

### 3.1 根容器创建：唯一工厂

```
MainImeServiceDel.createIMERootContainer()            [sogou/MainImeServiceDel.java:3388]
└─ b0.f0().z(true)                                    [main/manager/b0.java:1778]
   ├─ new NormalIMERootContainer(appContext)          [:1780]
   ├─ rootContainer.addViewTouchEventListener(b0.this.o)   [:1782]  ← 全局触摸监听器
   ├─ inputWindow = MainImeServiceDel.getInstance().getInputWindow()   [:1783]
   │             = SogouIME.r()  →  com.sogou.core.ui.a              [base/i.java:252-258]
   ├─ inputWindow.c().addOnLayoutChangeListener(b0.this.n)             [:1784]
   ├─ new com.sohu.inputmethod.ui.frame.c(ctx, rootContainer)          [:1785]
   ├─ inputWindow.s(cVar)  ← 设置窗口背景绘制器                        [:1787]
   ├─ Log.d("sg-input", "MainIMEFunctionManager createIMERootContainer: ")
   ├─ f(rootContainer) → rootContainer.setGestureIntercept(new com.sogou.switchover.a())  [:1789]
   └─ if (z) rootContainer.addHeaderDecorator(new b())                 [:1799]
```
另一处调用：`sogou/g0.java:569`（`g0.p(boolean)` 中 `if (b0.f0().e0() == null) createIMERootContainer();`）。

### 3.2 视图装配：只认这三个对象

| 角色 | 取法 | 类型 |
|---|---|---|
| 根容器 | `b0.f0().e0()` | `NormalIMERootContainer` |
| 键盘 | `b0.f0().i0()` | `s0`（唯一实现 `com.sogou.lib.bu.ui.component.keyboard.d`） |
| 候选栏 | `b0.f0().b0()`，或 `b0.f0().c0().v()` | `IMEInputCandidateViewContainer` |
| 键盘的"另一半" | `b0.f0().c0().C()` | `s0`（与 `i0()` 同型；`c0()` 返回 `com.sohu.inputmethod.main.manager.o`） |

装配语句（全仓库出现的全部形式）【S】：
```java
b0.f0().e0().setCandidatesView(b0.f0().b0());                      // sogou/g0.java:413, :437
b0.f0().e0().setKeyboardView(b0.f0().i0().getRootView());           // sogou/g0.java:414, :666
b0.f0().e0().setKeyboardView(c0.C().getRootView());                 // sogou/MainImeServiceDel.java:2326
b0.f0().e0().setKeyboardView(expressionScreenView);                 // sogou/j.java:299
```

### 3.3 交给框架：**唯一漏斗** `sogouIME.setInputView(View)`

`com/sohu/inputmethod/base/i.java:514-534`：
```java
public void setInputView(View view, MainImeServiceDel.INPUT_VIEW_TYPE input_view_type) {
    SogouIME sogouIME = mImeService;
    if (sogouIME != null) {
        try {
            if (input_view_type == MainImeServiceDel.INPUT_VIEW_TYPE.GAME_BLANK_VIEW) sogouIME.a(view);
            else sogouIME.setInputView(view);              // ← 全部输入视图都走这里
        } catch (IllegalStateException unused) {
            if (view.getParent() instanceof ViewGroup) {      // 父容器冲突时先摘再装
                ((ViewGroup) view.getParent()).removeView(view);
                if (input_view_type == ...GAME_BLANK_VIEW) sogouIME.a(view); else sogouIME.setInputView(view);
            }
        }
    }
}
```
子类：`MainImeServiceDel.java:7934-7940` 只做 `Log.d(TAG, "setInputView: " + type); super...; resetAfterSetInputView(); logInputViewLayout(view);`

> **设计结论 B**：`com.sogou.lib.spage.BaseInputMethodService#setInputView(android.view.View)` 是**所有界面态（18 种 INPUT_VIEW_TYPE）共用的唯一注入点**。钩住它 = 拿到每一次界面切换 + 拥有替换最终呈现的能力。它比 `onCreateInputView` 更可靠（后者在本 APK 中根本不存在，见报告 2）。

### 3.4 `INPUT_VIEW_TYPE` 枚举全值【D】（`MainImeServiceDel.java:367-386`）

`KEYBOARD_VIEW`、`KEYBOARD_LOADING_VIEW`、`EDIT_VIEW`、`KEYBOARD_SWITCH_VIEW`、`PLATFORM_COMPLETE_VIEW`、`CLIPBOARD_VIEW`、`SHORTCUT_PHRASE_VIEW`、`EXPRESSION_VIEW`、`FLOAT_ROOT_VIEW`、`GAME_BLANK_VIEW`、`CLIPBOARD_EXPLODE_VIEW`、`FANLINGXI_KEYBOARD_INITIATIVE`、`FANLINGXI_KEYBOARD`、`MINI_VOICE_VIEW`、`KEYBOARD_BUBBLE`、`KEYBOARD_SOUSOU`、`MUSIC_KEYBOARD`、`STYLUS_BLANK_VIEW`（共 18 个）。
当前值由 `MainImeServiceDel.setInputViewType(INPUT_VIEW_TYPE)`（:7942）写入字段 `mInputViewType`，被 `:1131` 等处判断。

---

## 4. 键盘内容：不是普通 View 树（决定了"能改什么/不能改什么"）

`getKeyboardView()` 拿到的 `KeyboardRootComponentView` 只有 **一个真实 View**，其余全是自绘"虚拟组件"：

```
KeyboardRootComponentView (extends FrameLayout 语义的 android.view.View)
├─ mDecorComponent   (组件：装饰层，承载全部视觉与命中)
│   └─ mContentComponent (com.sogou.theme.data.keyboard.c 对应的键盘组件 g)
│        ├─ 行组件（AdapterComponent，每行一个）
│        │    └─ 键组件（Key_XXX，几何来自 theme 的 py_*.ini）
│        └─ 候选编码区组件 CandidateCodeView
├─ mAttachInfo       (帧/事件调度器：a() 帧开始、l(MotionEvent) 收事件、b() 帧结束)
└─ mParent = new l(this)（内部 ViewParent 适配器）
```

由此推出三条硬约束：
1. **没有可枚举的按键 View** → 不能用 `findViewById` / 遍历子 View 的方式做"iOS 键帽美化"；
2. **命中测试在组件内部**（`mDecorComponent.h1(event)`、`KeyboardRootComponentView.getKeyLocationInWindow(float,float,int)[]`）→ 想保留搜狗手感就必须保留这个 View；
3. **绘制完全可控**（`onDraw` → `mDecorComponent.a5(canvas)`，`setDrawCacheEnabled`）→ 可在其上层叠加我们自己的绘制层，或把整个 View 换成我们的实现但同时接管命中与事件派发。

---

## 5. 供 Phase 2/3 使用的接缝清单（按风险从低到高）

| # | 接缝 | 方法签名 | 优点 | 风险 | 等级 |
|---|---|---|---|---|---|
| S1 | 只在旁边"加一层" | `NormalIMERootContainer#addChildView(View,int)`（:193）+ `addSmartThemeLayerView(View)`（:234，z=1000） | 不改搜狗任何逻辑，z=1000 永远最上层 | 需要自己同步尺寸/透明度；触摸要能穿透 | 【S】 |
| S2 | 跟随键盘安装 | `NormalIMERootContainer#setKeyboardView(View)` after | 每次换键盘都能重新挂我们的层 | 拿不到"当前是哪种键盘"（需配合 S4） | 【S】 |
| S3 | 候选栏替换 | `NormalIMERootContainer#setCandidatesView(View)` after | 候选栏是一个普通 View，容易整体替换 | 候选数据来源需另行接入（报告 4/8） | 【S】 |
| S4 | 全局界面态漏斗 | `com.sogou.lib.spage.BaseInputMethodService#setInputView(View)` | 18 种界面态全覆盖，一处即控 | 混淆/AspectJ 风险最低（真实方法，非合成） | 【S】 |
| S5 | 内容槽替换 | `com.sogou.core.ui.a#u(View)` | 连背景层一起掌控 | 影响窗口背景/模糊/壁纸主题 | 【S】 |
| S6 | 根容器整体替换 | `b0#z(boolean)` after 换返回值 | 完全自主 | 与 `b0.b` 字段、`ui/frame/c` 背景绘制器耦合，易崩 | 【R】 |

**MVP 建议**：先 S2 + S1（不动搜狗任何东西，只叠一层 + 跟随安装），把"iOS 外观显示出来"跑通；再用 S4 做统一接管。

---

## 6. 尺寸与几何（可直接用于我们自己的布局）

来自主题声明（`apktool_out/assets/theme/theme_default/1080/port/py_9.ini`）【S，资源文件非代码】：

| 项 | 值 |
|---|---|
| 键盘宽 | `W = 1`（占屏宽 100%） |
| 键盘高 | `H = 0.591667`（占可用高度比例 → 1080p 竖屏约 639 px，含候选栏） |
| 键盘内边距 | `PADDINGS=0.00741,0.00313,0.00741,0.00741`（比例值） |
| 行数 | 4 行（`ROWS=ROW1,ROW2,ROW3,ROW4`） |
| ROW1 上偏移 | `H_OFFSET = 0.16204`（= 顶行与候选栏之间的额外留白比例） |
| 单键宽 | `Key W = 0.22037`（= 1/4 减去间距） |
| 单键高 | `Key H = 0.24922`（≈ 1/4） |
| 退格宽 | `Key_Backspace W = 0.16204` |
| 触摸模式 | `TOUCH_MODE = 2`（UNKNOWN：数值语义未确认） |
| 候选栏 | `[CandidateBar] PADDINGS=`；`[ComposingView] BG_COLOR=0xFFF5F5F5`；`[CloudView] BG_COLOR=0xFFC8E0E8` |

> iOS 键盘的实际几何应参考 iOS 系统输入法（比例约为：键高占键盘高 1/4，间距 6dp，退格 1.5 倍宽，底部安全区单独处理），**不要直接复用搜狗的 0.591667 比例**；但可以作为"搜狗期望的高度"参考，用来决定我们是否要覆盖 `onComputeInsets`。

---

## 7. 运行时验证清单（Phase 1 必做，未做之前不得宣称已达成的结论）

1. dump 真实 View 树：确认 `StyleConstraintLayout` → `WindowBackgroundLayout` + `NormalIMERootContainer` 三层结构与 z-index 顺序。
2. 确认 `getKeyboardView()` 的运行时类名确实是 `KeyboardRootComponentView`。
3. 确认 `setKeyboardView` 在一次"显示键盘"中恰好被调用一次、且在 `setInputView` 之前。
4. 确认表情面板打开时走 `setKeyboardView(expressionScreenView)`（`j.java:299` 路径是否被触发）。
5. 确认 `NormalIMERootContainer.dispatchTouchEvent` 的 AspectJ/Fluency 包装不会导致 hook 失效（拿不到事件）。
6. 确认 `mChildrenArray` 的 `complateChildrenAdd()` 是否在每个界面态都重排（关系到我们插入的层会不会被清掉）。
7. 确认 `onComputeInsets` / `updateInputFrameLayout(left,top,w,h)` 的实际调用序列，避免我们的层被裁切。

---

## 8. 本章 UNKNOWN

- `TOUCH_MODE=2` 的语义；
- `setElderVoiceView` 的 z-index（补充：第 4 路复核给出 `Z_INDEX_ELDER_VOICE_VIEW=16`，见 §9.2）；
- `com.sohu.inputmethod.ui.frame.c`（背景绘制器，12,341 B）在换肤/壁纸主题下的重绘时机；
- `KeyboardHeaderContainer`（工具条）内部结构与其 3D 皮肤（`Skin3DUiProcessor.onInputViewLayoutChange(Context,View)`）的联动；
- 悬浮键盘（`FLOAT_ROOT_VIEW`）与折叠屏/分屏下的布局参数覆盖逻辑。

---

## 9. 第 4 路独立复核（子代理 `20eb95d8`）结论与修正

> 复核方式：只读 `analysis/jadx_out/sources`，逐跳核对调用点；`q.py mfind` 做 DEX 级确认。

### 9.1 三条必须写进设计的修正

1. **`setInputView(View)` 从不接收键盘 View。** 传给它的对象只有 `NormalIMERootContainer`（GAME/STYLUS 空白态传一个占位 View）。DEX 级确认：全 APK 只有 3 处 `setInputView` 声明 —— `spage/BaseInputMethodService.setInputView(View)`、`base/i.setInputView(View, INPUT_VIEW_TYPE)`、`MainImeServiceDel.setInputView(View,INPUT_VIEW_TYPE)`+`setInputViewType(...)`。⇒ 想换键盘 UI，**只能**从 `NormalIMERootContainer.setKeyboardView(View)`（或更上游的键盘工厂）入手，不能指望"截获 setInputView 的入参"。
2. **`InputMethodService.setInputView` 收到的是 `StyleConstraintLayout`**（`com.sogou.core.ui.window.StyleConstraintLayout extends ConstraintLayout`），它由 `com/sogou/core/ui/window/b.java:40-48` 创建、`c()`（:77-80）返回，再由 `spage/BaseInputMethodService.java:300` 交给框架。该布局里有两个子节点：`WindowBackgroundLayout`（背景，`-1×-1`）与 `NormalIMERootContainer`（`com/sogou/core/ui/a.java:98-112 u(View)` 加入）。
3. **`q.py mfind 'onCreateInputView|onCreateCandidatesView'` 无输出** —— 这两个 IME 回调在整个 APK 的任何 dex 里都不存在（第 3 次独立确认，此前是字符串扫描 + 方法名索引）。

### 9.2 z-index 常量符号名（与 §2 表格对应，均来自 `ui/frame/NormalIMERootContainer.java` 字段声明）

`Z_INDEX_FLX_IME_VIEW=0`、`Z_INDEX_BACKGROUND_VIEW=1`（**无写入方，UNKNOWN**）、`Z_INDEX_CANDIDATES=2`、`Z_INDEX_KEYBOARD=3`、`Z_INDEX_FIRST_CANDIDATS=4`（搜狗拼写如此）、`Z_INDEX_KEYBOARD_RESIZE_VIEW=5`、`Z_INDEX_HEADER_VIEW=6`、`Z_INDEX_KEYBOARD_HANDWRITE_LAYOUT=7`、`Z_INDEX_HW_HALF_SCREEN_DISPATCHEVENT_VIEW=8`、`Z_INDEX_FLOATDRAG_VIEW=9`、`Z_INDEX_KEYBOARD_CHANGE_VIEW_LEFT=10`、`Z_INDEX_KEYBOARD_CHANGE_VIEW_RIGHT=11`、`Z_INDEX_PLATFORM_VIEW=12`、`Z_INDEX_FLOAT_RESIZE_VIEW=13`、`Z_INDEX_FIRST_GUIDANCE_VIEW=14`、`Z_INDEX_MUSIC_KEYBOARD=15`、`Z_INDEX_ELDER_VOICE_VIEW=16`、`Z_INDEX_SMART_LAYER_VIEW=1000`。

### 9.3 键盘对象的唯一创建点（换 UI 最上游的缝）

```
doWhenInitialInterfaceIOFinishFirstTime()            sogou/n1.java:604（handler case 123 后）
  → createIMERootContainer()                         MainImeServiceDel.java:3388
    → b0.z(true)                                     main/manager/b0.java:1778-1802（全 APK 唯一 new NormalIMERootContainer）
      → A0(1) → new o(ctx, KEYBOARD_VIEW)
        → main/view/k.z(Context)                     main/view/k.java:209-225
          → I()                                      main/view/k.java:54-67
            → com.sohu.inputmethod.ui.j.k().m()      ui/j.java:154-157  ← 全 APK 唯一 new keyboard.d 处
              → new com.sogou.lib.bu.ui.component.keyboard.d(...)   keyboard/d.java:61-63 内 new KeyboardRootComponentView
            → E(h.getRootView())                     （= main/view/c.java:38-40，键盘槽位字段 d）
          → main/view/c.x(NormalIMERootContainer)    main/view/c.java:46-62
            → rc.setCandidatesView(this.c)  (z=2)
            → rc.setKeyboardView(this.d)    (z=3)
```

⇒ `com.sohu.inputmethod.ui.j#m()`（`ui/j.java:154-157`）与 `#h()`（:123-125，返回缓存的 `s0`）是**唯一的键盘工厂缝**：在这里返回一个"自己的 `s0` 实现"，`getRootView()` 交出自己的 View，就能让所有后续 `setKeyboardView(...getRootView())` 调用点（`g0.java:414/666`、`MainImeServiceDel.java:2326`、`manager/j.java:299` 等）自动换成我们的 UI。

### 9.4 缝位风险重排（替代 §5.1 的初步排序）

| 排名 | 缝 | 收益 | 代价/风险 |
|---|---|---|---|
| 1 | `NormalIMERootContainer#setKeyboardView(View)`（:946-985）+ 我们的 View **实现 `s0`**（空实现亦可） | 只换键盘，候选条/工具条/insets/主题全保留；`instanceof s0` 的布局规则（rule 6 vs 3）与 `checkSogouKeyboardViewExists()`（`index3 instanceof s0`，:266-269）继续成立 | `getKeyboardView()` 的 10 处消费者会拿到我们的 View（`b0.java:863`、`manager/d.java:45`、`BubblePage.java:37/40`、`MainImeServiceImpl.java:614/667`、`feedback/d.java:112`、`ui/u.java:101`）→ 需保证 `s0` 的空实现不 NPE |
| 2 | `ui.j#m()` / `ui.j#h()` 返回自定义 `s0` | 最抗"重复 `setKeyboardView`"（浮窗/游戏/加载态都会重新 `getRootView()`） | `s0` 有约 90 个成员，需转发包装（Java 动态代理或手写委托）；另有 `ui/frame/e.f(s0)` 尺寸镜像逻辑（`ui/frame/e.java:40-56`） |
| 3 | `main/view/c#E(View)`（键盘槽字段写入，`main/view/c.java:38-40`） | 在 `x()` 推入容器前替换 | 与 1 相同的 `s0` 要求；`view.k.m()/L()` 仍暴露真实 `s0` |
| 4 | 纯叠加：`setDispatchEventView(View,int,int,boolean)`（:594-611）或 `RootContainer#addChildView(View,int)`（:81-96）+ `updateAllChildren()`（:1012-1019） | 不替换任何东西，风险最低，可先用它做 Phase 2 的"可见性验证" | 我们的层与原键盘共存，需要自己处理遮挡/触摸吞并 |
| 5 | 换掉整个输入视图：`spage/BaseInputMethodService#setInputView`（:291-303）/ `core/ui/a#u`（:98-112） | 完全掌控 | 候选条/工具条/主题背景/insets 全部丢失，需自行实现 `onComputeInsets`（`MainImeServiceDel` :1318 读 `getInputWindow().c().getHeight()`） |
| 6 | 只旁观：`addKeyboardViewChangedListener(foreign.multilanguage.view.d)`（:230-232） | 可探测每一次键盘切换 | 仅通知，不能改 |

**MVP 建议（与 §5.1 一致，此处更精确）**：Phase 2 用排名 4 做"注入可见性 + 生命周期"验证；Phase 3 用排名 1（+ 我们的 View 实现 `s0`）做真实替换；排名 2 作为兼容兜底；排名 6 作为看门狗。

### 9.5 其它新增事实

- 键盘工厂调用链中，`com.sohu.inputmethod.ui.j.k().m()`（`ui/j.java:154-157`）是**唯一**创建 `keyboard.d` 的地方；`ui/j.java:123-125` 的 `h()` 返回已缓存的 `s0`。
- `KeyboardRootComponentView` 由 `keyboard/d.java:61-63` 创建（即键盘落地时才有，`onStartInputView` 时已存在）。
- `MainImeServiceDel#doOnStartInputView`（:4047，2610 条指令）无法从 `jadx_out` 反编译；用 `jadx --show-bad-code` 恢复后可读到：OTG 兜底 `createIMERootContainer(); b0.f0().w(KEYBOARD_VIEW)`、`e0().setInputViewShown(true)`、要求 `c0.C()!=null`（即键盘组件此时必须已就绪）。**该恢复代码不属于 `analysis/`，不入库**，结论按【R】处理。
- 收起路径：`MainImeServiceDel#onFinishInputView`（:6161-6190）→ `doOnFinishInputView`（:6181）→ 硬键盘连接且软键盘未显示时 `e0().clearAllChildren()`。
