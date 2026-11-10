## v2.1.8 — 修掉「滑块拉不动圆角」的两个死结 + 状态卡终于能看

### 找到的两个真问题

**1. 小于 56px 的键帽位图，滑块被整段架空**

v2.1.5 起为了救「被拉伸绘制的小图」，加过一条兜底：

```java
if (shortSide < 56f) {
    radius = rect.height() * Math.max(ratio, 0.44f);   // ← 滑块在这一段完全无效
}
```

后果是：**只要位图短边 < 56px，半径恒等于 0.44×键高**，滑块 10%~44% 拉出来一模一样。
用户拖动滑块看到毫无反应，这条兜底是首要嫌疑。本版直接删掉它 ——
小图上圆角偏小只是保守，而让每一次拖动都落空才是真 bug。

**2. 上限 0.48 让滑块最后 2% 是空的**

`RADIUS_CAP = 0.48` 意味着拉到 48% 和 50% 结果完全相同。改成 **0.5**：
50% 正好是「短边一半」的胶囊线，是这颗键在几何上能有的最圆形态。

### 3. 状态卡原来根本显示不出圆角值

这是上一版就该发现的问题：解析状态串时，`skinr=` / `blur=` / `acc=` / `monetsrc=`
全都掉进 `default` 分支，被当布尔渲染成 **「关」**。于是状态卡上永远只有 `skinr=关`，
用户想看「圆角到底算了多少」，看到的却是一个"关" —— 等于没有诊断。

本版把它们按原值显示，并把圆角单独成行：

```
圆角=0.40/53px/96x132  键帽=120/key_qwerty.png@96x132
```

- `0.40` = 当前生效比例（滑块值或自动档）
- `53px` = 画在这张位图上的真实半径
- `96x132` = 键帽位图本身的尺寸（决定 `53px` 在屏幕上占多少）
- `键帽=120/…` = **键帽重画了多少张**；若是 `0/…`，会直接补一句「键帽未重画，滑块不生效」——
  这种情况再怎么调圆角都不会有变化，问题在 H41 那条重绘链上，与圆角无关

### 判定表（装完看这一行就够）

| 状态卡 | 含义 | 下一步 |
| --- | --- | --- |
| `圆角=0.10/13px/96x132  键帽=120/…` | 滑块生效，比例与像素都对得上 | 觉得不够圆就把滑块往右拉；`13px` 说明你拉到 10% 了 |
| `圆角=0.40/53px/96x132  键帽=0/…` | 比例读到了，但键帽没重画 | 圆角这条路是好的，问题在重绘链（H41 没挂上/名字没认出来） |
| `圆角=-` | 一次都没算过 | 同上，先看 `键帽=` |

### 构建验证

- `build/AppleInputForSogou_v2.1.8.apk` = 25,777,733 B；badging `com.chiron.iosemoji` / `68` / `2.1.8`；
  证书 `4fbf65ed…c207`（与 2.1.x 同一张，可直接覆盖安装）。
- `classes.dex` 182,396 → 183,248 B；`skinkey` / `KEY_REDRAWN` / `RADIUS_CAP` 均在字符串池；
  APK 内 `assets/payload.zip` 与 2.1.7 sha256 相同（3911 条目 / Emoji 17.0）。

### 还没验证的一点（需要真机一行数据）

滑块 → 配置 → `targetRatio()` 这条链是逐环查过的（广播 extras、`RuntimeConfig.STRINGS`、
`keyRadiusPercent()` 的解析与夹取都没问题）。现在缺的是「键帽到底有没有被重画」这一个事实。
状态卡那行 `键帽=N/名字@宽x高` 就是答案：`N>0` 说明画了，那么这次的 0.5 上限与删兜底会立刻见效；
`N=0` 说明这条链压根没走到键帽上，得换到 H41 上去修。

## v2.1.7 — 边缘高光 + 液态玻璃（照 WeType UI Enhanced 移植）

### 为什么这次是"照搬"，不是"另做一套"

v2.1.6 的圆角滑块也是参考它做的，但只取了思路。这一版两个效果**数值与调用序列全部一比一移植**，
因为那套在微信输入法上已经跑通、观感也验证过，自己再造一套只会再交一次学费。

### 1. 边缘高光 —— 4 层 CSS inset box-shadow，纯 Canvas

参照 `graphics/WeTypeBloomStrokeDrawable.kt`，它的效果就是这四条：

```css
inset 2px 2px 0.25px -1.5px rgba(255,255,255,0.70)   /* 上沿亮线 */
inset 1px 1px 2px   0      rgba(255,255,255,0.80)   /* 左上柔光 */
inset -1px -1px 2px 0      rgba(255,255,255,0.60)   /* 右下反光 */
inset 0 0 10px 0.5px       rgba(0,0,0,0.09)         /* 内暗角 */
```

移植到 `IosSkin.drawRim()`，几何完全同构：内阴影 = 「整块」减去「缩 `spread` 再偏移 `(ox,oy)` 的洞」，
只留贴边的那一圈（`insetBand()`）。三处换算也照抄：

| 项 | WeType | 这里 |
| --- | --- | --- |
| 长度单位 | dp | 位图 px，`dp = 键高/44`（96×132 的键 → 3.0） |
| 模糊半径 | `blur × 0.866`（与 CSS sigma 对齐） | 同一个系数 |
| 夜间强度 | alpha × 0.25 | 同一个系数 |

**为什么高光要配玻璃才明显**：白色键帽本身就是 255，白高光没有"可上升空间"。实测剖面：
实心键帽 255 → 加高光后仍是 254~255（看不出），而玻璃键帽（0.74 alpha 叠在背景上，约 246）
加高光后顶边 253、往下 246，这才有 7~9 级的可见梯度。所以两项是一对。

### 2. 液态玻璃 —— 澎湃原生模糊 + 半透明玻璃面

参照 `graphics/WeTypeHyperMaterial.kt`，调用序列原样：

```java
setPassWindowBlurEnabled(view, true);      // 并读 mNeedPassWindowBlur 校验是否真的准入
setMiBackgroundBlurMode(1);
setMiBackgroundBlurRadius(40dp);
setMiViewBlurMode(1);
setMiBackgroundBlendColors([light: 121/3/3 | dark: 15/3/3]);   // 三色 tint，数值照抄
setMiBackgroundBlurType(0);
setMixEffectEnabled(false);
setMiBlurWinType(65536) → 500ms → 1;       // 新版 ROM 的离屏填充，早切不生效
```

落在 `hook/HyperGlassHook.java`（H70），挂在 H02 `setKeyboardView` 上 —— 每次弹键盘都会走一次，
所以"改设置 → 再弹一次键盘"就能生效。**任何一个 API 抛异常或被拒，整段回滚**（`rollback()`），
不会留下半套配置把键盘刷成透明。

**准入条件（与 WeType 的 `isAvailable()` 同源）**：Android 13+ / 有这套 View 方法 /
`ro.mi.os.version.code ≥ 3` / `persist.sys.background_blur_supported=true` /
`background_blur_enable=1`。不同的是：**系统属性读不到时不拦**（hidden API 受限不等于机型不支持），
交给实际调用去失败 —— 属性读失败一次就永久误判"不支持"，比调用失败更难查。

### 3. 透明度为什么是固定值

一开始写的是"模糊挂上了用 0.62、没挂上用 0.92"，画完发现是错的：皮肤位图在键盘第一次搭起来时
就解码了，模糊要等 View attach 之后才开 —— 顺序不保证，第一次弹键盘必然拿到兜底档，而且不会重解码，
表现就是"开了没反应"。所以改成固定 **键帽 0.74 / 底色 0.66 / 气泡 0.88 / 强调键 0.82**，
与先后顺序无关。比 WeType 的 50% tint 保守一点（我们的正文色是搜狗按 ini 画的，透太多影响对比度）。

### 4. 没有搬的部分（以及为什么）

| WeType 里有的 | 这里 | 原因 |
| --- | --- | --- |
| `setMiGlass` / `setMiGlassBlurRadius` / `setGlassBloom` / `setMiViewMaterialType` | ✗ | 需要 `persist.sys.bionic_material_supported` + `advanced_visual_release ≥ 4`，且参数是 20+ 个浮点的原生令牌，机型差异大 |
| `RuntimeShader` 画的外阴影 | ✗ | 只在面板外沿有用；我们是位图键帽，外面就是键盘底色 |
| 视图层实时重绘 | ✗ | 搜狗是解码好的位图，只能"生成时决定"，不能"每帧重算" |

### 构建验证

- `build/AppleInputForSogou_v2.1.7.apk` = 25,777,733 B；badging `com.chiron.iosemoji` / `67` / `2.1.7`；
  证书 `4fbf65ed…c207`（与 2.1.x 同一张，可直接覆盖安装）。
- `classes.dex` 170,168 → 182,396 B；`HyperGlassHook` / `edge_highlight_enabled` /
  `liquid_glass_enabled` / `hyper_blur_enabled` / `setMiBackgroundBlurMode` /
  `mNeedPassWindowBlur` 均在 dex 字符串池中。
- APK 内 `assets/payload.zip` 与 2.1.6 sha256 相同（3911 条目 / Emoji 17.0）—— 表情、快捷码未动。

### 待验证

- 状态卡新增 `blur=`：`ok` = 澎湃模糊真的挂上了；`非澎湃 3+` / `系统「背景模糊」未开` /
  `窗口未准入` / `该机型无 View 模糊 API` = 卡在哪一步，直接照着这一行判断。
- 若第 2 格（玻璃但无模糊）看着"太透"，把设置页的「液态玻璃」关掉即可，配色不受影响。

## v2.1.6 — 键帽圆角变成可调滑块（参考 WeType UI Enhanced）

### 为什么做这个

前几版圆角都写死在代码里：2.1.3 是 ×1.5、2.1.4 是 30%~35%、2.1.5 是 34%~38%。
每调一次手感都要改 Java、重新打包、覆盖安装 —— 这不是"可调"，是"每次找我改一行数"。

[WeType UI Enhanced](https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook) 的
`keyCornerRadius` 做对了这件事：圆角是设置页里的一个滑块。本版照这个思路给搜狗版补上。

### 改了什么

**1. 设置页 →「外观」新增一行「键帽圆角」滑块**

- `0` = **自动**（跟随皮肤预设，落在键高的 34%~38%）
- `10`~`50` = **锁定**，直接作为「半径 / 键高」的比例
- 1%~9% 会被自动吸到 10%：这一段在真机上小到看不出来，留着只会让人以为"没生效"
- 松手才落盘 + 下发，拖动过程只更新右上角数值

**2. 配置链路（跨进程）**

```java
// ModuleConfig：存字符串而不是 int
public static final String KEY_KEY_RADIUS = "key_radius_percent";   // "0" | "10".."50"
public static int keyRadiusPercent();                               // 解析失败一律退回 0

// IosSkin.targetRatio()：滑块优先，没设才走预设区间
int user = ModuleConfig.keyRadiusPercent();
float eff = (user > 0) ? user / 100f
                       : Math.max(RATIO_MIN, Math.min(RATIO_MAX, p.radiusRatio * RATIO_GAIN));
```

为什么用字符串：`RuntimeConfig` 本来就有 `STRINGS` 一条通路（皮肤预设、莫奈主色都在用它），
再为 int 开一套 map 只会多一处类型不一致的坑。`SettingsActivity.sendConfigIntent()`
和 `RuntimeConfig.applyFromIntent()` 各加一行即可。

**3. 层级关系没有丢**

长按气泡仍然是 `键帽 × 1.2`、封顶 42%，所以无论滑块在哪一档，「气泡比键帽圆」的相对关系都成立。

### 为什么没有把 WeType 的其它功能一起搬过来

| WeType 功能 | 搜狗版能不能做 | 原因 |
| --- | --- | --- |
| 键帽圆角 | ✅ 本版已做 | 位图重绘时自己画圆角 |
| 按键/背景颜色 | ✅ 已有（9 套预设） | `SkinPreset` |
| 品牌强调色 | ✅ 已有（accent） | `colorOfKind` |
| 背景模糊 | ❌ | 微信输入法是 Compose + RenderEffect；搜狗是解码好的位图，没有可挂模糊的视图层 |
| 边缘高光 / 液态玻璃 | ❌ | 依赖 HyperOS 原生视效接口，且需要实时着色器 |
| 候选栏圆角/边距 | ⚠️ 待评估 | 候选栏底图是九宫格拉伸，改圆角会破坏切边参数 |
| 工具栏图标背景透明度 | ⚠️ 待评估 | 图标是透明底单色字形，铺色会糊成方块（已有保护名单） |

模糊和高光不是"偷懒没做"，是位图皮肤这条路上没有对应的渲染时机 —— 除非改成整层自绘键盘，
那会打乱搜狗自己算好的九宫格 insets 与按键命中区（README 里写过为什么不做）。

### 生效时机（重要）

改完滑块**可能不会立刻变**：搜狗会把解码后的键帽位图缓存住，`dropCaches()` 能清掉它那份缓存，
但已经被 View 持有的 Drawable 不会自动换。所以 Toast 里直接写了"若没变化请点「重启输入法」"。
换皮肤预设能立刻看到，是因为切主题时搜狗会整包重新解码，圆角没有这个时机。

### 构建验证

- `build/AppleInputForSogou_v2.1.6.apk`；badging `com.chiron.iosemoji` / `66` / `2.1.6`；
  证书 `4fbf65ed…c207`（与 2.1.x 同一张，可直接覆盖安装）。
- `classes.dex` 170,168 B（2.1.5 为 167,176 B）；`key_radius_percent` / `keyRadiusPercent`
  均已在 dex 字符串池中；APK 内 `assets/payload.zip` 与 2.1.5 sha256 相同（3911 条目 / Emoji 17.0）。

### 待验证

- 真机上滑到 50% 时，窄键（删除键、`123`）会不会被 `RADIUS_CAP = 0.48×短边` 挡住 —— 这是刻意的几何保险，
  挡住说明"再大就变胶囊了"。状态卡 `skinr=` 会显示实际像素，可以直接核对。

## v2.1.5 — 修掉"改了比例却没变化"：圆角不再受原图探测值压制

### 真正的病根（v2.1.3 / v2.1.4 为什么看着一样）

`detectRadius()` 的探测上限是硬编码的 24px：

```java
int maxProbe = Math.max(1, Math.min(h / 2, 24));   // ← 这里
```

搜狗键帽图的真实圆角都超过 24，于是 `detected` 几乎恒等于 24。而两版的半径都是

```java
radius = max(detected, spec) ...   // spec = 键高 × ratio
```

**只要 `spec ≤ 24`，结果就被锁死在 24，ratio 怎么调都不动。**

| 键 | 键高 | v2.1.3 | v2.1.4 | 观感 |
| --- | --- | --- | --- | --- |
| 小键（123 / 符号 / 删除） | 70~80px | 24px | 24px | **完全相同** |
| 字母键（iOS 预设） | 132px | 36px | 41px | 只差 5px = 键高的 3.8%，看不出 |

所以 v2.1.4 把区间从 30%~35% 写对了、`RATIO_GAIN` 也调对了，**但那个 24px 的天花板把所有努力都吃掉了**。上一版给你的解释（"换成按区间取值"）是对的，只是没说到点子上 —— 区间对了，可值没落到区间里。

### 改法

半径**完全由规格决定**，原图只负责定位轮廓：

```java
float ratio  = targetRatio(p, kind);        // 0.34 ~ 0.38
float radius = rect.height() * ratio;       // 不再 max(detected, ...)
float cap    = Math.min(rect.width(), rect.height()) * RADIUS_CAP;
radius = Math.max(2f, Math.min(radius, cap));
```

另外加一条兜底：**位图短边 < 56px 时**（说明它是被 9-patch / 平铺拉伸绘制的），
按小尺寸算出的圆角放大后会被摊平，此时直接给到 `键高 × 0.44`，拉伸后仍是圆角矩形。

`detectRadius()` 保留但不再参与计算，javadoc 里写明了重新启用的前提（探测上限要先改成 `键高/2`）。

### 数值（132px 键高）

| 预设 | 预设 ratio | v2.1.3 | v2.1.4 | v2.1.5 |
| --- | --- | --- | --- | --- |
| 搜狗经典 | 0.10 | 36px | 40px | **45px（34%）** |
| iOS / 幽墨 Material | 0.12 | 36px | 41px | **46px（35%）** |
| iOS 净白 / 樱花粉 | 0.13 | 36px | 45px | **50px（38%）** |
| Google / 幽墨 Pixel / 微信 | 0.14~0.19 | 36~38px | 46px | **50px（38%）** |

长按气泡 = 键帽 ×1.2，封顶 42%。

### 诊断升级

状态卡的 `skinr=` 从 `比例/像素` 扩成 **`比例/像素/图宽x高`**，例如 `skinr=0.35/46px/96x132`：

- 宽高是 96×132 → 图是原尺寸，46px 就是真机上画出来的圆角；
- 宽高很小（如 24×24）→ 这张图会被拉伸绘制，走的正是上面那条 < 56px 的兜底；
- 显示 `skinr=-` → 图根本没重画，看 `skimg=调用/重画` 与 `skip=`。

### 构建验证

- `build/AppleInputForSogou_v2.1.5.apk` = 25,769,541 B；badging `com.chiron.iosemoji` / `65` / `2.1.5`；
  证书 `4fbf65ed…c207`（与 2.1.x 同一张，可直接覆盖安装）。
- `classes.dex` 167,176 B（2.1.4 为 167,100 B）；APK 内 `assets/payload.zip` 与 2.1.4 sha256 相同
  （3911 条目 / Emoji 17.0）—— 表情、快捷码、四渠道档案未动。

## v2.1.4 — 圆角改成「按区间取值」：九套皮肤统一 30%~35%

### 为什么改（v2.1.3 的问题）

v2.1.3 是「最终半径 ×1.5」。问题在于各套预设的 `radiusRatio` 本来就不是一个数
（搜狗经典 0.10 / iOS 0.12 / iOS 净白 0.13 / Google 0.14 / 微信 0.16~0.19），乘同一个倍数之后：

| 预设 | ratio | v2.1.3 生效比例 |
| --- | --- | --- |
| 搜狗经典 | 0.10 | 15% ← 几乎看不出变化，观感就是"这套皮肤没生效" |
| iOS / 幽墨 Material | 0.12 | 18% |
| iOS 净白 / 樱花粉 | 0.13 | 20% |
| Google / 幽墨 Pixel | 0.14 | 21% |
| 微信 | 0.16 / 0.19 | 27% / 34% |

