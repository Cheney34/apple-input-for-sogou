# 06 · 表情面板结构与替换点（Emoji Panel Map）

> 目标 APK：`com.sohu.inputmethod.sogou.xiaomi` 10.32.21.202607061714
> 反编译产物：`analysis/jadx_out/sources/`（源码）、`analysis/apktool_out/`（资源）、`analysis/dex_index.json`（DEX 索引）
> 本文所有 `文件:行号` 均指上述产物内的真实位置；DEX 归属来自 `analysis/q.py show` 的索引查询。

## 0. 证据等级

| 标记 | 含义 |
|---|---|
| 【D】 | DEX 索引级确认（`q.py show` 给出的类描述符/方法签名，最权威） |
| 【S】 | 反编译源码级确认（jadx 源码可读，行号已复核） |
| 【R】 | 资源级确认（apktool 解包资源、assets 内容） |
| 【U】 | UNKNOWN，运行时或关键实现未能静态确认 |

**重要前置事实**：表情面板**不是** `imskit` 虚拟控件树（virtual widget）渲染的，而是**标准 Android View 树**（`RelativeLayout`/`FrameLayout`/`ViewPager`/`RecyclerView`）。这与键盘主体（`RootComponentView` + 组件树自绘）是两套完全不同的渲染体系 —— 这意味着表情面板的替换难度**显著低于**键盘主体，可以直接用真实 View 覆盖。

---

## 1. 打开路径（完整调用链）

### 1.1 触发：功能键 → 路由

【S】`com/sohu/inputmethod/sogou/fkey/c.java:24`
```java
public void d(int i, int[] iArr, boolean z, int i2, int i3, String str, boolean z2)
```
- `:50` `iExpressionService.setCurrentExpressionMode(0)` + `:51` `iMainImeService.showExpressionScreen(true, 0)` ← **case -30251**（表情）
- `:37-40` 其他 case → mode 1/2/3（-30252 = 颜文字/symbol? -30253, -30254）【U：各 case 与 mode 的精确对应仅部分确认】

【D】`com/sogou/sogou_router_base/IService/IMainImeService` → `showExpressionScreen(Z I)V`
【S】实现：`com/sohu/inputmethod/routerimpl/MainImeServiceImpl.java:1451` → `:1453` `b0.f0().T0(4, com.sohu.inputmethod.main.manager.j.n(z, i, false))`

### 1.2 管理层：b0 → j

【S】`com/sohu/inputmethod/main/manager/b0.java:891` `public boolean T0(int i, String str)`
- `:906` `this.j.L(str);`
- `:917` `cVar.c(this.b, cVar.d);`  ← `cVar` = `com.sohu.inputmethod.main.manager.c`，`this.b` = `NormalIMERootContainer`，`cVar.d` = `INPUT_VIEW_TYPE` 实例

【S】`com/sohu/inputmethod/main/manager/j.java:598` `public boolean c(NormalIMERootContainer, INPUT_VIEW_TYPE)` → 内部 `:273` `O(...)`
```java
// j.java:292-302
View expressionScreenView = a2.getExpressionScreenView(
        com.sogou.lib.common.content.b.a(), z, i2, F5, isFullScreenHW, b0.f0().N(), b0.f0().O());
if (expressionScreenView != null) {
    if (com.sogou.bu.basic.h.b) {           // 游戏模式分支
        b0.f0().o1(expressionScreenView);
        return true;
    }
    b0.f0().n1(false);
    b0.f0().e0().setKeyboardView(expressionScreenView);        // ← 面板作为 z=3 键盘视图装入
    b0.f0().w(MainImeServiceDel.INPUT_VIEW_TYPE.EXPRESSION_VIEW);
    return true;
}
return false;
```
> **关键结论 E1**：表情面板复用 `NormalIMERootContainer.setKeyboardView(View)`（z-index=3），与键盘本体**同一个槽位**，切换靠 `INPUT_VIEW_TYPE.EXPRESSION_VIEW` 标记。iOS 化时"键盘 ↔ 表情"切换就在这个槽位上做，天然互斥、无需自己管可见性。

### 1.3 工厂层：ExpressionServiceImpl → f → g → ExpressionKeyboardEmoji

