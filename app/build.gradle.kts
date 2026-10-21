import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Release signing material never enters the repository (§10).
 *
 * The release build reads it from the environment, which GitHub Actions fills from repository
 * secrets. Everything is optional at configuration time so ordinary debug builds, unit tests
 * and lint do not need a keystore at all; [requireReleaseSigning] fails the release task
 * later, with a message that says what is missing, instead of producing an unsigned APK that
 * would only be discovered at install time.
 */
// Names are prefixed to avoid shadowing SigningConfig's own members inside `create("release")`,
// where a bare `keyPassword` resolves to the receiver property and would assign null to itself.
val envStoreFile: String? = System.getenv("OPEN_NOTES_KEYSTORE_FILE")
val envStorePassword: String? = System.getenv("OPEN_NOTES_KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("OPEN_NOTES_KEY_ALIAS")
val envKeyPassword: String? = System.getenv("OPEN_NOTES_KEY_PASSWORD")
val signingConfigured = listOf(envStoreFile, envStorePassword, envKeyAlias, envKeyPassword)
    .all { !it.isNullOrBlank() } && envStoreFile?.let { file(it).exists() } == true

// Version identity comes from the release tag: `-PversionName=1.0.0 -PversionCode=7`.
val releaseVersionName: String = (findProperty("versionName") as String?) ?: "0.1.0"
val releaseVersionCode: Int = (findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.example.privatenotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.privatenotes"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingConfigured) {
            create("release") {
                storeFile = file(envStoreFile!!)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                // Both v1 and v2/v3 signatures: minSdk 26 installs fine with v2, but keeping v1
                // makes the APK verifiable by older tooling as well.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.markwon.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher: encrypts the database file itself, from the very first byte written.
    implementation(libs.sqlcipher)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Refuses to assemble an unsigned release.
 *
 * Without this, a missing secret would still produce `app-release-unsigned.apk`, and the problem
 * would surface as an install failure on a user's device — after publishing. §10 asks for the
 * signing setup to be verified before the first release rather than assumed.
 */
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    group = "verification"
    description = "Fails when release signing material is missing from the environment."

    // Providers are configuration-cache friendly; project and script objects are not.
    val storeFile = providers.environmentVariable("OPEN_NOTES_KEYSTORE_FILE").orElse("")
    val storePassword = providers.environmentVariable("OPEN_NOTES_KEYSTORE_PASSWORD").orElse("")
    val alias = providers.environmentVariable("OPEN_NOTES_KEY_ALIAS").orElse("")
    val keyPassword = providers.environmentVariable("OPEN_NOTES_KEY_PASSWORD").orElse("")

    doLast {
        val missing = listOf("OPEN_NOTES_KEYSTORE_FILE" to storeFile.get(),
            "OPEN_NOTES_KEYSTORE_PASSWORD" to storePassword.get(),
            "OPEN_NOTES_KEY_ALIAS" to alias.get(),
            "OPEN_NOTES_KEY_PASSWORD" to keyPassword.get()).filter { it.second.isBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured: missing " +
                    missing.joinToString { it.first } +
                    ". GitHub Actions supplies these from repository secrets. Refusing to " +
                    "build an unsigned release.",
            )
        }
        if (!File(storeFile.get()).exists()) {
            throw GradleException(
                "Release keystore not found at \${OPEN_NOTES_KEYSTORE_FILE}. Refusing to build " +
                    "an unsigned release.",
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(requireReleaseSigning)
}
