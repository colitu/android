// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    dependencies {
        classpath(libs.gradle.license.plugin)
        // AGP 9.4.1 still pulls these build-tool libraries at versions with known
        // advisories (osv-scanner 2026-10-21); raise them to the patched releases.
        constraints {
            listOf("bcprov", "bcpkix", "bcutil").forEach {
                classpath("org.bouncycastle:$it-jdk18on:1.85") { because("GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-wg6q-6289-32hp") }
            }
            classpath("org.jdom:jdom2:2.0.6.1") { because("GHSA-2363-cqg2-863c XXE") }
            classpath("org.bitbucket.b_c:jose4j:0.9.6") { because("GHSA-3677-xxcr-wjqv") }
            classpath("org.apache.commons:commons-lang3:3.18.0") { because("GHSA-j288-q9x7-2f5v") }
            classpath("org.codehaus.plexus:plexus-utils:3.6.1") { because("GHSA-6fmv-xxpf-w3cw") }
        }
    }
    // Reproducible builds: the plugin classpath (AGP, R8, Kotlin) is locked in
    // buildscript-gradle.lockfile. Refresh: ./gradlew :app:dependencies --write-locks
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}
