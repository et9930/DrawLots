import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 发布签名配置从仓库根的 `keystore.properties` 读取（该文件不进版本库）。
 * 文件不存在时 release 仍然能构建，只是产出未签名的 APK（assembleRelease 不报错）。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

/**
 * 版本号来源（优先级从高到低）：
 *  1. Gradle 属性：`-PversionName=1.0.2`（可选 `-PversionCode=12345` 覆盖自动值）
 *  2. CI 环境变量：`GITHUB_REF_NAME`（打 tag 触发时就是 `v1.0.2`）
 *  3. 下面的默认值（本地日常构建）
 *
 * `versionCode` 默认由语义化版本自动算出（major*10000 + minor*100 + patch），
 * 保证单调递增——Android 用它判断能否覆盖升级，绝不能忘记手动加。
 */
fun semverToCode(name: String): Int {
    val core = name.substringBefore('-').removePrefix("v")
    val parts = core.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10_000 + minor * 100 + patch
}

val gradleVersionName = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
val ciTagName = System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }
val resolvedVersionName: String = gradleVersionName
    ?: ciTagName?.removePrefix("v")
    ?: "1.0.1"
val resolvedVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: if (gradleVersionName != null || ciTagName != null) semverToCode(resolvedVersionName) else 2

android {
    namespace = "com.drawlots.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.drawlots.app"
        minSdk = 24
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric（UI 冒烟测试）在受限环境里需要离线使用预先下载好的 android-all jar：
// 只有在项目根目录存在 robolectric-deps/ 时才开启离线模式，普通环境会自动联网下载。
val robolectricDeps = rootProject.file("robolectric-deps")
if (robolectricDeps.isDirectory) {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", robolectricDeps.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 联机：二维码生成/解码 + 相机扫码
    // 依赖版本已按 AAR 元数据验证：camera 1.6.0 要求 minCompileSdk=36 / minAGP=8.9.1，与当前环境兼容
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-core:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")

    // Robolectric + Compose 测试：在 JVM 上真的把界面跑起来（不需要模拟器）。
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
