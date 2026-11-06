# FEASIBILITY — 四大核心功能可行性报告

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` 10.32.21.202607061714（小米定制版）
> 基线工程：Chiron Emoji v6.4 / `chiron-apple-emoji/lsposed-sogou`（v1.8，versionCode 10，包名 `com.chiron.iosemoji`）
> 本文只做**静态可行性**判断；结论的证据源见同目录 `CLASS_MAP.md` / `VIEW_HIERARCHY.md` / `INPUT_ENGINE_MAP.md` / `EMOJI_PANEL_MAP.md` / `EVENT_CALLBACK_MAP.md`。
> **本轮没有任何运行时验证**，所有"✅ 可行"仅表示"静态证据充分、无阻断性障碍"，不代表已在设备上跑通。

---

## §0 总览

| # | 核心功能 | 可行性 | 主缝 | 最大风险 | 建议阶段 |
|---|---|---|---|---|---|
| 1 | iOS 风格键盘 UI 替换（外观 + 触摸 + 布局） | ✅ 可行（高置信） | `NormalIMERootContainer#setKeyboardView` + 自绘 View（实现 `s0`） | 输入区高度/insets（`onComputeInsets`）；长按/滑动手势需自行实现 | Phase 3 |
| 2 | iOS 风格候选栏替换 | ✅ 可行（中高置信） | `setCandidatesView(View)`（z=2）或 `r2#Z7(data.b,int,boolean)` | 候选附加信息字段全混淆（注释/类型不可读）；绘制算法未反编译 | Phase 4 |
| 3 | 长按符号 / 中英切换（iOS 交互） | ✅ 可行（中置信） | 自绘层实现 + `InputLogic#onText` / `MainImeServiceDel#onKey` | 键码/事件码语义部分 UNKNOWN（`f1`、`onKey` 参数） | Phase 5 |
| 4 | iOS 表情面板替换（含苹果 emoji 注入） | ✅ 可行（中高置信） | `EXPRESSION_VIEW` 槽（z=3 复用）+ `sogou.u#f(BaseExpressionInfo)` 上屏 | 表情面板被 presenter 反向驱动；原生 Assets 与磁盘分组需并行处理 | Phase 6 |

**总体判断**：本任务书的四大功能**在静态层面全部具备可实施的注入点**，且"搜狗负责输入、Chiron 负责体验"的分工在结构上成立——输入引擎（native + `IMEInterface`）、候选生成（`getCandidatesNative`）、上屏（`ic.g`）三者都位于 UI 层之下，与 UI 解耦。

**总体风险排序**（高→低）：输入区高度与 insets > 键盘重建后的对象失效 > 候选附加信息语义不明 > 表情面板反向驱动 > 手势与键码语义 > 主题/皮肤背景绘制。

---

## §1 功能一：iOS 风格键盘 UI 替换

### 1.1 目标
- 视觉：iOS 26 风格键帽、圆角、阴影、按压缩放、浅/深色自适应、符号层配色。
- 交互：整键点击、长按弹层（带模糊背景）、上下滑动切符号/数字、左右滑动移动光标、双击空格句号、全键盘 9 宫格/26 键布局切换。
- 约束：**不改变**搜狗的出词、联想、云候选、学习、按键音/振动策略（后者可选接管）。

### 1.2 静态证据（为何可行）
1. 键盘 View 是一个**独立可替换的 z=3 槽位**：`NormalIMERootContainer#setKeyboardView(View)`【S】`ui/frame/NormalIMERootContainer.java:946-985`，接受任意 `View`，只做 `addChildView(view,3)`，并对 `instanceof s0` / `instanceof KeyboardRootComponentView` 做两处样式分支。
2. 键盘的创建被收敛到**唯一工厂**：`com.sohu.inputmethod.ui.j#m()`（`ui/j.java:154-157`）→ `new com.sogou.lib.bu.ui.component.keyboard.d(...)` → `new KeyboardRootComponentView(...)`（`keyboard/d.java:61-63`）。所有后续重装（悬浮键盘 `g0.java:414/666`、表情面板 `manager/j.java:299`、`MainImeServiceDel.java:2326`）都是通过 `s0#getRootView()` 拿 View 再 `setKeyboardView`。
3. 键盘内容不是 Android View 树，而是 `imskit` 虚拟组件渲染在**一张** `View` 上（`RootComponentView extends View`【D】），因此"替换"等于"换掉这一张 View"，不存在"逐个键替换 View"的复杂度。
4. 键盘几何是**声明式**的（`assets/theme/theme_default/1080/port/py_9.ini`：`W=1 H=0.591667 PADDINGS=… ROWS=ROW1..ROW4`、`[Key] W=0.22037 H=0.24922`），说明高度与行高由主题配置驱动 ⇒ 我们的自绘层可以按同一比例体系布局，也可以不复用（见 1.4 风险）。
5. 文字注入路径有多个可证入口（`INPUT_ENGINE_MAP.md` §4 / `EVENT_CALLBACK_MAP.md` §4），因此自绘键盘**不需要**理解搜狗的触摸命中算法。

