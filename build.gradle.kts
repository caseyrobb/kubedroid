import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // NOTE: no org.jetbrains.kotlin.android — Kotlin support is built into AGP 9+
    // (kotl.in/gradle/agp-built-in-kotlin). Compose/serialization plugins remain.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.neozeon.kubedroid"
    compileSdk = 37 // platform android-37.0 (AGP 9.4 max); core 1.19.x requires >= 37

    defaultConfig {
        applicationId = "com.neozeon.kubedroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"
    }

    // Spec 11 stage 3: the AGP SDK-dependency blob (SDKP pair in the APK
    // Signing Block) is version-stamped/encrypted and differs every build
    // — it breaks reproducible builds and only feeds Play Console, which we
    // do not use. Opt out (F-Droid publishes byte-reproducible APKs).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Spec 09: release signing, conditional on a local git-ignored
    // properties file (see docs/09-release-signing.md). Absent -> unsigned
    // release, exactly the pre-spec behavior.
    val keystorePropsFile = rootProject.file(
        System.getenv("KUBEDROID_KEYSTORE_PROPERTIES") ?: "keystore.properties",
    )
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false // R8 deferred (spec 09 Non-goals)
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // META-INF duplicates from client-java's auth transitives.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*ThirdParty-LICENSE",
                "META-INF/*.version",
                "META-INF/native-image/**",
                "META-INF/proguard/**",
                "native-image.properties",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric needs reflective access to java.base internals
                // (e.g. FileDescriptor) which JDK 17+ encapsulates by default.
                // https://robolectric.org/getting-started/
                it.jvmArgs(
                    "--add-opens", "java.base/java.io=ALL-UNNAMED",
                    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens", "java.base/java.util=ALL-UNNAMED",
                    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
                    "--add-opens", "java.base/java.net=ALL-UNNAMED",
                    "--add-opens", "java.base/sun.nio.fs=ALL-UNNAMED",
                    // Robolectric 4.17 FileDescriptorInterceptor reflects into
                    // jdk.internal.access.SharedSecrets (robolectric#11434).
                    "--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.navigation3)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // spec 03: cluster connection management
    implementation(libs.kubernetes.client.java) {
        // client-java drags netty in only for Exec/PortForwarder, and the
        // AWS SDK only for EKS get-token — both are exec-style auth that
        // spec 03 declares non-goals, so kubedroid never reaches them.
        exclude(group = "io.netty")
        exclude(group = "software.amazon.awssdk")
    }
    implementation(libs.snakeyaml)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Robolectric needs the test-manifest AAR (declares the ComponentActivity
    // that createComposeRule launches) on the unit-test classpath, not just debug.
    testImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
