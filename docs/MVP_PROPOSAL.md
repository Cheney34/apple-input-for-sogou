# MVP_PROPOSAL — 最小可行验证方案（Display-Layer Replacement）

> 目标：用**最小改动**证明一句话——「搜狗负责输入，Apple 负责体验」在 10.32.21 这个版本上可落地。
> 本 MVP **不追求好看**，只追求**证明链路**：自绘的键盘 View 出现在 z=3 槽位、点击能出字、候选条照常出现、关闭模块完全恢复。
> 前置：Phase 0（本目录 8 份静态分析）已完成；Phase 1（运行时确认）与 Phase 2（本 MVP）的边界在 §5 说明。

---

## §1 验证目标（4 条，全部可量化）

| # | 断言 | 通过标准 |
|---|---|---|
| A1 | 键盘槽可以被外部 View 接管 | z=3 位置显示 Apple 的占位键盘（纯色 + 3 行假键），**候选条与工具条仍在原位**，无遮挡、无裁切 |
| A2 | 接管后搜狗引擎仍在工作 | 点自绘键盘的 "n"、"i" → 输入框出现 "ni"，且**搜狗候选条出现 "你/尼/泥…"** |
| A3 | 上屏走原生链路 | 点候选条第一个候选 → 输入框得到 "你"，且再次输入 "ni" 时候选顺序发生变化（学习生效） |
| A4 | 可完全退出 | 关闭模块并重启输入法 → 100% 恢复搜狗原生键盘，无残留（截图对比） |

> A2 是**整个 MVP 的核心**：它同时证明"我们的键盘能驱动引擎"与"引擎→候选条→上屏"这条原生链路未被破坏。

---

## §2 极小实现范围（明确不做的事）

**做：**
- 一个 LSPosed 模块骨架（Gradle + Kotlin），包名 `com.chiron.iosinput`（新仓库，见 `REPO_SPLIT.md`）。
- 2 个 hook：H01（`keyboard.d#getRootView`）+ H02（`setKeyboardView`）——互为兜底。
- 1 个 `ChironKeyboardView`：`FrameLayout` + 3 行 `Button`（q w e r t y u i o p / a s d f g h j k l / z x c v b n m）＋ 退格 ＋ 空格。
- 键盘高度：沿用搜狗配置比例（`H=0.591667` × 屏宽），保证 insets 不需要改。
- 文字：点键 → `InputLogic#onText(String, 0)`（H13 的"调用"用法）；退格 → `MainImeServiceDel#onKey(-5, new int[]{-5}, false)` 或 `ic.g#deleteSurroundingText`（Phase 1 定案）。
- 开关：SharedPreferences 一个 `keyboard_replace_enabled`；关掉立刻回原生。
- 日志：每个 hook 安装/命中/异常都打 `XposedBridge.log`，tag `AppleInputForSogou`。

**不做：** 候选条替换、表情面板、长按符号、主题皮肤、手势、振动音效、多语言、悬浮键盘、设置 UI。

---

## §3 代码骨架（Kotlin，仅示意结构，不含 UI 细节）

```
app/src/main/java/com/chiron/iosinput/
├── HookEntry.kt                 // IXposedHookLoadPackage
├── config/ModuleConfig.kt       // 开关 + VersionProfile + 日志级别
├── compat/VersionProfile.kt     // versionName 白名单 + dex 校验
├── hook/HookRegistry.kt         // hook ID → installer，幂等 + try/catch
├── hook/KeyboardRootHook.kt     // H01: keyboard.d#getRootView
├── hook/SetKeyboardViewHook.kt  // H02: NormalIMERootContainer#setKeyboardView
├── hook/TextInjector.kt         // H13: 反射调用 InputLogic#onText
└── keyboard/ChironKeyboardView.kt  // 占位键盘（MVP）
```

关键片段（描述符均已在 DEX 中确认）：

```kotlin
// H01 —— 一次 hook 覆盖所有 setKeyboardView(...getRootView())
XposedHelpers.findAndHookMethod(
    "com.sogou.lib.bu.ui.component.keyboard.d", cl,
    "getRootView", object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            if (!ModuleConfig.keyboardReplaceEnabled) return
            val ctx = (p.thisObject as? View?.context) ?: return
            p.result = KeyboardHolder.obtain(ctx)      // 单例，复用同一个 View
        }
    })

// H02 —— 兜底：z=3 装进来的若不是我们的 View，就叠一层
XposedHelpers.findAndHookMethod(
    "com.sohu.inputmethod.ui.frame.NormalIMERootContainer", cl,
    "setKeyboardView", View::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val container = p.thisObject
            val v = p.args[0] as? View ?: return
            if (v is ChironKeyboardView) return
            XposedHelpers.callMethod(container, "addChildView",
                KeyboardHolder.obtain(v.context), 3)
        }
    })

// 文字注入：调框架自己的入口，不 hook 它
fun typeChar(text: CharSequence) {
    val logic = XposedHelpers.callStaticMethod(
        Class.forName("com.sohu.inputmethod.input.i", false, cl), "m")
    val inputLogic = XposedHelpers.callMethod(logic, "l")
    XposedHelpers.callMethod(inputLogic, "onText", text, 0)
}
```