- 【S】`com/sogou/expressionplugin/impl/ExpressionServiceImpl.java:271` `getExpressionScreenView(Context,boolean,int,int,boolean,int,int)` → `:272` `f.e0().a0(...)`
- 【S】`com/sogou/expressionplugin/expression/manager/f.java`（单例 `e0()`）→ `a0(...)`
- 【S】`com/sogou/expressionplugin/expression/g.java:133` `public View f(boolean z, int i, int i2, int i3)`
  - `i == 0` → `:161-163` `new ExpressionKeyboardEmoji(this)` → `:164` `this.p.f0(z)`
  - `:145` `ExpressionViewContainer` 创建，`:148` `setWidth(...)`，`:153` 设置 padding，`:170-171` `this.m.removeAllViews(); this.m.addView(f0);`
- 【S】`com/sogou/expressionplugin/expression/ExpressionKeyboardEmoji.java:1140` `new EmojiKeyboardView(this.r, this.T, this.t.g)` → `:1180` `this.v.initData()` → `:1187` `return this.v`
- 【D】返回类型确认：`Lcom/sogou/expressionplugin/expression/ExpressionKeyboardEmoji;->f0(Z)Landroid/view/View;`（classes2.dex）

### 1.4 备选入口（候选条上的表情按钮）

【S】`com/sogou/expressionplugin/expression/candidate/ExpressionFunctionCandidateView.java:191/197/203/208` → `showExpressionScreen(false, 0/1/2/3)`
（即：候选栏的"表情/颜文字"入口与功能键入口**汇流**到同一个 `IMainImeService.showExpressionScreen`，Hook 该路由方法可一网打尽）

### 1.5 关闭路径

- 【S】`main/manager/j.java:650` `r()` ← `b0.f0().s(4)` = `t(4,true)`（`b0.java:1533`；case 4 在 `:1596-1598`；`:1635` `e(root, type, false, z)`）
- 【S】`main/manager/c.java:79`（hide）→ `ExpressionKeyboardEmoji.p0()`（`ExpressionKeyboardEmoji.java:1221`，`:1227` 移除 overlay + `addChildViewToRootContatiner(null, 100)`）

---

## 2. 视图层级

```
NormalIMERootContainer (ui/frame, 继承 CornerRelativeLayout)
└── [z=3] ExpressionViewContainer        com.sogou.expressionplugin.expression.ExpressionViewContainer [classes2]
                                                    extends android.widget.RelativeLayout  【D】
    ├── m  : 内容容器（:170 removeAllViews / addView(f0)）
    │   └── EmojiKeyboardView           com.sogou.expressionplugin.emoji.EmojiKeyboardView [classes2]
    │                                               extends com.sohu.inputmethod.ui.CornerFrameLayout
    │                                               implements com.sogou.expressionplugin.expression.view.b  【D】
    │       ├── ExpressionBottomTab      com.sogou.expressionplugin.expression.ExpressionBottomTab:36 (RelativeLayout)
    │       │   ├── ExpBottomMenuRV      ui/view/bottom/ExpBottomMenuRV [classes3] extends RecyclerView  【D】
    │       │   │                        setChoosePos(I)V / setMenuData(List,int)  【D】
    │       │   ├── ExpressionTabSwitch
    │       │   ├── ExpressionTabImageView mCollectAndHistoryView (:60)
    │       │   └── tab.c mMoreView (:67)
    │       ├── NoScrollViewPager        com.sogou.expressionplugin.emoji.NoScrollViewPager [classes2]
    │       │                            extends androidx.viewpager.widget.ViewPager  【D】
    │       │   └── NormalEmojiGridView  (每页一个分组网格)
    │       ├── mEmojiDeleteIcon (View, :55)
    │       └── [overlay] FrameLayout w  (ExpressionKeyboardEmoji.java:1153-1154, visibility=8, attach 于 :1185)
    ├── 删除按钮容器 / popTip（setPopTipLayout, setDeleteButtonPosition …）
    └── 背景（dispatchDraw / reDrawBackground / updateBackgroundFromPhoneTheme）
```

【S】`EmojiKeyboardView.java:301` `ExpressionBottomTab` addView；`:313` `this.mEmojiViewPager.addOnPageChangeListener(new e(context));` `:314` pager addView。

---

## 3. 分组 / 页码模型

### 3.1 pager 的"页"= 分组 + 1