### 1.3 实施路径（静态可证）
```
Phase 3 目标拓扑：
  StyleConstraintLayout (搜狗)
   ├─ WindowBackgroundLayout   ← 保留（或用自己的背景层覆盖）
   └─ NormalIMERootContainer   ← 保留（候选条/工具条/insets 都靠它）
       ├─ [z=2] IMEInputCandidateViewContainer  ← 保留（Phase 4 再换）
       ├─ [z=3] ★ChironKeyboardView（自绘，实现 s0）
       └─ [z=6] KeyboardHeaderContainer          ← 保留（工具条，可选隐藏）
```
- 注入方式 A（推荐）：hook `NormalIMERootContainer#setKeyboardView(View)`，after-hook 里把我们自己的 View 以**同样的 z=3**再 `addChildView(view,3)`（覆盖原键盘），或 before-hook 里把参数换成我们的 View。我们的 View **实现 `com.sohu.inputmethod.sogou.s0`**（空实现 + `getRootView()` 返回自身），从而让 `instanceof s0` 的布局规则与 `checkSogouKeyboardViewExists()`（`index3 instanceof s0`）继续成立。
- 注入方式 B（更抗重装）：hook `com.sohu.inputmethod.ui.j#m()` / `#h()`，返回自定义 `s0` 包装器（转发所有成员到真实 `s0`，只把 `getRootView()` 换成我们的 View）。
- 触摸：我们的 View 自己实现 `onTouchEvent`（或 `dispatchTouchEvent`），命中自己的键位模型，**不经过 imskit**。
- 文字：字母/数字 → `com.sohu.inputmethod.input.api.a#onText(CharSequence,int)`（实现 `InputLogic.java:2899`）；功能键 → `MainImeServiceDel#onKey(int,int[],boolean)`；兜底 → `ic.g#commitText`。

### 1.4 风险与降级
| 风险 | 说明 | 降级方案 |
|---|---|---|
| **输入区高度/insets** | `onComputeInsets` 读 `getInputWindow().c().getHeight()`（`MainImeServiceDel.java:1318`）；自绘层高度若与搜狗配置不一致，会出现"应用被遮挡 / 键盘悬空 / 候选条被裁切" | 保持与 `py_9.ini` 的 `H=0.591667` 同比例；必要时 hook `onComputeInsets` 自行上报 rect（Phase 3 必须验证） |
| 键盘重建 | 悬浮/游戏/加载态会 `recycleKeyboard()` 后重新 `setKeyboardView`；我们的注入可能只生效一次 | 方式 B 免疫；方式 A 需挂 `addKeyboardViewChangedListener`（`setKeyboardView` 末尾会通知，:976-984）做看门狗重装 |
| `getKeyboardView()` 消费者 | 10 处消费者会拿到我们的 View（`b0.java:863`、`manager/d.java:45`、`BubblePage.java:37/40`、`MainImeServiceImpl.java:614/667`、`feedback/d.java:112`、`ui/u.java:101`） | 我们的 View 实现 `s0` 并保证所有方法不抛异常；`getHeight()/getKeyboardHeight()` 返回真实高度 |
| 键码/事件码语义 | `InputLogic#onKey` 参数 4/5 未反编译 | 优先用 `onText` 入口；功能键用可枚举的码值（退格 -5、回车 10、空格 32）先覆盖 90% 场景 |
| 主题/皮肤 | 搜狗换肤会重绘 `WindowBackgroundLayout` 与 `KeyboardBackgroundVirtualGif`；我们的层可能被主题覆盖或与皮肤冲突 | 首版禁用搜狗皮肤（配置项 `useSogouTheme=false`），自绘层不透明背景 |
| 振动/音效 | 搜狗按键反馈由内部实现 | 首版用我们自己的 `Vibrator`/`SoundPool`；如需原生策略，Phase 5 再桥接 |

