plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.custodysim.reader.episteme"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.reader.jsoup)
    implementation(libs.reader.timber)
    implementation(libs.reader.coroutines)
    implementation(libs.reader.serialization.json)
    implementation(libs.reader.serialization.protobuf)
    testImplementation(libs.junit)
}
