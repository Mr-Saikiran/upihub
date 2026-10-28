import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing comes from `keystore.properties` (local, never committed) or,
 * on CI, from UPIHUB_* environment variables. Without either, release builds
 * are produced unsigned.
 */
val signing: Map<String, String?> = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    mapOf(
        "storeFile" to value("storeFile", "UPIHUB_KEYSTORE_FILE"),
        "storePassword" to value("storePassword", "UPIHUB_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "UPIHUB_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "UPIHUB_KEY_PASSWORD"),
    )
}
val hasReleaseSigning = signing.values.all { !it.isNullOrBlank() }

android {
    // Code package; the published identity is applicationId below.
    namespace = "dev.upihub"
    compileSdk = 36

    defaultConfig {
        applicationId = "techswitch.upihub"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signing.getValue("storeFile")!!)
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.path)
            // Optional mirror for Robolectric's Android runtime jars, e.g. when Maven Central rate-limits.
            providers.gradleProperty("robolectricRepo").orNull?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}
