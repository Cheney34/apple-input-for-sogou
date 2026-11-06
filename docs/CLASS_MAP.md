# Apple Input for Sogou — 阶段 0 静态分析报告（2/8）：核心类结构地图

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi`（小米定制版搜狗输入法）`10.32.21.202607061714`
> 分析日期：2026-11-06
> 分析方式：jadx 1.5.1 反编译（`analysis\jadx_out\sources\`）+ androguard 4.1.4 DEX 索引（`analysis\dex_index.json`，33,797 类）
> **本文档一切类名/方法名/描述符均来自上述产物，未经运行时验证的条目一律标注"运行时未验证"。**

---

## 0. 证据等级与阅读约定

| 标记 | 含义 |
|---|---|
| **【D】** | DEX 层确认：`dex_index.json` / `analysis\q.py show` 直接读出（类名、父类、方法描述符、所属 dex 文件）——**这是最硬的证据，模块里可以直接据此写 `findAndHookMethod`** |
| **【S】** | 源码层确认：jadx 反编译产物中读到的实现体（可能因混淆/内联与真实字节码有差异，AspectJ 织入方法尤其如此） |
| **【R】** | 需运行时验证：静态分析无法确定（真实调用顺序、对象实例、多进程行为等） |
| **UNKNOWN** | 未确认，禁止编码实现时假定 |

引用格式 `包路径/类.java:行号`，均相对于 `AppleInputForSogou\analysis\jadx_out\sources\`。

**混淆/AspectJ 警告【D】**：本 APK 用 AspectJ 大面积织入，几乎每个业务类都带合成成员（`ajc$tjp_*`、`M/N/O/.../X(...org.aspectj.lang.a)`、`$$SRouter$$Autowired`）。后果：
1. 禁止"遍历所有方法名找特征"的反射探测 —— 会把 AspectJ 合成方法当成业务方法；
2. 必须用**精确** `XposedHelpers.findAndHookMethod(className, classLoader, methodName, paramTypes...)`；
3. 某些业务方法被织入包装后**返回值/调用顺序**与源码看起来不一致（例如 `NormalIMERootContainer.dispatchTouchEvent` 实际经过 `com.sogou.bu.fluency.core.b.e().f(...)` 异步化，见 S 证据 `ui/frame/NormalIMERootContainer.java:294`）。

---

## 1. 进程与入口

| 项 | 值 | 等级 |
|---|---|---|
| 输入法 Service（manifest 声明） | `com.sohu.inputmethod.sogou.xiaomi.SogouIME` | 【D】 |
| 其实际行数 | 空子类 `public class SogouIME extends com.sohu.inputmethod.sogou.SogouIME {}`（源码 151 B） | 【S】 |
| Application | `com.sohu.inputmethod.sogou.SogouTinkerApplication extends com.tencent.rfix.loader.app.RFixApplication`（classes4，1,451 B），真实业务 Application 为 `com.sohu.inputmethod.sogou.SogouAppApplication` | 【S】 |
| 宿主进程名 | `com.sohu.inputmethod.sogou.xiaomi`（manifest `android:process`）；另有 `:ac_checker :heap_analysis :p0 :patch :rarely :remote :speech` 等子进程 | 【D】 |
| 热修复 | 腾讯 RFix/Tinker（`com.tencent.rfix.loader.*`） | 【D】 |

> **模块必须只在 `com.sohu.inputmethod.sogou.xiaomi` 与 `com.sohu.inputmethod.sogou` 两个包名 + 上述主进程内生效**；子进程（`:p0`、`:remote` 等）不加载输入视图，进了只会白付代价。

---

## 2. InputMethodService 继承链（完整，共 5 个类，无第六个）

```
android.inputmethodservice.InputMethodService
└─ Lcom/sogou/gesture/BaseStylusInputMethodService;              [classes2.dex] abstract
   └─ Lcom/sogou/lib/spage/BaseInputMethodService;               [classes2.dex]
      └─ Lcom/sohu/inputmethod/sogou/BaseSogouIME;               [classes4.dex] abstract
         └─ Lcom/sohu/inputmethod/sogou/SogouIME;                [classes4.dex]
            └─ Lcom/sohu/inputmethod/sogou/xiaomi/SogouIME;      [classes4.dex]  ← manifest 声明的那个