### 1.5 验证方法（Phase 3 出口标准）
1. 小米 HyperOS 设备上，任意输入框弹出键盘 → 100% 显示 Chiron 键盘（截图对比 iOS 参考图）。
2. 26 键拼音输入 "nihao" → 候选条出现 "你好/你号…"（证明引擎链路未被破坏）。
3. 点选首个候选 → 输入框得到 "你好"，且搜狗词频学习生效（第二次输入 "nih" 时 "你好" 排名上升）。
4. 悬浮键盘、游戏键盘、从表情面板返回键盘 → 三次切换后仍是 Chiron 键盘。
5. 收起/弹出 10 次无崩溃、无内存持续增长（`dumpsys meminfo` 对比）。
6. 关闭模块（LSPosed 作用域取消）→ 完全恢复搜狗原生键盘（证明"失败即原样"）。

---

## §2 功能二：iOS 风格候选栏

### 2.1 目标
- 视觉：iOS 候选条（单行、居中/左对齐可配、无分隔线、按压高亮圆角、浅灰底）。
- 数据：完全复用搜狗候选（含云候选、联想、英文、符号）。
- 交互：左右滑动翻页、长按进入"候选解释/删除该词"（可选）、点击上屏。

### 2.2 静态证据
1. 候选条是根容器的**独立 z=2 槽**：`setCandidatesView(View)`【S】`:579-591`（`Z_INDEX_CANDIDATES=2`）。
2. 候选数据是**全局单例、纯拉取**：`presenter.a#k()` → `model.a`（`List<CharSequence> c` + `List<model.l> m`），视图侧只通过 `data.b` 接口读取（`INPUT_ENGINE_MAP.md` §2-§3）。⇒ 我们可以**完全自绘**，只要持有同一个 `data.b`。
3. 推送入口唯一：`AbstractImeServiceDelegate#showCandidateWordView(r2,int,boolean)`【S】`:695` → `r2#Z7(data.b,int,boolean)`【S】`sogou/r2.java:2127` → `N7` 里 `this.q1 = bVar`【S】`:740`。
4. 选中回调接口是 `com.sohu.inputmethod.sogou.s`【D】`sogou/s.java:7`（不是 `s0`），安装点 `InputLogic#assemblePinyinMode()` → `b0.f0().P().R5(...)`【S】`input/InputLogic.java:334`。⇒ 自绘候选条可以自己实现 `s` 或直接调用 `InputLogic#pickSuggestionManually(int,CharSequence)`【S】`:3666`。
5. 预编辑（composing）独立于候选条：`ComposeMgr` + `MainComposingView`（`INPUT_ENGINE_MAP.md` §7）。

### 2.3 实施路径
- 首选：hook `setCandidatesView(View)`，把参数换成 `ChironCandidateBar`（我们持有 `data.b` 的方式有两种：a) after-hook `showCandidateWordView` 记录下来；b) 直接调用 `presenter.a#k()` 取同一个单例）。
- 选中：调用 `InputLogic#pickSuggestionManually(int,CharSequence)`，或构造 `s` 实现并注册到 `b0.f0().P().R5(...)`。
- 翻页：`model.a#s0()/u0()/t0()`（【S】`model/a.java:24/38/608`）；如果这些入口在运行时不可用，降级为"只显示当前页 + 用 `data.b#w()/O()` 判断页边界"。

### 2.4 风险
| 风险 | 说明 | 降级 |
|---|---|---|
| 附加信息语义不明 | `model.l` 字段全为单字母（`h/i/j/B/…`），无法确认哪个是"注释/来源/类型" | 首版只渲染 `CharSequence` 文本；不显示注释 |
| 云候选/特殊候选 | 云候选、表情候选、剪贴板候选走同一容器的其他虚拟子组件（`NewIMEFunctionCandidateView`、`ClipboardFirstCandidateView`…） | 首版只替换主候选条 `r2` 对应区域；其它子条保留（可先用 `setVisibility(GONE)` 隐藏） |
| 翻页入口未验证 | `model.a#s0()` 等在实际输入中是否被调用未知 | 若不可用，只显示一页 + 让用户按"下一页"触发 `handlePageDown`（`IMEInterface.java:1794`） |
| 与功能条争空间 | z=2 容器里同时有功能条/工具条 | 自绘条固定高度（如 44dp），功能条保留可见 |

