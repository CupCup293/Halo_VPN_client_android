plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.halo.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.halo.vpn"
        minSdk = 29 // Android 10 â€” Galaxy Ñ One UI 2 Ð¸ Ð½Ð¾Ð²ÐµÐµ
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ÐŸÐ¾ÐºÐ° Ð¿Ð¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ Ð¾Ñ‚Ð»Ð°Ð´Ð¾Ñ‡Ð½Ñ‹Ð¼ ÐºÐ»ÑŽÑ‡Ð¾Ð¼, Ñ‡Ñ‚Ð¾Ð±Ñ‹ APK ÑÑ‚Ð°Ð²Ð¸Ð»ÑÑ Ð½Ð° Ñ‚ÐµÐ»ÐµÑ„Ð¾Ð½. Ð¡Ð²Ð¾Ð¹ ÐºÐ»ÑŽÑ‡ â€” Ð¿ÐµÑ€ÐµÐ´ Ð¿ÑƒÐ±Ð»Ð¸ÐºÐ°Ñ†Ð¸ÐµÐ¹.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // ÐžÑ‚Ð´ÐµÐ»ÑŒÐ½Ñ‹Ð¹ APK Ð½Ð° Ð°Ñ€Ñ…Ð¸Ñ‚ÐµÐºÑ‚ÑƒÑ€Ñƒ: arm64-v8a â€” Ñ‚ÐµÐ»ÐµÑ„Ð¾Ð½Ñ‹ Galaxy, x86_64 â€” ÑÐ¼ÑƒÐ»ÑÑ‚Ð¾Ñ€ Ð½Ð° ÐºÐ¾Ð¼Ð¿ÑŒÑŽÑ‚ÐµÑ€Ðµ.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    // Ð“ÐµÐ¾-Ð±Ð°Ð·Ñ‹ Ð½Ðµ ÑÐ¶Ð¸Ð¼Ð°ÐµÐ¼: ÐºÐ¾Ð¿Ð¸Ñ€ÑƒÑŽÑ‚ÑÑ Ð½Ð° Ð´Ð¸ÑÐº ÐºÐ°Ðº ÐµÑÑ‚ÑŒ.
    androidResources { noCompress += "dat" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
