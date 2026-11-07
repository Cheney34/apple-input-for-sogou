plugins {
    id("com.android.application")
}

android {
    namespace = "com.chiron.iosemoji"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chiron.iosemoji"
        minSdk = 26
        targetSdk = 35
        versionCode = 56
        versionName = "2.0.3-beta16"
        // 只针对 arm64 的搜狗包做验证；模块本身是纯 Java，不引入 native
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        // 复用仓库 signing/debug.keystore（alias/keypass 见 signing/README.md）
        create("debugKey") {
            storeFile = rootProject.file("../signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugKey")
        }
        release {
            // 混淆会破坏 XposedHelpers 对字符串类名的可读性和排错能力，默认关闭
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    // Xposed API 82：仅编译期可见，不打包进 APK（运行时由 LSPosed 提供）
    compileOnly(files("libs/XposedBridgeAPI-82.jar"))
}
