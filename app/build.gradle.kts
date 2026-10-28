plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kevin.evcarhud"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.evcarhud.app"
        minSdk = 29
        targetSdk = 37
        // Mỗi lần build trên GitHub tăng số phiên bản (Play Store bắt buộc versionCode mới cho mỗi lần tải lên)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Khoá debug cố định trong repo: APK build trên GitHub và build ở máy nhà cài đè lên nhau được (giữ cài đặt, key HERE)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Khoá tải lên Play Store: chỉ có khi CI có secret PLAY_KEYSTORE_B64 / PLAY_KEY_PASS (không để trong repo công khai)
        if (System.getenv("PLAY_KEY_PASS") != null) {
            create("upload") {
                storeFile = file(System.getenv("PLAY_KEYSTORE") ?: "upload.jks")
                storePassword = System.getenv("PLAY_KEY_PASS")
                keyAlias = "upload"
                keyPassword = System.getenv("PLAY_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("PLAY_KEY_PASS") != null) signingConfig = signingConfigs.getByName("upload")
            optimization {
                // Tạm tắt rút gọn mã cho bản Play thử nghiệm (ONNX Runtime, MapLibre, Car App dùng reflection)
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    androidResources { noCompress += "onnx" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}



dependencies {
    // Đọc webcam / camera USB chuẩn UVC qua OTG (Maven Central) cho máy không hỗ trợ camera ngoài qua Camera2
    implementation("com.herohan:UVCAndroid:1.0.13")
    // Trang Bản đồ: bản đồ vector + nhà 3D (ô vector HERE qua Worker)
    implementation("org.maplibre.gl:android-sdk:11.8.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Màn hình trên Android Auto (ô Android Auto của xe)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.onnxruntime.android.qnn)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}