同一台机器上换皮肤，圆润度会跟着皮肤跳 —— 这不是"协调"，而是没统一。

### 改法

不再乘倍数，改成**先把预设比例线性放大，再夹进目标区间**（`skin/IosSkin.java`）：

```java
private static final float RATIO_MIN  = 0.30f;  // 目标区间下限（占键高）
private static final float RATIO_MAX  = 0.35f;  // 目标区间上限
private static final float RATIO_GAIN = 2.6f;   // 放大系数：0.10~0.14 正好推进 0.26~0.36

static float targetRatio(SkinPreset p, int kind) {
    float eff = Math.max(RATIO_MIN, Math.min(RATIO_MAX, p.radiusRatio * RATIO_GAIN));
    if (kind == KIND_POPUP) eff = Math.min(0.42f, eff * 1.2f);   // 长按气泡再圆一档
    return eff;
}
```

生效结果（132px 键高）：

| 预设 | v2.1.3 | v2.1.4 |
| --- | --- | --- |
| 搜狗经典 | 15% | **30%**（40px） |
| iOS / 幽墨 Material | 18% | **31%**（41px） |
| iOS 净白 / 樱花粉 | 20% | **34%**（45px） |
| Google / 幽墨 Pixel / 微信 | 21%~34% | **35%**（46px） |

- `RADIUS_CAP` 从 0.46 收到纯几何保险的 **0.48**：设计值已经由 0.30~0.35 管住，
  这个上限只用来防止"宽度远小于高度"的窄键算出超过半宽的半径。
- `detectRadius()` 那一路没变：搜狗原图圆角更大时仍取原图值（不会把圆角画到超出键帽轮廓）。

### 新增诊断：`skinr=`

状态卡多一项 `skinr=<比例>/<像素>`（最近一张键帽图的实际取值）。装机后若仍觉得"圆角没生效"，
先看这一项就能分辨两种完全不同的情况：

- `skimg=120/0` 且 `skinr=-` → **图没重画**（名字没认出来 / 第三方皮肤被跳过），看 `skip=` 里的图名；
- `skimg=120/96` 且 `skinr=0.31/41px` → 图重画了、比例也对，那问题在"看着不够圆"，直接调 `RATIO_MIN/MAX`。

### 构建验证

- `build/AppleInputForSogou_v2.1.4.apk` = 25,769,541 B；badging `com.chiron.iosemoji` / `64` / `2.1.4`；
  证书 `4fbf65ed…c207`（与 2.1.x 同一张，可直接覆盖安装）。
- APK 内 `assets/payload.zip` 与 2.1.3 逐字节相同（3911 条目 / Emoji 17.0）—— 表情、快捷码、四渠道档案未动。
- `classes.dex` 167,100 B（2.1.3 为 166,608 B）。

## v2.1.3 — 键帽圆角整体圆润 +50%

### 改什么（只有一处）

`skin/IosSkin.java` 新增两个常量，`synth()` 里三处画圆角的地方（键帽、长按气泡、强调键）统一走它们：

```java
public static final float RADIUS_BOOST = 1.5f;   // 最终半径 ×1.5（相对原来圆润 +50%）
private static final float RADIUS_CAP  = 0.46f;  // 圆角上限 = 短边的 46%（原来 50%）
```

本版基于 **v2.1.2**（快捷码 / Emoji 17.0）修改：`assets/payload.zip` 3911 条目与 2.1.2 逐字节相同，
表情、快捷码、四渠道档案一律未动 —— 只动了键帽怎么画。

### 为什么乘在「最终半径」上，而不是逐套改 `radiusRatio`

实际生效半径是 `max(detectRadius(原图), 键高 × radiusRatio)`：

- `detectRadius` 扫的是搜狗位图自己的 alpha 圆角，**探测上限 24px**，小键（功能键 / 数字键）的键高
  本来就不到 100px，于是它的圆角被这一路决定；大键（字母键，键高 130+）则由「预设比例」这一路决定；
- 只把 `radiusRatio` 从 0.12 调到 0.18 的话，大键变圆、小键几乎不动 —— 同一排按键出现两种曲率，
  这才是"失去协调性"；
- 乘在最终值上，所有键按同一倍数变圆，且「气泡 = 键帽 ×1.5」「强调键 = 键帽」的层级关系原样保留。

### 数值

| 预设 | 键帽 ratio | 132px 键高上的半径 v2.1.2 → v2.1.3 |
| --- | --- | --- |
| 搜狗经典 | 0.10 | 24px(18%) → 36px(27%) |
| iOS / 幽墨 Material | 0.12 | 24px(18%) → 36px(27%) |
| iOS 净白 / 樱花粉 | 0.13 | 24px(18%) → 36px(27%) |
| Google / 幽墨 Pixel | 0.14 | 24px(18%) → 36px(27%) |
| 微信 | 0.16 / 0.19 | 25px(19%) → 36px(27%) / 30px(23%) → 45px(34%) |

（各套数值接近，是因为真机上小键的 `detectRadius` 都顶到 24px 上限 —— 这正说明"乘最终值"才能让它们一起变圆。）

### 上限为什么从 0.5 收到 0.46

0.5 就是"胶囊"（短边一半 = 两侧全半圆）。乘 1.5 之后小键离这条线很近，一旦跨过去，
一排键里会混进几颗药丸，整排的"矩形感"就散了。留 4% 直边，既保住键帽的识别度，也保住整排的连续性。

### 构建验证

- `build/AppleInputForSogou_v2.1.3.apk` = 25,769,541 B；badging `com.chiron.iosemoji` / `63` / `2.1.3` /
  label `Apple Input for Sogou`；证书 `4fbf65ed…c207`（与 2.1.0 / 2.1.2 同一张，可直接覆盖安装）。
- `classes.dex` 166,608 B（2.1.2 为 166,108 B）；APK 内 `assets/payload.zip` 与 2.1.2 的 sha256 相同、3911 条目。
- 对比图（按同一套公式算半径，非真机截图）：`radius-preview` 见发行说明。

### 待验证

- 真机上"一排键是否还看得出直边"。嫌太圆就把 `RADIUS_BOOST` 改成 1.35（相对 +35%），配色一个字节都不用动。

## v2.1.2 — 快捷码：让 Emoji 17.0 的新表情真的能打出来

### 为什么

v2.1.1 把 8 个新表情补进了搜狗的面板清单，但那只解决「面板里点得到」。
实际情况是：**字体（Chiron 6.5）只负责渲染，键盘里有没有这个键是另一条链路**。
系统键盘（Gboard / 小米键盘）的面板数据编在各自 APK 里，换字体对它零影响；
搜狗的面板数据虽然可以换，但要装分组、重启输入法，而且新条目排在分组最末尾，
不知道的人根本翻不到。结果就是：能看、不能打。

### 改了什么

- **新增快捷码**：打 `pg1`–`pg8` 上屏直接变成 8 个新表情，不经过候选条：

  | 码 | 表情 | 名称 |
  |---|---|---|
  | `pg1` | 🫪 | 变形的脸 |
  | `pg2` | 🫯 | 打斗云团 |
  | `pg3` | 🫈 | 毛怪 |
  | `pg4` | 🫍 | 虎鲸 |
  | `pg5` | 🛘 | 山体滑坡 |
  | `pg6` | 🪊 | 长号 |
  | `pg7` | 🪎 | 宝箱 |
  | `pg8` | 🧑‍🩰 | 芭蕾舞者 |

- 走的是已有的 `InputConnection.commitText` 改写链路（和 `苹果logo` →  同一条），
  **不碰搜狗的候选对象**，所以不会因为搜狗升级内部类名而崩。
- 码位用 `appendCodePoint` 现场拼，不写代理对字面量；日志里输出 `U+1FAEA` 而不是字形本身
  （LSPosed 日志页对补充平面字符会显示成方块）。
- 匹配前剥掉尾随空格再比较，替换后把空格补回去：英文候选上屏常常是 `"pg1 "` 一整串，
  不处理的话要么匹配不上，要么替换完两个词粘在一起。
- 开关 `logo_trigger_enabled`（显示名从「Logo 触发词」改成「快捷码（Logo / 新表情）」）
  **默认改为开**，装完不用去设置里找。全等匹配，`pg1很好用` 这种句子不受影响。
- 新增 [`docs/怎么打出新表情.md`](docs/怎么打出新表情.md)：讲清「显示」和「输入」的区别，
  给三条路（快捷码 / 搜狗面板 / 自定义短语），以及为什么不做「打中文自动变表情」。
- 版本号 `2.1.1 → 2.1.2`，`versionCode 61 → 62`（同签名，可直接覆盖安装）。

### 没做的

- **候选条里直接出图**（iOS 那种打完「苹果」候选位置出现 🍎 和 ）：搜狗候选是 native
  引擎算出来的，Java 侧只负责把算好的字符串塞进候选 View。想凭空插一条就得复刻它的候选对象，
  每个版本都会变。所有触发都留在上屏这一层。
- 面板清单本身没动，payload 仍是 3911 条目（v2.1.1 的产物）。

---

## v2.1.1 — 表情包升到 Emoji 17.0（iOS 26 字形）

### 为什么

`manifests/` 是 Emoji 16.0 时代整理的静态清单，17.0 的 7 个新表情在面板里根本没有位置——
字体换了也没用，面板读的是自己的 `emojipackage`。这一版把清单和素材一起补到 17.0。

素材来源：Unicode 17.0 / Emoji 17.0（2025-09 定稿）。npm 的 `emoji-datasource-apple` 停在
16.0.0，改用 `iamcal/emoji-data` 的仓库快照（commit `e77759c2`），其 Apple PNG 与 Emojipedia
的 `source/apple/453`（iOS 26.4 测试版美术）逐像素一致。

### 改了什么

- **新增 8 条 `exp`**（id 22067–22074，接在原有 20000–22066 连续区段之后）：

  | 分组 | unified | 名称 |
  |---|---|---|
  | 笑脸 | `1FAEA_FE0F` | 变形的脸 |
  | 笑脸 | `1FAEF_FE0F` | 打斗云团 |
  | 人物 | `1FAC8_FE0F` | 毛怪 |
  | 人物 | `1F9D1_200D_1FA70` | 芭蕾舞者（`supportSkin=1`） |
  | 动物与自然 | `1FACD_FE0F` | 虎鲸 |
  | 旅行与地点 | `1F6D8_FE0F` | 山体滑坡 |
  | 物品 | `1FA8A_FE0F` | 长号 |
  | 物品 | `1FA8E_FE0F` | 宝箱 |

- **6 条老表情打开肤色**：`👯 戴兔耳朵的人` / `兔先生` / `兔女郎`、`🤼 摔跤选手` / `男生摔跤` /
  `女生摔跤` 在 17.0 才补肤色，`supportSkin` 由 `0` 改成 `1`，`make_payload.py` 会展开 5 档。
- **素材换版**：`--src` 指向 17.0 的 3946 张 Apple PNG，`--version 17.0`。
- **payload 条目 3868 → 3911**：表情位图 3817 → 3870（+8 新条目 +5 芭蕾肤色 +30 六条老表情的肤色），
  加上 10 张分组封面与目录/`info.xml`/`version.json`。
- **`tools/add_unicode17.py`（新增）**：把上面这批改动固化成脚本，幂等（按 `unified` 判重）、
  默认干跑。下次 Unicode 18.0 只需改 `NEW` 表，不用手改两千多行 XML（还容易写错 id）。
- 版本号 `2.1.0 → 2.1.1`，`versionCode 60 → 61`（同签名，可直接覆盖安装）。

### 没做的

- **150 条新 ZWJ 序列**（`🧑🏻‍🐰‍🧑🏼` 这类双人双肤色的组合）没有进面板：搜狗的表情包格式
  一个条目只能有一个 `_1`–`_5` 肤色索引，表达不了「A 浅肤色 + B 深肤色」。系统字体里这些序列是有的
  （`chiron-apple-emoji` 6.5），面板里打不出来。
- **Emoji 17.0 定稿去掉了 U+1FADD 苹果核**，所以清单里没有它；上游 17.0 集合同样没有，两边一致。
- v2.1.0 里提到的「微信发送键强调绿未生效」仍未修，等真机回报 `[Key_Enter]` 段后再做。

---

## v2.1.0 — 第一个正式版

### 定位

把从 v2.0.3-beta2 到 beta18 一路验证过的东西固定下来，发一个**不带实验性改动**的版本：
默认配置下只改皮肤颜色（H40 ini + H41 位图），不改布局、不插自绘键盘、不做下载包替换；
资源层兜底（H50）、全面屏底栏（H60）都是**默认关**的独立开关。

### 内容

- **四渠道档案**：小米定制版 / 搜狗官方 20.19.0 / OPPO 定制版 / vivo 定制版；未识别包名**不装任何 hook**（不再回落小米）。
- **九套皮肤**：搜狗经典（旧版）/ iOS / iOS 净白 / 微信 / Google / 幽墨 Material / 幽墨 Pixel / 樱花粉 / 莫奈取色。
- **莫奈**：按 Material 3 Expressive（2025）色阶，可锁定主色（自动/粉/紫/蓝/绿/橙/青），对比度保底。
- **颜色只走两条通道**：`colors.ini`/`cands.ini` 等 ini 字段 + `res/{black,white}/*.png` 位图；位图不改尺寸（保九宫格）、不碰 `npTc`。
- **状态卡**：显示连上的**渠道名 + 包名**，并回报 `skin=/skini=/skimg=/skinse=/skintp=/monetsrc=/acc=` 等诊断值。
- **表情替换**：`payload.zip` 10 组 / 2067 项 / 3868 条目，与 v1.8 双向差集为空。

### 已知未完成（不属于回归 bug）

- 微信风格的**发送键强调绿仍未生效**：搜狗默认皮肤的 `[Key_Enter]` 没有独立 `BG_IMAGES`，回车键沿用功能键 / 字母键底图，
  而 H41 是按**图片路径**上色的，无法区分「这张图是给回车键用的」。需要真机回报 `acc=A/B` 与该皮肤 `[Key_Enter]` 段后再做 2.1.1。
- 官方 / OPPO / vivo 的皮肤为**部分适配**；vivo 的皮肤类被重打包，位图入口尚未定位（可开 H50 兜底）。

## v2.0.3-beta18 — 莫奈改按 Material 3 Expressive（2025）色阶

### 为什么改
- beta16 把色度压到「近中性」（底色 chroma ≤0.055），用户反馈**饱和度太低、对比度不够**：
  键盘看起来是一块灰板，键帽和底几乎分不开。这符合 M3 的 2021 baseline，但不符合 2025 的 Expressive 方向。

### 改法（`skin/MonetPalette.java`）
- 色度档重定：`BG_CHROMA_RATIO=0.42`（底色取主色色度 42%，上限 0.16）、`KEY_CHROMA_RATIO=0.10`（键帽近中性，上限 0.05）、
  `FUNC_CHROMA_RATIO=0.85`（功能键最饱和，上限 0.30）。
- 角色对照（Expressive）：底 = `surfaceContainer`、键帽 = `surfaceContainerLowest`、按下 = `surfaceContainerHigh`、
  功能键 = `secondaryContainer`、功能键文字 = `onSecondaryContainer`、正文 = `onSurface`、强调 = `primary`。
- 明度阶梯：浅色 键 1.00 / 底 0.888 / 按下 0.828 / 功能键 0.880；深色 键 0.265 / 底 0.135 / 按下 0.335 / 功能键 0.300。
- **对比度保底**：`KEY_MIN_LIGHT=1.28`、`KEY_MIN_DARK=1.55`，不足就把底色继续压暗（最多 20 档），不靠描边凑层次。
- 系统动态色分支改读 `system_surface_container_lowest / _low / _highest`；若系统给的底与键帽拉不开
  （浅色系统里常见同色），**不再硬用**，直接退回自派生色阶 —— 之前「对比度太低」就是这里来的。
- 功能键按下色也改由同一色阶派生（`funcC × 0.85`）。

### 与 beta17 的关系
- beta17（微信发送键强调色 `accentEnter` + `IosSkin.KIND_ACCENT/glyphMask`）**未单独发版**，已包含在本版内。

### 构建验证
- APK `build/v203b18/AppleInputForSogou_v2.0.3-beta18.apk` = 22,758,981 B；
  badging `com.chiron.iosemoji` / 58 / 2.0.3-beta18 / label `Apple Input for Sogou`；证书 `4fbf65ed…c207`。

### 待验证
- 六套主色在浅色/深色下的观感（脚本实测键/底亮度比：浅色 1.28–1.30、深色 1.55–1.60）。
- 系统动态色可用时（Android 12+ 且壁纸取色正常）是否直接用系统 surface，不再二次染色。
## v2.0.3-beta17 — 微信风格：发送键刷成强调色（按交接包选摘移植）

### 来源
- 用户提供的 `wechat-skin-handoff.zip`（`HANDOFF.txt` + `SkinPreset.java` + `IosSkin.java`）。
- 该交接包**没有** `MonetPalette`（自带旧版 system_neutral 掺色实现）、也没有 `SOGOU_OLD`，
  且 wechat 的配色值与本仓库 beta11 已一致，所以**不做整文件覆盖**，只选摘两处新增能力：
  1. `SkinPreset.accentEnter` 字段（只有微信为 true，其余预设默认 false）；
  2. `IosSkin.KIND_ACCENT` + `glyphMask`：强调键铺 accent 圆角底、把原图字形抠成对比色盖回。

### 改法（`lsposed/app/src/main/java/com/chiron/iosemoji/skin/`）
- `SkinPreset.java`：新增 `public final boolean accentEnter`；构造器加 18 参重载（17 参版本委托、默认 false），
  `wechatLight()/wechatDark()` 传 `true`；其余工厂方法一行未动。
- `IosSkin.java`：新增 `KIND_ACCENT = 4`、`ACCENT_KEYS`/`GLYPH_KEPT` 计数与 `accentKeys()/accentGlyphs()`；
  `kindOf()` 在 `isBgImage` 之后、**`isProtected` 之前**判 `isAccentKey()`（否则 enter/return 被保护名单吃掉）；
  `colorOfKind()` 增 `case KIND_ACCENT`（`!p.accentEnter` 时返回 0，调用方跳过 ⇒ 其他预设回到旧行为）；
  `synth()` 增强调键分支（排在 `hasInk`/`opaqueRatio` 之前，因为这两个判据是「有字形就不动」，
  而强调键要「留住字形、只换底」）；`patchIni()` 在 `accentEnter` 时把 `isAccentSection()` 命中的段
  底色改 accent、`TEXT_COLOR(_PRESSED)` 改成对 accent 收口后的对比色。
