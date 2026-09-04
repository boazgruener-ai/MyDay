import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ch.boazgruener.myday"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.boazgruener.myday"
        minSdk = 30
        targetSdk = 37
        versionCode = 12
        versionName = "0.4.3"

        buildConfigField(
            "String",
            "GOOGLE_OAUTH_CLIENT_ID",
            "\"${localProps.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_MAPS_API_KEY",
            "\"${localProps.getProperty("GOOGLE_MAPS_API_KEY", "")}\""
        )

        // Home location - defaults to a generic Zurich city-center point so the app builds and
        // runs for anyone cloning the repo; a real device's own values live in local.properties
        // (gitignored), not here.
        buildConfigField(
            "String", "HOME_CITY", "\"${localProps.getProperty("HOME_CITY", "Zurich")}\""
        )
        buildConfigField(
            "double", "HOME_LATITUDE", localProps.getProperty("HOME_LATITUDE", "47.3769")
        )
        buildConfigField(
            "double", "HOME_LONGITUDE", localProps.getProperty("HOME_LONGITUDE", "8.5417")
        )
        buildConfigField(
            "String", "HOME_REGION", "\"${localProps.getProperty("HOME_REGION", "Zurich")}\""
        )
        buildConfigField(
            "String", "HOME_COUNTRY", "\"${localProps.getProperty("HOME_COUNTRY", "CH")}\""
        )
        buildConfigField(
            "String", "HOME_TIMEZONE", "\"${localProps.getProperty("HOME_TIMEZONE", "Europe/Zurich")}\""
        )
        buildConfigField(
            "String", "HOME_DISPLAY", "\"${localProps.getProperty("HOME_DISPLAY", "Zurich, Switzerland")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // kotlin.compilerOptions.jvmTarget is intentionally not set - with AGP's built-in Kotlin
    // it defaults to compileOptions.targetCompatibility above.

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Default AGP output naming is "<module-name>-<buildType>.apk" - since the module here is just
// named "app" (a Gradle convention, unrelated to the actual app name), that produced
// "app-debug.apk" instead of anything recognizable as Myday. Renamed so a build handed to
// someone else is self-explanatory as a file.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Myday-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.play.services.auth)
    implementation(libs.google.play.services.location)
    implementation(libs.tink.android)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.openwakeword)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
