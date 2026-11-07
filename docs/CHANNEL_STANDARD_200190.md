# 渠道分析 #2：搜狗官方版 20.19.0（`com.sohu.inputmethod.sogou`）

> Phase 2 产出。**未修改任何代码**，只做取证。
> APK：`base.apk` 98,337,108 B / SHA-256 `FC137C6B1AC5029A3751D15C0A605F31C0DB3029EA0A09B1BD9713F18BF67D05`
> 包名 `com.sohu.inputmethod.sogou` / versionName `20.19.0` / versionCode `2640` / minSdk 21 / targetSdk 30 / 7 dex / **60,763 类**
> 取证工具：`ChironIOSInput/analysis/probe_channel.py`、`probe2.py`、`probe3.py`（androguard 4.1.4）

---

## 0. 一句话结论

**这个渠道比预想的好得多**：皮肤层只需要换 2 个类名（`common.j` → `common.g`，且 `f` 参数少一个），
输入视图层有 1:1 对应物（`NormalIMERootContainer` → `com.sogou.bu.basic.SogouInputArea`），
而且 `setInputView` 全 APK 只有**一处**声明——比小米版的 3 跳链更干净。

⚠️ 修正审计 §8-②的措辞：说"普通版整层不存在"不准确。准确说法是「**架构同源，包名整体搬家**」：
`com.sohu.inputmethod.*` / `com.sogou.lib.*` 的大部分类搬到了 `com.sogou.bu.*` / `com.sogou.imskit.*`，
类名大多保留。所以适配工作量是「改名字」，不是「重写」。

---

## 1. 输入法服务链（实测）

```
android.inputmethodservice.InputMethodService
└─ Lcom/sogou/bu/ims/support/BaseInputMethodService;      ← 声明 setInputView(View)（全 APK 唯一）
   └─ Lcom/sohu/inputmethod/sogou/SogouIME;               ← 名字与小米版一致（不同包）
```

| 对比项 | 小米 10.32.21 | 官方 20.19.0 |
|---|---|---|
| IME 具体类 | `com.sohu.inputmethod.sogou.xiaomi.SogouIME` | `com.sohu.inputmethod.sogou.SogouIME` |
| IME 中间层 | `BaseSogouIME` → `com.sogou.lib.spage.BaseInputMethodService` | 无中间层，直接 `com.sogou.bu.ims.support.BaseInputMethodService` |
| `setInputView` 声明数 | 3（`spage` / `base.i` / `MainImeServiceDel`） | **1**（`com.sogou.bu.ims.support.BaseInputMethodService`） |
| `onCreateInputView` | 无 | **无**（同样把视图 push 进来） |

⇒ **H70（建议新增）：`com.sogou.bu.ims.support.BaseInputMethodService#setInputView(View)`**
在这一渠道等价于小米版的 H02 组合（既是"所有界面态的总入口"，又能拿到根容器）。

---

## 2. 皮肤层（可复用度最高）

| 环节 | 小米 10.32.21 | 官方 20.19.0 | 代码是否需要改 |
|---|---|---|---|
| ini 入口 | `com.sogou.theme.parse.utils.b#a(String) : parse.entity.b` | **完全一致** | ❌ 不用改 |
| 解析器 | `com.sogou.theme.parse.utils.c#n(BufferedReader)` | 同名存在（13 个方法） | ❌ |
| ini 载体 | `com.sogou.theme.parse.entity.b`（`a()/b()/c()`） | **完全一致** | ❌ |
| impl 抽象/实现 | `parse.impl.a` / `parse.impl.b` | 同名存在（5 / 2 个方法） | ❌ |
| **位图入口** | `com.sogou.theme.common.j#f(Context,String,Z,Z)` | **`com.sogou.theme.common.g`**：`f(Context,String,Z)`、`g(Context,String,Z,Z)` | ✅ 需要（类名 `j`→`g`，且 `f` 少一个参数） |
| 位图缓存 | `common.j#j()/g(...)` | 待确认（`common.g` 只有 3 个声明方法：`()V`、`()Z`、`(Z)V` —— 说明取图入口在**父类**或其它类，需再查） | ⏳ |

