import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release 签名材料绝不进版本库。
 *
 * 风险很具体：这个 keystore 一旦连同口令一起泄漏，别人就能签出**同一个包名、
 * 同一个证书**的 APK，直接覆盖安装到你手机上。Android 对「签名相同」的
 * 覆盖安装不弹任何提示，用户看到的只是「秒跳更新了」——而这个新版本身就
 * 持有无障碍权限。
 *
 * 所以口令只从「环境变量」或 local.properties（已 gitignore）读取，
 * 脚本里不保留任何默认值。CI 只编译 debug，不需要签名材料。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) runCatching { f.inputStream().use { load(it) } }
}

fun secret(envKey: String, propKey: String): String =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: ""

val releaseStorePass = secret("ADSKIP_STORE_PASS", "adskip.storePassword")
val releaseKeyPass = secret("ADSKIP_KEY_PASS", "adskip.keyPassword")
val releaseKeyAlias = System.getenv("ADSKIP_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("adskip.keyAlias")?.takeIf { it.isNotBlank() }
    ?: "adskip"
val releaseKeystore = rootProject.file("keystore/adskip-release.jks")

// 只在真的要产出 release 包时校验，assembleDebug / CI 编译不受影响。
gradle.taskGraph.whenReady {
    val needsRelease = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease"
    }
    if (!needsRelease) return@whenReady
    val missing = mutableListOf<String>()
    if (!releaseKeystore.exists()) missing += "keystore/adskip-release.jks"
    if (releaseStorePass.isBlank()) missing += "ADSKIP_STORE_PASS（或 local.properties 的 adskip.storePassword）"
    if (releaseKeyPass.isBlank()) missing += "ADSKIP_KEY_PASS（或 local.properties 的 adskip.keyPassword）"
    if (missing.isNotEmpty()) {
        throw GradleException(
            "release 签名材料缺失：${missing.joinToString("；")}。\n" +
                "请把口令写进 local.properties（不要提交），或设置同名环境变量。"
        )
    }
}

android {
    namespace = "com.miaotiao.adskip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miaotiao.adskip"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.3.4"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore.takeIf { it.exists() }
            storePassword = releaseStorePass
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        // 临时诊断构建：模拟 release 的 debuggable=false，看是否导致无障碍不派发事件
        create("relnoDbg") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // 回退判定跑在 JVM 单元测试里。
    //
    // 为什么必须有：「什么时候该按返回」依赖一堆时序条件，真机上几乎无法
    // 稳定复现——用户自己都说不清是哪一步触发的。除了拿真机碰运气，
    // 唯一能长期维护的办法就是把判定抽成纯函数，用断言把
    // 「哪些组合该回退、哪些绝对不该回退」钉死。
    testImplementation("junit:junit:4.13.2")
}