### 2.5 验证方法
1. "nihao" → 自绘候选条显示 5-9 个候选，顺序与原生一致（可通过开关切换原生/自绘对比截图）。
2. 点选第 2 个候选 → 上屏正确。
3. 输入英文 "hello" → 英文候选正常显示与上屏。
4. 左右滑动翻页 → 出现第二页候选（若降级则记录为已知限制）。
5. 长按拼音串 → 候选条清空（`isCandidatesListEmpty()`）且不崩溃。

---

## §3 功能三：长按符号 / 中英切换（iOS 交互）

### 3.1 目标
- 字母键长按 → iOS 风格符号/变音弹层（如 a → à á â ä æ）。
- 数字键长按 → 符号。
- 中英切换：iOS 的"地球键"式循环（中/英/表情），或单击切换、长按选择语言列表。
- 空格长按 → 单手键盘/输入法切换（iOS 行为）。

### 3.2 静态证据
1. 键对象模型完整且**语义可读**：`theme.data.key.a`（`P()` 主码、`Q(int)` 第 i 码、`R()` 码数、`J0()` 标签、`L0()` 长按数组、`Y()` 弹层、`p0()/z0()` 弹层键）——见 `EVENT_CALLBACK_MAP.md` §5。⇒ 我们**能读到**搜狗自身的"某键有哪些长按符号"，不必硬编码（但位置信息在 `theme.data.module.c`，需运行时确认）。
2. 切换语言/输入法的框架能力存在：`INPUT_VIEW_TYPE` 18 态（含 `KEYBOARD_SWITCH_VIEW`、`MUSIC_KEYBOARD` 等），`b0.w(type)`【S】`main/manager/b0.java:1698-1704` 是全界面态切换入口。
3. 文字/按键注入入口已具备（`onText` / `onKey` / `handleSymbolCode`），因此"长按弹层里点一个符号"= 调 `onText("à",0)` 即可。
4. 表情/语言切换的系统级能力：`com.sogou.inputmethod.oem.*`（`IOemService`/`IOemConfigService`）+ 搜狗自身的 `foreign`（多语言）/`flxbridge`（跨语言）包存在（`CLASS_MAP.md` §10）。

### 3.3 实施路径
- 长按符号：完全在自绘层实现（`ChironKeyboardView` 的长按 handler → 弹层 View → 命中 → `onText`）。**优先级最高**，因为它不依赖任何 UNKNOWN。
- 中英切换：自绘层"地球键" → 调 `MainImeServiceDel#onKey(码)` 或搜狗内部的语言切换方法（**UNKNOWN**，需 Phase 5 找入口；备选：模拟长按空格打开系统输入法选择器 `InputMethodManager.showInputMethodPicker()`）。
- 语言列表的读取：`foreign` 包（多语言）与 `fltlanguage` 相关类存在，但"当前已启用语言集合"的读取入口 **UNKNOWN**。

### 3.4 风险
| 风险 | 说明 | 降级 |
|---|---|---|
| 中英切换入口未知 | 搜狗的语言切换可能由内部 `InputLogic` 状态机驱动，无公开入口 | 首版实现"中文/英文"两态切换（我们自绘层直接改自己的键盘布局，文字仍走 `onText`）；语言列表由模块设置页手动配置，不问系统 |
| 符号表来源 | `assets/exp_symbol`、`exp_symbol_hot` 存在（`APK_OVERVIEW.md`），但符号与键位的映射关系未知 | 首版用自建 iOS 符号表（24 组、每组 ≤9 个），与搜狗无关 |
| 长按与滑动冲突 | 自绘层需自己实现"长按 350ms 才弹层，期间移动 > 阈值则取消" | 纯自绘层问题，无外部依赖 |
| 单手键盘/浮动键盘 | 对应 `FLOAT_ROOT_VIEW` 等状态，切换涉及 `b0.w(type)` + 布局参数 | Phase 7 再实现；首版禁用 |

