import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nexg.ide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexg.ide"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // Says what is actually in the APK. Leaving this at "phase2" while the
        // editor is in the build makes a badging dump lie to whoever is checking
        // which milestone they installed.
        versionName = "0.1.0-phase2-3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The debug variant is the only one that ships the test harness
            // (app/src/debug/). Flagged in the app so a debug build can never be
            // mistaken for a production one, and so the test bridge itself can
            // refuse to answer on a non-debuggable build.
            buildConfigField("boolean", "TEST_HARNESS", "true")
        }
        release {
            isMinifyEnabled = false
            // Must stay false: the release variant has no test harness at all,
            // so there is nothing to guard, and leaving it true would only
            // imply that release has a test path to disable.
            buildConfigField("boolean", "TEST_HARNESS", "false")
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

    buildFeatures {
        compose = true
        // AGP 8.x disables BuildConfig generation by default. NexGApp reads
        // BuildConfig.DEBUG to pick the release log level, so it is needed.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// `android.kotlinOptions` is deprecated in Kotlin 2.1 and warns on every
// configuration. The replacement sets the same JVM target without going
// through the deprecated block, and is scoped to the app module's Kotlin
// compilations so it does not affect the root project.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // Phase 2: local persistence. Room is the only storage layer added in
    // this phase; Settings/AI/build-history tables are deferred to their
    // own phases per PLAN.MD phase ordering.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Phase 4: AI backend + BYOK credential storage + developer-tool boundary.
    // OkHttp is the HTTP + transport layer (the plan deliberately excludes
    // Retrofit), kotlinx-serialization parses the Gemini SSE payloads, and
    // security-crypto backs the Keystore credential store. mockwebserver is
    // test-only: the backend contract is asserted against a real HTTP server
    // on localhost, never against fakes on the JVM.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    // The editor bridge parses WebView messages with `org.json`, which the
    // Android stub jar throws "not mocked" for. The real implementation on the
    // test classpath lets the message contract be tested on the JVM instead of
    // only on a device; production keeps using the platform's own org.json.
    testImplementation("org.json:json:20231013")

    // ===================================================================
    // TEST INFRASTRUCTURE — DEBUG VARIANT ONLY. NEVER MOVE TO `implementation`.
    // ===================================================================
    // Everything in app/src/debug/ (the NexG test controller, the allowlisted
    // test bridge, the Shizuku probe, the exported test receiver) is compiled
    // ONLY into the debug variant, so the release APK physically cannot contain
    // it. This is the mechanism behind the "release must not depend on Shizuku"
    // requirement: `assembleRelease` never resolves these artifacts at all, which
    // is verified from the built APK rather than asserted in a comment.
    //
    // Shizuku is OPTIONAL. The app is fully functional with Shizuku absent,
    // stopped, or permission-denied; the probe reports that state and the
    // affected tests report UNAVAILABLE rather than PASS.
    debugImplementation(libs.shizuku.api)
    debugImplementation(libs.shizuku.provider)
}
