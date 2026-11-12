plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has Kotlin built in: this line only pins the Kotlin Gradle Plugin version on the
    // build classpath, it is never applied to a module.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
