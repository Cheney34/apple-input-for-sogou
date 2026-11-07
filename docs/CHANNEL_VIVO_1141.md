# 渠道分析 #3：vivo 定制版 11.41.14023.2609221718（`com.sohu.inputmethod.sogou.vivo`）

> Phase 2 产出。**未修改任何与 vivo 相关的行为代码**（只在 `Targets` 里登记了已核实项，未核实项一律 `null`）。
> APK：`搜狗輸入法定製版-com.sohu.inputmethod.sogou.vivo-11.41.14023.2609221718.apk`
> 86,289,609 B / SHA-256 `3B26078EC9E1F51BEF01794BE584061EE833D05CBEDCCA88AB1FC83031EBDD86`
> 包名 `com.sohu.inputmethod.sogou.vivo` / versionCode `6034` / versionName `11.41.14023.2609221718`
> minSdk 21 / targetSdk 33 / **5 dex / 34,885 类**
> 取证工具：`ChironIOSInput/analysis/probe_channel.py`、`probe2.py`、`probe3.py` + dex 字符串矩阵

---

## 0. 一句话结论

vivo 版**和官方版同源**（IME 链一致），**表情层与输入容器完全沿用官方版的名字**，
但**主题/皮肤模块被整体 repackage 进默认包**（跟 OPPO 一样）——
所以 vivo 现在能拿到「表情 + 根容器 + 键盘根 View」，**拿不到皮肤**。

按你定的优先级（小米 > 官方 > OPPO == vivo），vivo 的皮肤要等**官方版皮肤实测通过**之后，
用同一套方法反查它的默认包类名（已有强候选 `mc4`）。

---

## 1. 输入法服务链

```
android.inputmethodservice.InputMethodService
└─ Lcom/sogou/bu/ims/support/BaseInputMethodService;   ← setInputView(View) 全 APK 唯一声明
   └─ Lcom/sohu/inputmethod/sogou/SogouIME;            ← 与官方版同名同包
      └─ Lcom/sohu/inputmethod/sogou/vivo/SogouIME;    ← 渠道壳（空子类）
```

⇒ 与小/官方一致：**没有 `onCreateInputView`**，输入视图仍是 push 进来；
`com.sogou.bu.ims.support.BaseInputMethodService#setInputView(View)` 在这一渠道同样是总入口。

---

## 2. 皮肤层：**类全部缺失**（被重打包）

| 小米/官方用的类 | vivo |
|---|:--:|
| `com.sogou.theme.parse.utils.b`（ini 入口） | ❌ 缺失 |
| `com.sogou.theme.parse.utils.c`（行解析器） | ❌ 缺失 |
| `com.sogou.theme.common.j` / `common.g`（位图入口） | ❌ 缺失 |
| `com.sogou.theme.parse.entity.b`（ini 载体） | ❌ 缺失 |
| `com.sogou.theme.parse.impl.a` / `impl.b` | ❌ 缺失 |

**位图入口的强候选**（签名与官方 `common.g` 同形，**未运行验证**）：

| 候选 | 方法签名 |
|---|---|
| `mc4` | `f(Context, String, boolean) : Bitmap` |
| `mc4` | `g(Context, String, boolean, boolean) : Bitmap` |
| `i85` | `e(Context, String) : Bitmap` / `f(Context, String) : Bitmap` |

⇒ 只找到「输出端」还不够：改皮肤必须同时拿到 **ini 的 `ArrayMap<段, Map<键,值>>`**，
而 vivo 的 ini 入口类名还没定位，所以 **vivo 皮肤层本轮不启用**（`Targets.VIVO.bitmapClass = null`）。
下一步怎么找 ini 入口：见 §6。

---

## 3. 输入视图层：**和官方版一样**

| 角色 | vivo | 说明 |
|---|:--:|---|
| `com.sogou.bu.basic.SogouInputArea` | ✅ | 根容器（`setKeyboardView` / `setCandidatesView`） |
| `com.sogou.bu.ui.keyboard.KeyboardRootComponentView` | ✅ | 键盘根 View |
| `com.sogou.bu.ui.keyboard.controller.b`（键盘外壳 facade） | ❌ | 未定位（官方版有） |
| `com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer` | ✅ | 候选容器（同名同包） |
| `com.sogou.input.ui.candidate.ImeInputCandidateRootView` | ✅ | 候选根视图（同名同包） |
| `com.sogou.imskit.*` | ✅（1,233 类） | 虚拟控件引擎在，`imskit/core/input/inputconnection/*` 也在 |

