plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tacchi.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tacchi.probe"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p0-fixture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../.devhome/tacchi-development.p12")
            storePassword = "android"
            keyAlias = "tacchi-dev"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
