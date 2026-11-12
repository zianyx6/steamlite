plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

android {
    namespace = "org.steamlite"
    compileSdk = 37
    defaultConfig { applicationId = "org.steamlite"; minSdk = 26; targetSdk = 34; versionCode = 101; versionName = "1.0.1"; testInstrumentationRunner = "org.steamlite.ImageNetworkCheck" }
    val releaseKey = System.getenv("STEAMLITE_KEYSTORE")
    if (!releaseKey.isNullOrBlank()) signingConfigs.create("distribution") {
        storeFile = file(releaseKey)
        storePassword = System.getenv("STEAMLITE_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("STEAMLITE_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("STEAMLITE_KEY_PASSWORD")
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        if (!releaseKey.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/**/OSGI-INF/MANIFEST.MF") }
}
androidComponents {
    beforeVariants(selector().all()) {
        it.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("io.github.kyant0:backdrop-android:2.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.github.penfeizhou.android.animation:apng:3.0.5")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
}
