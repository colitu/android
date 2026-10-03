import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

val signingProps = Properties().also { props ->
    val file = rootProject.file("signing.properties")
    if (file.exists()) props.load(file.inputStream())
}
fun secret(name: String, env: String) = signingProps.getProperty(name)?.takeIf { it.isNotBlank() }
    ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseKeystorePath = secret("KEYSTORE_PATH", "COLITU_ANDROID_KEYSTORE_PATH")
val releaseStorePassword = secret("STORE_PASS", "COLITU_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = secret("KEY_ALIAS", "COLITU_ANDROID_KEY_ALIAS")
val releaseKeyPassword = secret("KEY_PASS", "COLITU_ANDROID_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningConfigured = listOf(releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }
val releaseSigningRequired = providers.gradleProperty("COLITU_REQUIRE_RELEASE_SIGNING").orNull == "true" ||
    System.getenv("COLITU_REQUIRE_RELEASE_SIGNING") == "true"
// Ad-blocking DNS-over-HTTPS URLs (comma separated). They point at Colitu's
// own nodes, so they stay out of the public repository like the signing keys;
// without them the ad-blocking switch is not shown.
val adBlockDoh = secret("ADBLOCK_DOH", "COLITU_ADBLOCK_DOH").orEmpty()
if (releaseSigningRequired && adBlockDoh.isBlank()) {
    throw GradleException("ADBLOCK_DOH (signing.properties) or COLITU_ADBLOCK_DOH is required for a release build")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.colitulu"
        minSdk = 24
        targetSdk = 36
        versionCode = 25300
        versionName = "2.5.3"
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "COLITU_API_BASE_URL", "\"https://api.colitu.com/api/v1\"")
        buildConfigField("String", "COLITU_UPDATE_MANIFEST_URL", "\"https://colitu.com/downloads/android/latest.json\"")
        buildConfigField("boolean", "COLITU_FORCE_TV", "false")
        // Only the colitu.com APK ("direct") installs its own updates.
        buildConfigField("boolean", "COLITU_SELF_UPDATE", "false")
        buildConfigField("String", "COLITU_ADBLOCK_DOH", "\"${adBlockDoh.replace("\"", "")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystorePath?.let { rootProject.file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            // R8 drops the imported v2rayNG code the product no longer uses and
            // unused resources; keep rules for reflection/JNI are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            val overrideUrl = providers.gradleProperty("COLITU_API_BASE_URL").orNull
                ?: "https://api.colitu.com/api/v1"
            buildConfigField("String", "COLITU_API_BASE_URL", "\"${overrideUrl.trimEnd('/')}\"")

            providers.gradleProperty("COLITU_UPDATE_MANIFEST_URL").orNull?.let {
                buildConfigField("String", "COLITU_UPDATE_MANIFEST_URL", "\"$it\"")
            }
            // -PCOLITU_FORCE_TV=true shows the TV layout on a phone emulator.
            if (providers.gradleProperty("COLITU_FORCE_TV").orNull == "true") {
                buildConfigField("boolean", "COLITU_FORCE_TV", "true")
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
        // The APK published on colitu.com: same identity and version codes as
        // the Play build, plus the self-updater (src/direct).
        create("direct") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"colitu.com\"")
            buildConfigField("boolean", "COLITU_SELF_UPDATE", "true")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "Colitu_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "Colitu_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // Every lint error is fatal; the app's own texts live in ColituLoc
        // (ru/tr/en), Android resources only hold launcher/notification strings.
        abortOnError = true
    }

}

gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) && (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }
    if (buildsRelease && releaseSigningRequired && !releaseSigningConfigured) {
        throw GradleException("Protected release build requires COLITU_ANDROID_KEYSTORE_PATH, COLITU_ANDROID_STORE_PASSWORD, COLITU_ANDROID_KEY_ALIAS and COLITU_ANDROID_KEY_PASSWORD")
    }
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)

    // Compose (Colitu UI)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.runtime)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // UI Libraries
    implementation(libs.material)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR codes for links on TV (zxing core)
    implementation(libs.core)
    // Camera for scanning a TV's sign-in QR code (decoded with zxing core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
