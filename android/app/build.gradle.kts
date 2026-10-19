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
// Ad-blocking resolvers are not built in: the panel names them in the
// signed-in bootstrap (ColituAdBlock.remember), so no node host ships in the APK.
// Mirror origins of the API (comma separated, https://host). Also kept out of the public repository.
val mirrors = secret("MIRRORS", "COLITU_MIRRORS").orEmpty()
if (releaseSigningRequired && mirrors.isBlank()) {
    throw GradleException("MIRRORS (signing.properties) or COLITU_MIRRORS is required for a release build")
}

android {
    namespace = "com.v2ray.ang"
    // 37.2: OkHttp 5.5 and core-ktx 1.19 compile against API 37; targetSdk stays 36 (runtime behaviour unchanged).
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.colitulu"
        minSdk = 24
        targetSdk = 36
        versionCode = 29300
        versionName = "2.9.3"
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                // Per-ABI APKs for colitu.com/F-Droid; an app bundle (Google Play)
                // cannot be built with splits on, and Play splits by ABI itself.
                isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
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
        // Only the Google Play build ("playstore") follows Play's payments policy:
        // no upgrade buttons, price promos or links to buy on the website.
        buildConfigField("boolean", "COLITU_PLAY_POLICY", "false")
        // -PcolituRecoveryTest=true: test build for the recovery set (API blackholed, regular cache ignored). Never in a shipped build.
        buildConfigField("boolean", "COLITU_RECOVERY_TEST", (providers.gradleProperty("colituRecoveryTest").orNull == "true").toString())
        // Adaptive Connect 3.0 (hinted start order + recovery set): off unless -PcolituAdaptiveConnect3=true. See ColituFeatures.
        buildConfigField("boolean", "COLITU_ADAPTIVE_CONNECT_3", (providers.gradleProperty("colituAdaptiveConnect3").orNull == "true").toString())
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
            // Reproducible builds: no META-INF/version-control-info.textproto, whose
            // content depends on the checkout (a source tarball has no .git).
            vcsInfo.include = false
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
            buildConfigField("boolean", "COLITU_PLAY_POLICY", "true")
            // Same version code as the colitu.com APK (4000000 + versionCode), so
            // Play offers the next update to people who installed that APK.
            versionCode = 4_000_000 + defaultConfig.versionCode!!
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
        // The Play build already carries its final version code (see "playstore").
        if (variant.productFlavors.any { it.name == "playstore" }) return@all
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

    // Reproducible builds: the dependency block AGP adds to an APK is encrypted
    // for Google and differs on every build, so APKs (colitu.com, F-Droid) leave
    // it out. The Play bundle keeps it for the Play SDK Index.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    lint {
        // Every lint error is fatal; the app's own texts live in ColituLoc
        // (ru/tr/en), Android resources only hold launcher/notification strings.
        abortOnError = true
    }

}

// Google Play build: ARM only. The x86/x86_64 sidecars (colitu-engine,
// csl-client) are not 16 KB page aligned, which Play rejects; Play phones
// are ARM, and the colitu.com/F-Droid APKs keep every ABI.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "playstore")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(listOf("lib/x86/**", "lib/x86_64/**"))
    }
}

// The mirror origins are the one input that is not in the public source. They go into a
// single file at the APK root, colitu-build.json (read by ColituBuildValues), not into the
// code, so everything else in the APK is reproducible from the public source; to rebuild a
// published APK, take COLITU_MIRRORS from that file. Fixed layout: the same value gives the
// same bytes. No value (developer builds): no file.
abstract class ColituBuildValuesTask : DefaultTask() {
    @get:Input
    abstract val mirrorList: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val value = mirrorList.get()
        if (value.isNotEmpty()) {
            val escaped = value.replace("\\", "").replace("\"", "")
            dir.resolve("colitu-build.json").writeText("{\"mirrors\":\"$escaped\"}\n", Charsets.UTF_8)
        }
    }
}

val colituBuildValues = tasks.register<ColituBuildValuesTask>("colituBuildValues") {
    mirrorList.set(mirrors)
    outputDir.set(layout.buildDirectory.dir("generated/colitu-build-values"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(colituBuildValues, ColituBuildValuesTask::outputDir)
    }
}

// Reproducible builds: what ships in a release is locked in app/gradle.lockfile.
// After changing a dependency: ./gradlew :app:dependencies --write-locks
configurations.matching { it.name.endsWith("ReleaseRuntimeClasspath") || it.name.endsWith("ReleaseCompileClasspath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) && (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }
    if (buildsRelease && releaseSigningRequired && !releaseSigningConfigured) {
        throw GradleException("Protected release build requires COLITU_ANDROID_KEYSTORE_PATH, COLITU_ANDROID_STORE_PASSWORD, COLITU_ANDROID_KEY_ALIAS and COLITU_ANDROID_KEY_PASSWORD")
    }
    // Reproducible builds: a release is built from sources with the line endings every checkout
    // gets (LF, see .gitattributes); a CRLF copy left by an editor (text assets go into the APK
    // as they are) would build a different APK.
    if (buildsRelease && releaseSigningRequired) {
        val eol = providers.exec {
            commandLine("git", "-C", rootDir.parentFile.path, "ls-files", "--eol")
            isIgnoreExitValue = true
        }.standardOutput.asText.get()
        val crlf = eol.lineSequence()
            .filter { (" w/crlf " in it || " w/mixed " in it) && "eol=lf" in it }
            .map { it.substringAfter('\t') }
            .toList()
        if (crlf.isNotEmpty()) {
            throw GradleException("These files have CRLF line endings, the repository uses LF (.gitattributes); convert them (or git checkout -- <file> when unchanged):\n  " + crlf.take(20).joinToString("\n  "))
        }
    }
}

dependencies {
    // Google Play Billing: only in the Play build (src/playstore).
    "playstoreImplementation"("com.android.billingclient:billing-ktx:8.0.0")

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
