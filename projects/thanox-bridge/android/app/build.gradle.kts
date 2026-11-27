plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "im.majo.thanox_bridge"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "im.majo.thanox_bridge"
        minSdk = 28
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        val sharedUidSafetyTest = providers.gradleProperty("sharedUidSafetyTest").orNull == "true"
        val requestCoordinatorTest = providers.gradleProperty("requestCoordinatorTest").orNull == "true"
        require(!(sharedUidSafetyTest && requestCoordinatorTest)) {
            "Select only one opt-in instrumentation runner"
        }
        when {
            sharedUidSafetyTest ->
                testInstrumentationRunner = "im.majo.thanox_bridge.SharedUidSafetyInstrumentation"
            requestCoordinatorTest ->
                testInstrumentationRunner = "im.majo.thanox_bridge.RequestCoordinatorInstrumentation"
        }
    }

}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
