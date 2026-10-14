import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile")?.isNotBlank() == true &&
    keystoreProperties.getProperty("storePassword")?.isNotBlank() == true &&
    keystoreProperties.getProperty("keyAlias")?.isNotBlank() == true &&
    keystoreProperties.getProperty("keyPassword")?.isNotBlank() == true

fun readBuildSecret(name: String): String {
    val fromEnv = System.getenv(name)?.trim().orEmpty()
    if (fromEnv.isNotBlank()) return fromEnv
    val fromProp = (findProperty(name) as String?)?.trim().orEmpty()
    return fromProp
}

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val bridgeSupabaseUrl = readBuildSecret("BRIDGE_SUPABASE_URL")
val bridgeSupabaseAnonKey = readBuildSecret("BRIDGE_SUPABASE_ANON_KEY")
val hasBridgeRuntimeSecrets = bridgeSupabaseUrl.isNotBlank() && bridgeSupabaseAnonKey.isNotBlank()

android {
    namespace = "com.sushiamo.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sushiamo.bridge"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BRIDGE_SUPABASE_URL", "\"${escapeBuildConfig(bridgeSupabaseUrl)}\"")
        buildConfigField("String", "BRIDGE_SUPABASE_ANON_KEY", "\"${escapeBuildConfig(bridgeSupabaseAnonKey)}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        val name = task.name
        name.contains("Release") && (name.contains("assemble") || name.contains("bundle"))
    }
    if (releaseRequested && !hasBridgeRuntimeSecrets) {
        throw GradleException("Missing BRIDGE_SUPABASE_URL / BRIDGE_SUPABASE_ANON_KEY for release build")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
