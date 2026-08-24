plugins {
    alias(libs.plugins.driftwood.jvm.library)
}

dependencies {
    implementation(project(":core:crypto"))

    testImplementation(libs.junit)
}