- 新增 `isAccentKey()`（只看名字，排除 `icon*`/`*_icon`/`voice`/`mic`/`smile`）、
  `isAccentSection()`（段名含 enter/send/confirm，排除 candidate/cloud/popup）、
  `glyphMask()`（亮度中位数二分，输出白字 + 透明底的蒙版；纯色图返回 null 只铺底不盖字）。

### 构建验证
- APK `build/v203b17/AppleInputForSogou_v2.0.3-beta17.apk` = 22,758,981 B；
  badging `com.chiron.iosemoji` / 57 / 2.0.3-beta17 / label `Apple Input for Sogou`；证书 `4fbf65ed…c207`。
- `classes.dex` 164,768 B：126 个类全部 `Lcom/chiron/iosemoji/*`，`de/robv` 定义 0；
  串 `KIND_ACCENT`/`accentEnter`/`isAccentKey`/`isAccentSection`/`glyphMask`/`accentKeys`/`accentGlyphs` 均在。

### 待验证
- 微信风格下回车/发送键是否变绿且**字形仍在**（对比 `accentKeys` 与 `accentGlyphs` 两个计数，
  前者明显大于后者说明有些键只铺了底、字形没保住）。
- iOS / Google / 莫奈 / 搜狗经典 的发送键必须**保持原样**（它们 `accentEnter=false`）。

## v2.0.3-beta16 — 莫奈底色改回「近中性」（上一版被主色染成了色布）

### 问题
- beta15 把「主色饱和度 × 0.26」直接当底色的饱和度用，粉/紫/蓝一锁，整块键盘就被染成一块彩色布，
  比之前的版本更难看 —— 这与 Material You 的实际做法相反：`surfaceContainer` 的色度极低（只是带一点点倾向）。

### 改法
- `MonetPalette.derive()` 重写为「近中性阶梯」：底色色度上限 `BG_CHROMA_MAX=0.10`（实际取 `chroma*0.06`，
  最高 0.055），键帽 0.035、功能键 0.12 —— 彩色只留给强调色（`accent`，`chroma` 0.45–0.72）。
- 亮度阶梯重新定标，让键帽与底色重新拉开：
  - 浅色：底 `V=0.895`、键 `0.995`（≈ #E0DEE4 底 / #FBF9FE 键，对比 1.27:1，接近 iOS 的 1.45）；
  - 深色：底 `V=0.105`、键 `0.300`、按下 `0.375`、功能键 `0.245`（对比 ≈2.0:1，比旧版 1.35 明显清楚）。
- `dynamic` 分支（系统动态色可用时）**直接用系统给的 surface 值**，不再二次染色 —— 系统那套本来就最准。

### 各主色实际产出（浅色 / 深色）
| 主色 | 浅色底 / 键 | 深色底 / 键 | 强调 |
|---|---|---|---|
| 樱花粉 | `#E4DCDF` / `#FEF8FA` | `#1B1A1A` / `#474646` | `#BD4D73` |
| 薰衣草紫 | `#E0DEE4` / `#FBF9FE` | `#1A1A1B` / `#464646` | `#8768BD` |
| 天空蓝 | `#DCE1E4` / `#F8FBFE` | `#1A1A1B` / `#464646` | `#4D8BBD` |
| 抹茶绿 | `#DEE4DE` / `#F9FEF9` | `#1A1B1A` / `#464545` | `#67BD6B` |
| 蜜橘橙 | `#E4E0DA` / `#FEFBF6` | `#1B1A1A` / `#474645` | `#BD8635` |
| 湖青 | `#DAE4E3` / `#F6FEFD` | `#1A1B1B` / `#464747` | `#35BDB0` |

### 构建验证
- `build/v203b16/AppleInputForSogou_v2.0.3-beta16.apk` 22,758,981 B，versionCode 56；
- `classes.dex`：126 类全 `Lcom/chiron/iosemoji/*`；`BG_CHROMA_MAX`/`KEY_CHROMA_MAX`/`FUNC_CHROMA_MAX` 常量在。

## v2.0.3-beta15 — 莫奈主色可锁死（粉 / 紫 / 蓝 / 绿 / 橙 / 青）

### 为什么加
- 自动取色只看系统动态色和壁纸主色，遇到「壁纸配色不好看」就只能忍着。
  现在可以在设置页把莫奈的主色**钉死**，壁纸再花也不影响键盘。

### 怎么用
- 设置页 → 外观 →「莫奈主色」：`自动（跟随系统 / 壁纸）` / 樱花粉 / 薰衣草紫 / 天空蓝 / 抹茶绿 / 蜜橘橙 / 湖青。
- 只在「皮肤风格 = 莫奈取色」下生效；选完会即时下发到搜狗进程并清位图缓存。

### 实现
- `skin/MonetPalette`：取色优先级改为 **fixed（用户主色）→ dynamic（系统动态色）→ wallpaper（壁纸主色）
  → last（上次种子）→ default（内置紫）**；`fixed` 时整条色阶（底 / 键帽 / 按下 / 功能键 / 正文 / 强调）
  都由该主色的 HSV 按固定亮度阶梯派生，再走 WCAG 收口。
- 新增配置键 `monet_seed`（字符串，默认 `auto`）：`ModuleConfig.monetSeed()`、
  `ConfigProvider` 多一列、`ConfigBridge` 冷启动读取、`RuntimeConfig.applyFromIntent` 支持下发。
- 诊断串 `monetsrc=` 现在会显示 `src=fixed:pink,seed=#FFF06292` 这种形式，一眼看出用的是哪一路。

### 构建验证
- `build/v203b15/AppleInputForSogou_v2.0.3-beta15.apk` 22,758,981 B，versionCode 55；
- `classes.dex` 162,360 B，126 个类全部 `Lcom/chiron/iosemoji/*`；
- dex 内确认：`SEED_IDS`/`SEED_LABELS`/`monet_seed`/`monetSeedValue`/`monetsrc`/`fixed`
  与「莫奈主色」「樱花粉」「薰衣草紫」「天空蓝」「抹茶绿」「蜜橘橙」「湖青」「已换莫奈主色」。

## v2.0.3-beta14 — 莫奈取色按《Keyboard Monet 动态取色系统开发手册》重做

### 为什么改
- 旧实现是「读 `system_neutral1_*` / `system_accent1_*` 再粗暴掺色」，等于把系统灰阶当键帽色，
  既没有 Token 角色映射，也没有对比度收口，所以看起来「氛围感不够」。

### 现在的分层（照手册 §2–§8）
- 新增 `skin/MonetPalette.java`：取色 → 角色映射 → Token（`SkinPreset`）→ 渲染（`IosSkin` / 搜狗皮肤层）。
- **取色优先级**：① 系统动态色（`android:color/system_surface_container` / `system_surface_container_high` /
  `system_secondary_container` / `system_on_secondary_container` / `system_on_surface` / `system_primary`，Android 12+）；
  ② 取不到时用 `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` 的主色当种子，按固定色调表生成；
  ③ 上次成功的种子；④ 内置紫色种子。任何一步异常都回退，**绝不影响键盘呼出**。
- **角色映射**（手册 §4 Token 表）：键盘底←`surfaceContainer`、键帽←`surfaceContainerHigh`、
  功能键/按下←`secondaryContainer`、强调/动作←`primary`、正文←`onSurface`、功能键文字←`onSecondaryContainer`。
  深色模式不再靠「反色」，而是同一套种子按暗色色阶重新派生。
- **WCAG 收口**（手册 §8.2）：正文 4.5:1、图标/强调 3:1，不达标就在调色板层把前景推向黑/白，
  不在渲染层打补丁；派生后还会经过 `IosSkin.patchIni` 的 `ensureContrast` 二次兜底。

### 诊断
- 状态串新增 `monetsrc=`（`src=dynamic|wallpaper|last|default,seed=#AARRGGBB`），用来一眼看出
  「到底有没有取到系统动态色」。设置页「连接状态」卡可长按复制。

### 构建验证
- `build/v203b14/AppleInputForSogou_v2.0.3-beta14.apk` 22,754,885 B，versionCode 54；
- `classes.dex` 158,572 B，123 个类全部 `Lcom/chiron/iosemoji/*`（非本项目 0）；
- dex 内确认包含 `MonetPalette`、`monetsrc`、`system_surface_container`、`system_secondary_container`、
  `getWallpaperColors`、`bbnav`、`applyNavBar`。

### 待真机确认
- 莫奈在 HyperOS 上应命中 `src=dynamic`；若显示 `src=wallpaper` 说明系统动态色资源不可读（会回退到壁纸主色）。

## v2.0.3-beta13 — 系统底栏（导航条）跟皮肤色

用户澄清：无效的是系统底栏（导航条），不是输入法里那条 MIUI 底栏。全面屏键盘优化开着时，键盘下方那块由系统画，颜色在 PhoneWindow#setNavigationBarColor(int) 里定。

### 改动（hook/MiuiBottomBarHook.java）

- setNavigationBarColor 从只观察改成 before 改参：把颜色参数换成皮肤底色（SkinPreset.kbBg），并计 bbnav= 次数；其余参数与窗口属性一概不动。
- InputMethodService#onWindowShown 抓到 IME 窗口后立即刷一次（applyNavBar(Window)）：w.setNavigationBarColor(bg) + 图标明暗同步（API>=30 用 WindowInsetsController.setSystemBarsAppearance(APPEARANCE_LIGHT_NAVIGATION_BARS, ...)，API 26-29 退回 SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR）。
- 加重入保护 sApplying（apply() 内部会调 setNavigationBarColor，避免自咬），apply() 用 finally 复位。
- 仍受 miui_bottom_bar_enabled（默认开）与 3 次失败熔断保护；只改颜色，不动高度 / 手势条 / insets。

### 状态串新增

;bbnav=<改参次数>（和 bb= / bbmg= / bbtree= / bbdecor= 一起看）。

- 产物 build/v203b13/AppleInputForSogou_v2.0.3-beta13.apk，versionCode 53，22,754,885 B。

## v2.0.3-beta12 — 皮肤风格新增「搜狗经典（旧版）」；微信夜间用实拍值替换

### 1. 新增预设 `sogouold`（显示名「搜狗经典（旧版）」，列表第一位）

值全部来自搜狗自己的皮肤资源，不是我们编的：

| 来源 | 项 | 值 |
|---|---|---|
| `theme_default/1080/layout/colors.ini` | 正文（LATIN/FUNCTION/PRESSED） | `#35373B` |
| 同上 | 首候选/强调（`COLOR_FIRST_DEFAULT_THEME`） | `#FF6933` |
| 同上 | 长按气泡（`COLOR_POPUP_LONGPRESS_THEME`） | `#FFFFFF` |
| `theme_default/1080/layout/cands.ini` | 候选条/键盘底（`BG_COLOR`） | `#F5F5F5` |
| `res/black` 键帽底图 | 字母键帽 | `#FFFFFF`（夜间 `#4A4A4A`） |

圆角 0.10、极淡投影、无底边线 —— 选它等于「把皮肤刷回搜狗原本的样子」。

### 2. 微信夜间按实拍替换（同 beta11）

底 `#202020` / 键帽 `#606060` / 功能键 `#424242`（比键帽更深）/ 前往 `#5FC595` 薄荷绿 / 圆角 0.19 / 无投影。

### 3. 皮肤列表顺序

`搜狗经典（旧版）` → `iOS` → `iOS 净白` → `微信` → `Google` → `幽墨 Material` → `幽墨 Pixel` → `樱花粉` → `莫奈取色`（共 9 套）。

- 产物 `build/v203b12/AppleInputForSogou_v2.0.3-beta12.apk`，versionCode 52，22,754,885 B。

## v2.0.3-beta11 — 微信夜间配色按实拍重取（beta10 源码的增量）

用户给回的源码里相对 beta10 的唯一改动是 `wechatDark()`，这里原样落库：

| 项 | 旧值（beta10） | 新值（实拍采样） |
|---|---|---|
| 键盘底 | `#111111` | `#202020` |
| 字母键帽 | `#303030` | `#606060` |
| 功能键 | `#111111`（与底同） | `#424242`（**比键帽更深**） |
| 功能键按下 | `#2A2A2A` | `#4E4E4E` |
| 强调色（前往/回车） | `#07C160` 品牌绿 | `#5FC595` 薄荷绿 |
| 圆角 | 0.16 | **0.19**（squircle） |
| 投影 | `0x33000000` | 无（键帽全平） |

同时补了注释里的关键提醒：**浅色里功能键几乎与底色同色、夜间反而比键帽更深，两个方向是反的**，不要把同一套亮度关系套到两套配色上。

- 产物 `build/v203b11/AppleInputForSogou_v2.0.3-beta11.apk`，versionCode 51，22,754,885 B。

## v2.0.3-beta10 — 恢复旧版风格配色（含 iOS 净白预设）

按用户给回的旧版 `SkinPreset` 源码原样恢复：

- 预设回到 8 套：`ios` / **`iosclean`（iOS 净白，照参考截图逐像素取色：底 #EDEFF2、键帽纯白、功能键 #DDE1E8、回车 #3482FF）** / `wechat` / `google` / `material` / `pixel` / `sakura` / `monet`；
- `barBg` 回到**各预设自己的值**（iOS 与 iOS 净白本来就是与键盘底同色；微信/Google 是白色条），即 beta8 的「全部统一」被还原；
- 保留全部注释与色值来源说明（iPhone 17 深色三原色、莫奈掺色比例、youmo-ime Material/Pixel、樱花粉 #FB7299）。

如需再次把候选条/工具条与键盘底拉到完全同色，把 `SkinPreset` 构造器里 `this.barBg = kbBg;` 改回即可（一行）。

- 产物 `build/v203b10/AppleInputForSogou_v2.0.3-beta10.apk`，versionCode 50，22,754,885 B。
## v2.0.3-beta9 — 用 loadClass 分发 + 改掉 phrase 的默认色

beta6/7 没打通的原因：phrase 的 `InputMethodBottomManager` 是**动态 dex** 里的类，
`lpparam.classLoader` 里根本查不到（HyperCeiler 也是用 `ClassLoader#loadClass` 按包名分发才 hook 到的）。

本次两条：

1. `hookClassLoaderDispatch()`：hook `ClassLoader#loadClass(String)` 与 `loadClass(String,boolean)`，
   一旦有人加载 `com.miui.inputmethod.InputMethodBottomManager` 就顺手 `attachBottomManager(cls)`（命中后靠 `sManagerHooked` 短路）。
2. `seedDefaults(cls)`：把它的静态默认色 `sDefBottomViewColor` / `sDefIconColorUnpressed` / `sDefIconColorPressed` /
   `currentBottomViewColor` / `sPhrasePanelBottomViewColorBackup` 直接改成皮肤色 —— 这样 `addMiuiBottomViewInner`
   里那句 `setBottomColor(false,0,0,0)`（默认色重置）也会刷成我们的颜色。

phrase 5.7.4 实证：`:599 new InputMethodBottomView(context)` → `:610-612` 取 `input_bottom_view` 设底色
（资源名 `miuix.animation.R.color.input_bottom_background_color`）→ `:625 setBottomColor(false,0,0,0)`。

状态串新增 `bbdp=`（loadClass 分发是否装上）。

- 产物 `build/v203b9/AppleInputForSogou_v2.0.3-beta9.apk`，versionCode 49，22,754,885 B。
## v2.0.3-beta8 — 工具栏 / 候选条 / 全面屏底栏与键盘底**完全同色**

按需求把所有「条」的底色收敛到键盘底色（`kbBg`），不再保留独立色：

- `SkinPreset.barBg` 恒等于 `kbBg`（原参数保留只作兼容，不再生效）；
- `IosSkin`：候选条/工具条的 `BG_COLOR`、条内文字对比度基准、`candidate_bg*` / `first_cand_bottom` / `more_cands_bar_bg` / `function_bg` 等底图，全部改用 `kbBg`；
- 全面屏底栏（H61）：`colors()` 直接返回 `kbBg`，不再是 `barBg`。

效果：键盘底、候选条、工具条、全面屏底栏是同一个颜色值，肉眼看不到接缝。

- 产物 `build/v203b8/AppleInputForSogou_v2.0.3-beta8.apk`，versionCode 48，22,754,885 B。
## v2.0.3-beta7 — 底栏加第二条独立通道（视图树兜底）+ 开关默认开

beta6 的 H61 只在「找到 `InputMethodBottomManager` 且它的 `sBottomView` 字段名没被混淆」时才生效；
实机仍是原色 ⇒ 说明这条链在用户的 ROM 上没打通。beta7 增加一条**完全不依赖 phrase 内部字段**的通路：

1. 抓 IME 的 `decorView`：hook `InputMethodService#onWindowShown` 存 WeakReference。
2. 兜底刷色：从 decor 里递归找「`android.widget` 的 RelativeLayout/LinearLayout、可见、高 ≤96dp、宽 >120dp、背景为 null 或纯色」
   的节点（最多 3 个），直接 `setBackgroundColor(皮肤底)`，并把其中的 ImageView 染色、TextView 改文字色。
3. `miui_bottom_bar_enabled` **默认改为开**（H61 现在三条通路并行，且带熔断）。

诊断串新增 `bbtree=视图树命中数`、`bbdecor=1/0`（是否抓到 decor）。

- 产物 `build/v203b7/AppleInputForSogou_v2.0.3-beta7.apk`，versionCode 47，22,754,885 B。
## v2.0.3-beta6 — 全面屏底栏改 hook 真正的落点 InputMethodBottomManager（H61）

### 为什么之前的 H60 改不动底栏
- 底栏不是 framework 画的：MIUI 从 `com.miui.phrase` 动态 dex 里加载 `com.miui.inputmethod.InputMethodBottomManager` 后挂上去。
- 反编译 phrase 5.7.4 确认 `setBottomColor(boolean,int,int,int)` 第一行就是 `if (sBottomView == null) { Log.e(...); return; }`：
  搜狗上报颜色那一刻底栏还没建好，这次上报被**直接丢弃**；随后 `addMiuiBottomView()` 用默认色建视图，再没人重设 ⇒ 观感就是「怎么都没变」。