**对 H41 的影响**：H41 现在是「按方法名 `f/g/j/k/d/e` 对 `Targets.bitmapClass` 全部 hookAllMethods」。
只要把 `Targets.STANDARD.bitmapClass` 设成 `com.sogou.theme.common.g`，`f`/`g` 两个重载会被自动挂上，
**不需要改 H41 的代码**——这正是 beta2「按方法名全挂」设计的价值。

---

## 3. 输入视图层（有 1:1 对应物）

| 角色 | 小米 10.32.21 | 官方 20.19.0 |
|---|---|---|
| 输入区根容器 | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer`（`setKeyboardView` / `setCandidatesView` / z-index 子容器） | **`com.sogou.bu.basic.SogouInputArea`**（`setKeyboardView(View)` + `setCandidatesView(View)`，同类内） |
| 键盘外壳（`s0` 等价） | `com.sogou.lib.bu.ui.component.keyboard.d`（`implements s0`） | `com.sogou.bu.ui.keyboard.controller.b` / `.d`（`getRootView(): com.sogou.bu.ui.keyboard.KeyboardRootComponentView`） |
| 键盘根 View | `com.sohu.inputmethod.sogou.keyboard.KeyboardRootComponentView` | **`com.sogou.bu.ui.keyboard.KeyboardRootComponentView`**（同名不同包） |
| 候选容器 | `com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer` | **同名同包** ✅ |
| 候选根视图 | `com.sogou.input.ui.candidate.ImeInputCandidateRootView` | **同名同包** ✅ |
| 候选条 provider | `com.sohu.inputmethod.sogou.r2` + `AbstractImeServiceDelegate#showCandidateWordView` | 未找到（`showCandidateWordView` / `getCandsInfo` 全 APK **0 命中**）→ 需按 `CandidateViewListener` / `NewCandidateView` / `Toolbar#setCandidatesView` 继续追 |
| 虚拟控件引擎 | `com.sogou.imskit.*` | **`com.sogou.imskit.*` 3,679 类**（同名同包，含 `core/input/inputconnection/*`） |
| spage 框架 | `com.sogou.lib.spage.*` | `spage` 字样仍出现在 84 个类里，但 `BaseInputMethodService` 已在 `com.sogou.bu.ims.support` |

**对 H02 的影响**：`SetKeyboardViewHook` 现在写死 `NormalIMERootContainer`；
普通版要挂的是 `com.sogou.bu.basic.SogouInputArea#setKeyboardView(View)`。
⇒ 只要把类名搬进 `Targets`，逻辑可原样复用（同样 `setKeyboardView` + z 层级叠加）。

---

## 4. 文本提交层（差异较大）

| 项 | 小米 | 官方 20.19.0 |
|---|---|---|
| 实时 InputConnection | `SogouIME#getCurrentInputConnection()` + `com.sogou.bu.basic.ic.g#l()` | `SogouIME#getCurrentInputConnection()`（存在）+ `com.sohu.inputmethod.foreign/inputsession/l`；`com.sogou.bu.basic.ic.{c,m}` 声明 `commitText/setComposingText` |
| 文字注入入口 | `com.sohu.inputmethod.input.i#m()→l()→onText/onKey` | **`com.sohu.inputmethod.input` 只有 8 个类**，`InputLogic` 不存在 |
| 管理单例 | `com.sohu.inputmethod.main.manager.b0#f0()` | `main/` 包只有 72 类，`b0` 不存在 |

⇒ **T1（TextInjector）在官方版必须重做**，但可用的替代入口已经找到：
`com.sogou.imskit.core.input.inputconnection.*`（`CachedInputConnection` / `o1` / `p1` 都声明 `commitText`），
以及 `com.sogou.bu.basic.ic.m`。这条要和"键盘自绘"一起做，不阻塞皮肤层。

