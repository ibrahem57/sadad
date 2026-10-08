plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = System.getenv("SADAD_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("SADAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("SADAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SADAD_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.sadad.app"
    buildFeatures { buildConfig = true }
    val apiBaseUrl = providers.gradleProperty("SADAD_API_BASE_URL").getOrElse("http://10.0.2.2:8081/api")
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        applicationId = providers.gradleProperty("SADAD_TEST_APPLICATION_ID").getOrElse("com.sadad.app.test")
        testInstrumentationRunner = providers.gradleProperty("SADAD_TEST_RUNNER").getOrElse("com.sadad.app.QaInstrumentation")
        minSdk = 24
        targetSdk = 34
        versionCode = 31
        versionName = providers.gradleProperty("SADAD_VERSION_NAME").getOrElse("2.5.5")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartextTraffic"] = "false"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("server") {
            dimension = "edition"
            applicationId = providers.gradleProperty("SADAD_APPLICATION_ID").getOrElse("com.sadad.app")
            buildConfigField("boolean", "STANDALONE_MODE", "false")
        }
        create("standalone") {
            dimension = "edition"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            resValue("string", "app_name", "سدد - مستقل")
            buildConfigField("boolean", "STANDALONE_MODE", "true")
        }
    }
    signingConfigs {
        if (releaseSigningReady) create("release") {
            storeFile = file(releaseStoreFile!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug {
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies { }


