import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val developmentStore = rootProject.file("../.devhome/tacchi-development.p12")
require(developmentStore.isFile) { "Run ./hako ./tooling/prepare-android.sh first" }
val developmentKeys = KeyStore.getInstance("PKCS12").apply {
    developmentStore.inputStream().use { load(it, "android".toCharArray()) }
}
val developmentCertificate = requireNotNull(developmentKeys.getCertificate("tacchi-dev"))
val signerDigest = MessageDigest.getInstance("SHA-256")
    .digest(developmentCertificate.encoded).joinToString("") { "%02x".format(it) }

android {
    namespace = "im.majo.tacchi"
    compileSdk = 36
    defaultConfig {
        applicationId = "im.majo.tacchi"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PROBE_SIGNER_SHA256", "\"$signerDigest\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    signingConfigs {
        getByName("debug") {
            storeFile = developmentStore
            storePassword = "android"
            keyAlias = "tacchi-dev"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            // P0 artifact only: use this checkout's disposable development identity.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