```
证据：`analysis\q.py ims`（枚举全部 `InputMethodService` 后代，仅此 5 个）。**【D】**

### 2.1 `com.sogou.gesture.BaseStylusInputMethodService` 【D】
只声明手写笔相关方法，不涉及键盘视图：
`doSetStylusHandwritingSessionTimeout(Ljava/time/Duration;)V`、`onUpdateEditorToolType(I)V`、`performHandwritingGesture(Landroid/view/inputmethod/HandwritingGesture;Ljava/util/concurrent/Executor;Ljava/util/function/IntConsumer;)V`、`previewHandwritingGesture(Landroid/view/inputmethod/PreviewableHandwritingGesture;Landroid/os/CancellationSignal;)Z`。

### 2.2 `com.sogou.lib.spage.BaseInputMethodService` 【D】—— **spage 框架接缝（本项目最重要的类）**

完整声明方法（`q.py show` 原样）：

| 方法 | 说明（推断，【S】/【R】） |
|---|---|
| `setInputView(Landroid/view/View;)V` | **输入视图唯一注入口**（见 2.5） |
| `r()Lcom/sogou/core/ui/a;` | 返回 spage 输入窗口对象（`com.sogou.core.ui.a`），即"真实根视图"的持有者 |
| `q()Lcom/sogou/lib/spage/context/a;` | spage 上下文 |
| `s()Lcom/sogou/base/spage/b;` | spage 生命周期/事件总线 |
| `a(Landroid/view/View;)V` / `d()V` / `k()` | 未确认 |
| `c()Landroid/view/inputmethod/InputConnection;` | 输入连接读写入口之一 |
| `e(InputMethodService$Insets;)V`、`f(Configuration;)V`、`g(Window;ZZ)V`、`h()Z`、`i(I)Z`、`j(Z)V`、`l(Z)V`、`m(IILandroid/view/KeyEvent;)Z`、`n(ILandroid/view/KeyEvent;)Z`、`o(ILExtractedText;)V`、`p(IIIIII)V` | 与框架生命周期/回调的转发桩 |
| 标准生命周期 | `onCreate/onDestroy/onBindInput/onUnbindInput/onStartInput/onFinishInput/onStartInputView/onFinishInputView/onStartCandidatesView/onFinishCandidatesView/onComputeInsets/onConfigureWindow/onWindowShown/onWindowHidden/onKeyDown/onKeyUp/onKeyMultiple/onUpdateSelection/onUpdateExtractedText/onExtractedTextClicked/onViewClicked/onAppPrivateCommand/onConfigurationChanged/onLowMemory/onTrimMemory/onInitializeInterface/requestHideSelf(I)` |

### 2.3 `com.sohu.inputmethod.sogou.BaseSogouIME` 【D】（abstract）
`<init>()V`、`t(Lcom/sohu/inputmethod/sogou/BaseSogouIME;)Z`、`onBindInput()V`、`onCreate()V`、
`onCreateInputMethodInterface()Landroid/inputmethodservice/AbstractInputMethodService$AbstractInputMethodImpl;`、
`onDestroy()V`、`onInitializeInterface()V`、`onUnbind(Landroid/content/Intent;)Z`、`u()Z`、`v()Z`。
内部私有类 `b extends InputMethodService.InputMethodImpl` 反射调用 `com.android.internal.inputmethod.InputMethodPrivilegedOperationsRegistry.remove(IBinder)`【S】。

### 2.4 `com.sohu.inputmethod.sogou.SogouIME` 【D】
额外声明：`D()Lcom/sogou/bu/basic/ic/g;`、`getCurrentInputConnection()Landroid/view/inputmethod/InputConnection;`、`getMonitorInfo()Ljava/lang/String;`、`hideWindow()V`、`showWindow(Z)V`、`sendKeyChar(C)V`、`sendDownUpKeyEvents(I)V`、`showStatusIcon(I)V`、`onStartInputView/onFinishInputView/onShowInputRequested/onEvaluateInputViewShown/onComputeInsets`、`onStartStylusHandwriting`、`onStylusHandwritingMotionEvent(Landroid/view/MotionEvent;)V`、`dump(...)`，以及 AspectJ 合成 `M/N/O/P/Q/R/S/T/U/V/W/X(...,org.aspectj.lang.a)`。

`getCurrentInputConnection()` 实现【S】（`sogou/SogouIME.java`）：
```java
public InputConnection getCurrentInputConnection() {
    InputConnection l;
    if (u() || (l = D().l()) == null) return null;
    return l instanceof SogouInputConnectionManager.b ? ((SogouInputConnectionManager.b) l).i() : l;
}
```
→ 真实输入连接 = `D()`（`com.sogou.bu.basic.ic.g` 输入内核接口）`.l()`。**这是模块取"当前输入连接"的官方路径。**

### 2.5 `setInputView` 的真实实现 —— 视图树从这里长出来 【S】

`com/sogou/lib/spage/BaseInputMethodService.java:292-303`：
```java
@Override public void setInputView(View view) {
    this.b.u(view);                                   // b: com.sogou.core.ui.a —— 把 view 塞进 spage 窗口
    ConstraintLayout c = this.b.c();                  // spage 的 StyleConstraintLayout 根
    ViewParent parent = c.getParent();
    if (parent != null) ((ViewGroup) parent).removeView(c);
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.getLayoutParams();
    super.setInputView(c);                             // 真正交给 InputMethodService 的是 c，不是 view！
    c.setLayoutParams(lp);
    this.d.g();                                        // d: com.sogou.base.spage.b —— spage 生命周期推进
}
```
**结论**：`super.setInputView(...)` 收到的是 **`com.sogou.core.ui.window.b.c`（StyleConstraintLayout）**，而搜狗自己的内容视图只是它下面的一层子 View。任何"替换输入视图"的尝试都必须理解这三层结构（见 §3）。

---

## 3. 输入窗口三层结构（【S】全部确证）

```
InputMethodService 的 softInput 窗口 DecorView
└─ StyleConstraintLayout            com.sogou.core.ui.window.b.c      id = com.sogou.imskit.core.ui.kernal.a.a
   │                                （FrameLayout.LayoutParams(-2,-2), gravity=80 即底部对齐）
   ├─ [0] WindowBackgroundLayout    com.sogou.core.ui.window.b.d      match_parent × match_parent（窗口背景层）
   └─ [1] 内容视图 = NormalIMERootContainer（正常键盘态）或其它 INPUT_VIEW_TYPE 对应的 View
                                    com.sogou.core.ui.a.f  ← com.sogou.core.ui.a.u(View) 设置
```

关键类：

| 类 | dex | 关键成员 | 证据 |
|---|---|---|---|
| `com.sogou.core.ui.window.b` | classes2 | `protected StyleConstraintLayout c;` `protected WindowBackgroundLayout d;` `protected Window b;` `protected c<?> e;`；`c()`→`ConstraintLayout`；`d()`→背景组件；`h(c<?>)` 设背景；`i(int,int)` 设边距；`b(boolean)` 切换悬浮/贴底布局参数 | 【S】`sogou/core/ui/window/b.java` |
| `com.sogou.core.ui.a` | classes2 | `extends com.sogou.core.ui.window.b`；`private View f;`（内容视图）；**`u(View)`：先 `c.removeView(f)`，`f = view`，设 id（`view.getId()==-1` 时设 `com.sogou.core.ui.b.a`），然后 `l(); a(c, f); n();`**；`p()`→`f`；`k(View)`/`r(View)` 增删背景视图；`t(float)` 背景透明度；`s(com.sogou.core.ui.view.a)` 设背景绘制器 | 【S】`sogou/core/ui/a.java` |
| `com.sohu.inputmethod.ui.CornerRelativeLayout` | classes3 | `RootContainer` 的父类（圆角 RelativeLayout） | 【D】 |

> 结论：**`com.sogou.core.ui.a.u(View)` 是"内容视图"的唯一写入点**（每次切换键盘/表情/手写/游戏键盘都会走它），是"整块 UI 替换"的第一备选接缝（运行时未验证）。

---

## 4. 视图装配链：谁创建、谁安装、谁提交给框架

### 4.1 三个全局单例入口（【D】+【S】，模块取活对象的官方路径）

| 表达式 | 返回 | 位置 | 等级 |
|---|---|---|---|
| `com.sohu.inputmethod.sogou.MainImeServiceDel.getInstance()` | IME 总代理（`AbstractImeServiceDelegate` 的子类） | `sogou/MainImeServiceDel.java:1487` | 【D】 |
| `com.sohu.inputmethod.main.manager.b0.f0()` | 全局 IME 功能管理器单例（`private static b0 t;`） | `main/manager/b0.java:374`（类 decl 第 51 行 `private NormalIMERootContainer b;`） | 【D】 |
| `b0.f0().e0()` | **`NormalIMERootContainer`**（当前根容器；可能为 null） | `main/manager/b0.java:1182` | 【D】 |
| `b0.f0().i0()` | **`com.sohu.inputmethod.sogou.s0`**（键盘 facade，见 §5） | `main/manager/b0.java:1288` | 【D】 |
| `b0.f0().b0()` | **`IMEInputCandidateViewContainer`**（候选栏容器，见 §6） | `main/manager/b0.java:1116` | 【D】 |
| `b0.f0().c0()` | `com.sohu.inputmethod.main.manager.o`（键盘/候选管理器，`o.C()`→`s0`，`o.v()`→`IMEInputCandidateViewContainer`） | `main/manager/b0.java:1128`；`main/manager/o.java:251`、`:1041` | 【D】 |
| `b0.f0().t0()` | `MainImeServiceDel.getInstance()` | `main/manager/b0.java:419` | 【D】 |
| `MainImeServiceDel.getInstance().getInputWindow()` | `com.sogou.core.ui.a`（spage 输入窗口） | 声明在 `base/i.java:252`，实现 `return mImeService.r();` | 【D】+【S】 |
| `MainImeServiceDel.getInstance().getSogouInputConnection()` | `com.sogou.bu.basic.ic.g`（输入内核接口，可 `commitText`） | `n1.java:1161` 使用处 | 【S】 |

> `b0` 类关系：**`com.sohu.inputmethod.main.manager.b0` [classes3]**，字段 `private static b0 t;`、`private NormalIMERootContainer b;`、`private com.sohu.inputmethod.ui.frame.c c;`、`private IMEKeyboardResizeView d;`、`public q g; public o h; public n i; public j j; public p k; public z l;`。

### 4.2 根容器的创建（唯一工厂）【S】

`main/manager/b0.java:1778-1802`：
```java
public View z(boolean z) {
    if (this.b == null) {
        NormalIMERootContainer c = new NormalIMERootContainer(com.sogou.lib.common.content.b.a());  // :1780
        this.b = c;
        c.addViewTouchEventListener(this.o);                                                         // :1782
        com.sogou.core.ui.a inputWindow = MainImeServiceDel.getInstance().getInputWindow();          // :1783
        inputWindow.c().addOnLayoutChangeListener(this.n);                                           // :1784
        com.sohu.inputmethod.ui.frame.c cVar = new com.sohu.inputmethod.ui.frame.c(ctx, this.b);     // :1785
        this.c = cVar;
        inputWindow.s(cVar);                                                                          // :1787 设背景绘制器
        Log.d("sg-input", "MainIMEFunctionManager createIMERootContainer: ");                          // :1788
        f(this.b);                                                                                    // :1789 setGestureIntercept(switchover.a)
    }
    if (z) { ... this.b.addHeaderDecorator(new b()); }                                                // :1791-1800
    return this.b;
}
```
唯一调用者：`MainImeServiceDel.java:3388 public View createIMERootContainer() { return b0.f0().z(true); }`（另一处 `g0.java:569` 在 `if (b0.f0().e0() == null)` 时调用）。**【D】+【S】**

### 4.3 键盘视图与候选视图的安装（`setKeyboardView` / `setCandidatesView`）【S】

调用点（全仓库 grep `.setKeyboardView(`）：
| 位置 | 调用 |
|---|---|
| `sogou/MainImeServiceDel.java:2326` | `b0.f0().e0().setKeyboardView(c0.C().getRootView());`（`c0` = `manager.o`） |
| `sogou/g0.java:414`、`:666` | `b0.f0().e0().setKeyboardView(b0.f0().i0().getRootView());` |
| `sogou/j.java:299` | `b0.f0().e0().setKeyboardView(expressionScreenView);` ← **表情面板也是用 setKeyboardView 装进 z-index=3 的！** |
| `sogou/q.java:72/88/105`、`ui/frame/a.java:63`、`ui/frame/c.java:60` | inflate 其它键盘类视图 |

`NormalIMERootContainer.setKeyboardView(View)` 实现【S】（`ui/frame/NormalIMERootContainer.java:946-985`）：
```java
public void setKeyboardView(View view) {
    Log.d("sg-input", "NormalIMERootContainer setKeyboardView: ");
    View old = this.mCurKeyboardView;
    this.mCurKeyboardView = view;
    if (view == null) { removeChildView(3); }
    else {
        removeChildView(12);
        if (m.d) updateSmartBarVisibility(true);
        view.setId(com.sogou.lib.bu.ui.e.m);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(-2, -2);
        if (view instanceof s0)                    lp.addRule(6, com.sogou.lib.bu.ui.e.e);
        else                                       lp.addRule(3, com.sogou.lib.bu.ui.e.e);   // 位于候选栏之下
        if (view instanceof KeyboardRootComponentView) changeKeyboardTypeChangeViewVisibility(0);
        else                                          changeKeyboardTypeChangeViewVisibility(4);
        lp.addRule(9);
        if (view.getContext() != null) view.setLayoutParams(lp);
        addChildView(view, 3);                                                                  // z-index = 3
    }
    updateAllChildren();
    if (old != this.mCurKeyboardView) {
        for (com.sohu.inputmethod.foreign.multilanguage.view.d l : this.mKeyboardViewChangedListener)
            if (l != null) l.a(old, this.mCurKeyboardView);                                      // 变更广播
    }
}
```

`NormalIMERootContainer.setCandidatesView(View)`（`:579-591`）：设 id `com.sogou.lib.bu.ui.e.e`，`LayoutParams(-1,-2)`，`addRule(10)`（贴顶）+ `addRule(1, com.sogou.lib.bu.ui.e.h)`，`addChildView(view, 2)`。

### 4.4 `NormalIMERootContainer` 的 z-index 布局表【S】（`RootContainer.mChildrenArray: SparseArray<a>`，`a.b` = z-index）

| z-index | 内容 | getter |
|---|---|---|
| 0 | `floatFullView`（悬浮全屏遮罩，`b0.java` 侧 `p0.addView(this.floatFullView, 0)`） | — |
| 2 | **候选栏容器** | `getCandiatesView()` → `getChildViewByIndex(2)`（`:298`） |
| 3 | **键盘视图** | `getKeyboardView()` → `getChildViewByIndex(3)`（`:370`） |
| 4 | 首个候选容器 | `getFirstCandidatesView()` → `getChildViewByIndex(4)`（`:302`） |
| 9 | 悬浮拖拽条 | `getFloatDragView()` → `getChildViewByIndex(9)`（`:306`） |
| 11 | 键盘类型切换右视图（`setKeyboardTypeChangeViewRight`，`:930-944`） | — |
| 12 | 平台编辑视图（`setPlatformEditView`，`:987+`） | — |

基类 `com.sohu.inputmethod.ui.frame.RootContainer`【D】+【S】（`ui/frame/RootContainer.java`，169 行）：
- **package-private**：`class RootContainer extends com.sohu.inputmethod.ui.CornerRelativeLayout`（**模块无法直接 `XposedHelpers.findClass` 后强转/继承，只能通过父类或反射调用**）；
- 字段 `protected SparseArray<a> mChildrenArray;` `protected Context mContext;`，静态内部类 `protected static class a { public View a; public int b; a(int,View) }`；
- API：`public void addChildView(View,int)`（`z`=z-index，`SparseArray.put`）、`public void clearAllChildren()`、`public void complateChildrenAdd()`（拼写如此）→ `private fillAllChildren()`（先 `removeAllViews()` 再逐个 `addView`，捕获 `IllegalStateException` 并经 `com.sogou.pingback.j.l("AddViewExceptionInfo", ...)` 上报）、`public removeChildByZIndex(int)`、`protected removeChildAboveZIndex(int)`、`protected addChildAboveZIndex(int)`、`protected fillSameChildren(int)`、`protected removeChildView(int)`、`public boolean isInputViewShown()`/`isCandidateShown()`（均为 `return false;` 桩）、`setInputViewShown(boolean)`/`setCandidateViewShown(boolean)`（空实现桩）、`dispatchDraw` → `com.sohu.inputmethod.ui.frame.g.a(canvas, ...)`。

`NormalIMERootContainer` 其它已确认公开 API（【S】，行号为 `ui/frame/NormalIMERootContainer.java`）：
`:57 private View mCurKeyboardView;`、`:63 ArrayList<com.sohu.inputmethod.foreign.multilanguage.view.d> mKeyboardViewChangedListener;`、`:230 addKeyboardViewChangedListener`、`:252 addViewTouchEventListener(com.sohu.inputmethod.ui.frame.i)`、`:266 checkSogouKeyboardViewExists()`、`:294 dispatchTouchEvent(MotionEvent)`（经 AspectJ → `com.sogou.bu.fluency.core.b.e().f(...)`）、`:338 getHeaderView(int)`、`:416 isInputViewShown()`、`:428 isKeyboardViewShowInFlxImeView()`、`:560 removeKeyboardViewChangedListener`、`:794 setInputViewOffset()`、`:848 setInputViewShown(Z)`（日志 `"NormalIMERootContainer setInputViewShown: "`）、`:1012 updateAllChildren()`、`:987 setPlatformEditView(View)`、`:594 setDispatchEventView(View,int,int,boolean)`。

### 4.5 显示键盘的完整路径（静态，运行时未验证）

```
用户点输入框 → 系统 bind → SogouIME.onStartInputView
  → MainImeServiceDel.doOnStartInputView(EditorInfo,boolean)   [MainImeServiceDel.java:4047，jadx 未反编译 → 需读 smali]
  → g0.p(boolean)  [sogou/g0.java:553]  ← 若 b0.f0().e0()==null 则 createIMERootContainer()
  → mHandler.sendEmptyMessage(63)
  → n1.d(Message) case 63  [sogou/n1.java:1138] → com.sohu.inputmethod.sogou.window.d.t(ctx).E0(false); g0.D(true)
  → b0.f0().e0().setCandidatesView(b0.f0().b0())         [:413/:437]
  → b0.f0().e0().setKeyboardView(b0.f0().i0().getRootView())  [:414/:666]
  → b0.f0().w(INPUT_VIEW_TYPE.KEYBOARD_VIEW)              [:667]
  → MainImeServiceDel.setInputView(View, INPUT_VIEW_TYPE) [MainImeServiceDel.java:7935]
  → BaseInputMethodService.setInputView(View)             [spage:292]
  → com.sogou.core.ui.a.u(rootContainer) → StyleConstraintLayout c → super.setInputView(c)
```

---

## 5. 键盘渲染引擎：`com.sogou.imskit`（虚拟控件树）—— **不是普通 View 树**

搜狗键盘**不是**一组 `Button`/`View`，而是一套自绘的"虚拟控件"系统：一个 `RootComponentView`（唯一真实 View）承载整棵组件树，由组件自行测量/绘制/命中测试。

| 概念 | 类 | dex | 证据 |
|---|---|---|---|
| 真实 View 载体 | `com.sogou.imskit.core.ui.virtualwidget.component.RootComponentView` extends `android.view.View` | classes2 | 【S】277 行，见下 |
| 按键键盘专用载体 | `com.sohu.inputmethod.sogou.keyboard.KeyboardRootComponentView extends RootComponentView` | classes3 | 【D】`s0.getRootView()` 实际返回类型【R】 |
| 组件基类（Component） | `com.sogou.imskit.core.ui.virtualwidget.component.g`（46,056 B） | classes2 | 【D】 |
| 组件基类 2 | `...component.a`（45,560 B） | classes2 | 【D】 |
| 容器组件基类 | `...component.h`（23,103 B） | classes2 | 【D】 |
| 绝对定位容器 | `...component.b extends h`（内含 `public static class a extends h.b {int c; int d;}`） | classes2 | 【S】 |
| 适配器容器（键盘行） | `...component.c<T extends interfaces.a> extends h`，`T W4()` 返回适配器、`boolean Y4(g,int,long)` 项点击、`I3/O4/Z3` 抛异常 | classes2 | 【S】 |
| 组件接口 | `interfaces.a`（适配器：`g a(int,g,h)`/`void b(b)`/`int c()`/`void d(b)`/`int e(int)`/`int getCount()`/`long getItemId(int)`）、`b`（`void a()`）、`c`（`void X(g)`+`boolean t(g)`）、`d`（`boolean a(g,MotionEvent)`）、`e`/`f`（空标记） | classes2 | 【S】 |
| 无障碍 | `...component.accessibility.ComponentExploreByTouchHelper`（4,269 B） | classes2 | 【D】 |

`RootComponentView` 关键实现【S】（`sogou/imskit/core/ui/virtualwidget/component/RootComponentView.java`）：
```java
public boolean onTouchEvent(MotionEvent e) {           // :226-234
    SystemClock.uptimeMillis();
    this.mAttachInfo.a();                              // 帧开始
    this.mAttachInfo.l(e);                             // 事件交给调度器
    boolean r = this.mDecorComponent.h1(e);            // 装饰组件分发（命中→按键）
    this.mAttachInfo.b();                              // 帧结束
    return r;
}
public boolean onHoverEvent(MotionEvent e) { return this.mDecorComponent.f1(e); }
protected void onMeasure(...)  { this.mDecorComponent.L3(pL,pT,pR,pB); this.mDecorComponent.c5(wSpec,hSpec); setMeasuredDimension(T1(), S1()); }
protected void onLayout(...)   { this.mDecorComponent.b5(z, 0, 0, w, h); }
protected void onSizeChanged(...) { invalidateDrawingCache(); this.mDecorComponent.Z4(0,0,i,i2); }
protected void onDraw(Canvas c) { /* 缓存位图 + */ this.mDecorComponent.a5(c); }
public void setContentComponent(g gVar) { /* 若不同：存、invalidateDrawingCache()、mDecorComponent.M4()、mDecorComponent.Y3(gVar)、requestLayout()、invalidate() */ }
public void setDrawCacheEnabled(boolean z) { this.mAttachInfo.c = z; }
public void setSupportMultiTouch(boolean z) { this.mAttachInfo.k(z); }
```
- 字段：`d mAttachInfo`（帧/事件调度器）、`g mContentComponent`、`k mDecorComponent`、`i mParent = new l(this)`、`LongSparseArray<Long> mMeasureCache`、缓存位图/画布；常量 `PFLAG_FORCE_LAYOUT=4096`、`PFLAG_IS_LAID_OUT=4`、`PFLAG_LAYOUT_REQUIRED=8192`。
- `KeyboardRootComponentView` 追加：`getKeyLocationInWindow(float,float,int)[]`、`getKeyLocationOnScreen(...)[]`、键盘背景位图、`setContentComponent(g)` 中捕获 `c mCurrKeyboardComponent`、`setDrawEventListener(k)`、`DRAW_EVENT_VALID_TIMEOUT=200`、构造里 `setImportantForAccessibility(2)` + `setSupportMultiTouch(true)`。【S】

**对本项目的意义（结论）**：
1. 键盘"外观"完全由 `com.sogou.theme.*` 主题数据 + 组件绘制代码决定 → **改视觉 = 改主题数据或换掉 RootComponentView 的绘制**，不必逐个 View 重写；
2. 键盘"手感/命中"由 `mAttachInfo`/`mDecorComponent`/`KeyboardRootComponentView.getKeyLocationInWindow` 决定 → **可以保留搜狗的命中测试，只换渲染**；
3. 但组件树是自绘的，**没有标准 View 可供 iOS 风格逐键美化** → iOS 键盘必须由我们自绘（Canvas/Compose/自定义 View），与搜狗键盘并存或替换。

### 5.1 键盘 facade：`com.sohu.inputmethod.sogou.s0` 【D】

`s0` 是抽象接口（classes3），**唯一实现类 = `com.sogou.lib.bu.ui.component.keyboard.d`**（classes2，30,897 B）【D，`q.py impl`】。摘录方法（`q.py show`）：`getRootView()Landroid/view/View;`、`getHeight()I`、`getWidth()I`、`getKeyboardHeight()I`、`getVisibility()I`、`getWindowToken()Landroid/os/IBinder;`、`recycleKeyboard()V`、`d0()Lcom/sogou/lib/bu/ui/component/keyboard/g;`（键盘主组件）、`g0()Lcom/sohu/inputmethod/sogou/f1;`（候选/输入逻辑衔接）、`o0()Lcom/sohu/inputmethod/sogou/c5;`、`l(Lcom/sogou/theme/data/key/a;)V`、`o(Lcom/sohu/inputmethod/keyboardhandwrite/a;)V`、`t0(FF)[I`、`u0(FF)[I`（坐标→按键定位，极可能是触摸映射的关键）、以及约 80 个单字母方法（`n()`/`u()` 被 `MainImeServiceDel:949` 当状态谓词用）。

`com.sogou.lib.bu.ui.component.keyboard.d` 构造签名【S】：
`d(Context, com.sohu.inputmethod.input.api.a, r0, com.sogou.talkback.a, n0, com.sohu.inputmethod.voiceinput.helper.a, u2)`，内部 `this.c = new KeyboardRootComponentView(context)`、`setDrawCacheEnabled(sogou.input.keyboard.a.a().b())`，维护 `ArrayMap<CharSequence,g> a/b` 键盘组件缓存，构建入口私有方法 `z0(...)`、`x0`、`y0`、`O0(com.sogou.theme.data.keyboard.c,boolean,int)`、`J0(com.sogou.theme.data.key.a)`、`G0(boolean)`。

接口实现关系【D，`q.py impl`】：`f1` → `com.sohu.inputmethod.sogou.e1` [classes3] + `h1` [classes4]；`c5` → 无实现；`s0` → 仅 `d`。

### 5.2 键盘组件包 `com.sohu.inputmethod.sogou.keyboard` 【D】

| 文件 | 大小 | 声明 | dex |
|---|---|---|---|
| `KeyboardRootComponentView.java` | 3,462 | `public class KeyboardRootComponentView extends RootComponentView` | classes3 |
| `h.java` | 21,382 | `public class h extends com.sogou.lib.bu.ui.component.keyboard.c implements com.sogou.imskit.core.ui.virtualwidget.component.interfaces.c, i, Observer` | classes3 |
| `KeyboardAnimComponent.java` | 19,563 | `extends com.sogou.imskit.core.ui.virtualwidget.component.g implements com.sogou.theme.data.animation.interfaces.a` | classes3 |
| `c.java` | 13,365 | `public class c<T extends com.sogou.theme.data.keyboard.c> extends com.sogou.imskit.core.ui.virtualwidget.component.b` | classes3 |
| `g.java` | 11,133 | `extends ...component.g` | classes3 |
| `b.java` | 9,573 | — | classes3 |
| `j.java` | 7,664 | `extends com.sogou.lib.bu.ui.component.keyboard.e` | classes3 |
| `e.java` / `a.java` / `d.java` / `f.java` | 2,599 / 999 / 873 / 551 | — | classes3 |
| `i.java` / `k.java` | 227 / 121 | 接口 | classes3 |

### 5.3 按键事件派发 `com.sogou.imskit.core.ims.keyevent` 【S】

`KeyEventDispatcher`（classes2）：单例 `d()`，`CopyOnWriteArrayList<b> a`；`b(int,KeyEvent)` 逆序遍历调 `onKeyDown`、`c(int,KeyEvent)` 调 `onKeyUp`；`e(LifecycleOwner,b)` 用 `LifecycleAwareKeyEventListener` 包装，仅在 `Lifecycle.State.STARTED` 时下发，`ON_DESTROY` 自动摘除。
兄弟：`ims/keyevent/{a.java 453 B,b.java 581 B}`、`ims/lifecycle/{a 3,432,b 8,058,IMELifecycleCallback 2,480}`、`ims/a.java`、`ui/kernal/a.java`（其静态 `a` 是 StyleConstraintLayout 的 id 来源）。

---

## 6. 候选词层

| 类 | dex | 说明 | 证据 |
|---|---|---|---|
| `com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer` | classes3 | 候选栏容器（103,551 B，最大候选类）；`b0.f0().b0()` 与 `manager.o.v()` 都返回它 | 【D】 |
| `com.sohu.inputmethod.main.view.IMECommonCandidateView` | classes3 | 通用候选视图（18,508 B） | 【D】 |
| `com.sohu.inputmethod.main.view.CommonCandidateViewTouchHelper` | classes4 | 触摸辅助（3,110 B） | 【D】 |
| `com.sohu.inputmethod.main.view.KeyboardLayoutPager` | classes3 | 键盘布局分页（3,351 B） | 【D】 |
| `com.sohu.inputmethod.main.view.{j,m,w,k}` | classes3 | 混淆类 12,767 / 24,939 / 10,253 / 8,676 B | 【D】 |
| `com.sohu.inputmethod.main.view.candidate.{a,b}` | classes3 | 9,450 / 8,568 B | 【D】 |
| `com.sogou.input.ui.candidate.BaseCandidateComponent` | classes3 | 候选组件基类 | 【D】 |
| `com.sogou.input.ui.candidate.ImeInputCandidateRootView` | classes2 | 候选根视图 | 【D】 |
| `com.sohu.inputmethod.sogou.NewIMEFunctionCandidateView` / `CandidateCloudView` / `ClipboardFirstCandidateView` | classes3/4 | 功能候选 / 云候选 / 剪贴板首候选 | 【D】 |
| `com.sohu.inputmethod.bubble.keyboard.BubbleCandidateView` | — | 气泡候选 | 【D】 |
| `com.sohu.inputmethod.sogou.r2` | classes3/4 | `AbstractImeServiceDelegate.getCandidateView()` 返回类型（`b0.f0().P()`） | 【S】`sogou/AbstractImeServiceDelegate.java:111` |
| `com.sohu.inputmethod.candidate.listener.*` | — | 该包**只有** listener 子包，无视图类 | 【D】 |
| `com.sogou.expressionplugin.expression.candidate.ExpressionFunctionCandidateView` | — | 表情面板内的功能候选 | 【D】 |

候选栏在视图树中的位置：`NormalIMERootContainer` 的 **z-index = 2**，id `com.sogou.lib.bu.ui.e.e`，`LayoutParams(-1,-2)` 贴顶，键盘（z-index 3）位于其下方（`addRule(3, e.e)`）。

---

## 7. 输入 / 提交层

| 类 | dex | 关键点 |
|---|---|---|
| `com.sohu.inputmethod.input.InputLogic` | classes3 | 输入逻辑中枢（229,539 B）；`commitText` 直接调用 11 处 |
| `com.sohu.inputmethod.input.KeyboardTouchEventHandler` | classes3 | 触摸 → 逻辑（84,643 B）；`:1925` 用 `MainImeServiceDel.getInstance().getInputWindow()` |
| `com.sohu.inputmethod.sogou.u` | classes3 | `commitText` 调用最多（24 处） |
| `com.sohu.inputmethod.sogou.SogouInputConnectionManager` | classes3 | 输入连接管理单例（48,542 B）：`public static SogouInputConnectionManager P()`（`:735`）、`public static b Q(InputConnection)`（`:747`）、`public static b a(InputConnection)`（`:383`）；静态标志 `s,t,u,v`；内部 `b implements InputConnection` 包装真实连接：`commitText(cs,i)` 里 `SogouInputConnectionManager.s = true; boolean r = this.a.commitText(cs,i); this.b.e(cs,i); return r;`（`:442`），同理 `setComposingText(:664)`、`finishComposingText(:464)`、`deleteSurroundingText(:450)`、`sendKeyEvent(:650)`、`getTextBeforeCursor(:537)`、`getExtractedText(:478)`；`i()`（`:554`）返回被包装的真实连接；`a0()` 生成调用栈诊断串；环形缓冲 `a k = new a(16384)` |
| `com.sogou.bu.basic.ic.g` | classes2 | **输入内核接口**（715 B）：`extends InputConnection`，另有 `int b(); boolean c(CharSequence,int); void clear(); void d(String); void e(BaseExpressionInfo); InputConnection g(); void h(EditorInfo); void j(); InputConnection l(); void n(e); void o(e); void u(h,boolean,c);`，内部 `interface a<T> { void onResult(T t); }`。**`SogouIME.D()` / `MainImeServiceDel.getSogouInputConnection()` 都返回它** |
| `com.sogou.bu.basic.ic.u` | classes2 | `extends b`（19,709 B）：线程池 Future 化的异步 InputConnection 代理 |
| `com.sogou.bu.basic.ic.d` | classes2 | `extends BaseInputConnection implements e`（TextView 场景，日志 tag `"KeyboardInputConnection"`） |
| `com.sogou.bu.basic.ic.a` | classes2 | `abstract class a implements e`，全部输入法方法空实现 |
| `com.sohu.inputmethod.input.api.*` | — | `a`(153 B)/`b`(373)/`candidate/a`/`compose/a`/`data/*`/`fkey/*`/`listener/{a,b}`：输入 API 契约 |
| `com.sohu.inputmethod.flxbridge.e` | — | Flex/Flx 桥（commitText 9 处） |

**提交链路（静态推断）**：候选点击 → 候选视图回调 → `InputLogic` → `SogouInputConnectionManager.b.commitText` → 真实 `InputConnection.commitText` → 目标 App。
（`commitText` 的实际首发点与 `u.java` 的角色待 A 组报告确认，运行时未验证。）

---

## 8. 表情层 `com.sogou.expressionplugin` 【D】

包结构：`banner, base, bean, doutu, emoji, eventbus, expression, handler, impl, net, pic, pingback, symbol, sync, sys, ui, utils, video, wacher` + 顶层 `ExpressionSPage.java`、`MainExpressionActivity.java`、`MyApplication.java`。
与模块参考实现相关、需逐一验证的类：`com.sogou.expressionplugin.emoji.NoScrollViewPager`、`com.sogou.expressionplugin.emoji.EmojiKeyboardView`（含内部类 `EmojiKeyboardView$e`）、`com.sogou.expressionplugin.expression.ExpressionUtil`。
（详细结构、`/sogou/emojipackage` 目录规则、`ExpressionUtil.u(AssetManager,String,String)` 是否存在 → 见报告 6/8「表情数据/面板结构」。）

---

## 9. 主题层 `com.sogou.theme`（**不用 hook 也能改外观的备选路线**）【D】

子包：`adapter, api, asset, bean, common, component, constants, data, install, installchecker, interfaces, loginfo, manager, net, network, newinstall, operation, paidfont, parse, service, setting, settings, shortvideo, state, strategy, themecolor, ui, util, utils, viewmodel`。
核心数据类：`com.sogou.theme.data.keyboard.c<T extends com.sogou.theme.data.key.a> extends s<Integer, e<T>> implements com.sogou.theme.component.i`（20,704 B）：`ArrayList<T> w`（行）、`List<com.sogou.theme.data.key.a> x`（键）、`com.sogou.theme.data.foreground.b p`（前景）、动画 `j v`。

资源格式（`apktool_out/assets/theme/`）：
- `thememanifest.ini`：`THEME_SET=theme_default,theme_game`；`theme_default/theme.ini`：`[ThemeInfo] VERSION=4 PROTOCOL=5`、`[Advance]` 键盘类型→布局 ini 映射（`PY=py_9.ini,py_26.ini,big_9.ini`、`EN`、`AB`、`12`、`EDIT`、`BH`、`HW`、`HWFULL`、`VOICE`、`WUBI=wubi.ini`、`CT`）、`[FOREIGN_COMPONENT]`；
- `theme_default/1080/port/py_9.ini`：`[Keyboard] W=1 H=0.591667 TOUCH_MODE=2 PADDINGS=... BG_IMAGE=Background ROWS=ROW1..ROW4`，`[ROW1] H_OFFSET=0.16204 KEYS=Key_PunctPY,Key_ABC,Key_DEF,Key_Backspace`，`[ROW4] KEYS=Key_SymbolTable,Key_Digits,Key_Space_py9,Key_SwitchToEN,Key_Enter`，`[Key] W=0.22037 H=0.24922`，`[Key_Backspace] W=0.16204 TYPE=2 F_FLAG=1 F_KEYS=,Key_Clear_Input`；
- `theme_default/1080/layout/{cands.ini 26,344, keys.ini 5,377, colors.ini 980, template.ini 86,301, image_list.ini 28,595, images_config.ini 31,934, MoreCandsContainer.ini, SymbolAndHWBihuaMoreCands.ini, config.ini, texts.ini}`；
- 自定义皮肤 `theme_custom/{mecSkinBlack.ssf 842,448, mecSkinWhite.ssf 719,297}`：`.ssf` 是 ZIP（`50 4B 03 04`，含 `phoneTheme.ini`）。

> **战略含义**：几何（键位/宽高/行高）完全声明化。理论上可以"造一套 iOS 布局的 theme 包"喂给搜狗引擎 —— 但按键绘制风格（圆角、阴影、字体、按下气泡）受 `template.ini`/`images_config.ini` 与组件绘制代码限制，无法做到 iOS 质感；**故本项目走"自绘 UI + 复用引擎"路线，主题仅作参考与兜底**。

---

## 10. 厂商适配层 `com.sogou.inputmethod.oem` 【D】

`OemServiceImpl`、`OemConfigImpl`、`OemAppServiceImpl`、`ScreenConfigImpl`、`BaseOemService`、`entry/a`（`SogouTinkerApplication.attachBaseContext` 调 `oem.entry.a.c().d(...)`）、网络开关连接器 `MiuiNetSwitchConnector` / `XiaomiNetSwitchConnector` / `TinkerNetSwitchConnector`、`api/b`（`a()` 门面，如 `getEdgeToEdgeBottomMargin()`）、`api/aspectj/ProtectedDataCheck`、`api/service/{IOemService,IOemAppService,IOemConfigService,...}`、`api/input/{symbol,cursorslide}`、`api/floatkeyboard`。
小米专属包 `com/sohu/inputmethod/sogou/xiaomi/` 只有 `SogouIME.java`（151 B）、`R.java`（2 MB）、`wxapi/*`。
另有 `com.sogou.inputmethod.oem.xiaomi.material.a`（`MainImeServiceDel:3381` 调用 `a().j()`，疑为 HyperOS 材质/模糊适配，运行时未验证）。

---

## 11. 与模块命名空间的对照（用户建议结构的落点）

| 用户建议的模块包 | 本分析中对应的搜狗侧类（hook 目标） |
|---|---|
| `hook.KeyboardHook` | `com.sohu.inputmethod.main.manager.b0#z(boolean)`、`com.sohu.inputmethod.ui.frame.NormalIMERootContainer#setKeyboardView(View)`、`com.sogou.core.ui.a#u(View)` |
| `hook.TouchHook` | `com.sogou.imskit.core.ui.virtualwidget.component.RootComponentView#onTouchEvent(MotionEvent)`、`com.sohu.inputmethod.input.KeyboardTouchEventHandler`、`com.sogou.imskit.core.ims.keyevent.KeyEventDispatcher` |
| `hook.CandidateHook` | `NormalIMERootContainer#setCandidatesView(View)`、`IMEInputCandidateViewContainer`、`manager.o#v()` |
| `hook.EmojiHook` | `NormalIMERootContainer#setKeyboardView`（表情面板以 z=3 装入）、`com.sogou.expressionplugin.*` |
| `hook.SogouAdapter` | 取活对象：`b0.f0().e0()/i0()/b0()`、`MainImeServiceDel.getInstance().getInputWindow()/getSogouInputConnection()` |
| 提交/落地文字 | `com.sogou.bu.basic.ic.g`（`l()`/`c()`）、`SogouInputConnectionManager.b` |

---

## 12. 待确认清单（禁止在未验证前编码）

1. `NormalIMERootContainer` 的 `dispatchTouchEvent` 经 AspectJ/Fluency 异步化后，hook 能否稳定命中（【R】）。
2. `s0.getRootView()` 真实返回类型是否恒为 `KeyboardRootComponentView`（【R】）。
3. `com.sogou.core.ui.a.u(View)` 是否在每次视图切换都被调用（【R】）。
4. `MainImeServiceDel.doOnStartInputView(EditorInfo,boolean)` 方法体（jadx 未反编译，需 smali）（【R】）。
5. `s0.t0(float,float)[I` / `u0(float,float)[I` 是否即"坐标→键位索引"（【R】）。
6. `com.sogou.bu.basic.ic.g` 的哪些方法可安全用于"模块直接提交文字"（【R】）。
7. 键盘组件树里"按键"对象的公开类型（`com.sogou.theme.data.key.*` 具体子类）与命中回调（由 A 组报告补充）。
8. `libSGMtlgShell.so` 是否含 Xposed/注入检测（【R】，静态不可判定）。