### 3.5 验证方法
1. 长按 "a" 350ms → 出现 iOS 弹层，拖动到 "á" 抬起 → 输入框得到 "á"。
2. 长按空格 → 系统输入法选择器出现（或按配置不响应）。
3. 地球键循环：中文 → 英文 → 表情 → 中文，每次切换后键盘布局与候选行为对应正确。
4. 快速滑动（<350ms）不触发弹层，正常输入字符。

---

## §4 功能四：iOS 表情面板替换

### 4.1 目标
- 用 Chiron 的苹果 emoji 面板（v6.4 能力：10 个分组 PNG + payload 注入 + 分组过滤 + 左右滑动）**替代**搜狗原生表情面板的 UI，但**保留**搜狗的表情输入通道。
- 额外消灭搜狗内置的"组合(pg)"与"拼装"分组（v1.8 的 `GroupFilterHook` 已实现，需在本工程复核）。

### 4.2 静态证据
1. 表情面板**占用的是 z=3 键盘槽**：`manager/j.java:292-302`【S】`b0.f0().e0().setKeyboardView(expressionScreenView); b0.f0().w(EXPRESSION_VIEW)`。⇒ 它与键盘 UI 共用同一缝位，因此功能一与功能四的注入技术**是同一套**。
2. 面板实例由 `ExpressionKeyboardEmoji#f0(boolean)`（【D】返回 `Landroid/view/View;`）产出，`ExpressionServiceImpl.java:271-272` 调用；`EmojiKeyboardView` 由 `ExpressionKeyboardEmoji` 创建（:1140）+ `initData()`（:1180）。
3. 页模型与滑动门已确认：`NoScrollViewPager.slide` + `setScrollable`（`EMOJI_PANEL_MAP.md` §3）；分组数据来自 `res/xml/preemoji.xml`（81,414 B）+ `manager.f.Q0(Context)`（:650）。
4. 表情上屏**旁路候选引擎**：`ExpressionKeyboardEmoji$a.onClick` → `commitBaseExpression` → `sogou.u#f(BaseExpressionInfo)`（`sogou/u.java:1171`）→ `ExpressionUtil.J` → `commitText`【S】`:1208`。
5. 分组过滤的两个已实现缝：`ExpressionUtil.m0()/n0()`（列目录）与 `ExpressionUtil.u(AssetManager,String,String)`（资产解包）——v1.8 的 `GroupFilterHook` 正是 hook 这两处 + `File.list()`。
6. **对 v1.8 的两处修正**（本工程必须改）：
   - v1.8 注释称"`ExpressionUtil.w1()` 每进程重解压"——**该方法不存在**；真实解包在 `expression/manager/f.F(...)`（:419），入口 `f.E(Context)`（:991），仅 `ExpressionServiceImpl.java:475` 调用且受版本号门控 ⇒ 只 hook `u(...)` 不足以阻止重解压，需同时 hook `f.E(Context)` 或 `f.F(...)`。
   - v1.8 的 `o0(Set)` 认知需注意：`o0` 列的是 `d.F`（`<sdcard>/sogou/expression/`），不是 `emojipackage` 目录。

### 4.3 实施路径（两套并行，推荐 A'）
- **A'（推荐，风险最低）**：保留 `EmojiKeyboardView` 实例与数据链（这样 presenter 的反向驱动 `updateMenuData/showSlideAnimation/setRVCanScroll` 全部成立），只**隐藏原生 UI 子层**并把 Chiron 的 emoji 视图叠加/替换进去；滑动交给原生 pager（`setScrollable(true)` 已由 v1.8 `GestureHook` 打开）。
- **B（彻底）**：hook `ExpressionKeyboardEmoji#f0(boolean)` 返回 Chiron 面板 View。风险：presenter 会继续对 `EmojiKeyboardView` 调用 5 个方法，若我们把实例丢弃，可能出现 NPE 或无响应——必须保留实例（只不挂到视图树）。
- 分组过滤：迁移 v1.8 `GroupFilterHook`，但**补上** `expression/manager/f#E(Context)` / `#F(...)` 的 hook，确保"组合(pg)/拼装"不复活；`File.list()` 过滤保留作为兜底。
- 苹果 emoji 注入：沿用 v6.4 的 `assets/payload.zip` + `manifests/*.xml` 分组方案（`ref/chiron-apple-emoji/lsposed-sogou/tools/make_payload.py`）；需在本工程复核 `EmojiService` 的安装协议（CMD_INSTALL_ALL / INSTALL_CORE / INSTALL_GROUPS / RESTORE）。

