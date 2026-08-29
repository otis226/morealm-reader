plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.otis.edgereader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.otis.edgereader"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0-dev1"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
