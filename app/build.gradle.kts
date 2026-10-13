plugins {
    id("com.android.application")
}

android {
    namespace = "com.nox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nox.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // If hev-socks5-tunnel was cloned into src/main/jni (Android Studio route), Gradle builds it.
    // On GitHub Actions the library is built separately and copied into jniLibs instead.
    if (file("src/main/jni/hev-socks5-tunnel").exists()) {
        externalNativeBuild {
            ndkBuild { path = file("src/main/jni/Android.mk") }
        }
    }

    // Xray runs as a child process from the native-lib dir, so libs must be extracted to disk.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