### 4.4 风险
| 风险 | 说明 | 降级 |
|---|---|---|
| presenter 反向驱动 | `EmojiKeyboardView` 被 `presenter.i` 反向调用（`updateMenuData`/`showSlideAnimation`/`setRVCanScroll`/`getPresenter`/`initData`） | 始终保留真实实例（A' 天然满足） |
| 重解压复活分组 | 只 hook `u(...)` 不够（见 §4.2-6） | 同时 hook `f.E(Context)` / `f.F(...)`；保留 `File.list()` 过滤 |
| 分组 ID/目录约定 | 目录名 `<id>_<timestamp>`（`expression/d.java:144`） | 复用 v1.8 的 `manifests/` 目录前缀（`3_9000000010000`…`12_9000000008000`） |
| 表情上屏编码 | 苹果 emoji 用 `unified` 码点；搜狗用 softbank/`fileName` | 复用 v1.8 `sogou.u#f(BaseExpressionInfo)` 路径（已验证 commitText 在 :1208） |
| 面板高度 | 表情面板高度与键盘不同（`ExpressionViewContainer#setExpressionViewHeight`） | 自绘面板高度取 `ExpressionViewContainer` 上报值（Phase 6 验证） |

### 4.5 验证方法
1. 点表情键 → 出现 Chiron 面板（不是搜狗原生 12 宫格）。
2. 分组列表只剩 Chiron 的 10 组，无"组合(pg)"/"拼装"。
3. 左右滑动切组（v1.8 GestureHook 生效）。
4. 点 emoji → 上屏为该 emoji（微信/备忘录/浏览器三个目标各验一次）。
5. 进程重启（杀进程）后分组仍被过滤（验证 hook 时机覆盖所有 entry，v1.8 用 0/300/1200/3000ms 四次安装）。
6. 从表情面板返回键盘 → 键盘仍是 Chiron 键盘（两功能共存验证）。

---

## §5 跨功能结论

### 5.1 为什么"只做 UI 层"在结构上是安全的
- 引擎：native `libsogouime.so` + `IMENativeInterface`/`IMECoreInterface`/`IMEInterface`（`INPUT_ENGINE_MAP.md` §1、§4）——我们不触碰。
- 候选：`model.a` 全局单例 + `data.b` 门面——我们只读。
- 上屏：`com.sogou.bu.basic.ic.g`——我们复用。
- 我们的改动集中在三个 z 槽（2/3/1000）与三个数据入口。

### 5.2 统一的失败策略（用户硬性要求）
1. 所有 hook 在 `try/catch (Throwable)` 内安装，失败仅记日志，**不抛出、不改搜狗行为**。
2. 每个功能独立开关（SharedPreferences），关闭即不安装对应 hook。
3. 版本/兼容配置：`VersionProfile` 记录 `versionName`/`versionCode`/dex 校验，不匹配时降级为"仅日志模式"（不注入 UI）。
4. 我们的 View 任何异常 → 移除自身，让搜狗原键盘重新 `setKeyboardView`（或用 `addKeyboardViewChangedListener` 触发重装）。
5. 所有 hook 入口幂等（`AtomicBoolean` / `Set<String>` 记录已装 hook）。

### 5.3 需要 Phase 1 运行时验证才能定稿的项（跨功能）
1. `onComputeInsets` 的真实计算与我们的高度对接方式。
2. `InputLogic#onKey` 参数语义、`f1` 的点击/长按/滑动判定。
3. `model.a` 的翻页入口在真实输入中是否被调用。
4. `j.java:299`（表情面板进 z=3）是否每次打开都触发。
5. `recycleKeyboard()` 后 `K0`/`N0`/`s0` 引用是否失效。
6. `SogouInputConnectionManager.b` 与 `ic.u` 的层序（决定 #7 兜底入口是否安全）。
7. 小米 HyperOS 的输入法白名单/自启动策略是否影响 LSPosed 注入时机（UNKNOWN，设备相关）。
