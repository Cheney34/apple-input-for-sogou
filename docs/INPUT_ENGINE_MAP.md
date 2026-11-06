# INPUT_ENGINE_MAP — 输入引擎 / 候选数据流 / 上屏链路

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` 10.32.21.202607061714
> 唯一证据源：`analysis/jadx_out/sources/**`（jadx 反编译）、`analysis/apktool_out/**`（资源与 assets）、`analysis/dex_index.json`（DEX 索引，由 androguard 生成）。
> 行号 = 该 `.java` 文件中的物理行号。证据等级定义见 `CLASS_MAP.md` §0。

---

## §0 本文件的证据等级约定

| 标记 | 含义 |
|---|---|
| 【D】 | DEX 索引直接读出（类名/父类/接口/方法描述符），不会因反编译失败而丢失 |
| 【S】 | jadx 源码中直接可见（行号给出） |
| 【R】 | 同文档中的引用/推论（有调用点，但目标方法体未成功反编译） |
| **UNKNOWN** | 无法从静态分析确认，必须在 Phase 1 运行时验证 |

本轮**未做任何运行时验证**，因此所有"能替换/能注入"的结论都是候选方案，最终以 Phase 1 设备测试为准。

---

## §1 引擎总览：三层结构

```
┌──────────────────────────── UI 层（可被本模块替换）────────────────────────────┐
│ KeyboardRootComponentView(z3) / IMEInputCandidateViewContainer(z2) / 表情面板   │
│ —— 见 VIEW_HIERARCHY.md、EMOJI_PANEL_MAP.md                                     │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ 只读"拉取"，不持有数据
┌───────────────────────────────────┴───────────────────────────────────────────┐
│ 控制层：MainImeServiceDel / InputLogic / KeyboardTouchEventHandler            │
│         候选模型：presenter.a → model.a (List<CharSequence> + List<model.l>)   │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ IMEInterface（Java 门面）
┌───────────────────────────────────┴───────────────────────────────────────────┐
│ 引擎层：IMECoreInterface extends IMENativeInterface（~250 native）             │
│         原生库：libsogouime.so（System.loadLibrary("sogouime")）               │
└───────────────────────────────────────────────────────────────────────────────┘
```

- 原生库加载点【S】：`com/sohu/inputmethod/engine/f.java:423` → `System.loadLibrary("sogouime")`；外层壳 `libSGMtlgShell.so` 由 `com/typany/shell/LibraryLoader.java:12` 加载（加固/保护壳，行为 UNKNOWN）。
- 词典管理【S】：`com/sohu/inputmethod/engine/f.java`（日志 tag `"DictionaryManager"`）
  - `v()`：加载 `sogouime` 并把恢复标志写回 `com.sohu.inputmethod.sogou.recovery.b`（捕获 `NoSuchFieldError|NoSuchMethodError|UnsatisfiedLinkError`）。
  - `s(Context, IMEInterface)` = **引擎 open**：`iMEInterface.open(l.e1.getBytes(), l.z.getBytes(), ErrorTrace.CORE_LOG_SDCARD_PATH.getBytes(), l.O.getBytes(), com.sohu.util.o.e(ctx).j(2), (ForeignLanguagePackageManager.K(3)+"/core/dict/").getBytes(), SettingManager.t2(ctx).G(R.string.pref_dict_memory_opt,true))`，随后 `setParameter(66,…)`、`setLWPreLocalGearPosition`、`setParameter(60,…)`、`setLastAssocLayer`、`setParameter(77,privacyMode)`。
  - 系统词典 9 个 bin（`sgim_sys.bin`、`sgim_gd_bigram/cagram/nmode/asso_sc/asso_bs/abg/asso_bt`、`sgim_bh.bin`），扩展词典 43 个 bin（`sgim_py`、`sgim_aid`、`sgim_cf`、`sgim_cor`、`sgim_gd_em`、`sgim_lm(.9key)`、`sgim_pyslm`、`sgim_en_sys`、`sgim_ip`、`sgim_name`、`sgim_qr`、`sgim_sp`、`sgim_gd_wb`、`sgim_InputStr` …）。
- **对本项目的意义**：引擎完全藏在 `IMEInterface` 后面，输入法业务代码只跟 Java 层交互 ⇒ 只要不碰 `IMEInterface`/`IMECoreInterface` 的调用约定，替换 UI 不会损害候选质量。这是"搜狗负责输入、Chiron 负责体验"的结构性依据。

---

## §2 候选数据流（10 步，全部有调用点）

| # | 环节 | 证据 |
|---|---|---|
| 1 | 原生取一页候选文本 | `IMENativeInterface#getCandidatesNative(char[],int,int,int)`【S】`engine/IMENativeInterface.java:132`（参数名来自 AspectJ join point `outputChars:maxCandLength:maxCands:type`，见 `engine/IMECoreInterface.java:501`） |
| 2 | 取当前页候选条数 | `IMECoreInterface#getCandidateWordCount(int)`【S】`engine/IMECoreInterface.java:1322-1328` → `getCandidatesNative(this.mOutputChars, Opcodes.SHL_LONG, i, 2)` |
| 3 | NUL 分隔字符串解码 | `IMECoreInterface#splitCandidates(char[],int,List<CharSequence>,int)`【S】`engine/IMECoreInterface.java:762-778`（`com.sohu.util.g0.h(char[],int)` 逐条解码，`list.add(i3+i2, h)`） |
| 4 | 门面装配（文本 + 附加信息） | `IMEInterface#appendCandidateWords(List<CharSequence>,List<model.l>,int)`【S】`engine/IMEInterface.java:698-707` → `getUnCommittedText` + `getCandidateWordCount` + `splitNewCandidates`【S】`:526-561`（`com.sohu.util.h0.a()` 复用 `model.l` 对象池） |
| 5 | 唯一的生产者回调 | `com.sohu.inputmethod.presenter.a$f#d(List,List,int)`【S】`presenter/a.java:202` → `cVar.appendCandidateWords(list,list2,i2)`（全工程只有此处调用第 4 步） |
| 6 | 存进全局单例 | `presenter.a#k()`【S】`presenter/a.java:261` 返回静态 `h = new model.a(32)`【S】`presenter/a.java:12`；列表 `model.c.c`【S】`model/c.java:18`、附加信息 `model.a.m`【S】`model/a.java:10`；抓页入口 `model.a#s0()/u0()/t0()/a0()`【S】`model/a.java:24/38/608/280`，每次抓页后 `data.a#b(List,List)` 后处理【S】`input/api/data/a.java:10` |
| 7 | 服务层刷新 | `MainImeServiceDel#updateCandidateContainer()`【S】`sogou/MainImeServiceDel.java:9433-9438`：`handleInputFirstPage(); setStatus(refreshCandidates()); updateSuggestions(); updateCandidateCloud();`；`refreshCandidates()` → `base/h.java:289` → `IMEInterface#refreshCandidates`【S】`engine/IMEInterface.java:2260` → `IMECoreInterface#refreshCandidates`【S】`:3096` → `refreshCandidatesNative()`【S】`engine/IMENativeInterface.java:498` |
| 8 | 建议编排 | `InputLogic#updateSuggestions(boolean)`【S】`input/InputLogic.java:5422-5426`：`updateCandidateCodes(true); CloudMgr.z0().u(false); updateCandidateWords(true); updateComposingText();`（`updateCandidateWords` 本体未反编译，**UNKNOWN**） |
| 9 | 推给视图 | `AbstractImeServiceDelegate#showCandidateWordView(r2,int,boolean)`【S】`sogou/AbstractImeServiceDelegate.java:695-699` → `r2#Z7(getCandsInfo(), i, z)`；`getCandsInfo()` = `k3.a()`【S】`:226-228` = `presenter.a.k()`【S】`sogou/k3.java:5-7`；重载 `showCandidateWordView(r2,boolean)` → `r2#a8(data.b,boolean)`【S】`:701-705` |
| 10 | 视图接管数据引用 | `r2#Z7(data.b,int,boolean)`【S】`sogou/r2.java:2127-2160`：`bVar==null → b1.a()`、`dismissCandsViewDel(false)`、`isCandidatesListEmpty() → b1.a()`、`F7(); N7(bVar); e8(i,z); r6(); q6();`；`N7`【S】`:736-755` 只做一件事 `this.q1 = bVar;`【S】`:740` |

**关键结构结论**：候选栏是**纯拉取式渲染器**——`r2.q1`（`public com.sohu.inputmethod.input.api.data.b q1;`【S】`sogou/BaseCandidateComponent.java:105`）是它唯一的数据来源，且**只**在 `N7` 里被赋值。没有任何"List 被 push 进某个 Widget"的设计，也没有 per-candidate 的 View/TextView。

---

## §3 数据模型（命名全部是单字母混淆字段）

### §3.1 候选文本容器 `com.sohu.inputmethod.model.c` [classes4]【D】
`model/c.java`：
- `List<CharSequence> c = new ArrayList()`【S】:18 — 显示文本。
- `int f` — 页偏移基址（所有 `x(int)` 都做 `i2 = i - this.f`）。
- `int b` — 页大小；`util.b d`、`util.a e` — 分页/预取相关。
- `m0()` 返回 `c`【S】:114；`isCandidatesListEmpty()` = `c.size()==0`【S】:101；`b(List,List)` = `reset()` + `c.addAll(...)`【S】:139-144。

### §3.2 候选条（含附加信息）`com.sohu.inputmethod.model.l` [classes3]【D】
`model/l.java:4`，字段**全部无意义命名**【S】`:29-34`：
`A:boolean, B:CharSequence, a:int, c:int(@Deprecated), e:int, f:int, h:CharSequence, i:CharSequence, j:CharSequence, m:int, o:char, p:int, q:int, r:int, s:int, t:int, u:int, v:int, w:String, x:long, y:boolean, z:boolean`（+ 默认值 `k,g,d,b,n,l`）；`a()` 清空 `h/i/j/B` 与 `w`【S】:36。
⇒ 语义（注释/类型/词频等）只能从调用点反推，**UNKNOWN**，禁止在模块里臆造字段含义。

### §3.3 子类与注册表
- `com.sohu.inputmethod.model.a extends model.c` [classes4]【D】：`List<l> m`【S】`model/a.java:10`（与 `c` 平行）、`data.c k`【S】:8（抓页器）、`data.a l`【S】:9（后处理器）；`u()` 返回 `m`【S】:621。
- `com.sohu.inputmethod.model.d extends model.c`（组合/联想等其他用途，`presenter.a.j = new model.d(32)`【S】`presenter/a.java:14`）。
- `com.sohu.inputmethod.presenter.a` [classes3]【D】：静态 `h = new model.a(32)`【S】:12、`i = new model.a(32)`【S】:13、`j = new model.d(32)`【S】:14；抓页器 `b,c,d,e,f`（实现 `input.api.data.c`）在构造器 :221-225 绑定；`data.a` 安装点 `U(data.a)`；`h0(data.c)` 换抓页器。

### §3.4 对外门面（本模块应如何使用数据）
| 接口 | 关键成员 | 证据 |
|---|---|---|
| `com.sohu.inputmethod.input.api.data.b` [classes3] | `CharSequence a(int)` :60、`l q(int)` :125、`List<l> u()` :135、`List<CharSequence> m0()` :113、`isCandidatesListEmpty()` :96、`b(List,List)` :64、`U(data.a)` :48、`h0(data.c)` :90、`int w()` 页数、`int O()` 页偏移、`int A(int)` 绝对索引换算 |【S】`input/api/data/b.java` |
| `com.sohu.inputmethod.input.api.data.a` | `b(List<CharSequence>,List<l>)` 后处理钩子 |【S】`input/api/data/a.java:7-10` |
| `com.sohu.inputmethod.input.api.data.c` | 抓页器接口（`e/a/d/c` 方法） |【S】`input/api/data/c.java:7` |
| `com.sohu.inputmethod.input.api.data.d` | 预编辑：`StringBuilder d()`、`CharSequence g()/t()`、`m(CharSequence)`、`isEmpty()` |【S】`input/api/data/d.java:6` |

---

## §4 JNI 边界清单（本模块绝对不能替换的部分）

`native` 声明在全 APK 只出现在 **`com.sohu.inputmethod.engine.IMENativeInterface`** 一个类里【D】`q.py mfind ' native '` + `engine/IMENativeInterface.java:8`（`abstract class`，`private long mNativeContext`）。

与候选直接相关：

| native | 描述符 | 行 |
|---|---|---|
| `getCandidatesNative` | `(char[] cArr, int i, int i2, int i3) → int`（输出缓冲、每词最大长度、本页最大数、类型） | `engine/IMENativeInterface.java:132` |
| `refreshCandidatesNative` | `() → int` | :498 |
| `getCandidateInfo` | `(int,int) → int` | :126 |
| `getCandidateProbableNumNative` | `() → int` | :129 |
| `getComposingInfo` | `(int) → int` | :177 |
| `getComposingTextNative` | `(char[],int) → int` | :183 |
| `getComposingTextCursorFlagNative` | `(char[],int) → int` | :180 |
| `deleteCandidateWord` | `(int) → boolean` | :100 |
| `selectHWCandidate` | `(char[]) → void` | :528 |
| `getNextDigitCandidateCodeNative` | `(char[]) → int` | :253 |
| `isContextAwareAdjustCandidate` | `(int) → int` | :369 |

输入/学习/配置（同一类）：`handleInputNative(int,int,int)`（+ 两个重载）、`associateNative(String,int,int[])`、`learnWord(String,String,int)`、`learnSmileWordUser(String,int)`、`saveUserDict(byte[],boolean)`、`setParameter(int,int)`、`setModeNative(int)`、`setActiveMode(int,boolean)`、`deleteWord(int)`、`punctuationAdjust(char)`、`simToTradWord(String)`、`getTextPinyin(String)`、`assocBlackListFilter(String[])`。

Java 侧缓冲区【S】：`IMECoreInterface:354 mOutputChars = new char[5216]`；单页读取用 `16` 作为"每词最大长度"、`4`/`2` 作为类型参数（【S】`:608-614`、`:1322-1328`）。

---

## §5 候选栏视图结构（为什么它可以被整块换掉）

- 容器是**真 ViewGroup**：`public class IMEInputCandidateViewContainer extends RelativeLayout implements com.sogou.debug.d.a`【S】`main/view/IMEInputCandidateViewContainer.java:71`，构造器 :359，`initViews()` :1401-1467。
- 但它**只有一个真实子 View**：`ImeInputCandidateRootView extends com.sogou.imskit.core.ui.virtualwidget.component.RootComponentView implements com.sogou.input.ui.candidate.listener.a`【S】`sogou/input/ui/candidate/ImeInputCandidateRootView.java:7`，由 `addView(..., -1, -1)` 加入【S】`:1466`，内容组件 `setContentComponent(this.group)`【S】:1465。
- 组件链【S】：`com.sogou.input.ui.candidate.c :14 extends …virtualwidget.component.g` → `a :18` → `BaseCandidateComponent :36 implements Observer, Animation.AnimationListener, listener.a` → `r2 :46 extends BaseCandidateComponent implements d.a`。
- `initViews()` 装配的虚拟子组件【S】`:1406-1463`：`r2`（候选词条 :1416）、`NewIMEFunctionCandidateView`（功能/工具条 :1422）、`o2 mButtonsParent` :1425、`p2 mButtonSearch` :1428、`ClipboardFirstCandidateView` :1435、`com.sohu.inputmethod.sogou.p` :1436、`BusinessQuickPortal.a` :1439、`com.sohu.inputmethod.sogou.q` :1444、`GameCandidateView` :1448、`com.sogou.input.ui.candidate.d` ×3（左/右翻页、渐变 :1452/1456/1460）、`CandidateOperateView` :1463、`floatmode.a` :1406、`com.sogou.theme.operation.i`("ThemeCandOpView") :1411。
- **没有 per-candidate View**：候选文字由 `r2#O6(Canvas)` 直接绘制（【S】`sogou/r2.java:776`，805 条指令，**未反编译 → UNKNOWN**）；每项几何用 `com.sohu.inputmethod.layout.candidate.b{int a,b,c,d; boolean e;}`【S】`layout/candidate/b.java:4-9`，存在 `sogou/input/ui/candidate/b.java:8 public b[] s0`，由 `c4(int,int,int,int,boolean)` 生成【S】:21-35。
- 第二套同族候选条：`com.sohu.inputmethod.sogou.t2 extends BaseCandidateComponent`【S】`sogou/t2.java:26`（构造器 :54，覆写 `R5` :1015）= 横向候选编码条，`b0.f0().c0().A()`（`main/manager/o.java:243-245`，字段 `private t2 i` :42）。

### §5.1 三条可替换缝（详见 HOOK_POINTS.md）
1. **换整条**：`NormalIMERootContainer#setCandidatesView(View)`【S】`ui/frame/NormalIMERootContainer.java:579-591`（z=2，`Z_INDEX_CANDIDATES=2`【S】:36），或 `RootContainer#addChildView(View,int)`【S】`ui/frame/RootContainer.java:81-96`。
2. **换数据源（保留 Sogou 绘制）**：`presenter.a#k()` 替换 / `data.b#h0(data.c)` 换抓页器 / `data.b#U(data.a)` 装后处理器 / 在 `InputLogic#updateSuggestions` 之后对 `m0()` 做过滤。适用于"只改文案/只加 iOS 表情符号注入"。
3. **换推送入口**：`AbstractImeServiceDelegate#showCandidateWordView(r2,int,boolean)`【S】`:695`、`r2#Z7(data.b,int,boolean)`【S】`sogou/r2.java:2127`、`r2#N7(data.b)`【S】`:736`。

---

## §6 选中 → 上屏链路

```
用户点击候选
 └─ r2#v4(MotionEvent)            sogou/r2.java:2613   action==1 → z7((int)x,(int)y)
    └─ r2#z7(int,int)             :2746   A = q1.A(s1)+abs ; cs = q1.a(A)
       └─ this.r1.f(com.sogou.inputmethod.oem.config.c.e().h(h3.V().R(),A,cs), cs, x, y, null)   :2773
          └─ com.sohu.inputmethod.candidate.listener.d#f(int,CharSequence,int,int,String)   candidate/listener/d.java:246-248
             └─ d#b(...)      :84
                └─ this.g.pickSuggestionManually(i, cs)      :184   （this.g = InputLogic）
                   └─ InputLogic#pickSuggestionManually(int,CharSequence)   InputLogic.java:3666
                      └─ #pickSuggestionPinyin(int,CharSequence,boolean)    :863-869（未反编译，UNKNOWN）
                         └─ #pickSuggestion(CharSequence)   :3361
                            └─ sogouInputConnection.commitText(this.mCurrentPickSuggestion, 1)   :3433
```

- 选中回调接口是 `com.sohu.inputmethod.sogou.s`【D】`sogou/s.java:7`（内嵌 `interface a { boolean d(int,int,CharSequence,int,int,String); }`），**不是** `s0`。安装点【S】：`InputLogic#assemblePinyinMode()` `input/InputLogic.java:329` → `b0.f0().P().R5(getCandidateWordSelectedListener());` :334（另见 :262/270/357/374/391/1315）；`b0.f0().P()` = `main/manager/b0.java:786` → `main/manager/o.java:1048` → `private r2 g` `o.java:40`。
- `r1` 字段类型 `protected s r1`【S】`sogou/BaseCandidateComponent.java:107`，由 `public void R5(s)`【S】:444-446 注入。

---

## §7 预编辑（composing）

- 显示层【S】：`public abstract class BaseComposingView extends View`【S】`sogou/BaseComposingView.java:40`；实现类 `MainComposingView extends BaseComposingView implements Observer`【S】`sogou/MainComposingView.java:18`（只有 `(Context,AttributeSet)` 构造器 ⇒ 由 XML 膨胀，XML 位置 UNKNOWN）。
- 上屏【S】：`BaseComposingView#showComposing(data.d, InputConnection, int)` :1211 → `setComposingInfo(d)` :1213 → `inputConnection.setComposingText(mSpannedComposing, i)` :1219；另有 :1223-1241、:1306。
- 驱动【S】：`com.sohu.inputmethod.sogou.ComposeMgr` 单例 `u()` :146（字段 `private MainComposingView b` :24）：`T(InputConnection,boolean)` :311-319、`d0(data.d,boolean)` :429-437、`c0(data.d,String,boolean)` :389-404；数据源 `p()` = `k3.d()` = `model.g.f().d()` :606-608；`X()` :345-351 推给应用。`InputLogic#updateComposingText()` :5215-5228 → `ComposeMgr.u().X()`。
- ⇒ 若 iOS 风格要自绘预编辑条，hook 点在 `ComposeMgr#T/d0/c0`（保留 `setComposingText` 语义，只换视觉）。

---

## §8 提交层（本模块"只读体验"的兜底通道）

| 类 | 角色 | 证据 |
|---|---|---|
| `com.sogou.bu.basic.ic.g`【D】 | 输入连接核心接口：`int b()`, `boolean c(CharSequence,int)`, `void clear()`, `void d(String)`, `void e(BaseExpressionInfo)`, `InputConnection g()`, `void h(EditorInfo)`, `void j()`, `InputConnection l()`, `void n(e)`, `void o(e)`, `void u(h,boolean,c)` | `com/sogou/bu/basic/ic/g.java`（接口体 715 B） |
| `com.sogou.bu.basic.ic.b` / `ic.u`【D】 | `abstract class b implements g`；`u extends b` 是真正实现（1 线程池 `"SogouInputConnectionWrapper"`，字段 `volatile h a; volatile e b; c d; ExecutorService f;`） | `ic/b.java`、`ic/u.java` |
| `com.sohu.inputmethod.sogou.SogouInputConnectionManager`【S】 | 代理/装饰：`P()` 单例 :735、`Q(InputConnection)` :747、`a(InputConnection)` :383；内部类 `b implements InputConnection` 同步转发 `commitText` :442（并置静态标志 `s`）等；`i()` :554 取回底层 IC；`a0()` :? 生成调用栈诊断串 | `sogou/SogouInputConnectionManager.java` |
| `SogouIME.D()`【S】 | 每次取 IC：`ic.f.c().e(SogouInputConnectionManager.P().o0(super.c()), H(), new a())` | `sogou/SogouIME.java:501-502` |
| `SogouIME.getCurrentInputConnection()`【S】 | `u() || D().l()==null → null`；否则若为 `SogouInputConnectionManager.b` 则拆包 `.i()` | :584 |
| `sogou\u`【S】 | 统一上屏助手（含符号/配对/预编辑清理）：`Z()` 单例 :211-220、`C(CharSequence,int)` :413-521、`f(BaseExpressionInfo)` :1171（表情专用，旁路候选引擎） | `sogou/u.java` |

- **对本项目的意义**：所有最终文字都经过 `com.sogou.bu.basic.ic.g`。因此即便 UI 全替换，只要复用 `InputLogic#onText` / `sogou.u#C` 之一，就能保证"候选→上屏"与搜狗原生行为一致（联想、英文、符号自动配对、表情编码不变）。

---

## §9 可行性结论（候选/引擎侧）

| 诉求 | 结论 | 依据 |
|---|---|---|
| 保留引擎与词典 | ✅ 天然满足 | 引擎在 native + `IMEInterface` 门面之后，UI 层只调用门面（§1、§4） |
| 保留候选生成 | ✅ 天然满足 | 候选由 native 生成，Java 层只解码/装配（§2 步骤 1-4） |
| 保留上屏 | ✅ 天然满足 | 上屏统一走 `ic.g`（§8），可原样复用 |
| 整条候选栏换成 iOS 风格 | ✅ 有 3 条缝，风险由低到高见 HOOK_POINTS.md | §5.1 |
| 用 iOS 表情替换搜狗表情面板 | ✅ 见 EMOJI_PANEL_MAP.md 缝表 | 表情上屏走 `sogou.u#f`，与候选项同层 |
| 精确读取"候选词条的注释/类型" | ❌ 当前不可行 | `model.l` 字段全混淆，语义 **UNKNOWN**（§3.2） |
| 读取候选栏绘制细节（字号/间距/截断规则） | ❌ 当前不可行 | `r2#O6(Canvas)` 未反编译（§5） |

---

## §10 UNKNOWN（必须在 Phase 1 运行时确认）

1. `InputLogic#updateCandidateWords(boolean)`（:5194，929 条指令）与 `#pickSuggestionPinyin`（:863）未反编译 ⇒ 步骤 8 与步骤 15 的具体语句未逐行确认。
2. `r2#O6(Canvas)`（:776，805 条指令）未反编译 ⇒ 候选条实际布局/绘制算法未知。
3. `model.l` 的 `h/i/j/B` 与 `data.b#I()/N()/t()/C()/d0()/e()` 的真实语义未知。
4. `MainComposingView` 的 XML 宿主父容器未知（apktool 资源里未定位）。
5. 候选页滚动/翻页是否经 `model.a#s0()/u0()/t0()` 之外的路径（如云端候选）未知。
6. `SogouInputConnectionManager.b` 是否被二次包装（`ic.u` 与 `Manager.b` 的层序）未完全确认。
7. `sogou\u` 内 24 处 `commitText` 的触发场景对应关系未逐一梳理。