### 本次改法（H61，三条一起做）
1. `customizeBottomViewColor` / `setBottomColor` **before 改参**：`args[0]=true`、`args[1]`=`SkinPreset.barBg`、`args[2]/[3]`=图标两态色（按对比度算）。
2. `addMiuiBottomView` / `refreshBottomSkin` **after** 补一次：反射改 `sBottomView.setBackgroundColor` + `sLeftButton/sRightButton.setBackgroundTintList` + `currentBottomViewColor`。
3. framework 侧只保留「观察 + 触发补一次」，不再改 framework 调用参数（缩小对键盘唤起的影响面）。
- 类加载：依次用 `lpparam.classLoader` → Context classLoader → 当前线程 → 系统 loader 找该类，并在 0.3/1/3/6/12/24/45 秒重试（动态 dex 注入有延迟）。
- 诊断：状态串新增 `bb=改参/主动应用;bbmg=1;bbfw=1;bbld=ime;bbdark=…;bbdis=…`。
- 开关：仍是 `miui_bottom_bar_enabled`，**默认关**，需手动打开「全面屏底栏跟随皮肤（实验）」。

### 构建验证
- `build/v203b6/AppleInputForSogou_v2.0.3-beta6.apk`，versionCode 46，22,750,789 B，证书同上（可覆盖升级）。
- dex：120 个类全 `Lcom/chiron/iosemoji/*`，无 `de/robv` 定义；串 `InputMethodBottomManager`/`H61 hooked`/`sBottomIconStates` 均在。
## v2.0.3-beta5 — 皮肤拆三层开关：资源层（H50）默认关

基线：v2.0.3-beta4（`a207636`）。用户反馈：**即使只开「改皮肤」，键盘也呼不出来**。

### 1. 结论：小米/官方根本不需要 H50
alpha12 时期（只有 H40 ini + H41 位图）小米上皮肤是能用的；H50 是 v2.0.2-beta1 才加进来的
「资源层兜底」，用来救类名被重打包的 OPPO/vivo。它 hook 的是 `AssetManager#open`，属于进程级拦截，
一旦改到结构性文件，主题解析就失败 → 键盘视图建不起来。

所以改成三层各自独立：

| 层 | 开关 | 默认 | 小米/官方 | OPPO/vivo |
| --- | --- | --- | --- | --- |
| 类名层 ini（H40） | `theme_skin_enabled` | 开 | 需要 | 类名缺失，用不到 |
| 类名层位图（H41） | `theme_skin_enabled` | 开 | 需要 | 类名缺失，用不到 |
| 资源层 ini（H50） | `asset_skin_enabled` | **关** | 不需要 | 需要，手动开 |
| 资源层位图 | `asset_skin_images_enabled` | 关 | 不需要 | 可选（nine-patch 有风险） |

### 2. H50 只碰四个纯颜色文件
新增 `INI_WHITELIST = {colors.ini, cands.ini, cands_outer.ini, phoneSkin.ini}`，
其余 ini（`file_name_mapping_*.ini`、`thememanifest.ini`、`theme.ini`、`layout/template.ini`、`keys.ini`、
`texts.ini`、`images_config.ini`、`image_list.ini`）**全部原样放过**并计入 `iniskip` 诊断 ——
这些是映射/模板/九宫格切边定义，改一个字节就可能让主题解析炸掉。

### 3. 构建与验证
- `build/v203b5/AppleInputForSogou_v2.0.3-beta5.apk` 22,750,789 B；`versionCode 45` / `2.0.3-beta5`；签名同上（可覆盖升级）。
- dex：116 个类全部 `Lcom/chiron/iosemoji/*`，非 chiron 0；串 `asset_skin_enabled`、`isColorIni`、`cands_outer.ini`、
  `iniskip`、`isNinePatch` 均在包内。

### 4. 装完请确认
1. 设置页「资源层兜底（H50）」必须是**关**；「资源层改位图」也必须是**关**；
2. 只留「改皮肤（推荐）」开，然后点「重启输入法」；
3. 键盘应恢复正常且键帽变色；把 `skin= / skini= / skimg= / H41 bitmap getters hooked on N class(es)` 发我。

## v2.0.3-beta4 — 紧急修复：键盘呼不出来（nine-patch 被重编码）+ H60 默认关

基线：v2.0.3-beta3（`172474a`）。用户反馈「底栏颜色没应用，而且又无法呼出键盘了」。

### 1. 键盘呼不出来：H50 重编码 nine-patch（根因）
`hook/AssetSkinHook.java#patchImageBytes()` 原来是 `BitmapFactory.decodeByteArray` → `IosSkin.synth` →
`out.compress(PNG, 100, bos)`。搜狗皮肤里的键帽底图大多是 **nine-patch**（`.9.png` / PNG 内嵌 `npTc` 块），
重新编码会把切边数据整块丢掉 ⇒ 系统或搜狗 Inflate 这张图时抛异常 ⇒ 键盘视图建不起来。

- 新增 `isNinePatch(path, raw)`：文件名字面 `.9.png`，或原始字节里出现 `npTc` 块，一律 `return null`（原样放过）；
- 资源层位图改为**默认关**：新增开关 `asset_skin_images_enabled`（默认 `false`），H50 默认只兜底 **ini 颜色**，位图继续交给类名层 H41（那条路已验证可用、且不碰 nine-patch）；
- 设置页新增「资源层改位图（实验）」开关，文案写明风险。

### 2. H60 默认关 + 不再改 framework 调用参数
上一版在 `customizeBottomViewColor` 的 before 里改写 `args[1..3]`，是 beta3 唯一触碰 framework 调用参数的地方，
也是「键盘唤起失败」的次要嫌疑。现在：

- 删除 before 改参，改成 **after 只观察**（计数用于诊断）+ `addMiuiBottomView` after 与 `setNavigationBarColor` after 主动下发；
- `miui_bottom_bar_enabled` 默认改 **关**；
- 新增熔断：`apply()` 连续失败 3 次 → `H60 circuit breaker OPEN`，本进程不再改底栏。

### 3. 构建与验证
- `build/v203b4/AppleInputForSogou_v2.0.3-beta4.apk` 22,750,789 B；`versionCode 44` / `2.0.3-beta4`；
  证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（可覆盖升级）。
- `dexdump -f`：116 个类全部 `Lcom/chiron/iosemoji/*`，非 chiron 0、`de/robv` 定义 0。
- dex 字符串确认：`isNinePatch`、`asset_skin_images_enabled`、`H60 color report observed`、`H60 circuit breaker OPEN` 均在；旧串 `H60 color report patched` 已消失（改参逻辑确实删掉了）。

### 4. 现场自救（键盘已经呼不出来的情况）
打开模块 App →「功能开关」→ 关掉「改皮肤（推荐）」→ 点「重启输入法（重载皮肤）」，键盘即可恢复；
只想保留颜色、担心位图的话，保持「资源层改位图」关闭即可。

## v2.0.3-beta3 — MIUI「全面屏键盘优化」底栏跟随皮肤（H60）+ 状态里标明渠道

基线：v2.0.3-beta2（`0e7e4bc`），只加一条 MIUI 专属改造 + 修「看不出连的是哪个渠道」。

### 1. 新增 H60：`hook/MiuiBottomBarHook.java`
小米的「全面屏键盘优化」底栏由 `com.miui.phrase` 动态 dex 里的 `InputMethodBottomManager` 画，
颜色走 framework 的 `InputMethodServiceInjector#customizeBottomViewColor(boolean,int,int,int)`，
**既不读搜狗 ini 也不读搜狗位图**，所以 H40/H41 改不到它。三处 hook（全部在 boot classpath，
用 `null` ClassLoader，不需要给 LSPosed 加 `com.miui.phrase` 作用域）：

- `customizeBottomViewColor` **before 改参**（治本：搜狗每次上报颜色都会被改写成皮肤色）；
- `addMiuiBottomView` **after** → 下一帧补一次（底栏刚创建 / 切换输入法重建的时序）；
- `com.android.internal.policy.PhoneWindow#setNavigationBarColor` **after** → 补一次（换肤 / 深浅色切换的时序）。

配色与 H40/H41 同源：`SkinPreset.kbBg` + 反色图标；`ThemeSkinHook` 解析到 `/black/`、`/white/` 时
回调 `noteSkinDark()`，保证底栏跟「实际渲染的键盘」同色而不是猜系统夜间模式。
只改颜色，不碰任何 View 的尺寸/padding/insets。新开关 `miui_bottom_bar_enabled`（默认开，仅小米渠道安装）。

### 2. 四处保守化（避免把别人的界面改坏）

- **不动 `args[0]`（isCustom）**：原实现无条件置 `TRUE`，会把某些 ROM 上本来不该出现的底栏点亮；
- **透明/无底栏场景不接管**：调用方传 `alpha==0` 时直接放过（全屏、游戏、悬浮键盘）；
- **`setNavigationBarColor` 节流 400ms**：它是进程级高频方法，任何窗口改色都会触发；
- **找不到注入类不再抛异常**：非 MIUI 机型只记一行 `H60 skipped`，诊断里用 `bb=0/0;bbm=0` 表示没挂上。

### 3. 状态里标明「哪个渠道连上了」

`ConfigBridge.state()` 新增 `ch=<渠道名>;chpkg=<包名>`，设置页「连接状态」卡显示
`已连接 · 小米定制版（com.sohu.inputmethod.sogou.xiaomi）`，开关生效的 Toast 也会带上渠道；
未连接时提示作用域覆盖的四个渠道。之前只有 `v=/sg=/hooks=`，小米和 OPPO 连上都长得一样。

### 4. 构建验证

- `build/v203b3/AppleInputForSogou_v2.0.3-beta3.apk` = **22,750,789 B**，`versionCode=43` / `2.0.3-beta3`，证书同前（可覆盖升级）；
- `classes.dex` 147,480 B，`dexdump -f` 116 个类**全部** `Lcom/chiron/iosemoji/*`、非 chiron 0 个、`de/robv` 定义 0 个；
- dex 字符串确认含 `MiuiBottomBarHook` / `H60_miui_bottom_bar` / `customizeBottomViewColor` / `setNavigationBarColor` / `miui_bottom_bar_enabled` / `;ch=` / `;chpkg=`。

### 5. 待验证

- 小米真机：`bb=<改参次数>/<主动应用次数>` 左侧应 > 0（说明颜色上报真走了这条通道）；
- 官方 / OPPO / vivo：H60 不装（`bbm=0` 正常），看 `H41 bitmap getters hooked on N class(es)` 与 `skini/skimg`；
- 小米回归：键盘/中英文/候选/皮肤/表情/开关可逆。

---

## v2.0.3-beta2 — 恢复资源层兜底（H50）；下线自绘键盘；修「官方版只有工具栏变色」

基线：从 v2.0.3-beta1（`df2a0db`）继续，不改皮肤算法本身，只修「哪个渠道根本没被改到」与「哪条 hook 越界」。

### 1. H50 回来了（这次带刹车）
`hook/AssetSkinHook.java` 按原样恢复（从 `df2a0db^` 取回，435 行），并在 `HookEntry.installThemeHooks()` 里重新挂上。

理由：OPPO / vivo 把主题模块整体 repackage 进默认包，类名层（H40/H41）拿不到入口；
而所有渠道读皮肤都走 `assets/theme/…`，资源层是唯一「不依赖类名」的兜底。
它同时受「改皮肤」总开关（`theme_skin_enabled`）控制，关掉就两层一起停。

保留的三条铁律（beta3/beta4 的教训）：
- **第三方皮肤一个字都不改**：`isDefaultTheme()` 命中 `sogou_custom` / `theme_custom` / `/themes/` / `.ssf` / `phonetheme` 一律放过；
- **ini 只做行内取值替换**（`applyDiff`），改完 `sameSkeleton()` 复核段名/键数，不一致返回原文件；
- **熔断**：连续 3 次失败后本进程不再改写任何皮肤资源（`H50 circuit breaker OPEN`）。

### 2. 自绘键盘整体下线（你说「不生效」，直接删掉）
删除：
- `hook/KeyboardRootHook.java`（H01，返回自绘键盘）、`hook/TextInjector.java`（只为自绘键盘造字）
- `keyboard/ChironKeyboardView.java`、`KeyView.java`、`KeyboardTheme.java`、`KeyboardFactory.java`
- 配置键 `keyboard_replace_enabled`（含 `ModuleConfig` / `ConfigValues` / `RuntimeConfig` / `SettingsActivity` / `ConfigMigration` / 文档）

保留并改造：`hook/SetKeyboardViewHook.java`（H02）不再注入任何 View，只做一件事 ——
**键盘每次被装上时对齐一次模块配置**（`ConfigBridge.resyncNow()`）。
这个时机正好覆盖「改完设置 → 点输入框 → 键盘重弹」，比依赖广播更可靠（国产 ROM 会拦广播）。

### 3. 渠道档案支持多候选（这是「官方版只有工具栏变色」的真因）
`compat/Targets.java` 数组化：
- `themeIniMethods: String[]`（vivo 的 ini 入口有 `d/e/f` 三个重载）
- `bitmapClasses: String[]` + `bitmapMethods: String[]`（各渠道解码入口不同：小米 `common.j`、官方 `common.g`、OPPO `nm1`、vivo 走通用图片工具）

四档现状：
| 渠道 | ini 入口 | 位图入口 | 说明 |
| --- | --- | --- | --- |
| 小米 | `com.sogou.theme.parse.utils.b#a` | `com.sogou.theme.common.j#{f,g,j,k,d,e}` | 已验证 |
| 官方 20.19.0 | 同名同签名 | `com.sogou.theme.common.g` + `common.j` | 待实测 |
| OPPO | `yy1#a` | `nm1#{j,k,e,f,g,d}` | 类名已修（默认包无 `defpackage` 前缀） |
| vivo | `gh#{d,e,f}`（直接返回 ArrayMap） | 未定位（先用 H50 兜底） | 待实测 |

`ThemeSkinHook` 侧配套：
- H40 改 `hookAllMethods` 按方法名挂（不再写死 `(String)` 单签名），并用 `asSections()` 兼容「返回 `entity.b`（要 `b()`）」与「直接返回 ArrayMap」两种形态；
- H41 遍历「候选类 × 方法名」，日志改成 `H41 bitmap getters hooked on N class(es), M method name(s)`；
- **位图过滤改为按 basename 判定**（`kindOf` / `isProtected` / `colorOf` 都吃图名而非整条路径），并新增 `looksLikeSkinImage()` 放行 `candidate_bg*` / `key_qwerty*` 这类相对名或逻辑名 ——
  官方版传进来的不是完整路径，旧的 `isSkinPath()` 要求路径里含 `theme`，于是键帽图全被过滤掉，只剩 ini 层的工具栏/候选条变色。

### 4. 构建验证（本机实测）
- 产物：`build/v203b2/AppleInputForSogou_v2.0.3-beta2.apk`，**22,746,693 B**
- `aapt2 dump badging`：`com.chiron.iosemoji` / versionCode **42** / versionName **2.0.3-beta2** / label `Apple Input for Sogou`
- 证书：`CN=Android Debug`，SHA-256 `4fbf65ed…c207`（同包名覆盖升级）
- `classes.dex` 139,848 B；`dexdump -f` 列出 **110 个类全为 `Lcom/chiron/iosemoji/*`**，非 chiron 0 个
- 字符串检查：含 `AssetSkinHook` / `circuit breaker OPEN` / `bitmapClasses` / `H41 bitmap getters hooked on` / `gh`；
  不含 `KeyboardRootHook` / `ChironKeyboardView` / `keyboard_replace_enabled` / `resource_skin_enabled` / `defpackage`

### 5. 待真机确认
- **官方版**：键帽是否开始变色 —— 看日志 `H41 bitmap getters hooked on 3 class(es)` 与设置页 `skimg=解码/重画`
- **vivo**：是否出现 `H40 skin ini patched`（ini 层）、`skimg` 是否非 0、`asset=…`（H50 层）
- **OPPO**：`yy1#a` 是否命中 + H50
- **小米回归**：键盘弹出 / 中英文 / 候选 / 皮肤 / 表情 / 开关双向可逆
## v2.0.3-beta1 — 删掉资源层皮肤 hook（H50）；渠道档案化（官方 / OPPO / vivo 进 Targets）

基线说明：本版从 **稳定版 beta2（`7401aa5`）** 出发，只做「多渠道档案化 + 删除越界实现」，不改 beta2 的皮肤算法。

### 1. 删除 H50（资源层皮肤 hook）——你要求的
`hook/AssetSkinHook.java`（435 行）整文件删除。它做的事情是 hook `android.content.res.AssetManager#open`，
在**进程级**改写所有 `theme/…` 开头的 ini 与位图字节——违反手册 §6.3「不能对所有 `AssetManager.open()` 无差别替换」，
也是 beta3/4/5 出现「键盘呼不出来」的结构性原因。

连带清理（残留 grep = 0）：
- `HookEntry`：`import AssetSkinHook` + `AssetSkinHook.install(cl)`
- `ConfigBridge`：`AssetSkinHook.reset()`、`state()` 里的 `asset=` / `asample=`；**保留** 定时重读（2/5/10/20/40/60s）与 `resyncNow()`
- `ModuleConfig`：`KEY_RESOURCE_SKIN` / `resourceSkinEnabled()`
- `ConfigValues`：该键（KEYS 恢复 7 项 + 自绘键盘）
- `SettingsActivity`：「改皮肤（资源层）」开关、`资源=` / `读到的皮肤资源` 两行状态

保留下来的保护：`ThemeSkinHook` 里的第三方皮肤门控（`.ssf` / `sogou_custom` / `theme_custom` / `/themes/` / `phoneTheme`
一律不碰，计 `第三方跳过=`）、熔断、ini 行内替换、骨架复核。

### 2. 渠道档案化（Targets）
- 修 **OPPO**：`defpackage.yy1/nm1` → **`yy1`/`nm1`**（默认包类的真实 FQCN；写成 `defpackage.` 会 ClassNotFound）
- 新增 **`STANDARD`**（官方 20.19.0 `com.sohu.inputmethod.sogou`）：ini 入口与小米同名同签名；
  位图入口 `com.sogou.theme.common.g`（`f(Ctx,Str,Z)` / `g(Ctx,Str,Z,Z)`）；根容器 `com.sogou.bu.basic.SogouInputArea`；
  键盘外壳 `com.sogou.bu.ui.keyboard.controller.b`；候选视图/代理未定位 = `null`
