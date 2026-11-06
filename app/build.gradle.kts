import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keyProperties = Properties().apply {
    val keyFile = rootProject.file("key.properties")
    if (keyFile.exists()) {
        keyFile.inputStream().use { load(it) }
    }
}
// X.Y.Z → X·1 000 000 + Y·1 000 + Z (as in Mau-Mau Flip). `-PpnVersion=1.0.99` builds an older
// version, e.g. to try the update function against the latest GitHub release.
val appVersion = (findProperty("pnVersion") as String?) ?: "1.1.6"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (x, y, z) -> x * 1_000_000 + y * 1_000 + z }

val hasProductionReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keyProperties.getProperty(it)?.isNotBlank() == true } &&
    file(keyProperties.getProperty("storeFile").orEmpty()).isFile

android {
    namespace = "de.shakie.playernext"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.shakie.playernext"
        // API 30 gives MediaStore genre/album-artist/disc columns and
        // createWriteRequest() for tag editing without broad storage access.
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        manifestPlaceholders["appLabel"] = "Player NEXT"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasProductionReleaseSigning) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Player NEXT Debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasProductionReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    val media3 = "1.10.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
