plugins {
    alias(libs.plugins.driftwood.jvm.library)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit)
}
