// Top-level build file where you can add configuration options common to all sub-projects/modules.
// All dependencies are managed via the version catalog (gradle/libs.versions.toml).
// All libraries used are FOSS-compatible (Apache 2.0, MIT, BSD, JetBrains licenses).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
}
