plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.park"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.park"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.04"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // TESTING ONLY: sign the release build with the default Android DEBUG keystore
            // (~/.android/debug.keystore) so `assembleRelease` gives an APK that installs
            // (an unsigned one fails with INSTALL_PARSE_FAILED_NO_CERTIFICATES) and the shrunk build can
            // be tried on real devices. This is NOT a production key: anyone can sign an APK
            // with it, and a store or an update over a properly signed install won't accept it.
            // Replace with a real signing config before distributing anything.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                // R8 SHRINK ONLY: removes unreachable code and resources, nothing else. The extra
                // rules (-dontoptimize, -dontobfuscate so the on-device crash handler's stack
                // traces stay readable) are in src/main/keepRules/park.keep, which AGP picks up
                // by itself; AGP also adds its own default rules file automatically.
                enable = true
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        // MigrationTestHelper reads the exported schemas/*.json as test assets to build a
        // database at an OLD version — without this the androidTest can't find them.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    testOptions {
        // Robolectric (JVM tests that simulate Android, see below) needs the merged resources and
        // manifest, e.g. to build notifications against the app's real channels and icons.
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 17 (API 37) runtime reaches into JDK internals (FileDescriptor via
        // jdk.internal.access) while setting up each test; recent JDKs block that unless it is
        // exported explicitly. Affects only the unit-test JVM, never the app.
        unitTests.all {
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED"
            )
        }
    }
    buildFeatures {
        compose = true
        // Required for BuildConfig.VERSION_NAME/VERSION_CODE (used in SettingsScreen's
        // version footer) to generate at all — AGP 8+ made this opt-in rather than
        // automatic, so compose = true alone isn't enough anymore.
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Not in the version catalog — added directly, same pattern as the lifecycle-process/
    // play-services-base entries below. Version comes from the compose BOM already applied
    // above. Needed for real Material Icons (Icons.Filled.*) in place of emoji/glyph
    // characters used as icons throughout the UI.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Not in the version catalog (libs.versions.toml isn't visible from here) — added
    // directly so ParkApp can observe ProcessLifecycleOwner (app-level foreground/background
    // transitions, distinct from any single Activity's onStop) to flush a pending widget
    // refresh right as the app leaves the foreground, while the process is still guaranteed
    // alive. Match this version to whatever your other androidx.lifecycle:* artifacts use if
    // you fold it into the catalog.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    testImplementation(libs.junit)
    // Android's own org.json classes are stubs that throw on the JVM, so unit tests of code that
    // parses JSON (RppDataApiTest) need the real library. Test-only; the app uses the platform's.
    testImplementation("org.json:json:20260814")
    // Robolectric runs Android code (Room, AlarmManager, NotificationManager) inside plain JVM tests,
    // with simulated ("shadow") system services a test can inspect. Test-only: never in the APK.
    // Used by the reminder-arming characterization tests (docs/park-sources-refactor-spec.md, Part B).
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(libs.work.runtime.ktx)
    implementation(libs.osmdroid)
    implementation(libs.datastore.preferences)
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    implementation("androidx.glance:glance-appwidget:1.2.0") // verify latest stable
    // Not in the version catalog — added directly, same as the two above. Provides
    // ProviderInstaller (com.google.android.gms.security.ProviderInstaller), used in
    // ParkApp to patch an old device's stale CA trust store at runtime and fix "Trust
    // anchor for certification path not found" on hardware whose OS itself no longer gets
    // updates (confirmed: a Galaxy S9). Requires Google Play Services to be installed on
    // the device, which is true of virtually every real Android phone (including the S9)
    // but not guaranteed on things like AOSP emulators or de-Googled ROMs — ProviderInstaller
    // is called defensively (try/catch) specifically because of that.
    implementation("com.google.android.gms:play-services-base:18.5.0") // verify latest stable
}