【S】`EmojiKeyboardView.updateMenuData()` `:743`
```java
:750  expressionBottomTab.setMenuData(iVar.o(), this.mPresenter.m());
:757  mCurrentViewPagerPosition = mPresenter.m() + 1;
:759  new ArrayList(mPresenter.o().size() + 1)
:760-761 synthetic com.sogou.expressionplugin.expression.b  (bVar.e = (ArrayList) mPresenter.e0())
:762  arrayList.add(bVar);          // 第 0 页 = 收藏/最近使用伪分组
:763  addAll(mPresenter.o());       // 之后才是真实分组
:764  mEmojiViewPagerAdapter.j(arrayList);
:766-769 setCurrentItem(mCurrentViewPagerPosition, false);
```
- 页 0 = **收藏 + 最近使用**（内容来自 `presenter.i.e0()`，`expression/presenter/i.java:1235`）
- 页 i(i≥1) ↔ 分组 `presenter.o().get(i-1)`
- `presenter.a.o()` `Lcom/sogou/expressionplugin/expression/presenter/a;->o()Ljava/util/List;`【D】、`m()I`（当前 tab 索引）【D】

### 3.2 选中事件

【S】`EmojiKeyboardView.java:152` `class e implements ViewPager.OnPageChangeListener`；`:168` `onPageSelected(int i)`
```
:170 mCurrentViewPagerPosition = i
:176 mPresenter.o().get(mCurrentViewPagerPosition - 1).d      // 越界风险点
:182 mPresenter.w(i - 1)
:199 mBottomTab.setChoosePos(i - 1)
:201 setCollectAndHistorySelected(true)   // i == 0
:202 mEmojiViewPagerAdapter.h(i).f(i)
:204 setCollectAndHistorySelected(false)  // i != 0
:207 mEmojiViewPagerAdapter.h(i).f(i)
:209-214 删除图标可见性（依 adapter.e().get(i).e）
```
- `adapter.h(int)` = `com/sogou/expressionplugin/expression/ui/viewpager/BaseExpressionViewPagerAdapter.h(I)`（`:85`），返回每页控制器 `emoji/adapter/item/a`，其 `f(I)` 在 `emoji/adapter/item/a.java:45`
- `ExpressionBottomTab.setChoosePos(I)` `:524`、`setCollectAndHistorySelected(Z)` `:539`；`ExpBottomMenuRV.setChoosePos(I)`【D】

### 3.3 滚动锁定：slide 旗标

【D】`Lcom/sogou/expressionplugin/emoji/NoScrollViewPager;`（classes2.dex，全文 40 行，已逐行复核）
```java
private boolean slide;                                  // :12
public NoScrollViewPager(Context c){ super(c); slide = true; }        // :16
public NoScrollViewPager(Context c, AttributeSet a){ super(c,a); slide = true; }  // :21
public boolean onInterceptTouchEvent(MotionEvent m){ return this.slide && super.onInterceptTouchEvent(m); } // :25
public boolean onTouchEvent(MotionEvent m){ if (this.slide) return super.onTouchEvent(m); return true; }    // :30
public void setScrollable(boolean z){ this.slide = z; }  // :37
```
`setScrollable` 的 4 个调用点（全在 `EmojiKeyboardView`）：
| 位置 | 值 | 语义 |
|---|---|---|
| `:111` | `true` | `showSlideAnimation()` 的 900ms 动画结束后恢复 |
| `:466` | `true` | `hideChooseAssembleView()`（退出拼装选择） |
| `:587` | `z` | `setRVCanScroll(boolean)` 透传 |
| `:642` | `false` | `showChooseAssembleView()`（进入拼装选择，禁止翻页） |
| `:700` | `false` | `showSlideAnimation()` 起始，配 `ValueAnimator.ofFloat(0,900)` 时长 `900L`（`:709`） |

---

## 4. 数据模型

### 4.1 分组 bean

【S】`com/sogou/expressionplugin/expression/b.java` implements `com.sogou.expressionplugin.ui.view.bottom.c`
| 字段 | 类型 | 含义 |
|---|---|---|
| `b` | String | type（`yellowface` / `combination` / `assemble` / `doutu` …） |
| `c` | String | name（英文标识） |
| `d` | String | chineseName（"黄脸"） |
| `e` | `ArrayList<BaseExpressionInfo>` | 该组表情项 |
| `f` | `List<AssembleEmoji>` | 拼装组专用 |
| `g` | boolean | — |
| `h` | int | id（3=黄脸、2=组合、-2=拼装） |
| `k` | String | 磁盘目录名（`<id>_<timestamp>`） |
| `l` | long | timestamp，`compareTo` 按其倒序（`:39-49`） |
| `n` | int | version |
| `o`/`s`/`t` | Drawable / String / String | 图标：Drawable / imagePath / assetsKey |