- 新增 **`VIVO`**（`com.sohu.inputmethod.sogou.vivo` 11.41.14023）：皮肤类被重打包（全部缺失）⇒ 皮肤层不启用；
  根容器 `SogouInputArea` ✅、表情三件套 ✅（`ExpressionUtil` 未改名）、键盘 facade 未定位
- **`forPackage()` 未知包返回 `null`**（不再回落小米）；`HookEntry` 遇到 null 只打一行日志、什么都不装
- 三个原本硬编码的 hook 改为读档案：`KeyboardRootHook`（`keyboardFacadeClass`）、
  `SetKeyboardViewHook`（`rootContainerClass`）、`CandidateProbeHook`（`candidateViewClass`/`candidateDelegateClass`）；
  档案里为 `null` 就跳过
- `Protocol.TARGETS` / `xposed_scope` / `<queries>` 增加 `com.sohu.inputmethod.sogou.vivo`

### 3. 构建与验证
- `versionCode 41` / `2.0.3-beta1`；`build/v203/AppleInputForSogou_v2.0.3-beta1.apk` = 22,750,789 B
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级）
- `classes.dex` 148,472 B：**117 类全部 `Lcom/chiron/iosemoji/*`**、`de/robv` 定义 0、`AssetSkinHook` 残留 0、
  `resource_skin_enabled` 残留 0、`defpackage` 字符串 0
- 新渠道字符串已入包：`com.sogou.bu.basic.SogouInputArea`、`com.sogou.theme.common.g`、
  `com.sogou.bu.ui.keyboard.controller.b`、`sogou.vivo`、`unknown channel`

### 4. 各渠道现在的真实能力（**未实测的一律不许说"已支持"**）
| 渠道 | 皮肤 | 表情 | 自绘键盘 | 候选探针 |
|---|---|---|---|---|
| 小米（beta2 验证过） | ✅ | ✅ | 实验 | ✅ |
| 官方 20.19.0 | ⏳ 待实测 | ⏳ | ⏳（有对应物） | ❌ 未定位 |
| OPPO 10.32.0622 | ⏳ 待实测（本轮修好类名） | 部分 | ❌ | ❌ |
| vivo 11.41.14023 | ❌ 未定位（皮肤类被重打包） | ⏳ | ❌ | ❌ |

### 5. 回归要求
小米版必须复测：键盘弹出 / 中英文 / 候选 / 皮肤 / 表情 / 开关即时可逆。任一不过就不算通过。
## v2.0.2-beta5 — 两层皮肤拆成两个开关（自救版）

### 背景
> beta3、4都有问题

你这句话信息量太少（我看不到你的屏幕），所以这一版不猜着改风格，而是**把可能出问题的那一层变成可关的开关**，让你在手机上 30 秒自救。

### 改了什么
1. **皮肤拆成两层，各一个开关**（设置 → 外观）
   - `改皮肤（推荐）` = 类名层（H40 改 ini + H41 改位图）—— 小米版走这条
   - `改皮肤（资源层）` = H50 在 `AssetManager` 层改写（OPPO 定制版这类改了类名的走这条）
   - 单独关掉任意一层，另一层照常工作；两层都关 = 皮肤完全不动（最安全的回退位）
2. **修 beta4 的过度收紧**（很可能就是它让 beta4"什么都没变"）
   beta4 把 `.theme/`、`unPackThemeTmp` 一并排除，但这两处正是**系统把内置默认主题缓存/解包**的地方 ——
   排掉等于一行不生效。现在只排除真正的第三方目录：`sogou_custom`、`theme_custom`、`/themes/`、`.ssf`、`phoneTheme`。
3. 熔断（连续失败 3 次停用本层）、ini 行内替换、骨架复核 —— 全部保留。

### 建议排查顺序（每步都能立刻回退）
| 症状 | 操作 |
|---|---|
| 键盘**呼不出来** / 输入法闪退 | 关掉 `改皮肤（资源层）`；还不行就关 `改皮肤（推荐）` |
| 皮肤**完全没变** | 确认上面两个开关是开的；看状态卡 `资源=` `第三方跳过=` `对比修正=` 是否非 0 |
| 换了**第三方皮肤**键盘异常 | 确认 `第三方跳过=` > 0（>0 说明我们确实放过了它） |

### 构建与验证
- `versionCode 40` / `2.0.2-beta5`，`AppleInputForSogou_v2.0.2-beta5.apk` = 22,754,885 B
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级）
- `classes.dex` 155,736 B：**120 类全部 `Lcom/chiron/iosemoji/*`**，无 `de/robv` 定义
- 已确认含 `resource_skin_enabled` / `resourceSkinEnabled` / `sogou_custom` / `theme_custom` / `.ssf`
# Changelog

本项目遵循「分析 → 验证 → 实现」的推进方式：每个版本只写**有证据支撑**的东西。

## v2.0.2-beta4 — 修「加载第三方皮肤时键盘呼不出来」

### 用户反馈
> 卧槽 成功是成功了 但是加载第三方皮肤 键盘无法呼出

皮肤终于生效了，但**一换第三方皮肤（用户自己下载的 .ssf 主题包）键盘就起不来**。
这个是我 beta3 引入的回归，责任在「改得太宽」。

### 真因（三个叠加）
1. **范围没有边界**：H50 挂在 `AssetManager#open` 上，`theme/` 前缀一律改写；
   H40/H41 挂在主题解析/位图加载器上，也不区分是哪套皮肤。
   第三方皮肤同样会走这些入口 ⇒ 我们把它一起改了。
2. **ini 改法太粗**：beta3 把整份 ini 解析成段表再**重新序列化**写回。
   第三方皮肤的 ini 里可能有注释、续行、重复键、无等号行，
   重新拼回去就会变形 ⇒ 主题解析抛异常 ⇒ 输入法视图起不来（表现就是「键盘呼不出来」）。
3. **没有熔断**：出错只记日志，下一次打开继续错。

### 改法（三条铁律）
1. **只改内置默认皮肤**：`isDefaultTheme(path)` —— 只放行 `theme_default` / `theme/…`；
   命中 `sogou_custom` / `/themes/` / `.theme/` / `theme_custom` / `unPackThemeTmp` /
   `phoneTheme` / `.ssf` 的一律**原样放过**（并计入 `skintp=` 计数）。
   第三方皮肤是用户自己挑的外观，我们不该碰，也碰不起。
2. **ini 只做行内取值替换**：先用段表算出「哪几个键要变成什么」（diff），
   再回到**原始文本**逐行只替换这些键的值，其余字节一个不动；
   替换后还要复核「段数/段名/键数」与原文完全一致，不一致就**放弃改写、返回原文件**。
3. **熔断**：连续失败 3 次 → 本进程整体停用这一层（日志 `circuit breaker OPEN`），
   宁可没有皮肤，也不能让输入法起不来。

### 状态卡新增
`第三方跳过=N`（`skintp`）—— 你在用第三方皮肤时这个数应该 > 0，说明我们确实放过了它。

### 构建与验证
- `versionCode 39` / `2.0.2-beta4`，`AppleInputForSogou_v2.0.2-beta4.apk` = 22,754,885 B
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级）
- `classes.dex` 155,272 B：**120 个类全部 `Lcom/chiron/iosemoji/*`**，无 `de/robv` 定义
- 已确认含 `isDefaultTheme`、`skintp`、`sogou_custom`、`unPackThemeTmp`、`circuit breaker OPEN`
## v2.0.2-beta3 — 皮肤改到「资源层」：不再依赖混淆类名 + 治「连不上」

### 用户反馈
> 它是能够连接上的，但是它不能应用皮肤。然后真正的 oppo 机主，它又说连不上，我又没有 oppo 手机，我又做不了测试。

两条都不需要我手上有机器就能改对：一条靠**换拦截层**，一条靠**换配置通道**。

### 1. 皮肤：新增资源级 hook（H50 `AssetSkinHook`）
之前所有皮肤改动都挂在「主题解析类」上（小米 `parse.utils.b#a` / OPPO `defpackage.yy1#a`），
只要 OEM 版把类改名，整条链就断。但**所有版本读皮肤资源的物理动作是同一个**：

```
context.getAssets().open("theme/theme_default/1080/res/black/key_qwerty.png")
```

于是直接在 `android.content.res.AssetManager#open(String)` / `open(String,int)` 上做后置替换：

- `*.ini`（布局/颜色/图片清单）→ 按行解析成段表 → `IosSkin.patchIni(...)` 改颜色 → 序列化回文本流；
- `*.png` → 解码 → `IosSkin.synth(...)` 按预设重绘键帽 → 重新编码成 PNG 流；
- 其它一律原样放行；emoji / lottie / 字体明确排除，不碰。

好处：**与类名、与版本、与 OEM 无关**；坏处：只有「从 assets 读」时才命中——
所以它同时是探针，把见过的皮肤资源路径记下来（`asample=`），一看就知道该版是不是改成解包到文件了。

### 2. 配置：「连不上」改成不依赖广播
- `ConfigBridge` 新增**定时重读**：进程启动后 2/5/10/20/40/60 秒各读一次模块 App 的
  `ContentProvider`（普通 Binder 调用，ColorOS/HyperOS 拦得住广播、拦不住它）；
- 新增 `resyncNow()`，挂在 **H02 键盘装上**的时机上 —— 「改完设置 → 切回输入框 → 键盘重弹」
  就对齐一次配置，等于绕开广播；
- 状态串新增 `resync=`（重读次数）与 `asample=`（资源样本），设置页对应显示
  「资源=open/theme/ini/img」「重读=N」「读到的皮肤资源：…」；
- 「兼容信息 → 作用域」不再写死两个包名，改为按 `Protocol.TARGETS` 列出（**含 OPPO 包名**）——
  之前这行漏了 OPPO，容易让人以为不用勾作用域。

### 构建与验证
- `versionCode 38` / `2.0.2-beta3`，`AppleInputForSogou_v2.0.2-beta3.apk` = 22,754,885 B
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（同包名覆盖升级）
- `classes.dex` 152,828 B：**120 个类全部 `Lcom/chiron/iosemoji/*`**，无 `de/robv` 定义；
  已确认含 `AssetSkinHook`、`asample`、`resync`、`scheduleResync`、`H50 asset skin hook ready`

### 需要用户回报的三行（决定下一步往哪修）
1. `asset=openN/themeN/iniN/imgN` —— `theme` 是 0 说明皮肤不是走 assets 读的
2. `asample=…` —— 实际读到的资源路径长什么样
3. `H41 bitmap getters hooked on …: N/6` —— 类名这条路挂上没
## v2.0.2-beta2 — 修好 OPPO 定制版「皮肤完全不生效」

### 用户反馈
> 我试了一下 oppo 的定制版，它是用不了我那个皮肤的。你看一下它是不是路径不一样的。

### 先排除「路径不一样」
解包 OPPO 的 APK 逐项核对，**资源路径与小米版完全一致**：

| 资源 | OPPO | 小米 |
| --- | --- | --- |
| `assets/theme/thememanifest.ini` | 296 B | 296 B |
| `assets/theme/file_name_mapping_1080.ini` | 146,736 B | 119,390 B |
| `assets/theme/theme_default/theme.ini` | 759 B | 同结构 |
| `theme_default/1080/{layout,port,land,res}` | 有 | 有 |
| 键帽 png 名（`key.png`/`key_qwerty.png`/`key_func.png`/…） | 一致 | 一致 |

（几何值有差异但不在本次问题范围内：OPPO `py_9.ini` 是 `H=0.58333`、`PADDINGS=0,0,0,0`、`ROW1 H_OFFSET=0.175`。）

### 真因：位图入口的方法名/重载不同
- 小米版位图统一走 `com.sogou.theme.common.j#f(Context,String,boolean,boolean)`，所以旧 H41 只挂了这一个固定签名。
- OPPO 定制版这一套被塞进默认包，类是 `defpackage.nm1`，常用的是**带缓存**的 `k(...)` 与 **Drawable** 的 `e(...)`，名字与参数个数都不同。
- 结果：H41 在 OPPO 上**一次都没触发** → 皮肤整套不生效（ini 那条线 H40 是好的，所以「配置能连、开关能点，但键盘没变」）。

### 改法
1. H41 不再写死签名：`GETTER_NAMES = {f, g, j, k, d, e}` 逐个 `XposedBridge.hookAllMethods(cls, name, …)`，命中哪个算哪个（`H41 bitmap getters hooked on defpackage.nm1: N/6`）。
2. 结果类型兼容 **Bitmap / BitmapDrawable**（Drawable 用 `new BitmapDrawable(res, tinted)` 包回去，不改变返回类型）。
3. 参数不按位置猜：`pickPath()` 挑「含 `/` 或以 `.png/.ini/.webp` 结尾」的 String，`pickContext()` 挑 Context，缺了就用 `HookState.context()`。
4. 诊断补两块：`skinse=`（见到但没处理的资源数）+ `seen=`（`原因:文件名` 列表，最多 15 条），设置页「连接状态」卡直接可看。
5. 删掉重复的 H43（`g/k` 已由 H41 统一挂，避免重复计数）。

