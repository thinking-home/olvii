plugins { id("com.android.application") }

android {
    // Keep the package identity and SDK contract stable.
    namespace = "home.olvii"
    compileSdk = 36
    defaultConfig {
        // Supported devices and the application version installed for the MVP.
        applicationId = "home.olvii"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        // Compile Kotlin and Java against the same bytecode level.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Signaling transport and native audio/WebRTC implementation.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