### 4.2 表情项 bean

【S】`com/sogou/sogou_router_base/base_bean/BaseExpressionInfo.java`
`unified`(:34) / `softbank`(:32) / `fileName`(:16) / `folderName`(:17) / `descOther`(:11 中文描述) / `descPinyin`(:12) / `isSupportMoreColor`(:28) / `currentColor`(:10) / `hasSelectedColor`(:25) / `isBuildIn`(:26) / `isFE0F`(:27) / `packageId`(:31) / `emojiList: HashMap<Integer,BaseExpressionInfo>`(:15 多肤色) / `groupEmojiText`(:21) / `groupEmojiRect`(:19) / `groupEmojiRectType`(:20) / `groupEmojiAnnotationText`(:18)。
> `unified` = Unicode 码位（`1F601`），`softbank` = 软银码（`58372`）—— **iOS 化只需 `unified`**，Sogou 同时维护两套是为了老协议兼容。

### 4.3 内置分组清单来源

【S】`expression/manager/f.java:650` `private List<b> Q0(Context)` → 解析 `context.getResources().getXml(k.c)`
【R】`k.c = 2131951628` = **`res/xml/preemoji.xml`**（解包后 81,414 B）
格式：
```xml
<expList version="1.0">
  <exppkg chineseName="黄脸" id="3" name="yellowface" type="yellowface" version="0">
    <exp FE0F="0" currentColor="0" hasSelectedColor="0" name="22557"
         other="[欣喜若狂的脸]" pinyin="xi'qi'..." softbank="58372"
         supportSkin="0" unified="1F601" />
  </exppkg>
</expList>
```
映射：`:667-673`（`b`：type/name/chineseName/version/id）、`:675-692`（`BaseExpressionInfo`：`fileName←name`、`softbank`、`unified`、`descOther←other`、`descPinyin←pinyin.split("\\|")`、`isSupportMoreColor←supportSkin=="1"`、`isBuildIn=true`）。
> **注意**：属性值在 AXML 中被 GBK 编码存储（jadx 反编译 `R.xml.preemoji` 引用时不可直接读中文）——若走"改主题/改资源"路线需处理编码，走 Hook 路线则直接操作 bean，无此问题。

### 4.4 磁盘（已安装/下载）分组

【S】`expression/d.java:144` `q = b + "/sogou/emojipackage/";`（`b = context.getFilesDir().getAbsolutePath()`，`:117`；异常回退 `"/data/data/<pkg>/files/"`，`:119`）
【S】目录命名 `expression/manager/f.java:423-424` `String str4 = str + "_"; String str5 = str4 + j;` → 目录 `d.q + str5`（`:442`，mkdirs `:444`）
- 下载组：`str = bVar.h + "_" + System.currentTimeMillis()`（`:1008-1027`）
- 组合组：`F(context, "2", j2, "combination", d.V, true)`（`:1035`）
- 拼装组：`F(context, "-2", ExpressionUtil.R(Integer.MAX_VALUE), "assemble", d.W, false)`（`:1044`），目录 `d.q + "-2_" + ExpressionUtil.R(Integer.MAX_VALUE)`（`:976`）
⇒ **实际目录名形如 `2_1700000000000`、`3_1700000000123`、`-2_2147483647`**
【S】枚举 API：`ExpressionUtil.m0()` `:2102` → `SparseArray<b>`（`new File(d.q).list()`，按 lastIndexOf("_") 切 id / 目录名 / 版本）；`ExpressionUtil.n0()` `:2148` → `ArrayList<b>`；`ExpressionUtil.l0(String,String,String)` `:2001` 读 `<dir>/info.xml`（`d.k0 = "info.xml"`，`d.java:189`）；`ExpressionUtil.o0(Set<String>)` `:2184` 列的是 **`new File(d.F)`**（`d.java:161` = `<sdcard>/sogou/expression/`），**不是** emojipackage。

