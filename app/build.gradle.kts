plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.exclusivo.moon"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.exclusivo.moon"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }
    val ks = System.getenv("KEYSTORE_PATH")
    if (ks != null) {
        signingConfigs.create("moon") {
            storeFile = file(ks)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "moon"
            storeType = "pkcs12"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("moon")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
