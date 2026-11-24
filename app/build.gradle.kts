import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version comes from the release tag in CI (-PappVersion=1.2.3); local builds use the default.
val appVersion: String = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
fun versionCodeOf(v: String): Int {
    val parts = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 1_000_000 + parts.getOrElse(1) { 0 } * 1_000 + parts.getOrElse(2) { 0 }
}

// GitHub "owner/repo" whose Releases feed the self-updater (github flavor).
val updateRepo: String = (findProperty("updateRepo") as String?) ?: "acc-studio/egHue"

// Release signing: keystore.properties locally, environment variables in CI.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
val releaseStoreFile: String? = signingValue("storeFile", "EGHUE_KEYSTORE")

android {
    namespace = "app.eghue"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.eghue"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "EGHUE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "EGHUE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "EGHUE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Sideloaded build that keeps itself up to date from GitHub Releases.
        create("github") { dimension = "distribution" }
        // Google Play build: Play delivers updates (with in-app update prompts).
        create("play") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    "playImplementation"(libs.play.app.update)
    "playImplementation"(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
