plugins {
    application
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.bundles.androidcompat.implementation)
    compileOnly(libs.bundles.androidcompat.compileonly)

    // Android stub library
    implementation(fileTree("lib/"))

    // Config API
    implementation(project(":AndroidCompat:Config"))

    annotationProcessor(libs.quickjs4j.processor)

}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
}
