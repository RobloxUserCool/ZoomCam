plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zoomcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zoomcam"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    val cx = "1.3.4"
    implementation("androidx.camera:camera-core:$cx")
    implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx")
    implementation("androidx.camera:camera-video:$cx")
    implementation("androidx.camera:camera-view:$cx")
}
