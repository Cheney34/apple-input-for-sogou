<#
.SYNOPSIS
  Apple Input for Sogou —— 不依赖 Gradle / Android Studio 的可复现打包脚本。

.DESCRIPTION
  只用 Android SDK 的 build-tools（aapt2 / d8 / zipalign / apksigner）+ JDK 就能出包，
  与 README §5 的七步链路一一对应：
    1) aapt2 compile   资源 -> res.zip
    2) aapt2 link      生成 base.apk + R.java
    3) javac           编译 Java（模块源码 + R.java + Xposed API）
    4) d8              class -> classes.dex
    5) 把 classes.dex 写回 base.apk
    6) zipalign
    7) apksigner       用 signing/debug.keystore 签名（与 v1.7 / v1.8 同一张证书）

  环境要求：
    * JDK 17+，且已设置 JAVA_HOME（javac / d8 / apksigner 都从这里找）
    * Android SDK build-tools 目录（里面要有 aapt2、d8.bat、zipalign.exe、apksigner.bat）
    * android.jar（platform-33 / 35 均可，>= compileSdk）

.PARAMETER AndroidJar
  android.jar 的绝对路径，例如 ...\platforms\android-35\android.jar

.PARAMETER BuildToolsDir
  build-tools 目录，例如 ...\build-tools\35.0.0

.PARAMETER VersionCode
  版本号（整数），要与 AndroidManifest 的 versionCode 一致才不会被覆盖，默认取 manifest 里的值

.PARAMETER VersionName
  版本名，同上

.PARAMETER Out
  输出 APK 路径（相对仓库根或绝对路径）

.EXAMPLE
  pwsh -File tools\build-apk.ps1 `
       -AndroidJar "D:\Android\platforms\android-35\android.jar" `
       -BuildToolsDir "D:\Android\build-tools\35.0.0" `
       -Out build\AppleInputForSogou_v2.0.0-alpha2.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AndroidJar,
    [Parameter(Mandatory = $true)][string]$BuildToolsDir,
    [int]$VersionCode = 0,
    [string]$VersionName = '',
    [string]$Out = 'build\AppleInputForSogou.apk',
    [string]$Keystore = 'signing\debug.keystore',
    [string]$KsAlias = 'androiddebugkey',
    [string]$KsPass = 'android',
    [string]$KeyPass = 'android'
)

$ErrorActionPreference = 'Stop'
function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

$Repo   = Split-Path -Parent $PSScriptRoot
$App    = Join-Path $Repo 'lsposed\app\src\main'
$Work   = Join-Path $Repo 'build'
$Gen    = Join-Path $Work 'gen'
$Cls    = Join-Path $Work 'classes'
$DexDir = Join-Path $Work 'dex'

foreach ($p in @($AndroidJar, (Join-Path $BuildToolsDir 'aapt2.exe'), (Join-Path $BuildToolsDir 'apksigner.bat'))) {
    if (-not (Test-Path $p)) { throw "缺少依赖：$p" }
}
if (-not $env:JAVA_HOME) { throw '请先设置 JAVA_HOME（JDK 17+）' }
$Javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
if (-not (Test-Path $Javac)) { throw "JAVA_HOME 下找不到 javac：$Javac" }

# 版本号：默认从 AndroidManifest 读，保证与清单一致
$Manifest = Join-Path $App 'AndroidManifest.xml'
if ($VersionCode -le 0) {
    $m = [regex]::Match([System.IO.File]::ReadAllText($Manifest), 'android:versionCode="(\d+)"')
    if (-not $m.Success) { throw 'AndroidManifest 里没有 versionCode' }
    $VersionCode = [int]$m.Groups[1].Value
}
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $m = [regex]::Match([System.IO.File]::ReadAllText($Manifest), 'android:versionName="([^"]+)"')
    if (-not $m.Success) { throw 'AndroidManifest 里没有 versionName' }
    $VersionName = $m.Groups[1].Value
}

Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, $Gen, $Cls, $DexDir | Out-Null

$aapt2     = Join-Path $BuildToolsDir 'aapt2.exe'
$zipalign  = Join-Path $BuildToolsDir 'zipalign.exe'
$apksigner = Join-Path $BuildToolsDir 'apksigner.bat'
$d8        = Join-Path $BuildToolsDir 'd8.bat'

# 1) 资源
Step "aapt2 compile"
& $aapt2 compile --dir (Join-Path $App 'res') -o (Join-Path $Work 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

# 2) link
Step "aapt2 link (v$VersionName / $VersionCode)"
$base = Join-Path $Work 'base.apk'
& $aapt2 link -o $base -I $AndroidJar --manifest $Manifest `
    -R (Join-Path $Work 'res.zip') -A (Join-Path $App 'assets') --java $Gen `
    --min-sdk-version 26 --target-sdk-version 35 `
    --version-code $VersionCode --version-name $VersionName --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# 3) javac（模块源码 + 生成的 R.java + Xposed API）