### 4.5 内置资产格式（`apktool_out/assets/`）

| 资产 | 大小 | 格式 | 常量 |
|---|---|---|---|
| `exp_emoji_group` | 6,724 B | XML：`<exppkg type="combination" name="combination" chineseName="组合" id="2">` 内含 `<exp><![CDATA[{EMOJI1F4A2}…]]></exp><note>视力测试</note>` | `d.V`（`d.java:175`） |
| `assemble_emoji_info` | 136 B | XML：`<exppkg type="assemble" name="assemble" chineseName="拼装" id="-2"></exppkg>` | `d.X`（`:177`） |
| `pre_assemble_emoji` | 1,723 B | JSON 数组：`{"headEmoji":{"fileName":"22593","unified":"1F468"},"clothesEmoji":{"hasDouble":true,"topsEmoji":{…},"trousersEmoji":{…}}}` | `d.W`（`:176`） |
| `exp_symbol` / `exp_symbol_hot` | — | 颜文字/符号包 | `d.T` / `d.U`（`:173-174`） |

解包逻辑：`expression/manager/f.java:419 private boolean F(Context,String str,long j,String str2,String str3,boolean z)`，由 `E(Context)`（`:991`，仅由 `impl/ExpressionServiceImpl.java:475` 调用一次）触发，对 `d.V`（`:1035`）与 `d.W`（`:1044`，内部再解 `d.X`）执行；**受版本号门控**（`v0() < 1`、`w0() == 0`、`g0()`），**不是每进程必跑**（见 §7 断言 C 复核）。

---

## 5. 提交路径（点击 → 上屏）

```
NormalEmojiGridView item onClick
  → ExpressionKeyboardEmoji$a.onClick(View)                 ExpressionKeyboardEmoji.java:222
      :247 R.O(baseExpressionInfo)                          // 仅 presenter 记账
      :248 o0(unified)
      :252 iMainImeService.playKeyClick(...)                // 点击音效/震动
      :253 iMainImeService.commitBaseExpression(baseExpressionInfo)
  → IMainImeService.commitBaseExpression(BaseExpressionInfo)  (router 接口)
  → MainImeServiceImpl.java:392 → :393 u.Z().f(baseExpressionInfo)
  → com/sohu/inputmethod/sogou/u.java:1171  public void f(BaseExpressionInfo)
        :1194 String u = j.u(ctx, baseExpressionInfo)
                → main/manager/j.java:478 → a2.getEmojiCommitStringByExpressionUtil(...)
                → ExpressionServiceImpl.java:216-217 → ExpressionUtil.J(context, info, bundle, clientPackage)  (ExpressionUtil.java:953)
                  ⇒ 默认返回 t(info.unified)（即 unified 码位对应的字符串），并按 clientPackage/bundle 做兼容替换（:957-983）
        :1195-1197 学习：sogouInputConnection.d("\\u" + Integer.toHexString(softbank))
        :1198-1199 非内置组：sogouInputConnection.e(info)
        :1201-1209 上屏三选一：
              if (j.I(ctx,true))      j(u, true);                                  // 电商/特殊模式
              else if (com.sogou.bu.basic.h.a) t(u, 1);                            // 浮动/游戏模式
              else sogouInputConnection2 = mainImeServiceDel.getSogouInputConnection();
                   sogouInputConnection2.commitText(u, 1);                         // ← 常规路径（:1208）
        :1220 learnSmileWordUser(descPinyin[i], softbank)
        :1225-1226 collectUserInput("EX")
```
- 拼装组点击：`ExpressionKeyboardEmoji$b.onClick`（`:259`）→ `commitSymbolExpression(ExpressionSymbolItemInfo)`（`:275`）→ `MainImeServiceImpl.java:439` → `u.D(...)`
- 微信表情：message 109（`:189`）→ `commitWechatEmoji`
- 面板内删除键：`sendDownUpKeyEvents(67)`（`:133-138`、`:358`）

> **结论 E2（重点）**：表情上屏**不经**候选词引擎、**不经** `InputLogic`，而是一条直连 `SogouInputConnectionManager` 的旁路：`u.f()` → `getSogouInputConnection().commitText(str, 1)`。
> 因此：**自绘表情面板只需在"点击 → 提交字符串"这一层对齐**，无需触碰输入引擎、候选、联想任何状态机。这也解释了既有 Chiron Emoji 模块为什么可以在 commit 层做替换（见 §7）。