⇒ **键盘外壳 facade 未定位**，所以 `supportsKeyboardReplace()` = false（自绘键盘在该渠道不启用）。
`SogouInputArea` 已在 `Targets` 里登记，后续只要找到 facade 就能开。

---

## 4. 表情层：**沿用官方版**

| 类 | vivo |
|---|:--:|
| `com.sogou.expressionplugin.expression.ExpressionUtil` | ✅（**没有**改成 `w`，与 OPPO 不同） |
| `com.sogou.expressionplugin.emoji.NoScrollViewPager` | ✅ |
| `com.sogou.expressionplugin.emoji.EmojiKeyboardView` | ✅ |

⇒ 表情分组过滤（L3/L4）与左右滑（L1/L2）在 vivo 上**理论上可用**（需实测）。

---

## 5. 文本提交层

| 项 | vivo |
|---|---|
| `SogouIME#getCurrentInputConnection()` | ✅ 存在 |
| `com.sohu.inputmethod.foreign/inputsession/d#getCurrentInputConnection` | ✅ 存在（官方版是 `l`，vivo 是 `d` —— 字母不同） |
| `commitText / setComposingText` 实现 | 默认包 `ac3`、`b03` |
| 小米那套 `input.i` / `b0` / `MainImeServiceDel#getSogouInputConnection` | ❌ 不存在 |

⇒ 与官方版一样，`TextInjector` 只能走「`getCurrentInputConnection().commitText`」这条通用回退；
自绘键盘在 vivo 不启用（facade 未定位），所以暂时不需要更深的注入点。

---

## 6. 下一步怎么定位 vivo 的 ini 入口（给后续一轮留的方法）

1. **从位图候选反查调用方**：在 `mc4`/`i85` 的调用者里找「同时拿 ini 的类」——
   jadx 里搜 `Lmc4;` 的引用，或 androguard 里找 `invoke-static ... Lmc4;->f(`。
2. **按字符串常量反查**：dex 里搜 `file_name_mapping_1080.ini` / `thememanifest.ini` / `theme_default.zip`，
   它们必然出现在 ini 入口类里（官方版是在 `parse/frame/h0.java`，被重打包后也还是那个角色）。
   注意 vivo 的 `assets/theme/` 是否还在（本轮未验证，建议下一轮先确认）。
3. **按「返回 ArrayMap 的 (String) 方法」扫全 dex**：
   候选形如 `static <X> a(String) : <Y>`，且 `<Y>` 有 `b()Landroidx/collection/ArrayMap;`。
4. **最后手段**：真机跑一次，只看 `H40`（挂 `null` 时不会装），改用「临时诊断 hook 所有
   `AssetManager#open("theme/…")` 只打印、不改」—— 只读探针不违反「不无差别改写」的原则，但**必须默认关**。

---

## 7. 本轮在 `Targets` 里登记的 vivo 档（只登记已核实项）

```java
public static final Targets VIVO = new Targets(
        "com.sohu.inputmethod.sogou.vivo", "vivo 定制版",
        null, null,                       // 皮肤 ini 入口未定位
        null, null,
        null, null,                       // 位图候选 mc4 未验证 ⇒ 皮肤不启用
        "com.sogou.bu.basic.SogouInputArea",
        null,                             // 键盘 facade 未定位
        null, null,                       // 候选视图未定位
        "com.sogou.expressionplugin.expression.ExpressionUtil", "u",
        "com.sogou.expressionplugin.emoji.NoScrollViewPager",
        "com.sogou.expressionplugin.emoji.EmojiKeyboardView",
        false);
```

结果：`supportsSkin()=false`、`supportsKeyboardReplace()=false`、`supportsCandidateProbe()=false`、
`supportsEmojiGroupFilter()=true`、`supportsEmojiSwipe()=true`。

**这意味着 vivo 上装模块 = 只有表情相关功能，皮肤完全不动搜狗原样**——这是当前能承诺的最小、最安全的行为。
