# signing/

这里放的是**调试签名**，不是发布密钥。

```
debug.keystore
  alias   androiddebugkey
  密码    android（store 和 key 都是这个）
  DN      CN=Android Debug,O=Android,C=US
  SHA-256 4fbf65ed205c4a25bb592673714533c5effbcd234b9c08ed1be20ad7bf53c207
```

## 为什么要提交到仓库里

Android 要求**同包名的更新必须用同一份签名**，否则安装时会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，用户必须先卸载再装（会丢模块配置）。

社区里已经分发的 v1.0 ~ v1.7 都是这份签名签的。如果 CI 每次构建时临时
`keytool -genkeypair` 生成新密钥，产出的包就装不上去。

把这份 debug key 放进来，本地和 CI 就能签出一致的包，零配置。

## 安全性

这是调试密钥，密码固定为 `android`，AOSP 自带的 `~/.android/debug.keystore`
也是同样的定位。它能证明"更新包来自同一个构建流水线"，但不构成任何身份保证
——任何拿到这个文件的人都能签出可覆盖安装的包。

所以：

- **不要**用它签任何要上架、要商用、或涉及签名校验（如 SafetyNet / Play Integrity）的东西；
- 真要正式分发，换成你自己的 release keystore，并通过
  `SIGNING_KEY_B64` Secret 注入（workflow 会优先用 Secret，不用仓库里这份）。