> `input.i#m()` / `#l()` 的用法来自 `EVENT_CALLBACK_MAP.md` §4 第 1 条（`input/i.java:29,240`），**Phase 1 必须先验证这两个访问器可用**；不可用则降级到 H16 `MainImeServiceDel#onKey`。

---

## §4 验收测试用例（Phase 2 出口）

| TC | 步骤 | 期望 |
|---|---|---|
| TC-01 | 打开备忘录输入框 | 出现 Apple 占位键盘；上方候选条（空）与工具条位置正常 |
| TC-02 | 点 "n" "i" | 输入框显示 "ni"；候选条出现拼音候选 |
| TC-03 | 点第 1 个候选 | 输入框变 "你"；候选条清空 |
| TC-04 | 再输入 "ni" | "你" 排在第 1 位（学习生效） |
| TC-05 | 点退格 ×2 | "ni" 被删除 |
| TC-06 | 切到微信 → 切回备忘录 | 键盘仍为 Chiron；无崩溃 |
| TC-07 | 打开表情面板再返回（若原生面板仍可用） | 返回后 z=3 仍是 Apple 键盘 |
| TC-08 | 关闭模块开关（不重启） | 立即恢复搜狗键盘（或下次弹出恢复） |
| TC-09 | 卸载/停用模块 + 重启输入法 | 100% 原生键盘，无残留 |
| TC-10 | 连续 6h 使用（或 200 次弹收） | 无 ANR、无内存泄漏（meminfo 稳定）、无 crash log |

TC-01～TC-05 是**必须通过**；TC-07/TC-08 允许"已知限制"记录。

---

## §5 Phase 1 与 Phase 2 的边界

**Phase 1（运行时确认，必须先做，1-2 天）** —— 只装"只读诊断"，不换任何 UI：
1. 打印真实类加载结果：`keyboard.d`、`NormalIMERootContainer`、`InputLogic`、`input.i` 是否能 `Class.forName` 到。
2. 打印完整输入视图树（递归 dump `StyleConstraintLayout` → 子 View → z 索引），确认 z=2/3 的实际 View 类型。
3. 每次 `setKeyboardView`/`setCandidatesView` 打日志（类名 + hash + 尺寸），确认调用频次与时机。
4. 每次 `showCandidateWordView` 打日志（候选条数、首词），确认候选刷新时机。
5. 验证 `input.i#m()` → `#l()` → `InputLogic#onText` 可反射调用成功（用一个测试字符 "测"）。
6. 记录 `onComputeInsets` 收到的实际高度与我们的比例是否一致。

**Phase 2（本 MVP）** —— 在 Phase 1 全部通过后，才启用 H01/H02 的替换。

---

## §6 时间与产出

| 阶段 | 内容 | 产出 |
|---|---|---|
| Phase 0 | 本目录 8 份静态分析 | ✅ 已完成 |
| Phase 1 | 只读诊断模块 | `AppleInputForSogou_Diag.apk` + 诊断日志样本 |
| Phase 2 | 本 MVP | `AppleInputForSogou_MVP.apk` + TC-01～TC-05 通过录像/截图 |
| Phase 3 | 真实 iOS 键盘 UI（外观 + 手势 + 长按符号） | `AppleInputForSogou_Phase3.apk` |
| Phase 4 | iOS 候选条 | Phase 4 包 |
| Phase 5 | 长按符号 / 中英切换完善 | Phase 5 包 |
| Phase 6 | 表情面板替换 | Phase 6 包 |
| Phase 7 | 兼容/发布（版本档、降级、多设备） | Release APK + 兼容性矩阵 |

---

## §7 失败情形的降级路径（每一层都有退路）

| 失败点 | 现象 | 降级 |
|---|---|---|
| H01 无效（`getRootView` 不是各处都调） | 键盘不换 | 启用 H02 after 叠加；再不行 H08 `core.ui.a#u` |
| H02 无效（`instanceof s0` 导致布局异常） | 键盘错位/空白 | 让我们的 View 实现 `s0`；或改用 H10 z=1000 叠加（不改原布局） |
| H13 无法反射 | 点键无字 | 改调 H16 `MainImeServiceDel#onKey(I[IZ)V`；再不行 H19 `ic.g#commitText` |
| 高度/insets 错 | 应用被遮挡或键盘悬空 | hook `onComputeInsets` 自报 rect；或把 Apple 键盘做成"半透明覆盖层"（H10）而不替换 |
| 搜狗版本升级导致 hook 失效 | `NoSuchMethodError` | VersionProfile 不匹配 → 自动进入"仅日志模式"，用户无感（行为回到原生） |

**安全底线**：任何一步失败都只是"体验不变"，绝不会导致输入法不可用——因为引擎、候选、上屏三层的代码路径一行未改。
