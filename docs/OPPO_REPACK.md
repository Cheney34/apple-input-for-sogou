> ⚠️ **本路线已被否决（2026-11-06）**：用户明确要求「只走 LSPosed hook，不要重写输入法 APK」。
> 本文仅作技术存档，仓库不再提供任何重打包产物，也不再推荐此做法。
> 皮肤功能请使用模块 v2.0.2-beta2 及以后版本（H40 改 ini + H41 挂全部位图取值器）。
# 把皮肤「烤」进 OPPO 定制版搜狗 APK（免 LSPosed 路线）

> 适用对象：`com.sohu.inputmethod.sogouoem`（OPPO 定制版，版本 `10.32.0622.2607201151`）。
> 目的：不装模块、不改代码，直接把 iOS / 微信 / Google 的键帽配色写进 APK 自带的默认皮肤，
> 重打包成一个"本来就是这个样子"的安装包。

## 一、为什么能这么做

搜狗所有键盘外观都来自 APK 里的 **默认皮肤资源**（`assets/theme/theme_default/`），
引擎启动时从 assets 直接读，不存在"必须联网下载皮肤"的前提：

```
assets/theme/thememanifest.ini          ← 声明 THEME_SET=theme_default,theme_game
  └─ theme_default/theme.ini            ← [ThemeInfo] + [Advance] 键盘类型 → 布局 ini 映射
      └─ 1080/port/py_9.ini             ← 几何（W/H/PADDINGS/ROWS）★不动
         1080/layout/colors.ini         ← 文字色/强调色
         1080/layout/cands.ini          ← 候选栏底色
         1080/res/white/*.png           ← 浅色键帽与底图
         1080/res/black/*.png           ← 深色键帽与底图
         1080/res/black/phoneSkin.ini   ← 夜间文字样式覆盖
```

## 二、两条铁律

1. **只改颜色，不动几何。** `W/H/PADDINGS/ROWS/H_OFFSET` 一动，命中区、键盘高度、
   输入法 insets 全部跟着变 —— 表现就是"键按不中、底下留白"。
2. **位图只改"实心"的底色与键帽。** 透明的高亮/蒙版（如候选词按压层）原样保留，
   否则每个候选词都会变成一块色块；图标（设置齿轮、语言旗帜、emoji）一律不动。

## 三、一步到位

```powershell
$py   = '<bundled python>\python.exe'          # 需要 Pillow
$apktool = '<repo>\ChironIOSInput\tools\apktool.jar'
$decoded = '<解包目录>\oppo_apktool'            # java -jar apktool.jar d -s -f <apk> -o oppo_apktool
$theme = "$decoded\assets\theme\theme_default"

# 1. 烤皮肤（--preset ios|wechat|google）
& $py tools\bake_theme.py --theme $theme --preset ios --mode all

# 2. 重打包（dump 原 dex，不重新编译代码）
java -Xmx4g -jar $apktool b $decoded -o build\oppo_unsigned.apk

# 3. 对齐 + 签名（用自己的 keystore）
zipalign -f -p 4 build\oppo_unsigned.apk build\oppo_aligned.apk
apksigner sign --ks signing\debug.keystore --ks-key-alias androiddebugkey `
  --ks-pass pass:android --key-pass pass:android --min-sdk-version 23 `
  --v1-signing-enabled true --v2-signing-enabled true `
  --out build\SogouOppo_AppleSkin_iOS.apk build\oppo_aligned.apk
```

`bake_theme.py` 的统计输出示例（iOS）：
浅色 `底色 33 / 键帽 20 / 气泡 9 / 保留原样 521`，深色 `32/19/9/520`，
另改 `colors.ini` 19 行、`phoneSkin.ini` 119 行、`cands.ini` 2 行。

## 四、安装前必须知道的三件事

1. **签名变了。** 原包是 Sohu 证书，重打包只能用我们自己的证书 ⇒ **不能覆盖安装**，
   必须先卸载 OPPO 定制版搜狗（预装系统应用需要 root 或 `pm uninstall --user 0 com.sohu.inputmethod.sogouoem`）。
2. **它是"改版应用"，不是插件。** 装上去之后搜狗就是那个样子，不需要 LSPosed / 模块；
   代价是以后再也不能靠 OPPO 官方更新（要更新就得重新烤一遍再装）。
3. **可能触发壳的自校验。** 该包带 `libSGMtlgShell.so`，若启动异常（闪退/白屏），
   说明壳校验签名没过 —— 那就只能回到 LSPosed 模块路线（`v2.0.2-beta2` 起已支持 OPPO 的皮肤 hook）。

## 五、这条路线**不**包含

- 表情包替换（那是模块 `assets/payload.zip` 的活儿，OPPO 原包用它自己的表情资源）；
- 候选栏 iOS 化、长按符号面板、表情面板 iOS 化（属 Phase 4–6，需要改代码而非资源）。

## 六、为什么不上传 GitHub

重打包的 APK 内含搜狗（Sohu）的完整代码与资源，公开分发涉及版权，
因此这些产物**只在本机**输出，不进仓库、不发 Release。仓库里提交的只有
`tools/bake_theme.py`（脚本本身）。
