plugins {
    id("com.android.library")
}

android {
    namespace = "com.shihab.diplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
        multiDexEnabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // OkHttp 3.12 is the last API 19-compatible line. Keep the legacy updater off
    // Android 4.4's system HttpsURLConnection, which can fall back to SSLv3.
    implementation("com.squareup.okhttp3:okhttp-urlconnection:3.12.13")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
}