---

## 6. 对既有 Chiron Emoji 模块 4 项断言的独立复核

既有模块 `ref/chiron-apple-emoji/lsposed-sogou/src/com/chiron/iosemoji/` 的 `GestureHook.java`、`GroupFilterHook.java` 注释中记载了 4 组断言（A~D）。本轮独立复核结论：

### A —— 翻页旗标：**部分证实**
- ✅ 证实：`NoScrollViewPager` 全文 40 行与断言一致（`slide` 字段、两构造器置 `true`、`onInterceptTouchEvent`/`onTouchEvent` 门控、`setScrollable(Z)V`）。
- ❌ 断言中的 `showSlideAnimation()` **不在** `NoScrollViewPager`，而在 `EmojiKeyboardView.showSlideAnimation()`（`:695-712`）：`:700` `setScrollable(false)` → `mPresenter.v(false)` → `ValueAnimator.ofFloat(0f, 900f)` `setDuration(900L)`（`:709`）→ 在 `AnimatorListener b.onAnimationEnd`（`:100-125`）`:111` `setScrollable(true)` + `:114` `mPresenter.v(true)`。
⇒ 原模块 Hook `EmojiKeyboardView.showSlideAnimation` 替换为 `XC_MethodReplacement.DO_NOTHING` 是**可行且正确**的拦截点（但会同时失去 presenter 的滚动状态回调，需自行补 `mPresenter.v(true)`）。

### B —— 页切换链：**证实**（一处索引细化）
- ✅ `initNormalEmojiGridView(Context)` `:304` → `new NoScrollViewPager(context)` `:305` → `new EmojiViewPagerAdapter(...)` `:306` → `setAdapter` `:312` → `addOnPageChangeListener(new e(context))` `:313` → `addView` `:314`；调用者 `initView` `:317`（`:332` 处调用）。
- ✅ `EmojiKeyboardView$e.onPageSelected(int)` `:168` → `mBottomTab.setChoosePos(i-1)` `:199` → `setCollectAndHistorySelected(i==0)` `:201/:204` → `adapter.h(i).f(i)` `:202/:207`。
- ⚠️ 细化：页 0 **不是**普通 tab 页，而是"收藏+历史"伪分组（内容 `presenter.e0()`），其选中态由独立的 `mCollectAndHistoryView`（`ExpressionBottomTab.java:60`，`setCollectAndHistorySelected` 在 `:539`）渲染；页数 = 分组数 + 1。

### C —— 分组资源提取：**部分证实 + 1 处驳回**
- ✅ `ExpressionUtil.u(AssetManager,String,String)` `:2631` 存在（asset→file，delete+mkdirs+1024B 循环拷贝），emoji 组封面调用点 `expression/manager/f.java:455`、`:1022`。
- ✅ `ExpressionUtil.m0()` `:2102` / `n0()` `:2148` 确实枚举 `new File(d.q).list()`（emojipackage 目录）。
- ❌ `ExpressionUtil.o0(Set<String>)` `:2184` 枚举的是 `new File(d.F)`（`<sdcard>/sogou/expression/`），**不是** emojipackage。
- ❌ "`w1()` 每次进程启动重解压 `assets/exp_emoji_group` + `assets/assemble_emoji_info`" —— **驳回**：`ExpressionUtil` 中不存在 `w1`；`w1` 实为 `expression/manager/f.java:909`（拷贝各组封面 PNG + 重写 `info.xml`）；实际解包是 `f.F(...)`（`:419`），入口 `f.E(Context)`（`:991`）**仅被 `ExpressionServiceImpl.java:475` 调用一次且受版本号门控**。
⇒ 原模块"伪装 `ExpressionUtil.u` 已解包完成"的 Hook 只对**封面 PNG** 生效，对组数据本身无效；隐藏内置组的**真正有效钩子**是 `File.list()` 过滤 + `presenter` 列表过滤（见 §7 表 F/H）。

