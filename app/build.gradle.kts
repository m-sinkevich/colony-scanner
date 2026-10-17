plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun cfg(name: String): String {
    val env = System.getenv(name)
    if (!env.isNullOrBlank()) return env
    return (project.findProperty(name) as String?) ?: ""
}

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.paradise.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paradise.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        buildConfigField("String", "SUPABASE_URL", quoted(cfg("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_KEY", quoted(cfg("SUPABASE_KEY")))
        buildConfigField("String", "MASTER_PIN", quoted(cfg("MASTER_PIN").ifBlank { "1234" }))
    }

    signingConfigs {
        create("game") {
            storeFile = file("scanner.keystore")
            storePassword = "paradise"
            keyAlias = "scanner"
            keyPassword = "paradise"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("game")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("game")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
