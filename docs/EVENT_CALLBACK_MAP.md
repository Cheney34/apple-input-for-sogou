# EVENT_CALLBACK_MAP — 触摸/按键事件回调链 + IME 生命周期回调

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` 10.32.21.202607061714
> 证据源：`analysis/jadx_out/sources/**`（行号为物理行号）；DEX 描述符以 `analysis/q.py show` 为准。
> 本文件是本项目中"事件层"的唯一权威记录：**任何自绘键盘若要产生文字，必须在本文件列出的回调点上对接**，不得臆造新的按键 API。

---

## §0 一句话结论

搜狗键盘**不是 View 树**，而是 `imskit` 虚拟组件树渲染在一张 `View`（`KeyboardRootComponentView`）上。因此"按键事件"的起点是 `RootComponentView#onTouchEvent`，终点是 `com.sogou.bu.basic.ic.g#commitText`。中间是一条 15 跳的固定链路（§3），其中 **3 跳的方法体 jadx 未能反编译**（§8），但每一跳的**调用点与签名都是可证的**。

---

## §1 触摸事件的物理入口

| 层 | 类/方法 | 证据 |
|---|---|---|
| 1 | `com.sohu.inputmethod.sogou.keyboard.KeyboardRootComponentView`（z=3 的键盘 View，**自身不覆写 `onTouchEvent`**） | 【S】`sogou/keyboard/KeyboardRootComponentView.java:11`（`extends …virtualwidget.component.RootComponentView`）；DEX【D】 |
| 2 | `com.sogou.imskit.core.ui.virtualwidget.component.RootComponentView#onTouchEvent(MotionEvent)` | 【S】:227-234 → `mAttachInfo.a(); mAttachInfo.l(event); boolean h1 = mDecorComponent.h1(event); mAttachInfo.b(); return h1;` |
| 3 | `mDecorComponent` = `com.sogou.imskit.core.ui.virtualwidget.component.k`（在 `initView` 中创建，:97）| 【S】`RootComponentView.java:93-102`；【D】`component/k.java`（`public final class k extends m`） |
| 4 | `k` **只**覆写 `e3`/`m1`，不覆写 `h1` → 落到 `component.h#h1(MotionEvent)` | 【S】`component/k.java:38-40, :43-44`；`component/h.java:655-700` |
| 5 | `component.h#h1`：DOWN 时 `f4(event)`（:328-346）重置命中目标；自上而下遍历子组件，可见性判定 `e4(g)=g.L2()`（:324-326），命中判定 `B4(x,y,g)=g.l3((f+this.w)-g.s,(f2+this.x)-g.u)`（:123-125）；命中者存 `q0`（:674-681） | 【S】`component/h.java` |
| 6 | 后续事件走 `h#p4(MotionEvent,boolean,g)`（:357-371）：先做坐标平移的副本，再 `gVar.h1(copy)` | 【S】同文件 |

> 与普通 Android View 的差异：`RootComponentView` 只做"把 MotionEvent 交给组件树"，坐标换算、命中、分发全在 `imskit` 组件层。**给自绘键盘挂 `onTouchEvent` 或 `OnClickListener` 都不可能触发搜狗输入**——必须走 §3 的调用点。

---

## §2 事件过滤器（可用的旁观/拦截点）

| 组件接口 | 用途 | 安装点 |
|---|---|---|
| `com.sogou.imskit.core.ui.virtualwidget.component.interfaces.d#a(g, MotionEvent)` | 组件级触摸**过滤器**：返回 false 即吞掉该事件（不进 `e3`） | 字段 `V.b`，由 `component.g#J3(interfaces.d)` 安装【S】`component/g.java:573-575`；实际安装方 `com.sogou.lib.bu.ui.component.b`【S】`lib/bu/ui/component/b.java:55`，返回 false 的实现 :93-96 |
| `com.sogou.imskit.core.ui.virtualwidget.component.interfaces.c#X(g)` / `#t(g)` | 组件被点击/长按？通知 | 安装于【S】`lib/bu/ui/component/b.java:54-55`、`keyboard/h.java:178,227`、`ui/keyboardswitch/h.java:63` |
| `RootComponentView#setOnGlobalHierarchyChangeListener(interfaces.e)` / `setOnGlobalStateChangeListener(interfaces.f)` | 全局层级/状态变化 | 【S】`RootComponentView.java` 末尾 |
| `NormalIMERootContainer#addViewTouchEventListener(ui.frame.i)` | 根容器级触摸（`b0.z()` 创建时即注册 `this.o`） | 【S】`ui/frame/NormalIMERootContainer.java:252`；创建处 `main/manager/b0.java:1782` |
| `NormalIMERootContainer#setGestureIntercept(a)` / `setDispatchEventView(View,int,int,boolean)` | 手势拦截 / 指定事件派发目标 | 【S】`:764` / `:594` |

---

## §3 完整链路（15 跳）

```
① RootComponentView#onTouchEvent            RootComponentView.java:227-234
②   → component.k（DecorComponent）→ component.h#h1       h.java:655-700
③     → component.g#h1                        g.java:1124-1131
       └─ 先跑 interfaces.d#a(g,event) 过滤器（field V.b）
④     → lib.bu.ui.component.keyboard.g#h1     g.java:1066-1077
         DOWN   → n8(event) :267-280  = s5(this.N0.U((int)x,(int)y,null))
         UP/CANCEL → this.d1.b4(false)
⑤       → （事件回传 super.h1 → component.g#e3）
⑥     → keyboard.g#e3(MotionEvent)            g.java:946-949
         → AspectJ 织入 com.sogou.bu.fluency.core.b.e().i(...) → synthetic z7(g,event,tjp) :303-309
         → gVar.N0.Z(event)
⑦   → com.sohu.inputmethod.input.KeyboardTouchEventHandler#Z(event)   KeyboardTouchEventHandler.java:1922-1963
       （主题先看：com.sogou.theme.parse.factory.a.s().a(event, getInputWindow().o())；再 a.o(event)、a.u(event)）
       → return h1(event) :1930-1943
⑧   → KeyboardTouchEventHandler#h1(event)     :2004-2110
       指针数 I0 :418；hover d1() :896 / 手势 c1() :781 / 手写笔 s1() :1138
       常规路径 → z = f1(event, action, false)  :2071
⑨   → #f1(MotionEvent,int,boolean)            :945   【方法体未反编译，3299 条指令】
       ├─ 长按：Handler msg 4 :176 → i1(event,boolean) :2112-2136
       │        → N0.onLongPress(s5.p0().Q(0), s5.p0().z0()) :2132
       ├─ 点击/按键：z0(int,int,int,long,boolean,int,int,boolean) :1560-1630（"detectAndSendKey"）
       │        K0.s5(i) :1566 → key = 布局里的键
       │        key.I0() != null → D1(...) :1567
       │        key.J0() != null → t1(NODE_TYPE_MASK_LOGICAL_OPERATION_MASK, int[], z, …) :1584
       │                         或 N0.onText(key.J0(), 128) :1586
       │        否则 code = key.Q(0) :1589 + 邻键数组 U(x,y,iArr)（数组长度 e1=12 :58）:1590-1592
       │        → t1(code, iArr, z, …) :1624
       ├─ 滑动/数字：w1 :1484-1505、x0 :1508-1553、u1 :1361-1397 → 均收敛到 r1(...)
       └─ 连发：Handler msg 3 :226-271 → q1() :1092-1100 → z0(...)；合成滑动码 r1(61808|61809,…) :1327-1330
⑩   → #t1(int,int[],boolean,int,int,int,int)  :1346-1352
       → r1(int,int[],boolean,int,int)        :1126-1136
         → N0.onKey(code, iArr, z, i2, i3)      或（K0.v5().b5() 时）N0.onNewKey(…)
⑪   → com.sohu.inputmethod.input.InputLogic#onKey(int,int[],boolean,int,int)   InputLogic.java:2768-2774
       【方法体未反编译】
       onNewKey 分支 → #onNewKey(int,int[],boolean,int,int,String) :2789-2791
                        → this.mNewKeyHandler.a(…)（mNewKeyHandler = new sogou.fkey.f() :152，getter :1803）
⑫   → com.sohu.inputmethod.sogou.fkey.f#a(int,int[],boolean,int,int,String,boolean)   fkey/f.java:173-195
       code ∈ [-30000,-39999] → talkback i.m().n().a(code, 0)
       其它 → i.m().l().onKey(code, iArr, z, i2, i3)   :182
              否则 m(…) :144-158 → 引擎处理器 h(code).d(…) 或 onKey(…)
⑬   → 文字生产（InputLogic 三个出口）
       #onText(CharSequence,int)      :2899-2905   if (i.m().n().onText(cs,i)) return;  u.Z().C(cs,i);
       #onText(CharSequence,boolean)  :2907-2925   getSogouInputConnection().commitText(cs, 1)  :2916
       #handleDigits(int)             :2088-2137   commitText(String.valueOf((char)i), 1)      :2121
⑭   → com.sohu.inputmethod.sogou.u#C(CharSequence,int)   u.java:413-521
       → H :759-761 → I :763-809
         长度 1 → 主服务 handleSymbolCode(charAt(0), i) :781-784 → InputLogic#handleSymbolCode(int,int) :2147→（上屏 :2235-2245）
         长度 >1 → sogouInputConnection.commitText(cs, 1) :796
⑮   → com.sogou.bu.basic.ic.g（真正上屏）
       取用点：MainImeServiceDel#getSogouInputConnection() :4959-4961 / InputLogic#getSogouInputConnection() InputLogic.java:668-673
```

**旁路（候选点选 / 表情上屏）**：`r2#z7 → s#f → candidate.listener.d#f → InputLogic#pickSuggestionManually → pickSuggestion → commitText`（`INPUT_ENGINE_MAP.md` §6）；表情：`ExpressionKeyboardEmoji$a.onClick → commitBaseExpression → sogou.u#f(BaseExpressionInfo) → ExpressionUtil.J → commitText`（`EMOJI_PANEL_MAP.md` §5）。

---

## §4 可复用的"程序化按键/上屏"入口（按可用性排序）

| # | 入口 | 描述符/位置 | 可用性 | 备注 |
|---|---|---|---|---|
| 1 | `com.sohu.inputmethod.input.api.a#onText(CharSequence,int)` | 接口 `input/api/a.java`；实现 `InputLogic.java:2899` | **高** | 框架自身文字入口；`commitType` 观察到 0/128/256/512；可达 `input.i.m().l()`【S】`input/i.java:29,240` |
| 2 | `sogou.u#C(CharSequence,int)` | `sogou/u.java:413` | **高** | 含符号自动配对/预编辑清理，语义最接近"用户敲了一个字" |
| 3 | `InputLogic#onText(CharSequence,boolean)` | `InputLogic.java:2907` | **高（裸）** | 直接 `commitText`，绕过 Sogou 的符号逻辑 |
| 4 | `KeyboardTouchEventHandler#y0(int,int,int,long,int,int,boolean)` → `z0(...)` | `KeyboardTouchEventHandler.java:1556-1558` / `:1560-1630` | **高（条件）** | 需键盘组件已膨胀（`K0` 由 `D(...)` 注入 :1671-1677）；"按键位序→按键"最完整 |
| 5 | `InputLogic#onKey(int,int[],boolean,int,int)` | `InputLogic.java:2768` | 中 | 参数 4/5 语义 **UNKNOWN**，需运行时验证 |
| 6 | `MainImeServiceDel#onKey(int,int[],boolean)` | `sogou/MainImeServiceDel.java:6311-6316`（→ `i.m().l().onKey(i2,iArr,z,0,0)`） | **高（绕过层）** | 跳过触摸层，直接按键 |
| 7 | `com.sogou.bu.basic.ic.g#commitText` | `SogouIME#getSogouInputConnection()` :4959 / `InputLogic#getSogouInputConnection()` :668 | **高（最裸）** | 完全绕过输入法逻辑，仅写文字 |
| 8 | `s0` / `com.sogou.lib.bu.ui.component.keyboard.d` | 【D】`sogou/s0.java`、`lib/bu/ui/component/keyboard/d.java:34` | ❌ | **没有**任何按键/文字注入方法，全部转发给组件 `g`（`K`→`g.U7` :414、`Q`→`g.q7` :535、`M0`→`g.G6` :464-498、`d0()`→`g` :684、`e0(int,CharSequence)`→`g.k7` :698-703；`t0/u0` 只是取键坐标 :1007-1026） |

**自绘键盘的推荐对接方式（Phase 3 验证目标）**：

- 字母/数字/符号键 → 走 #1 或 #4（保证联想、候选、自动大写均由引擎处理）。
- 功能键（退格/回车/空格/切换） → 走 #6 `MainImeServiceDel#onKey`，用它已有的事件码语义（键码表见 §5）。
- 表情/贴纸 → 走 `sogou.u#f(BaseExpressionInfo)`（与原生面板一致）。
- 兜底 → #7 直接 `commitText`（仅用于"故障降级"，不应作为常态，因为它不会更新搜狗自己的候选/学习状态）。

---

## §5 键模型（自绘键盘必须产出的数据结构）

- 键对象【D/S】：`com.sogou.theme.data.key.a<KeyComponent extends com.sogou.theme.component.c>` `sogou/theme/data/key/a.java:20`（`extends theme.data.view.n implements theme.component.i`）；持有 `r` = `com.sogou.theme.data.module.c`（码值/标签载体）、`N` 主题组件、`T` 长按弹层数组、几何 `P0()/Q0()`（由 `N.w1()` 算，:375-398）。
- 布局容器【D/S】：`com.sogou.theme.data.keyboard.c<T>` `theme/data/keyboard/c.java`：`w`=键列表 `ArrayList<T>` :45、`x`=`List<key.a>` :46、`j`=布局名 :35；查键 `c0(int code)` :495-507、`d0(int index)` :514-519、`k0()` :609、`e0()` 数量 :525-527、`b0()` 键盘名 :486-488。宿主 `sogou.keyboard.c<T>`（classes4）`protected final T t0` :19，`s5(int)=t0.d0(i)` :512-519。
- 替换 UI 需要复现的访问器（全部【S】`theme/data/key/a.java`）：`P()` 主码 :371-373、`Q(int)` 第 i 个码 :387-390、`R()` 码数量 :404-406、`J0()` 标签 :274-276、`I0()` 副标签 :190、`L0()` 长按数组 :310、`Y()` 弹层 :551-553、`p0()` 弹层键 :780-785、`z0()` :941-943、`n0()` 显示文本 :752、`d1(float,float)` 命中测试 :652-654、`S()` 主题组件 :417-420。
- 已观察到的键码：字母 97..122、数字 48..57、回车 10、空格 32、退格 -5、特殊 -46 / -66 / -1002 / -20。

> 结论：**自绘键盘不需要构造 `theme.data.key.a`**——只要把"用户敲了哪个字符/哪个功能码"翻译成 §4 的入口调用即可。`key.a` 只在"复用搜狗自己的按键派发（#4）"时才需要。

---

## §6 IME 生命周期回调（Hook 时机基础）

| 回调 | 声明处 | 说明 |
|---|---|---|
| `onCreate()` | `com/sogou/lib/spage/BaseInputMethodService.java:126-135` | 构造 spage context、`com.sogou.core.ui.a`（输入窗口宿主）、`base/spage/adapter/b` |
| `onInitializeInterface()` | `SogouIME` synthetic `T(...)` :332-339 → `MainImeServiceDel.onInitializeInterface()`；:6281 `recycleViews()`、:4150 `doWhenInitialInterfaceFinishEveryTime()` | 主题/词典初始化 |
| `setInputView(View)` | `spage/BaseInputMethodService.java:291-303` | **全界面态唯一入口**（收到的是 `StyleConstraintLayout`，内含 `NormalIMERootContainer`）——见 VIEW_HIERARCHY.md §3 |
| `onStartInput(EditorInfo,boolean)` / `onStartInputView(EditorInfo,boolean)` | `SogouIME` :960-969 → `V(...)` :348-385 → `super` → `MainImeServiceDel.onStartInputView` :6605+ → `doOnStartInputView` :4047（未反编译） | 每次弹出都会走；`doOnStartInputView` 里含 OTG 兜底 `createIMERootContainer(); b0.f0().w(KEYBOARD_VIEW)` |
| `onFinishInputView(boolean)` | `MainImeServiceDel.java:6161-6190` → `doOnFinishInputView` :6181 | 收起清理（含 `e0().clearAllChildren()`） |
| `onComputeInsets` | `MainImeServiceDel` :1318 附近（读 `getInputWindow().c().getHeight()`） | 自绘键盘必须自己接管高度，否则输入区遮挡 **（Phase 3 关键风险）** |
| `onWindowShown/onWindowHidden` | `spage/BaseInputMethodService`（DEX【D】） | 可用于启停渲染循环 |
| 按键回调 `onKeyDown/onKeyUp/onKeyMultiple` | `spage/BaseInputMethodService`（DEX【D】） | 硬键盘路径（软键盘不走这里） |

---

## §7 对本项目的事件层设计结论

1. **不要**试图 hook `View.onTouchEvent` / `OnClickListener` 去截获按键——键盘没有真实 View 子节点。
2. 自绘键盘 = 自己完全拥有触摸（因为 z=3 的 View 被我们替换），触摸结束后调用 §4 的入口。**这反而最简单**：不需要理解 `imskit` 的命中算法。
3. 需要"旁观"用户按键（例如统计/学习）时，用 §4 的入口包装或 `KeyboardTouchEventHandler#Z/h1` before-hook。
4. 需要"拦截"用户按键（例如 iOS 风格的长按符号）时，**先在自绘层处理**，未消费的事件再交给 #1/#6；不要试图在 `f1`（未反编译）里做文章。
5. 所有入口调用必须在主线程（`InputLogic`/`ic.u` 内部有线程池，但入口本身是主线程契约；`ic.u` 用 `ExecutorService` 串行化）。

---

## §8 UNKNOWN（Phase 1 必须验证）

1. `KeyboardTouchEventHandler#f1(MotionEvent,int,boolean)`（:945，3299 条指令）—— 点击/滑动/长按的确切判定阈值与优先级。
2. `InputLogic#onKey(int,int[],boolean,int,int)`（:2768）—— 参数 4/5 语义、返回值语义。
3. `U(int,int,int[])`（邻键数组生成，长度 12）与 `O0` 未解码。
4. `com.sogou.bu.basic.ic.g#commitText` 是否被 `SogouInputConnectionManager.b` 二次包装（层序）。
5. `sogou/u.java` 内 24 处 `commitText` 的具体场景对应。
6. 普通字母键的点击监听器由谁安装（`interfaces.c` 安装点只覆盖功能条/翻页键）。
7. `imskit` 组件树在键盘重建（`recycleKeyboard()`）后，`K0`/`N0` 引用是否失效（决定 #4 入口的稳定性）。
