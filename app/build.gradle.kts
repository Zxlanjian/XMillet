plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.univers629.millet"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.univers629.millet"
        minSdk = 33
        targetSdk = 34
        versionCode = 1230
        versionName = "1.2.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // 现代 LSPosed / LibXposed API (102.0.0，由框架注入运行时)
    compileOnly("io.github.libxposed:api:102.0.0")

    // LibXposed 服务端：让 LSPosed 能把本应用的设置以 RemotePreferences 递给 Hook 进程
    implementation("io.github.libxposed:service:102.0.0")

    // Jetpack Compose & Activity
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigationevent:navigationevent:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    // Miuix KMP UI 组件库、图标、导航、设置项与毛玻璃特效 (0.9.4)
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    testImplementation("junit:junit:4.13.2")
}