### D —— 目录命名：**证实**
- ✅ `d.q = filesDir + "/sogou/emojipackage/"`（`d.java:144`）；目录名 = `<id>_<timestamp>`（`f.java:423-424`、`:442`、`:1008-1027`、`:976`、`:1035`、`:1044`）。
- ✅ 既有模块 `GroupFilterHook.BLOCKED_PREFIX = {"2_", "-2_"}` 的匹配方式**成立**（组合组 id 2、拼装组 id -2）；但 `"2_"` 前缀会同时命中"以 2 开头的下载组 id"吗？——不会，因为下载组 id 从 3 起（`preemoji.xml` 内置组 id=3 黄脸），且 `-2_` 需单独匹配。**风险**：若用户安装了 id 以 2 开头的第三方组（未观察到），会被误杀。【U】

---

## 7. 替换点候选表（按风险升序）

| # | Hook 目标（DEX 签名） | 位置 | 用途 | 风险 / 备注 |
|---|---|---|---|---|
| F | `com.sogou.expressionplugin.expression.presenter.a#o()Ljava/util/List;` 【D】返回过滤副本 | `expression/presenter/a.java:130`（消费点 `EmojiKeyboardView.java:750`、`:759-764`） | 隐藏/重排分组（含"组合""拼装"） | **最低风险**。必须返回**稳定**的过滤副本，并保持 `m()` 索引一致；`onPageSelected` 会 `mPresenter.o().get(pos-1)`（`:176`），列表抖动 → `IndexOutOfBoundsException` |
| H | `com.sogou.expressionplugin.expression.ExpressionUtil#m0()` / `#n0()` 【D】 | `:2102` / `:2148` | 过滤磁盘下载组 | 中低。返回 `SparseArray`/`ArrayList` 的新实例，勿改原对象 |
| G | `com.sogou.expressionplugin.expression.manager.f#Q0(Context)`（`private`，需 `findAndHookMethod` + 参数 Class 精确匹配） | `:650` | 在内置组物化前过滤 `preemoji.xml` 结果 | 中。private 方法，混淆名稳定但跨版本易变；需 try/catch 回退 |
| B | `com.sogou.expressionplugin.emoji.NoScrollViewPager#setScrollable(Z)V` 【D】 + `EmojiKeyboardView#showSlideAnimation()V` 【D】 | `:37` / `:695` | 恢复左右滑动切组（iOS 手势） | 低（既有模块已验证此路可行）。注意 `showSlideAnimation` 替换后需自行恢复 `setScrollable(true)` 与 `mPresenter.v(true)` |
| A | `com.sogou.expressionplugin.expression.ExpressionKeyboardEmoji#f0(Z)Landroid/view/View;` 【D】 after-hook 替换返回值 | `ExpressionKeyboardEmoji.java:1137`（返回 `:1187`） | 整体替换表情面板 = 自绘 iOS 面板 | **高收益 / 中高风险**。`EmojiKeyboardView` 同时是 `com.sogou.expressionplugin.expression.view.b` 的实现者，被 presenter `i` 反向调用：`v.updateMenuData()`（`:139-147`）、`v.showSlideAnimation()`（`:148-153`）、`v.setRVCanScroll(false)`（`:306`）、`v.getPresenter()`（`:1148`）、`v.initData()`（`:1180`）、`p0()` 从 `v` 上摘 overlay（`:1230`）。⇒ 若替换返回值，必须**保留原 `EmojiKeyboardView` 实例**（只替换其内部 pager/grid），或完整实现 `view.b` 接口 |
| A' | 同上，改为 after-hook 在 `ExpressionViewContainer` 内 `m` 容器上做 "原视图隐藏 + 自绘视图叠加" | `expression/g.java:170-171` | 折中方案：保留 Sogou 面板存活，只做视觉覆盖 | **推荐**：规避 A 的接口反调风险，同时满足"iOS 视觉"。代价是 Sogou 面板仍在后台绘制（可 `setVisibility(GONE)` 但保留实例） |
| C | `com.sogou.expressionplugin.emoji.EmojiKeyboardView#setEmojiClickListener(Landroid/view/View$OnClickListener;)V` 【D】 包装入参 | 安装点 `:1142` | 点击上屏换成自己的提交逻辑 | 低。长按预览是**另外**三个监听（`setEmojiLongClickListener` `:204`、`setEmojiTouchListener` `:208`、组版 `:212/:216/:220`），只 hook click 会保留 Sogou 长按气泡 |
| D | `com.sogou.expressionplugin.expression.ExpressionKeyboardEmoji$a#onClick(Landroid/view/View;)V` | `:222` | 同上（更靠内） | 中高。内部类混淆字母（`a`）跨版本不稳定 |
| E | `com.sohu.inputmethod.sogou.u#f(Lcom/sogou/sogou_router_base/base_bean/BaseExpressionInfo;)V` | `sogou/u.java:1171` | 提交层最后一道闸（替换/改写上屏字符串） | 低风险、语义最强（既有的 `LogoHook` 思路同源）。缺点：只能改字符串，不能改交互 |
| I | `com.sohu.inputmethod.routerimpl.MainImeServiceImpl#commitBaseExpression(Lcom/sogou/sogou_router_base/base_bean/BaseExpressionInfo;)V` | `:392` | 路由层拦截（所有表情上屏唯一入口） | 低风险；应在 `u.f` 之前 |
| J | `com.sogou.sogou_router_base.IService.IMainImeService` 实现类 `MainImeServiceImpl#showExpressionScreen(Z I)V` | `:1451` | 打开的面板类型/时机拦截（表情/颜文字/斗图分流） | 低风险 |

