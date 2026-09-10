plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.gps"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.lanelevelgps"
        minSdk = 29
        targetSdk = 37
        versionCode = System.getenv("LANE_GPS_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("LANE_GPS_VERSION_NAME") ?: "0.1.0"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":lane-engine"))
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-viewbinding")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