Step "javac"
$xposedJar = Join-Path $Repo 'lsposed\app\libs\XposedBridgeAPI-82.jar'
$cp = $AndroidJar
if (Test-Path $xposedJar) {
    $cp = "$AndroidJar;$xposedJar"
    Write-Host '    使用真实的 XposedBridgeAPI-82.jar'
} else {
    Write-Host '    未找到 lsposed/app/libs/XposedBridgeAPI-82.jar，改用 xposed-stubs（仅编译用，不打包）' -ForegroundColor Yellow
}
$srcs = @()
$srcs += Get-ChildItem -Recurse -File -Filter *.java -Path (Join-Path $App 'java') | Select-Object -ExpandProperty FullName
$srcs += Get-ChildItem -Recurse -File -Filter *.java -Path $Gen | Select-Object -ExpandProperty FullName
$stubs = Join-Path $Repo 'lsposed\xposed-stubs'
if (Test-Path $stubs) {
    $srcs += Get-ChildItem -Recurse -File -Filter *.java -Path $stubs | Select-Object -ExpandProperty FullName
} elseif ((Test-Path $xposedJar) -eq $false) {
    throw '既没有 XposedBridgeAPI-82.jar，也没有 lsposed/xposed-stubs，无法编译'
}
$srcList = Join-Path $Work 'sources.txt'
[System.IO.File]::WriteAllLines($srcList, $srcs, (New-Object System.Text.UTF8Encoding($false)))  # 不能带 BOM
& $Javac -encoding UTF-8 -nowarn -source 17 -target 17 -classpath $cp -d $Cls "@$srcList"
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

# 4) d8
Step "d8"
$classesZip = Join-Path $Work 'classes.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($classesZip, 'Create')
Push-Location $Cls
try {
    Get-ChildItem -Recurse -File | ForEach-Object {
        $entry = $_.FullName.Substring($Cls.Length).TrimStart('\').Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entry) | Out-Null
    }
} finally { $zip.Dispose(); Pop-Location }
$dex = Join-Path $DexDir 'classes.dex'
& cmd /c "`"$d8`" --release --lib `"$AndroidJar`" --min-api 26 --output `"$DexDir`" `"$classesZip`""
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
if (-not (Test-Path $dex)) { throw "d8 没有产出 $dex" }

# 5) 把 dex 写回 apk
Step "inject classes.dex"
$un = Join-Path $Work 'unaligned.apk'
Copy-Item $base $un -Force
$zip = [System.IO.Compression.ZipFile]::Open($un, 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, 'classes.dex') | Out-Null
} finally { $zip.Dispose() }

# 6) zipalign
Step "zipalign"
$aligned = Join-Path $Work 'aligned.apk'
& $zipalign -f -p 4 $un $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

# 7) 签名
Step "apksigner"
if (-not [System.IO.Path]::IsPathRooted($Out)) { $Out = Join-Path $Repo $Out }
$outDir = Split-Path -Parent $Out
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$Ks = Join-Path $Repo $Keystore
if (-not (Test-Path $Ks)) { throw "找不到签名证书：$Ks" }
& cmd /c "`"$apksigner`" sign --ks `"$Ks`" --ks-key-alias $KsAlias --ks-pass pass:$KsPass --key-pass pass:$KeyPass --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true --out `"$Out`" `"$aligned`""
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

& cmd /c "`"$apksigner`" verify --print-certs `"$Out`"" | Write-Host
$item = Get-Item $Out
Step ("完成：" + $item.FullName + "  (" + $item.Length + " B)")
& $aapt2 dump badging $Out | Select-String -Pattern "^package:|^application-label:|^sdkVersion|^targetSdkVersion" | ForEach-Object { Write-Host "    $_" }