---

## 8. iOS 化设计建议（面向 Round 2 实施）

1. **优先 A' 覆盖，而非 A 全替换**：`ExpressionViewContainer.m` 里放自绘 `ChironEmojiPanel`，把 Sogou 的 `EmojiKeyboardView` `setVisibility(INVISIBLE)` 但**保留实例与生命周期**（避免 §7-A 的反调崩溃）。自绘面板直接读 `presenter`（`i.o()` / `i.e0()`）拿分组与表情 bean。
2. **数据只经由 `BaseExpressionInfo.unified`**：iOS 表情视觉用 `unified` → 系统 Emoji 字体或 Chiron 自带字体渲染；`softbank` 仅用于回写学习接口（`:1197`），不参与显示。
3. **提交统一走 `u.f(bean)`（#E）或 `commitBaseExpression`（#I）**：保证与 Sogou 学习、统计、`collectUserInput("EX")` 一致，不破坏引擎状态。
4. **分组列表两道过滤**：#F（内存 presenter 列表）+ #H（磁盘组），共同实现"隐藏组合/拼装、只留 iOS 分组"。
5. **手势**：若保留 Sogou 面板，用 #B 恢复 pager 滑动；若自绘，则用 `ViewPager2`/横向 `RecyclerView` 自行实现，无需碰 `slide` 旗标。
6. **键盘/表情切换**由 `INPUT_VIEW_TYPE.EXPRESSION_VIEW` 与 `setKeyboardView` 槽位天然互斥，切换动画可在 `NormalIMERootContainer.setKeyboardView` after-hook 里叠加（见 `VIEW_HIERARCHY.md` §5 S2）。

---

## 9. UNKNOWN 清单

1. `ExpressionKeyboardEmoji.f0(Z)` 的 `z` 参数语义（首帧/复用？）—— 静态可见仅传入 `j.n(...)`/`g.f(...)` 的实参，含义未确认。
2. `-30252 / -30253 / -30254` 三个功能键 case 与 `mode 1/2/3`（颜文字/斗图/图片）的精确映射。
3. `EmojiKeyboardView` 的 overlay `FrameLayout w`（`:1153-1154`）用途（推测为长按预览/更多肤色浮层）。
4. `presenter.o()` 返回列表的可变性与调用频次（是否每次翻页重建）—— 决定 #F 过滤实现的缓存策略。
5. 内置组 id 全集（`preemoji.xml` 共多少 `<exppkg>`，各 id）—— 需解析 81KB AXML（GBK 属性），本轮仅确认 id=3 黄脸存在。
6. `com.sogou.bu.basic.h.b`（游戏模式）与 `.a`（浮动模式）两个全局旗标的置位条件 —— 决定表情面板是走 z=3 槽位还是 `o1()` 独立浮窗。
7. `setRVCanScroll(Z)`（`:587`）的外部调用者。
8. 面板打开时 `j.java:299` 是否每次都执行（`com.sogou.bu.basic.h.b` 为真时走 `o1` 分支）—— 需运行时验证。

---

*本文件为第 6 项交付物（表情数据结构与面板结构）。相关文件：`CLASS_MAP.md`（第 2 项）、`VIEW_HIERARCHY.md`（第 3 项）。*