### 构建验证
- `AppleInputForSogou_v2.0.2-beta2.apk` = 22,750,789 B，`versionCode=37`、`versionName=2.0.2-beta2`、`application-label='Apple Input for Sogou'`。
- 证书 `CN=Android Debug`，SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`（与 v1.7/v1.8 一致 ⇒ 可覆盖升级）。
- `classes.dex` 146,180 B，116 个类全部 `Lcom/chiron/iosemoji/*`，`de/robv` 定义 0；dex 内含 `skinse`、`seen=`、`bitmap getters hooked on `、`defpackage.nm1`、`defpackage.yy1`。

### 装完请回报（OPPO 上）
日志 TAG `AppleInputForSogou`：
- `H41 bitmap getters hooked on defpackage.nm1: N/6`（N=0 说明入口名还没猜对）
- `H40 skin ini patched: …`（有这行说明 ini 线正常）
- 设置页「连接状态」里的 `skini= / skimg= / skinse= / seen=` 四个数
## v2.0.2-beta1 — 多定制版支持：OPPO 定制版也能改皮肤

小米版一直是适配目标，用户这次给了 **OPPO 定制版**（`com.sohu.inputmethod.sogouoem`，
`10.32.0622.2607201151`，versionCode 2570，6 个 dex / 34,599 类）。
直接装上去会发现「模块连不上」——因为两个版本的混淆结果完全不同。

### 为什么不一样（取证）

OPPO 版用 `-repackageclasses ''` 把整个主题/皮肤模块塞进了**默认包**：

| 功能 | 小米版 | OPPO 定制版 |
| --- | --- | --- |
| 皮肤 ini 入口 | `com.sogou.theme.parse.utils.b#a(String)` | `defpackage.yy1#a(String)` |
| ini 行解析器 | `com.sogou.theme.parse.utils.c#n(BufferedReader)` | 未定位（诊断 hook 跳过） |
| 皮肤位图入口 | `com.sogou.theme.common.j#f(Context,String,boolean,boolean)` | `defpackage.nm1#j(Context,String,boolean,boolean)` |
| 位图缓存清理 | `common.j#e()` 单例 → `j()` 缓存 | `nm1#f()` 单例 → `n()` 缓存 |
| 内置表情包工具 | `expression.ExpressionUtil#u(AssetManager,String,String)` | `expression.w#x(AssetManager,String,String)` |
| 根容器（setKeyboardView） | `com.sohu.inputmethod.ui.frame.NormalIMERootContainer` | 同名存在 ✅ |
| 键盘外壳（s0 实现） | `com.sogou.lib.bu.ui.component.keyboard.d` | 未定位 ❌ |
| 候选视图（`r2`: Z7/N7） | `com.sohu.inputmethod.sogou.r2` | 未定位 ❌ |

`com.sogou.theme.parse.entity.b` 这类**返回类型没被改名**，所以取值方式（`b()` 拿 `ArrayMap`）两边一样。

### 改法

新增 `compat/Targets.java`：把「同一功能在各定制版里的类名/方法名」做成一张表，
`HookEntry` 按 `lpparam.packageName` 选表，所有 hook 都从表里取名字：

- `Targets.XIAOMI` —— 小米定制版（原行为，一个字母没改）；
- `Targets.OPPO` —— OPPO 定制版：皮肤/表情两类填了核对过的新名字，
  **没核对到的（键盘外壳、候选视图、b0/InputLogic/presenter.a）一律填 `null`**，
  对应 hook 直接跳过并打日志 `keyboard facade not located for OPPO 定制版, keyboard hooks skipped`；
- 表里没有的包名回落到 `XIAOMI`，保持向后兼容。

配套改动：
- `ThemeSkinHook.install(cl, targets)`：H40/H41 用表里的名字；H42（ini 解析探针）在该版没定位到解析器时不装；
  H43（缓存取法）小米 `g` / OPPO `k` 都试；`dropCaches()` 单例 `e()`/`f()`、缓存 `j()`/`n()` 都试。
- `GestureHook.install(cl, targets)`、`GroupFilterHook.install(cl, targets)`：表情翻页/分组过滤的类名同样从表里取，
  OPPO 版 `ExpressionUtil` 未定位时 `isReady()` 返回 false 且打日志，不会误 hook。
- `Protocol.TARGETS`、`xposed_scope`、`<queries>` 都加上 `com.sohu.inputmethod.sogouoem`。

### 构建与验证

- `build/beta-oppo/AppleInputForSogou_v2.0.2-beta1.apk` = **22,750,789 B**，versionCode **36** / `2.0.2-beta1`，
  证书 SHA-256 `4fbf65ed…c207`（与 v1.7 / v1.8 / 2.0.x 一致 ⇒ 同包名覆盖升级）。
- dex 145,936 B，118 个类**全部** `Lcom/chiron/iosemoji/*`，`de/robv` 定义仍为 0（桩未被污染）；
  字符串表里可查到 `defpackage.yy1` / `defpackage.nm1` / `com.sogou.expressionplugin.expression.w` /
  `com.sohu.inputmethod.sogouoem` / `keyboard facade not located for `。

### 还没做的

OPPO 版的「自绘键盘」与「候选探针」两条线**未适配**——这两条依赖 `b0`、`InputLogic`、`keyboard.d`、`r2`，
在 OPPO 版里全部改名/搬家，还没定位到。皮肤与表情分组是 OPPO 版当前可用的部分。
需要真机回报：装到 OPPO 定制版后，日志里应有
`targets: OPPO 定制版(com.sohu.inputmethod.sogouoem) skin=ok kb=skip cand=skip emojiFilter=ok emojiSwipe=ok`，
再看设置页诊断行的 `skini=` / `skimg=` 是否在增长。
## v2.0.1-beta4 — iOS 深色底色压到 #222223

底色从 `#2F2F2F` 改为 **`#222223`**（按用户要求的 `#22223` 深灰域），键 `#6D6D6F` 与字 `#FDFDFE` 不变；
功能键与按下态在新色阶上重推，保证仍是同一套过渡而不是跳色：

| 层级 | beta3 | **beta4** | 推导 |
| --- | --- | --- | --- |
| 键盘底 / 候选栏 / 工具条 | `#2F2F2F` | **`#222223`** | 用户指定 |
| 功能键 | `#4A4A4C` | **`#444445`** | 底→键 45% |
| 功能键按下 | `#56565A` | **`#4F4F51`** | 底→键 60% |
| 按键 | `#6D6D6F` | `#6D6D6F` | 不变 |
| 按键按下 | `#7B7B7E` | **`#7F7F80`** | 键提亮 12% |
| 文字 | `#FDFDFE` | `#FDFDFE` | 不变 |

对比度：键 vs 底从 2.6:1 提到 **3.1:1**（更容易看出键帽边界），文字 vs 键仍为 5.2:1。

- `versionCode 35` / `2.0.1-beta4`，`AppleInputForSogou_v2.0.1-beta4.apk` 22,746,693 B；
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级 v1.7 / v1.8 / alpha2–alpha12 / beta1–beta3）。
## v2.0.1-beta3 — iOS 深色皮肤按 iPhone 17 实测取色重建

### 三个基准色（用户提供）

| 部位 | 色值 |
| --- | --- |
| 键盘底色 | `#2F2F2F` |
| 按键 | `#6D6D6F` |
| 文字 | `#FDFDFE` |

### 其余层级按同一色阶过渡

取「底色 → 按键」这段做线性过渡，按下态在按键上提亮，文字只有一个值：

| 层级 | 色值 | 怎么来的 |
| --- | --- | --- |
| 键盘底 / 候选栏 / 顶部工具条 | `#2F2F2F` | 基准色（iOS 的候选条与键盘是同一条底） |
| 功能键（123 / ⌫ / ⏎） | `#4A4A4C` | 底色→按键 **45%** |
| 功能键按下 | `#56565A` | 底色→按键 **60%** |
| 按键 | `#6D6D6F` | 基准色 |
| 按键按下 | `#7B7B7E` | 按键提亮 **12%**（同色相） |
| 长按气泡底 | `#6D6D6F` | 与按键同色 |
| 文字（含按下） | `#FDFDFE` | 基准色 |
| 强调色（候选高亮 / 首候选） | `#0A84FF` | iOS 系统蓝，未改 |

投影保留极轻微（Alpha `0x33`、位移 1px），底边分隔线仍画 —— 这两点是 iOS 键帽"浮一格"的关键，去掉会变成一片平灰。

### 构建

- `versionCode 34` / `2.0.1-beta3`，`AppleInputForSogou_v2.0.1-beta3.apk` 22,746,693 B；
- `classes.dex` 142,556 B，非 `com.chiron.iosemoji` 类 **0** 个；
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级 v1.7 / v1.8 / alpha2–alpha12 / beta1–beta2）。

### 说明

浅色 iOS 那一套没动。深色这套现在是"用户给的三个真实色值 + 同色阶推导"，不再是猜的。
## v2.0.1-beta2 — 再加三套皮肤：幽墨 Material / 幽墨 Pixel / 樱花粉

### 来源

按用户给的三个项目参考（只取"看得见的视觉参数"，不搬代码）：

| 参考项目 | 取到的东西 |
| --- | --- |
| `Sraw/youmo-ime`（幽默输入法，fcitx5-android） | `ThemePreset.kt` 里 `MaterialLight/Dark` 与 `PixelLight/Dark` 的**完整配色**（底色/键帽/功能键/候选文字/accent）—— 这两套是成熟的桌面级配色，直接按值搬进预设 |
| `NEORUAA/WeType_UI_Enhanced`（微信输入法美化模块） | 其"示例主题色 `#FB7299` + iOS 27 设计稿"的思路 → 做一套**樱花粉**（粉白底 + 粉键帽 + `#FB7299` accent） |
| `Hao-o0o/WeTypeHookR`（震动模块） | 无配色可参考（纯震动），跳过 |

### 新增预设

| id | 名称 | 浅色底 / 键 / 功能键 / accent | 深色底 / 键 / 功能键 / accent |
| --- | --- | --- | --- |
| `material` | 幽墨 Material | `#ECEFF1` / `#FBFBFC` / `#DFE2E4` / `#5CB5AB`（青绿） | `#263238` / `#404A50` / `#313C42` / `#6EACA8` |
| `pixel` | 幽墨 Pixel | `#EEEEEE` / `#FFFFFF` / `#E1E1E1` / `#4285F4` | `#2D2D2D` / `#464646` / `#373737` / `#5E97F6` |
| `sakura` | 樱花粉 | `#FFF1F5` / `#FFFFFF` / `#FFE0EA` / `#FB7299` | `#241A1E` / `#3A2C33` / `#2E2228` / `#FB7299` |

`IDS` 顺序 = 设置页顺序：`ios → wechat → google → material → pixel → sakura → monet`。

### 构建

- `versionCode 33` / `2.0.1-beta2`，`AppleInputForSogou_v2.0.1-beta2.apk` 22,746,693 B；
- `classes.dex` 142,544 B，非 `com.chiron.iosemoji` 类 **0** 个；
- dex 内确认 `material` / `pixel` / `sakura` / `樱花粉` / `幽墨 Material` / `幽墨 Pixel`；
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（覆盖升级 v1.7 / v1.8 / alpha2–alpha12 / beta1）。
## v2.0.1-beta1 — 微信皮肤重做 + 候选栏底色独立（barBg）

### 1. 微信皮肤"还是不像"的三个具体原因

之前那一版只是把 iOS 的白键灰底换成米灰，动作太小，观感区别不出来。这次按微信键盘自己的结构改：

| 部位 | 微信键盘真实观感 | 这一版 |
| --- | --- | --- |
| 功能键（123 / ⌫ / ⏎ / 中英） | **没有键帽**，直接画在底色上 | 功能键色 = 底色 `#EDEDED`（原来 `#E3E3E3`，能看出一个方块） |
| 键帽圆角 | 比方正，约 6–8dp | `radiusRatio` 0.10 → **0.16** |
| 投影 | 几乎没有 | 位移 1 → **0.5**，Alpha `0x14` → `0x12` |
| 候选栏底 | **白底**，跟键盘的米灰不同 | 新增 `barBg` 字段，微信 = `#FFFFFF`（原来一律用键盘底色） |
| 夜间 | 黑灰 `#111111` 底 / `#303030` 键 / 候选栏 `#232323` | 同（原来底色 `#1A1A1A` 偏亮、候选栏与底同色） |

### 2. `barBg`：候选栏 / 顶部工具条不再强制跟键盘同色

新增 `SkinPreset.barBg`（iOS = 与底色同 `#D1D4D9`；微信 = 白 `#FFFFFF`；Google = 白；莫奈 = 底色掺 accent 后的更亮一档）。

配套三处：
- `IosSkin.isBarImage()`：把 `candidate*` / `cand_bg*` / `bg_cand*` / `first_cand*` / `more_cands*` / `function_bg*` / `float_function*` / `cloud_bg*` / `bg_cloud*` / `bg_composing*` 判定为"条类底图"，平铺时取 `barBg`，其余取 `kbBg`；
- `patchIni()` 里 8 个候选/工具条段的 `BG_COLOR` 改用 `barBg`，文字对比度也按 `barBg` 实算；
- 候选高亮（accent）的对比度基准由 `kbBg` 改为 `barBg`。

### 3. 构建

- `versionCode 32` / `2.0.1-beta1`，`AppleInputForSogou_v2.0.1-beta1.apk` 22,746,693 B；
- `classes.dex` 141,200 B，`dexdump` 非 `com.chiron.iosemoji` 类 **0** 个；
- dex 内确认 `isBarImage` / `barBg` / `blend` / `separate` / `candidate_bg` / `more_cands_bar_bg` / `first_cand_bottom` / `function_bg`；
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（同包名覆盖升级 v1.7 / v1.8 / alpha2–alpha12）。

### 4. 仍待确认

- 回车/发送键的绿色：`py_9.ini` 里 `[Key_Enter]` 没有独立 `BG_IMAGES`，回车用的是字母键底图 `key_qwerty.png`，**按文件名上色做不到"只有发送键变绿"**，需要在键盘组件层单独处理（后续版本）。
- 若你看到"微信仍然像 iOS"，请截图或告诉我是哪一块（底色 / 键帽 / 功能键 / 候选栏 / 文字），我按部位单独调，每套皮肤的每个色值都是独立参数。
# v2.0.0-alpha12 — 三套皮肤「照着原生抄」+ 顶部工具条终于跟着一起画

### 用户反馈

> 「那个微信的皮肤。你没有学习到它配色，然后 google 那个。莫奈取色的色调有点不够氛围感。
> 然后上面那个工具栏没有统一到啊。」

## v2.0.0-alpha12 — 微信/Google/莫奈配色重写 + 候选栏与顶部工具条统一

### 1. 顶部工具条没统一：不是配色问题，是**那几张底图根本没被匹配到**

候选栏/工具条那一片的底色图在皮肤里叫 `candidate_bg*.png`、`first_cand_bottom.png`、
`more_cands_bar_bg.png`、`function_bg.png`、`candidate.png / candidate_p.png`（每格一张）。
而旧的名字规则写的是 `bg_candidate`（词序反了）→ **一个都没匹配上**；
更糟的是 `more` / `tool` 在「图标黑名单」里，`more_cands_bar_bg` 还会被当成图标跳过。

```
键盘底换了色 ✅   候选条/工具条底还是搜狗原来的灰 ❌   ← 你说的"上面那个工具栏没有统一到"
```

现在：
- 新增 `isBgImage()` 白名单，并且**放在图标黑名单之前**判断（背景图不会被 `more/tool` 误杀）；
  覆盖 `candidate_bg*`、`candidate(_n/_p)`、`candidate_tab*`、`first_cand_bottom`、
  `more_cands_bar_bg`、`more_cands_filter*_bg`、`function_bg`、`float_function*_bg`、
  `cloud_bg*`、`bg_cloud*`、`bg_composing*`、`blank_shadow_bg`、`bg_container_key` 等；
- `patchIni` 不再只改 8 个写死的段名，改成**语义匹配全部候选/工具条段**
  （`CandidateView` / `CandidateWordView` / `CandidateTabView` / `CandidateGridView` /
  `FirstCandidate*` / `CandidateViewWeixin` / `CandidateViewDoubleLines` / `More_Cands_Bar` /
  `Translate_Bar` / `SymbolGridView` / `ComposingView` / `CloudView` / `SmartBar` …），
  底色统一成键盘底色、文字色按对比度算。

### 2. 微信：按微信自己那套键盘重写配色

之前只是「浅灰底 + 白键 + 绿强调色」，太笼统。微信键盘的真实观感是：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 底色 | `#F2F2F2`（偏白） | **`#EBEBEB` 米灰**（微信那种略带暖的灰） |
| 功能键 | `#E9E9E9`（和底色几乎一样） | `#E3E3E3`，按下 `#D5D5D5` |
| 键帽形状 | 有 iOS 那道底边线 | **平的**：投影只有一点点（`0x14000000`），没有底边线 |
| 底边线 | 有 | **去掉**（`edgeLine=false`） |
| 夜间 | `#121212 / #3A3A3A` | `#1A1A1A / #303030`（微信夜间是黑灰，不是蓝黑），功能键只比底深一点 `#262626` |

### 3. Google：Gboard 的关系和 iOS 正好相反（已改成对的）

之前是「白底 + 灰键帽」，等于把 Gboard 画反了。Gboard 实际是**浅灰底 + 白键帽浮在上面**：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 底色 | `#FFFFFF` | **`#F1F3F4`**（浅灰） |
| 键帽 | `#F1F3F4`（比底深） | **`#FFFFFF`**（比底亮，靠投影浮起来） |
| 投影 | `0x0F000000` 很淡 | `0x1F000000`，半径 2.5 / 位移 1.5（Gboard 的 elevation 感） |
| 功能键 | `#E8EAED` | `#E8EAED`（和底色接近，只靠图标区分） |
| 夜间 | `#17181B / #4A4F55` | `#1F2023 / #3C4043`，功能键压到 `#2D2F31` |

### 4. 莫奈取色：把 accent 的色相「掺」进底色

之前只是拿 `neutral1_*` 直接铺 —— 结果就是一块灰板，没有氛围感。现在：

- **混色**：底色 = `neutral1_100` 与 `accent1_50` 混 45%；键帽 = `neutral1_50` 与 `accent1_100` 混 18%；
  功能键 = `neutral2_200` 与 `accent1_100` 混 30%；夜间同理（`neutral1_900` ↔ `accent1_900`）；
- **渐变**：底色加一层极轻的竖向渐变（顶部提亮 6%、底部压暗 5%）—— 整块键盘像从壁纸里长出来的；
- **保底**：混色后键帽可能贴太近，新增 `separate()` 保证键帽/功能键与底色至少 1.6:1 / 1.3:1。

### 5. 构建与验证

- `AppleInputForSogou_v2.0.0-alpha12.apk` 22,746,693 B，`versionCode 31` / `2.0.0-alpha12`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- `classes.dex` 140,664 B，117 个类全部属 `com.chiron.iosemoji`，`de.robv` 定义 0。
- dex 内确认含 `isBgImage`、`isBarSection`、`blend`、`separate`、`lighten` 及
  `candidate_bg` / `more_cands_bar_bg` / `first_cand_bottom` / `function_bg` 等新匹配名。

## v2.0.0-alpha11 — 表情包补齐：分组页签图标 + Unicode 16 新表情

### 用户反馈

> 「为什么我的 emoji 安装之后不像 1.8 版本一样？有一些 emoji，它丢失了那个显示的。」

### 根因（两处，都在 payload.zip 的生成环节，不是 hook 写错）

把 alpha10 的 `payload.zip` 和 v1.8 的逐条比对，**v1.8 有 3868 个条目，alpha10 只有 3850，差 18 个**：

1. **10 个分组页签图标整组缺失**（`smileys_emotion.png`、`people_body.png`、`component.png`、
   `animals_nature.png`、`food_drink.png`、`travel_places.png`、`activities.png`、`objects.png`、
   `symbols.png`、`flags.png`，84×84）。
   `tools/make_payload.py` 的 `--icons` 默认指向 `<仓库>/icons`，而这一版仓库里**根本没有 `icons/` 目录**
   → 脚本每次都打印「缺封面 icons/xx.png」，静默跳过。结果：表情装得上，但**底部页签图标是空白**，
   看起来就像「表情丢了」。
2. **8 个 Unicode 16 新表情没有位图**：素材用的是 emoji-datasource-apple **15.1.2**（3785 张），
   该版本早于 Unicode 16，缺 `1FA89` 竖琴、`1FA8F` 铲子、`1FADF` 溅、`1FAE9` 眼下袋、
   `1FAC6` 指纹、`1FABE` 无叶树、`1FADC` 根茎类、`1F1E8-1F1F6` 萨克岛旗 → 分组里那 8 格空白。

### 修法

- 新增 `icons/`（10 个 84×84 页签图标，随仓库提交，共约 24 KB）。
- 素材升级到 **emoji-datasource-apple 16.0.0**（3793 张），8 个新表情全部有了。
- 重新生成 `payload.zip`：**分组 10 个 / 表情 2067 条 / 写入 2067 张 / 缺 0 张**，
  条目数 **3868** —— 与 v1.8 **逐条完全一致**（`missing_vs_v18 = 0`，`extra_vs_v18 = 0`）。
- `README` §5.1 增加两条「别再踩」的说明与自检方法（条目数应为 3868）。

### 构建与验证

- `AppleInputForSogou_v2.0.0-alpha11.apk` 22,746,693 B，`versionCode 30` / `2.0.0-alpha11`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- APK 内 `assets/payload.zip` 22,613,259 B：3868 条目 / 3817 张表情 png / **10 张封面** / 10 份 `version.json`。
- `classes.dex` 138,028 B，117 个类全部属 `com.chiron.iosemoji`，`de.robv` 定义 0。

## v2.0.0-alpha10 — 深色模式对比度 + 图标（含 Emoji 图标）不再被裁剪/涂色

### 用户反馈

> 「深色模式下一些皮肤的对比度不够清晰。然后那个 Emoji 图标还是显示不全。」

### 1. 深色模式对比度：先修根因，再修配色

**根因一（判断错）**：以前用「路径里有没有 `/black/`」判断深色。但很多资源路径里
既没有 `/black/` 也没有 `/white/`（默认主题 zip、第三方皮肤、缓存目录），一律被当成浅色，
于是夜间出现「白字压白键」。
现在 `IosSkin.isDark(path, ctx)`：明确 `black` → 深色；明确 `white` → 浅色；**都不明确就跟系统夜间模式走**。

**根因二（配色本身太近）**：几套深色预设的键帽与底色对比度实测只有 1.35–1.55:1，等于看不出键位。

| 预设 | 原来（底 / 键帽） | 现在 | 对比度 |
| --- | --- | --- | --- |
| iOS 深色 | `#1C1C1E` / `#636366` | `#0F0F10` / `#6E6E73` | ≈3.2 → ≈5.0 |
| 微信深色 | `#1E1E1E` / `#2C2C2C` | `#121212` / `#3A3A3A` | ≈1.35 → ≈2.3 |
| Google 深色 | `#202124` / `#3C4043` | `#17181B` / `#4A4F55` | ≈1.55 → ≈2.6 |
| 莫奈深色 | `neutral1_900` / `neutral1_800` | `neutral1_900` / `neutral1_700`（按下 600） | ≈1.4 → ≈2.4 |

**根因三（文字色不看底色）**：以前深色皮肤一律配白字。现在新增 `IosSkin.ensureContrast(fg, bg, min)`，
按 WCAG 相对亮度实算：不足 3:1 就换黑/白里更好的那个。作用点：
`DEFAULT_TEXT_COLORS`（拉丁/数字/符号/功能/长按气泡）、`COMMON_TEXT_POPUP_COLOR(S)`、
候选栏与顶部条的 `TEXT_COLOR`、候选选中 `TEXT_HIGHLIGHT_COLOR/STYLE`。
被改写的次数在诊断行里显示为 **对比修正=n**。

### 2. Emoji 图标 / 其它图标显示不全

- 键盘侧：`KIND_KEY` 多了一道**满幅判据** —— 九宫格键帽图是满幅出血的（不透明区 ≥85% 边长），
  而图标/字形四周必有透明边。名字里同时带 `key` 和 `emoji` / `face` 的图最容易误判，这道几何判据专治它。
- 模块 App 侧：分组图标（10 组 Emoji 预览图）改成 `FIT_CENTER` + 1dp 内边距 + 24dp 方框，
  不再被行高裁掉一圈（原先是 22dp 且未显式指定缩放方式）。

### 3. 构建与验证

- `AppleInputForSogou_v2.0.0-alpha10.apk` 22,816,325 B，`versionCode 29` / `2.0.0-alpha10`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- `classes.dex` 138,028 B；`dexdump -f` 列出 **117 个类全部属 `Lcom/chiron/iosemoji/*`**，`de/robv` 定义 0 个。
- dex 内确认含 `ensureContrast`、`contrastFixes`、`isDark`、`skint`。

## v2.0.0-alpha9 — 图标不再被当键帽涂掉（设置齿轮 / 语言旗帜回归正常）

### 用户反馈

> 「基本没有什么问题了，但是有一些他那个图标啊，就是设置菜单，还有一个萨克岛的旗帜的显示有问题，就是搜狗输入法键盘里面。」

### 根因

alpha7/alpha8 为了救「键帽漏改」，加了一条兜底：**文件名里带 `key`、或以 `btn_` / `skey` 开头就算键帽**。
搜狗把**图标**也按这种规则命名（例如 `btn_key_menu.png`、`key_flag_lang.png`），于是：

1. 齿轮、旗帜这类图标被当成键帽；
2. 它们是「透明底 + 单色字形」，亮度分布非常均匀，`hasInk` 判不出"里面有图案"；
3. 结果在图标包围盒里画了一块圆角纯色 —— 用户看到的就是"图标变成一块方块 / 显示错乱"。

教训：**任何基于形状/亮度的启发式都救不了图标**，因为图标的"字形"本身就是一条纯色像素带。

### 改法（三条，全部是收紧）

| 收紧点 | 说明 |
| --- | --- |
| `kindOf` 改严格白名单 | 只认 `key_qwerty*` / `key_us*` / `key_uk*` / `key_number*` / `key_digit*` / `key_char*` / `key_symbol*` / `key_normal*` / `key_pressed*` / `key_function*` / `key_func*` / `key_space*` / `key_bg*` / `key.png` / `key_n.png` / `key_p.png`；整块底色只认 `bg.png` / `bg_cands*` / `bg_cloud*` / `bg_composing*` / `cand_bg*` / `bg_header*` / `bg_smart*` / `bg_top*` / `toolbar_bg*` 这类"明确是背景"的名字 |
| 新增 `IosSkin.isProtected()` | 名字含 `icon / logo / menu / setting / gear / flag / globe / lang / voice / mic / search / more / emoji / face / arrow / back / shift / delete / enter / switch / theme / preview / anim / loading / cursor / sogou …` 一律不碰。用户报的「设置齿轮」和「语言/地区旗帜」就在这里被挡住 |
| 新增覆盖率兜底 `opaqueRatio()` | 真键帽的不透明像素几乎填满包围盒（≥85%）；图标只占一部分。名字万一没被黑名单拦住，就靠这一步保命 —— 宁可这张不改，也不能把图标涂了 |

同时把 `looksLikeKey()` 标记为 `@Deprecated` 并写明"不要再接回来"，避免以后又把图标放进来。

### 诊断行再补一格

`皮肤=ios  ini=调用/改动  图=解码/重画  解析=n  缓存=n  保留=n  跳过图标=n`

**跳过图标** = 因命中黑名单而被直接放过的图标数量。装上 alpha9 后这个数字应该 > 0
（键盘上的齿轮、旗帜、搜索、语音等都在里面），而 **保留** 仍是"图里自带图标而保留的键帽"。

### 构建与验证

- `AppleInputForSogou_v2.0.0-alpha9.apk` 22,816,325 B，`versionCode 28` / `2.0.0-alpha9`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- `classes.dex` 136,516 B；`dexdump -f` 列出 **117 个类全部属 `Lcom/chiron/iosemoji/*`**，`de/robv` 定义 0 个。
- dex 内已确认含 `opaqueRatio`、`isProtected`、`skinp`（新增诊断键）。

## v2.0.0-alpha8 — 「重启输入法」按钮 + 按搜狗原始资源与平台规范重画键帽

### 1. 新增：重启输入法按钮

设置页「连接状态」卡里加了一个橙色按钮 **「重启输入法（重载皮肤）」**：
给搜狗进程发 `ACTION_RESTART` 有序广播，进程收到后先把状态回给 App，再结束自己
（`Process.killProcess(myPid())`）；系统下次需要输入法时会重新拉起它。

效果等同「系统设置 → 应用 → 搜狗输入法 → 强制停止」，但不用离开这一页。
换皮肤、换深浅色、改开关后观感没变时，按一下它是最干净的重载方式。

### 2. 键帽重画：从"自己发明参数"改成"读搜狗原图 + 平台规范"

alpha5–alpha7 的画法是直接在**不透明包围盒**里画圆角矩形，问题有三处，都已修：

| 问题 | 原来 | 现在 |
| --- | --- | --- |
| 相邻键粘连 | bbox 常常把整格占满，平铺后键与键之间没有缝 | 按高度 2% 内缩出**键缝**，再画键帽 |
| 圆角对不上 | 只用固定比例 `height × ratio` | 先从**原图 alpha 通道**测出这张资源自己的圆角（`detectRadius`），再取「原图圆角」与「预设规格圆角」较大值 |
| 图标被刷掉 | 平涂会把 ⌫ / ⏎ 这类**画在图里的图标**一起刷掉 | 用 `hasInk` 判断盒内是否存在明显对比像素（图标/文字），有则**原样保留不重绘**，只改纯色键帽 |

另外去掉了 alpha5 那圈硬描边（iOS 键帽只有一层极淡落影，没有描边），
投影改为画在键缝里，视觉上更接近 iOS / Material 3 的观感。

### 3. 诊断行补一个数字

皮肤诊断行现在是：

`皮肤=ios  ini=调用/改动  图=解码/重画  解析=n  缓存=n  保留=n`

其中 **保留** = 因"图里自带图标"而按设计跳过的张数（不是失败）。

### 4. 构建与验证

- `AppleInputForSogou_v2.0.0-alpha8.apk` 22,816,325 B，`versionCode 27` / `2.0.0-alpha8`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- `classes.dex` 133,344 B，`dexdump` 列出全部类均属 `com.chiron/iosemoji`（非本模块 0 个）；
  已确认包含 `ACTION_RESTART` / `detectRadius` / `hasInk` / `skink` 等新符号。

## v2.0.0-alpha7 — 把「改皮肤」变成可测量：修掉键帽漏改的真 bug + 加皮肤诊断

### 用户反馈（这一版要解决的问题）

1. 「怎么把全部皮肤都变成莫奈取色？」——选哪套都看不出区别。
2. 「根本没有解决任何一个皮肤的圆角」——键帽圆角一直是搜狗原来的样子。

### 找到的真 bug（alpha6 的）

`IosSkin.colorOf(name, preset)` 内部会**再调一次** `kindOf(name)` 重新分类。
而 alpha7 之前用来「兜底」的那条路（图名不认识但看着像键帽）恰好被这一步抵消：
`kindOf` 返回 `KIND_NONE` → `colorOf` 立刻返回 0 → 图被跳过。
结果就是：**只有名字正好在清单里的图会改，改不中的图一张也没改**，用户看到的就是
"圆角没变、四套皮肤看起来一样"。已新增 `colorOfKind(name, kind, preset)`，
让调用方把**已判定**的 kind 传进来，不再二次分类。

### 这一版做了什么

- 新增 `IosSkin.looksLikeKey(name)`：文件名含 `key` / 以 `btn_` / `skey` 开头即视为键帽，
  避免第三方皮肤改个名字就整片漏改。
- `isSkinPath()` 放宽：不再要求路径同时含 `theme` 与 `/res/`，兼容
  `/themes/<id>/`、`.theme/`、`unPackThemeTmp/` 这些皮肤落盘位置；同时排除
  `emoji/expression/lottie/preview/face/doutu`。
- 新增三个**只计数不改行为**的探针，用来判断"到底卡在哪一环"：
  - `H42` `com.sogou.theme.parse.utils.c#n` —— ini 是否真的在解析；
  - `H43` `com.sogou.theme.common.j#g` —— 是否一直命中位图缓存（那样就不会经过我们的重绘）；
  - H41 内部统计「解码次数 / 重画次数 / 没认出来的图名」。
- 设置页「连接状态」卡新增一行**皮肤诊断**，格式：
  `皮肤=ios  ini=被调用/已改动  图=解码/已重画  解析=n  缓存=n`，
  下面一行直接列出**没认出来的图名**（最多 12 个）。**长按这一行即可复制**。
- `ConfigBridge.state()` 把这些数字回传（`skin=` / `skini=` / `skimg=` / `skinq=` / `skinc=` / `skip=`）。

### 怎么用这几个数字判断

| 现象 | 含义 | 下一步 |
| --- | --- | --- |
| `ini=0/0` 且 `解析=0` | 皮肤根本没走 ini 解析入口（可能用的是缓存主题或别的皮肤机制） | 需要换入口 hook |
| `ini>0` 但 `已改动=0` | 键名对不上，patch 没落 | 把 `skip=` 里的段名发我 |
| `图>0` 但 `已重画=0` | 图解码了但没认出来 | 把 `skip=` 里的图名发我 |
| `缓存` 远大于 `图` | 一直命中缓存，重绘没机会跑 | 需要清缓存/换入口 |
| 都正常但仍不像 | 颜色到位、形状被搜狗其它层覆盖 | 需要继续找覆盖层 |

### 构建与验证

- `AppleInputForSogou_v2.0.0-alpha7.apk` 22,812,229 B，`versionCode 26` / `2.0.0-alpha7`；
  证书 SHA-256 `4fbf65ed…c207`（同签名覆盖升级）。
- `classes.dex` 130,180 B，`dexdump` 列出 110 个类**全部** `Lcom/chiron/iosemoji/*`，
  无 `de/robv` 定义；已确认包内含 `looksLikeKey` / `colorOfKind` /
  `H42_theme_ini_parser` / `H43_theme_bitmap_cached` / `skimg` 等新符号。

### 关于「装个 Android Studio 看看」

Android Studio 只能**编译打包**，它看不到你手机上搜狗输入法实际画出来的东西 ——
同一条 hook 在你机器上命中、在我这边不可能复现。真正能定位的只有**运行时数字**，
所以这一版的核心产出是上面那行诊断：装上 alpha7 → 点开键盘 → 回模块 App 的
「功能开关 / 兼容信息」，长按「皮肤 / 生效值」复制，把那一行粘给我即可。

## v2.0.0-alpha6 — 四套皮肤可选：iOS / 微信 / Google / 莫奈取色

### 为什么要多套

用户反馈「现在这个皮肤还是没有达到 iOS 的那种风格」，并且希望在 iOS 之外再要
**微信**、**Google 输入法（Gboard / Material 3）**、**莫奈取色（Android 12+ 动态配色）** 三套。
单一配色不可能同时满足三种审美，于是把「配色」从写死改成**可切换的预设**。

### 这一版怎么改

- **新增 `skin/SkinPreset`**：一套预设 = 底色 / 键帽 / 按下态 / 功能键 / 气泡 / 文字 / 强调色
  + 画法参数（圆角比例、投影、是否画底边分隔）。四套：
  | 预设 | 底 | 键帽 | 功能键 | 强调色 | 画法差别 |
  |---|---|---|---|---|---|
  | `ios` | `#D1D4D9` | 纯白 `#FFFFFF` | `#ADB3BC` | `#007AFF` | 圆角 0.12 + 淡底边 |
  | `wechat` | `#F2F2F2` | 纯白 | `#E9E9E9`（很淡） | 微信绿 `#07C160` | 圆角 0.10 + 淡底边 |
  | `google` | 白 | `#F1F3F4`（键帽比底深） | `#E8EAED` | Google 蓝 `#1A73E8` | 圆角 0.14，无底边 |
  | `monet` | 系统 `neutral1_100` | 系统 `neutral1_50` | `system_neutral2_200` | `system_accent1_500` | 圆角 0.16，取自壁纸配色 |
  莫奈预设走 `Resources.getIdentifier("system_accent1_500","color","android")` 读 framework
  动态配色（API 31+），读不到就退回一组 Material You 兜底色 —— 不会因为取色失败而变难看。
- **`IosSkin` 改为预设驱动**：`patchIni(path, sections, preset)` / `colorOf(name, preset)` /
  `synth(src, kind, color, preset)`；键帽重绘逻辑不变（沿用原图不透明包围盒 → 位置尺寸仍是搜狗的），
  但圆角、投影、底边、配色全部来自预设。
- **切换即时生效**：设置页新增「皮肤风格」行（`ios` / `wechat` / `google` / `monet`），选中后
  - 写 `skin_preset` 到 prefs（冷启动用），
  - 用有序广播连同 7 个开关一起下发，
  - 搜狗进程收到后调用 `ThemeSkinHook.dropCaches()` 清掉 `com.sogou.theme.common.j` 的位图缓存，
    再按新配色重画 —— 不换皮肤也要清缓存，否则还是上一套颜色。
- **配置通道扩展字符串键**：`RuntimeConfig` 增加 `STRINGS` 覆盖层与 `getString/putString`；
  `ConfigProvider` 额外返回 `skin_preset` 列（冷启动也能拿到）；`ModuleConfig.skinPreset()` 优先级
  运行时覆盖 > prefs > 默认 `ios`，非法值回落 `ios`。

### 构建与验证

- `build-alpha6`：`AppleInputForSogou_v2.0.0-alpha6.apk` 22,812,229 B
- `aapt2 dump badging`：`com.chiron.iosemoji` / `versionCode 25` / `2.0.0-alpha6` / label `Apple Input for Sogou`
- 证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`（与 v1.7 / v1.8 / alpha2–alpha5 一致，可覆盖升级）
- `dexdump -f`：105 个类全部 `Lcom/chiron/iosemoji/*`，`de/robv` 定义 0（桩未进包）
- dex 字符串确认含 `skin_preset` / `wechat` / `google` / `monet` / `skin caches dropped`

### 仍待确认（需要真机）

皮肤是否真的换成对应风格、切换后是否**不用重启输入法**就变色、莫奈在你机器上取到的色阶是否符合预期。

## v2.0.0-alpha5 — 键帽按 iOS 规格「重绘」，对比度与底色统一

### 为什么 alpha4 还不够像 iOS

alpha4 是给搜狗原图**刷一层色**（先铺目标色、再用原图 alpha 做 `DST_IN` 遮罩）。三个后果：

1. **对比度偏灰**：原图自带内阴影/渐变，刷色之后键帽不是 iOS 那种干净的纯白，整体发闷；
   文字色也只在少数键上改到了。
2. **边角不像**：刷色不改形状，圆角仍是搜狗自己的（近似小圆角），iOS 的键帽圆角明显更圆。
3. **候选栏/顶部条没跟键盘连成一片**：alpha4 把候选栏底色设成透明，结果上面一条灰带
   跟键盘的 `#D1D4D9` 底色对不上，看起来像"状态栏那一块没跟着一起画"。

### 这一版怎么改

- **H41 从「重上色」改成「按 iOS 规格重绘」**（`IosSkin.synth`）：
  - 先求原图的**不透明包围盒**——键帽的位置与大小仍然完全沿用搜狗的（所以九宫格拉伸参数、
    按键命中区一个像素都没变）；
  - 在包围盒里画 iOS 圆角矩形，**半径 = 键帽高度 × 0.12**；
  - 先画 1px 暗色再盖主色，露出的一条就是 iOS 键帽下面那道淡分隔线；
  - 配色改为纯白键帽 / 更灰的功能键（浅色），深色态底色 `#2C2C2E` + 中灰键帽 `#636366`。
- **H40 补对比度与底色统一**：
  - 文字色统一拉满：浅色纯黑 `#000000`、深色纯白 `#FFFFFF`（搜狗默认是 `#35373B` 这种发灰的）；
  - 候选栏 / 拼音区 / 云候选 / 顶部智能条（`CandidateBar` / `ComposingView` / `CloudView` /
    `FloatWindow` / `HeaderView` / `KeyboardHeader`）底色**统一成键盘底色**，不再透明；
  - 选中候选文字用 iOS 蓝（`#007AFF` / 深色 `#0A84FF`）。
- **位图分类细化**：`KIND_FILL`（整块底色，平铺）/ `KIND_KEY`（键帽，圆角重绘）/
  `KIND_POPUP`（气泡，圆角更大）；键帽再按「字母键 / 功能键」与「常态 / 按下态」分色。

### 构建与验证

- 产物 `AppleInputForSogou_v2.0.0-alpha5.apk`（versionCode 24 / versionName 2.0.0-alpha5，
  label `Apple Input for Sogou`，证书 `CN=Android Debug` SHA-256 `4fbf65ed…c207`）。
- `dexdump`：定义类 101 个，全部 `Lcom/chiron/iosemoji`，`de/robv` 定义 **0** 个。

### 还需要你确认的三件事

1. 键帽圆角是否接近 iOS（嫌圆/嫌方我调 `RADIUS_RATIO`）；
2. 对比度够不够（键帽纯白 vs 底色 `#D1D4D9`）；
3. 候选栏与顶部条是否已经和键盘连成一片。

## v2.0.0-alpha4 — 换方向：不再盖自绘键盘，改成改搜狗自己的默认皮肤

### 0. 为什么要换方向

- **现象**：装上 alpha2 / alpha3 后，键盘样式会「卡 bug」（键位、底色、候选区错乱）。
- **原因**：搜狗键盘**不是普通 View 树**，而是一棵虚拟组件树自绘
  （`imskit…component.RootComponentView` + 组件，**没有 per-key View**）。
  我们塞进 z=3 槽位的那个 View 只是个外壳：布局、命中测试、候选高度、insets、
  皮肤资源索引全都还是搜狗按自己那套算 —— 两套逻辑必然打架。
- **结论**：不再抢着画键盘，改为**顺着搜狗自己的皮肤引擎改外观**。
  搜狗自己画的键盘，天然不会有布局问题。

### 1. 搜狗皮肤是怎么跑的（取证）

```
assets/theme/thememanifest.ini             主题清单
└─ assets/theme/theme_default/theme.ini    [ThemeInfo] / [Advance]（键盘类型 → 布局 ini）
   ├─ 1080/layout/colors.ini               颜色表
   ├─ 1080/layout/images_config.ini        位图九宫格切边
   ├─ 1080/layout/image_list.ini           逻辑名 → png
   ├─ 1080/port/py_9.ini                   几何：W / H / PADDINGS / ROWS / H_OFFSET
   └─ 1080/res/{black,white}/*.png         键帽、键盘底、气泡等位图
```

关键发现：**ini 与位图各只有一个统一出口**，卡住这两个出口就能整套换皮：

- ini：`com.sogou.theme.parse.utils.b#a(java.lang.String)` [classes2] —— `assets/` 前缀走
  `AssetManager`，否则走文件；解析结果是可变的「段 → 键 → 值」表（`parse.entity.b#b()`）。
- 位图：`com.sogou.theme.common.j#f(Context,String,boolean,boolean)` [classes2] —— 资产走
  `getAssets().open()` + `BitmapFactory.decodeStream`，带缓存。

### 2. 新增两个 hook

| Hook | 目标（DEX 已验证） | 作用 |
| --- | --- | --- |
| `H40_theme_ini` | `com.sogou.theme.parse.utils.b#a(java.lang.String)` | 就地改颜色键：强调色 → iOS 蓝（`#007AFF` / 深色 `#0A84FF`）、候选栏与云候选底色 → 透明、功能键文字色 → 黑白 |
| `H41_theme_bitmap` | `com.sogou.theme.common.j#f(Context,String,boolean,boolean)` | 按文件名给位图重上色：键盘底 / 字母键 / 功能键 / 气泡各用 iOS 灰白色板 |

### 3. 只改颜色，绝不动几何

- **不动 `W`/`H`/`PADDINGS`/`ROWS`/`H_OFFSET`**：几何一变就连锁影响 insets 与命中区，
  这正是之前「样式卡 bug」的根源。
- 位图**只重上色、不改尺寸**：新建同尺寸位图铺目标色，再用原图 alpha 做
  `PorterDuff.Mode.DST_IN` 遮罩 → 形状与尺寸完全保持，`images_config.ini` 的九宫格切边参数继续有效。
- 两个 hook 都每次调用判开关：关掉「iOS 皮肤」，下一次读皮肤即回到搜狗原配色（不是一次性替换）。

### 4. 自绘键盘降级为实验开关

- `theme_skin_enabled`（**新，默认开**）：iOS 皮肤 —— 外观主路径。
- `keyboard_replace_enabled`（**默认改为关**）：自绘键盘（H01/H02），实验性。
- 新增 `config/ConfigMigration`：升级到 alpha4 时一次性把老配置里的
  `keyboard_replace_enabled=true` 关掉、`theme_skin_enabled` 置 true（只跑一次，标记 `migrated_v23_skin`）。

### 5. 构建与验证

- 产物：`AppleInputForSogou_v2.0.0-alpha4.apk` = 22,808,133 B；versionCode 23 / versionName 2.0.0-alpha4；
  `application-label='Apple Input for Sogou'`；证书 `CN=Android Debug`
  SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`（与 v1.7 / v1.8 一致 ⇒ 同包名覆盖升级）。
- `dexdump -f` 复验：定义类 101 个，**全部 `Lcom/chiron/iosemoji`**，`de/robv` 定义 **0** 个
  （alpha3 修的「桩类污染 dex」没有复发）；dex 字符串里可查到
  `hook/ThemeSkinHook`、`skin/IosSkin`、`config/ConfigMigration`、`H40_theme_ini`、`H41_theme_bitmap`、`theme_skin_enabled`。
- 仍未上机验证：皮肤是否真的生效、位图重上色是否覆盖到你用的那套皮肤、开关即时可逆。

## v2.0.0-alpha3 — 设置页真的能用了 + 修掉导致「连不上输入法」的构建缺陷

这一版只修两个问题，都是实测可验证的真 bug。

### 1. 「功能开关」页以前是假开关（已修）

- **症状**：点开设置页，拨开关没有任何反馈，输入法行为也不变。
- **原因**：旧实现只把值写进模块自己的 SharedPreferences，**从不下发到已经运行的搜狗进程**；
  hook 侧又只在进程启动时读一次，且冷启动时用 `XSharedPreferences` 读别的 App 的私有 prefs
  在部分系统上读不到 —— 于是开关永远是默认值。
- **修法（三条通道）**：
  1. **运行中即时生效**：改开关 → 写本地 prefs（冷启动用）**并且**用**有序广播**把所有键值
     直接下发到搜狗进程（`com.chiron.iosemoji.action.CFG`），hook 侧落到内存覆盖层
     `RuntimeConfig`，开关判断每次都读它；
  2. **回执即反馈**：搜狗进程收到后 `setResultData(...)` 回一份状态串，设置页据此弹出
     「已生效：iOS 键盘 → 开」或「已保存：…（搜狗进程未运行，切换输入法后生效）」；
  3. **冷启动也能读到**：新增只读 `ContentProvider`（`content://com.chiron.iosemoji.config/config`），
     搜狗进程启动时用它读一次开关，不再依赖跨进程 prefs 的可读性。
- 设置页新增「连接状态」卡：搜狗进程是否在线、已装 hook 列表、**进程内真正生效的配置值**、搜狗版本。
- 所有开关改为**双向即时可逆**：hook 常驻安装，回调里判断开关 —— 关掉立刻退回搜狗原行为
  （例如关掉「iOS 键盘」会把注入的键盘摘走）。

### 2. 修掉「模块连不上输入法」的构建缺陷（已修）

- **症状**：装上新版后，模块页显示未连接、LSPosed 里看不到本模块的 hook 日志。
- **原因**：构建脚本在缺少官方 `XposedBridgeAPI-82.jar` 时，把 `lsposed/xposed-stubs/*.java`
  当作**源码**参与编译，桩类被打进了 `classes.dex` —— 模块包里出现了**假的**
  `de.robv.android.xposed.*` 类（`XposedBridge`/`XposedHelpers`/`XC_MethodHook`/
  `IXposedHookLoadPackage`），运行时会与框架注入的同名类打架，导致模块内 hook 静默失效。
  用 `dexdump` 对比确认：修前 dex 109,680 B、含桩类；修后 107,320 B、94 个类**全部**是
  `com.chiron.iosemoji.*`，无 `de.robv` 定义。
- **修法**：桩只编译成独立 `xposed-stubs.jar` 作 `-classpath` 用，不进 `sources.txt`；
  d8 打包时再按 `de\robv\` 路径排除一次，双保险。
- 同时加固 attach：搜狗是 Tinker 热修复壳（`SogouTinkerApplication`），模块可能在
  `Application#attach` 之后才被加载，attach hook 永远不触发。现在额外用
  `ActivityThread.currentApplication()` 主动取 Context，并按 0/300/1200/3000/8000ms 重试。

### 构建产物（本机实测）

- `build/alpha3/AppleInputForSogou_v2.0.0-alpha3.apk`，22,804,037 B（含 `assets/payload.zip`）
- `package name='com.chiron.iosemoji' versionCode='22' versionName='2.0.0-alpha3'`，
  `application-label:'Apple Input for Sogou'`，证书 SHA-256 `4fbf65ed…c207`（同包名覆盖升级）

## v2.0.0-alpha2（重构建）— 项目改名为 Apple Input for Sogou

- **项目对外名称**：`Chiron iOS 输入` → **`Apple Input for Sogou`（Apple 输入法 · 搜狗版）**。
  应用名、模块描述、README/文档标题、日志 TAG（`AppleInputForSogou`）同步改名；
  **包名 `com.chiron.iosemoji` 与签名证书保持不变**，所以仍是同包名覆盖升级。
- **仓库改名**：`Cheney34/chiron-ios-input` → `Cheney34/apple-input-for-sogou`（GitHub 自动 301 重定向旧地址）。
- 新增 `tools/build-apk.ps1`：把「无 Gradle」的七步构建固化成一条命令 —— 自动从 `AndroidManifest.xml`
  读 `versionCode`/`versionName`，优先用 `lsposed/app/libs/XposedBridgeAPI-82.jar`，缺失时回退到
  `lsposed/xposed-stubs/`（编译期签名桩，不会打进 APK）。
- APK 重新构建并替换 Release 资产：`AppleInputForSogou_v2.0.0-alpha2.apk`（versionCode 21 不变，
  `application-label` 已变为 `Apple Input for Sogou`，证书 SHA-256 仍为 `4fbf65ed…c207`）。

## v2.0.0-alpha2 — 包名回退 + iOS 界面重写（真实出包，未上机验证）

> alpha1 把包名从 `com.chiron.iosemoji` 改成了 `com.chiron.iosinput`，导致手机上像是「另一个程序」、
> 无法覆盖升级。alpha2 起**包名回退为 `com.chiron.iosemoji`**（与 v1.7/v1.8 一致），并用同一张调试证书签名，
> 因此属于**同包名覆盖升级**：装上会直接替换旧模块，LSPosed 里的条目和作用域不用重配。

### 修复

- **包名回退**：`com.chiron.iosinput` → `com.chiron.iosemoji`（目录、21 个源文件、`AndroidManifest.xml`、
  `assets/xposed_init`、Gradle `namespace`/`applicationId` 全部同步）。证书与 v1.7/v1.8 相同
  （`CN=Android Debug`，SHA-256 `4fbf65ed…c207`），可直接覆盖安装。
- 明确：旧模块的 4 个 hook（`GestureHook`、`GroupFilterHook`、`LogoHook`、`EmojiService` 广播通道）
  **源码逐字搬入、逻辑未改**，只做了包名替换与去 UTF-8 BOM；新增的只有 `H01`/`H02`/`H04`，且各自挂在开关上。

### 界面重写（iOS 风格，全部自绘，不依赖 androidx / Material）

- **模块 App 界面**：`MainActivity` 与 `SettingsActivity` 从 XML 布局改成代码搭建的 iOS 设置页 ——
  大标题、分组卡片、圆角行、右侧 `✓` / `›`、自绘开关 `IosSwitch`、跟随系统深浅色
  （`values` / `values-night` 两套色板 + `IosUi` 取色）。
  功能与旧版一一对应：分组勾选、安装选中 / 安装全部 / 还原、隐藏桌面图标、连接检测、运行日志。
- **输入法键盘界面**：`keyboard/ChironKeyboardView` 重写为整套 iOS 键盘 ——
  字母页（qwertyuiop / asdfghjkl / ⇧ zxcvbnm⌫ / 123·地球·空格·回车）与符号页两套，
  按 `py_9.ini` 的 `H=0.591667` 锁定高度比例以免改变输入法 insets，
  按下时在键上方浮出 iOS 放大气泡，`⌫` 支持长按连删（420 ms 起、65 ms 间隔）。
  `KeyboardTheme` 管配色与比例常量，`KeyView` 只负责画键（功能键用 `Path` 手绘，避免字体缺字形）。
- 文字上屏仍走搜狗：字母/数字用 `TextInjector.key(code)` →
  `InputLogic.onKey(code, {code}, false, 0, 0)`，进搜狗拼音/数字引擎出候选；
  大写字母与中文标点用 `InputInjector.type()` 直接上屏；`⌫`/空格/回车分别用 `-5` / `32` / `10`。
  失败时逐级降级到 `getSogouInputConnection()`（`commitText` / `deleteSurroundingText` / `sendKeyEvent`）。

### 构建产物（本机实测）

- `aapt2`(2.19) + `javac`(17) + `d8` + `zipalign` + `apksigner` 手工链路真实出包
  `AppleInputForSogou_v2.0.0-alpha2.apk`（21.74 MB，含 `assets/payload.zip`）。
- `aapt2 dump badging`：`package name='com.chiron.iosemoji' versionCode='21' versionName='2.0.0-alpha2'
  minSdkVersion:'26' targetSdkVersion:'35' application-label:'Apple Input for Sogou'`。
- 仍未上机验证：H01/H02 是否命中、键盘触摸与 insets 表现，需按 `docs/MVP_PROPOSAL.md` TC 清单跑一遍。
## v2.0.0-alpha1 — 独立仓库首个版本（javac 冒烟编译通过，未上机验证）

### 验证状态

- ✅ `javac -Xlint:all --release 17`（classpath = `android.jar` API 35 + Xposed API 桩 + `R` 桩）：
  全部源码 **0 error**，仅 1 条无害的 `this-escape` 提示。
- ✅ 全仓库扫 `iosemoji|IOSEmoji` 残留 → 0 命中。
- ⚠️ 未做 Gradle 构建（环境无 Android SDK），未在真机/模拟器运行，
  hook 描述符是否在该版本搜狗中真的命中仍需上机按 `docs/MVP_PROPOSAL.md` TC-01~TC-10 验证。

### 构建产物（本机实测）

- 环境无 Android SDK / Gradle，改用 `android.jar`(API 35) + `build-tools 35` 手工链路
  `aapt2 compile/link → javac → d8 → zip → zipalign → apksigner` **真实出包**：
  `AppleInputForSogou_v2.0.0-alpha1.apk`，21.74 MB。
- `aapt2 dump badging` 与 `apksigner verify --print-certs` 均通过
  （`CN=Android Debug`，证书 SHA-256 `4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207`）。
- **首次内置 `assets/payload.zip`**（21.63 MB）：由 `tools/make_payload.py` 从
  `emoji-datasource-apple@15.1.2` 的 64×64 位图生成，10 个分组清单共 2067 项，成功写入 2059 张，
  缺 8 张（`1FA89/1FA8F/1FADF/1FAE9/1FAC6/1FABE/1FADC/1F1E8_1F1F6` —— 该素材版本尚无这几枚新 emoji，
  对应槽位在面板里显示为空白占位）。因此 v2.0.0-alpha2 的「安装表情分组」功能开箱可用。
  按版权要求，位图**不提交进 git**，只作为 Release 资产分发。

### 修复

- 从父仓库搬来的 6 个 Java 文件带 UTF-8 BOM（`\ufeff`），会让 `javac`/Gradle 直接编译失败，
  已统一去除：`EmojiService` / `GestureHook` / `GroupFilterHook` / `Groups` / `LogoHook` / `Protocol`。

### 新增：iOS 键盘显示层替换（Phase 2 骨架）

- `H01` hook `com.sogou.lib.bu.ui.component.keyboard.d#getRootView()`，
  一次覆盖所有 `setKeyboardView(<s0>.getRootView())` 调用点
  （`g0:414/666`、`MainImeServiceDel:2326`、`manager/j:299`、`main/view/c:60`）。
- `H02` hook `NormalIMERootContainer#setKeyboardView(View)` 兜底补塞 z=3 槽位。
- `ChironKeyboardView`：自绘键盘（QWERTY + 123/空格/⌫/⏎），高度按搜狗主题
  `H=0.591667 × 屏宽` 计算，避免改动 `onComputeInsets`。
- `TextInjector`：字符/功能键走搜狗自己的 `InputLogic#onText` / `#onKey`，
  InputConnection 作为兜底 —— **输入引擎完全不动**。

### 新增：配置与兼容

- `ModuleConfig`：7 个开关（总开关、键盘替换、表情分组过滤、表情滑动、Logo 触发词、
  候选探针、视图树诊断），经 `XSharedPreferences` 在搜狗进程内读取，读不到一律用默认值。
- `VersionProfile`：版本白名单 `10.32.21.202607061714`；非白名单版本自动降级为
  `DIAGNOSTIC_ONLY`（只读，不改界面）。
- `HookRegistry`：hook 安装幂等 + 失败隔离 + 统一日志（TAG `AppleInputForSogou`）。

### 新增：诊断

- `CandidateProbeHook`：只读打印候选列表推送（`r2#Z7` / `r2#N7` / `showCandidateWordView`）。
- `ViewTreeDumper`：输入法启动 3 秒后 dump 输入窗口视图树。
- `SettingsActivity`：开关界面 + 版本/兼容信息。

### 新增：文档

- `docs/` 全套静态分析：`APK_OVERVIEW`、`CLASS_MAP`、`VIEW_HIERARCHY`、`EVENT_CALLBACK_MAP`、
  `INPUT_ENGINE_MAP`、`EMOJI_PANEL_MAP`、`FEASIBILITY`、`HOOK_POINTS`（H01–H30）、
  `MVP_PROPOSAL`、`MIGRATION`。

### 变更（自 v1.8 起）

- 模块名 `IOSEmoji` → `AppleInputForSogou`（日志 TAG 同步改名）；**包名保持 `com.chiron.iosemoji`**，便于同签名覆盖升级 v1.7 / v1.8。
- 构建方式：`build.sh`（aapt2 + javac + d8 手写链路）→ **Gradle / Android Studio 工程**。
- 仓库：从 `chiron-apple-emoji` 的 `lsposed-sogou/` 目录拆分独立成库。

### 保留（v1.8 既有能力，行为不变）

- 表情分组安装器（10 组 Apple 风格 emoji，`assets/payload.zip` 由使用者本机生成）。
- 隐藏搜狗自带「组合」「拼装」分组（`GroupFilterHook`）。
- 表情面板左右滑动切组（`GestureHook`）。
- 触发词 → Apple  Logo（`LogoHook`，默认关闭）。

---

## v1.8（父仓库 chiron-apple-emoji）

- 修复 `LogoHook` 默认不再吃「苹果」「pg」这类常用词。
- 更早历史见父仓库 CHANGELOG。
