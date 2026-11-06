plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aufe.competition"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // 说明：本模块不声明 applicationId，由宿主 App 决定
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // 代码里用到 BuildConfig.DEBUG 控制 WebView 远程调试
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // appcompat 1.7.x：1.6.1 没有 Toolbar 的 navigationIconTint 属性，
    // 布局里那条 app:navigationIconTint 会直接 AAPT 报错。
    // 必须用 api 而不是 implementation：本模块的布局 XML 引用了 appcompat 的
    // attr（navigationIconTint / actionBarSize / colorPrimary），宿主打包资源时
    // 也要能解析它们，否则 app 合并 AAPT 时报 "attribute not found"。
    api("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // 说明：模块源码没有用到 com.google.android.material 的任何组件
    // （只有 AppCompatActivity + Toolbar），所以不引 material ——
    // 少一个传递依赖，也避免和宿主的 Compose 体系抢主题属性。

    // appcompat 1.6.1 传递进来的 lifecycle 2.6.1 / coroutines 1.6.4 与本机缓存、
    // 宿主版本都不一致，这里把版本抬到宿主在用的档位，避免重复类与离线取不到包。
    constraints {
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
        implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
        implementation("androidx.lifecycle:lifecycle-process:2.7.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
        // appcompat 的传递依赖把 stdlib-jdk8 拖到 1.8.20，和宿主的 1.9.x 对不上；
        // 1.9 之后 jdk8 这个 artifact 已经是空壳（内容全并入 stdlib），抬到 1.9.20 即可
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")
    }
}
