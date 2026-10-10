plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sadad.app"
    buildFeatures { buildConfig = true }
    val apiBaseUrl = providers.gradleProperty("SADAD_API_BASE_URL").getOrElse("https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-auth/api")
    val supabasePublishableKey = providers.gradleProperty("SADAD_SUPABASE_PUBLISHABLE_KEY").getOrElse("sb_publishable_dK7HuYY8TbO8ZiBPbOZ2cw_1ZRL_chh")
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        applicationId = "com.sadad.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "3.2.0"
        testInstrumentationRunner = "com.sadad.app.LedgerInstrumentation"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${supabasePublishableKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartextTraffic"] = "false"
    }
    buildTypes {
        // HTTP is allowed only in debug builds so local emulator testing can reach the dev API.
        debug {
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    annotationProcessor("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.11.2")
}
