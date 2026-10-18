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
    }
    // Reproducible builds: the plugin classpath (AGP, R8, Kotlin) is locked in
    // buildscript-gradle.lockfile. Refresh: ./gradlew :app:dependencies --write-locks
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}