---

## 5. 表情层

| 项 | 小米 | 官方 20.19.0 |
|---|---|---|
| 表情工具 | `ExpressionUtil`（`u(AssetManager,String,String)`） | **`ExpressionUtil` 同名存在**（需验证 `u` 签名） |
| 翻页控件 | `NoScrollViewPager` | **同名同包** ✅ |
| 表情页 | `ExpressionKeyboardEmoji` / `EmojiKeyboardView` | 需确认（`ExpressionKeyboardPage` / `ExpressionFunctionCandidateView` 存在） |

⇒ 表情层大概率可复用（L1/L2/L4 只要类名一致就生效）；`ExpressionKeyboardView#showSlideAnimation` 的存在性待查。

---

## 6. 建议的 Targets 写法（**待实测，不写入代码前先验证**）

```java
public static final Targets STANDARD = new Targets(
        "com.sohu.inputmethod.sogou", "官方版",
        "com.sogou.theme.parse.utils.b", "a",              // ini：同名同签名
        "com.sogou.theme.parse.utils.c", "n",              // 解析器：同名
        "com.sogou.theme.common.g", "f",                   // 位图：j→g，且 f 少一个参数
        "com.sogou.bu.basic.SogouInputArea",               // 根容器（对应 NormalIMERootContainer）
        "com.sogou.bu.ui.keyboard.controller.b",           // 键盘外壳
        null, null,                                        // 候选视图：未定位
        "com.sogou.expressionplugin.expression.ExpressionUtil", "u",
        "com.sogou.expressionplugin.emoji.NoScrollViewPager",
        "com.sogou.expressionplugin.emoji.EmojiKeyboardView");
```

3 个 `null` 分别是：候选视图类、候选代理类、键盘外壳（暂填，待实测后再定）。
按手册原则：**没核对过的不写**，所以上面代码块只是"草稿"，验证通过后才进 `Targets.java`。

---

## 7. 待确认（必须用真机日志）

| 编号 | 问题 | 怎么确认 |
|---|---|---|
| S1 | `parse.utils.b#a(String)` 是否真被调用（类存在 ≠ 走这条路） | 装机后看 `H40 skin ini patched: N`，N>0 即命中 |
| S2 | `common.g#f/#g` 是否真被调用 | 看 `H41 bitmap getters hooked on com.sogou.theme.common.g: M/6` + `skimg=` |
| S3 | `SogouInputArea#setKeyboardView` 是否在每个键盘态被调用 | 看 `H02 ...` 日志（需先加 profile 支持） |
| S4 | 该版是否需要 `NormalIMERootContainer` 的 `addSmartThemeLayerView`（小米有、OPPO 无） | 看 `SogouInputArea` 是否有等价方法（本轮未列全方法表） |
| S5 | 候选条真正的推送方法（`showCandidateWordView` 不存在的替代） | 追 `CandidateViewListener`、`NewCandidateView`、`Toolbar#setCandidatesView` |
| S6 | 表情 `ExpressionKeyboardView` / `showSlideAnimation` 是否存在 | `probe2.py` 加 name 过滤再跑一次 |

---

## 8. 下一步（按用户定的优先级）

1. **小米（已完成）** → 保持 beta2 基线，不动。
2. **官方版（本报告）** → 把 `Targets.STANDARD` 落地 **+ 4 个硬编码 hook 迁入 profile**，然后出 **一个只开皮肤层** 的验证包（键盘/候选/注入全部跳过）。
3. **OPPO** → 修 `yy1`/`nm1` 名字（去掉 `defpackage.` 前缀），顺手用同一套 profile 机制。
4. **vivo** → **缺 APK**，等提供后再做（结构可能与小米更接近，因为 vivo 定制版通常与小米一样基于渠道包改）。

> 每一步都遵守：小米回归 → 目标渠道实测 → 未测就标「未兼容」。
