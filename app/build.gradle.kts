plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace   = "com.wifi.visualizer"
    compileSdk  = 34

    defaultConfig {
        applicationId          = "com.wifi.visualizer"
        minSdk                 = 24
        targetSdk              = 34
        versionCode            = 2
        versionName            = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export directory (used by KSP at compile time)
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental",    "true")
            arg("room.generateKotlin", "true")
        }
    }

    signingConfigs {
        // Release signing is configured via environment variables / GitHub Secrets.
        // For local debug builds no extra config is needed.
        create("release") {
            val keystoreFile  = System.getenv("KEYSTORE_FILE")
            val keystorePass  = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias      = System.getenv("KEY_ALIAS")
            val keyPass       = System.getenv("KEY_PASSWORD")
            if (keystoreFile != null && keystorePass != null) {
                storeFile     = file(keystoreFile)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword   = keyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigningConfig = signingConfigs.getByName("release")
            // Only apply release signing when the keystore env vars are present
            if (releaseSigningConfig.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        viewBinding  = true
        buildConfig  = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Tell Android Lint to ignore the generated Room schema JSON files
    lint {
        disable += "InvalidPackage"
        abortOnError = false
    }
}

dependencies {
    // ── AndroidX core ─────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // ── Material Design ───────────────────────────────────────────────────────
    implementation("com.google.android.material:material:1.11.0")

    // ── Layout widgets ────────────────────────────────────────────────────────
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ── Lifecycle & ViewModel ─────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── Room (SQLite ORM) ─────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── ARCore ────────────────────────────────────────────────────────────────
    implementation("com.google.ar:core:1.41.0")

    // ── MPAndroidChart (charts) ───────────────────────────────────────────────
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.10")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
