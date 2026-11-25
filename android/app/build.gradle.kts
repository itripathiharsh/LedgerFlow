import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun propOrDefault(key: String, fallback: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: fallback).trim()

android {
    namespace = "com.ledgerflow.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ledgerflow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val supabaseUrl = propOrDefault("SUPABASE_URL", "https://rzlenoldloktswymuooz.supabase.co")
        val supabaseKey = propOrDefault("SUPABASE_PUBLISHABLE_KEY", "")
        val renderUrl = propOrDefault("RENDER_API_URL", "https://ledgerflow-api.onrender.com")
        val updateManifestUrl = propOrDefault("UPDATE_MANIFEST_URL", "${renderUrl.trimEnd('/')}/v1/app/update")
        val expectedSigningCertSha256 = propOrDefault("EXPECTED_SIGNING_CERT_SHA256", "")
        val admobAppId = propOrDefault("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        val admobBannerId = propOrDefault("ADMOB_BANNER_UNIT_ID", "ca-app-pub-3940256099942544/6300978111")

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "RENDER_API_URL", "\"$renderUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"$expectedSigningCertSha256\"")
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerId\"")
        manifestPlaceholders["admobAppId"] = admobAppId
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            isDefault = true
        }
        create("playStore") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.ads)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
