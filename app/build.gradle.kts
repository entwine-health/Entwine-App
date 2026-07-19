// Lucy app module — TDD D-11 stack: Kotlin, Compose, min 31, OkHttp WS,
// AudioRecord/AudioTrack, DataStore, WorkManager, Keystore-backed prefs.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: keystore lives in <repo>/keys (gitignored, restic-backed);
// password comes from the host secrets store or ENTWINE_KEYSTORE_PASSWORD env.
fun keystorePassword(): String? = System.getenv("ENTWINE_KEYSTORE_PASSWORD")

android {
    namespace = "health.entwine.lucy"
    compileSdk = 35

    signingConfigs {
        create("pilot") {
            val ksFile = rootProject.file("../keys/entwine-release.keystore")
            val pass = keystorePassword()
            if (ksFile.exists() && pass != null) {
                storeFile = ksFile
                storePassword = pass
                keyAlias = "entwine"
                keyPassword = pass
            }
        }
    }

    defaultConfig {
        applicationId = "health.entwine.lucy"
        minSdk = 31
        targetSdk = 35
        versionCode = 16
        versionName = "0.6.5"
    }
    // Twin-backend flavors (ADR-0020): `spark` = production Spark edge (default
    // package + label, unchanged); `aws` = the parallel AWS rig — own package
    // (side-by-side install), "_AWS" label, api-aws endpoints. Endpoints are
    // per-flavor; debug keeps the .debug suffix for both.
    flavorDimensions += "backend"
    productFlavors {
        create("spark") {
            dimension = "backend"
            resValue("string", "app_name", "Entwine")
            buildConfigField("String", "API_BASE", "\"https://api.entwine.health\"")
            buildConfigField("String", "WS_URL", "\"wss://api.entwine.health/v1/session\"")
        }
        create("aws") {
            dimension = "backend"
            applicationIdSuffix = ".aws"
            resValue("string", "app_name", "Entwine_AWS")
            buildConfigField("String", "API_BASE", "\"https://api-aws.entwine.health\"")
            buildConfigField("String", "WS_URL", "\"wss://api-aws.entwine.health/v1/session\"")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false // Stage-0: debuggable pilot builds; shrink later
            signingConfig = signingConfigs.getByName("pilot")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Endpoints live on the FLAVORS (buildType fields would override them — AGP
// precedence). One exception: spark+debug targets the tailnet dev box.
android.applicationVariants.all {
    if (flavorName == "spark" && buildType.name == "debug") {
        buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8000\"")
        buildConfigField("String", "WS_URL", "\"ws://10.0.2.2:8000/v1/session\"")
    }
}
